package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ChampionshipImagesTest {
    private String png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB),"png",out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
    @Test void preservaPngSemPerdaEComTransparencia() throws Exception {
        byte[] clean = ChampionshipImages.sanitize(png(100,80));
        assertTrue(ChampionshipImages.isPng(clean));
        var decoded = ImageIO.read(new ByteArrayInputStream(clean)); assertEquals(100,decoded.getWidth()); assertEquals(80,decoded.getHeight());
        assertEquals(0, decoded.getRGB(50,40));
    }
    @Test void pngPreservaPixelsDeTextoEArteColorida() throws Exception {
        var source = new BufferedImage(120,80,BufferedImage.TYPE_INT_ARGB);
        var random = new java.util.Random(42);
        for (int y=0;y<80;y++) for (int x=0;x<120;x++) source.setRGB(x,y,0xff000000 | random.nextInt(0xffffff));
        var output = new ByteArrayOutputStream(); ImageIO.write(source,"png",output);
        byte[] clean = ChampionshipImages.sanitize("data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
        var decoded = ImageIO.read(new ByteArrayInputStream(clean));
        for (int y=0;y<80;y++) for (int x=0;x<120;x++) assertEquals(source.getRGB(x,y),decoded.getRGB(x,y));
    }
    @Test void jpegRegravadoMantemDetalhesComErroBaixo() throws Exception {
        var source = new BufferedImage(256,128,BufferedImage.TYPE_INT_RGB);
        for (int y=0;y<128;y++) for (int x=0;x<256;x++) source.setRGB(x,y,((x&255)<<16) | ((y*2&255)<<8) | ((x+y)&255));
        var output = new ByteArrayOutputStream(); ImageIO.write(source,"jpeg",output);
        var original = ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
        byte[] clean = ChampionshipImages.sanitize("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
        assertFalse(ChampionshipImages.isPng(clean));
        var decoded = ImageIO.read(new ByteArrayInputStream(clean));
        long error = 0;
        for (int y=0;y<128;y++) for (int x=0;x<256;x++) for (int channel : new int[]{0,8,16}) {
            error += Math.abs(((original.getRGB(x,y)>>channel)&255)-((decoded.getRGB(x,y)>>channel)&255));
        }
        assertTrue(error / (256.0*128*3) < 2.5, "A recompressão deve manter o erro médio abaixo de 2,5 níveis de cor");
    }
    @Test void rejeitaConteudoAtivoArquivoFalsoEBombasDeDimensao() throws Exception {
        for (String invalid : new String[]{"data:image/svg+xml;base64,PHN2Zz4=","data:image/png;base64,PHNjcmlwdD4=","javascript:alert(1)","data:image/png;base64,%%%"})
            assertThrows(ApiException.class, () -> ChampionshipImages.sanitize(invalid));
        String oversized = png(2049,1);
        assertThrows(ApiException.class, () -> ChampionshipImages.sanitize(oversized));
        assertThrows(ApiException.class, () -> ChampionshipImages.sanitize("A".repeat(1_400_001)));
    }
    @Test void bloqueiaUrlsAtivasOuComCredenciais() {
        for (String url : new String[]{"javascript:alert(1)","data:text/html,hello","//evil.invalid/x","https://user:password@evil.invalid/x","http://evil.invalid/x"})
            assertThrows(ApiException.class, () -> ChampionshipImages.safeUrl(url));
        assertEquals("https://example.invalid/image.jpg",ChampionshipImages.safeUrl("https://example.invalid/image.jpg"));
    }
}
