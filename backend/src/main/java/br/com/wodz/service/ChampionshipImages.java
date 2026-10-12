package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Base64;

/** Decodifica e regrava pixels: não persiste metadados, SVG, nomes de arquivo ou conteúdo ativo. */
public final class ChampionshipImages {
    private ChampionshipImages() {}
    public static final int MAX_BYTES = 1_048_576;

    public static byte[] sanitize(String data) {
        if (data == null || data.length() > 1_400_000 || !data.matches("(?s)^data:image/(jpeg|png);base64,[A-Za-z0-9+/=]+$")) throw invalid();
        try {
            byte[] bytes = Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1));
            if (bytes.length > MAX_BYTES) throw invalid();
            try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName();
                    if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG")) throw invalid();
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 2048 || height > 2048 || (long) width * height > 4_000_000) throw invalid();
                    BufferedImage source = reader.read(0);
                    boolean png = format.equalsIgnoreCase("PNG");
                    BufferedImage clean = new BufferedImage(width, height, png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                    var graphics = clean.createGraphics();
                    try {
                        if (!png) { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height); }
                        graphics.drawImage(source, 0, 0, null);
                    }
                    finally { graphics.dispose(); source.flush(); }
                    try {
                        if (png) {
                            var output = new ByteArrayOutputStream();
                            if (!ImageIO.write(clean, "png", output) || output.size() > MAX_BYTES) throw invalid();
                            return output.toByteArray();
                        }
                        // Evita a qualidade JPEG padrão do encoder e mantém um piso de 90%.
                        for (float quality : new float[]{0.95f, 0.92f, 0.90f}) {
                            var output = new ByteArrayOutputStream();
                            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                            try (var stream = new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
                                writer.setOutput(stream);
                                var params = writer.getDefaultWriteParam();
                                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                                params.setCompressionQuality(quality);
                                writer.write(null, new IIOImage(clean, null, null), params);
                            } finally { writer.dispose(); }
                            if (output.size() <= MAX_BYTES) return output.toByteArray();
                        }
                        throw invalid();
                    } finally { clean.flush(); }
                } finally { reader.dispose(); }
            }
        } catch (java.io.IOException | IllegalArgumentException ex) { throw invalid(); }
    }

    public static boolean isPng(byte[] content) {
        return content.length >= 8 && content[0] == (byte) 0x89 && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47
                && content[4] == 0x0d && content[5] == 0x0a && content[6] == 0x1a && content[7] == 0x0a;
    }

    public static String safeUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.matches("/api/public/championship-images/[a-f0-9-]{36}")) return trimmed;
        try {
            URI uri = URI.create(trimmed);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null) return trimmed;
        } catch (IllegalArgumentException ignored) { }
        throw ApiException.badRequest("Use uma imagem enviada pelo sistema ou uma URL HTTPS válida");
    }

    private static ApiException invalid() { return ApiException.badRequest("Imagem inválida. Envie JPG ou PNG de até 1 MB e 2048 × 2048 pixels"); }
}
