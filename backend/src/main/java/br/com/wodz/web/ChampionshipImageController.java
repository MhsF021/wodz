package br.com.wodz.web;

import br.com.wodz.common.ApiException;
import br.com.wodz.service.ChampionshipImages;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** A imagem do campeonato é pública; somente o proprietário pode gravá-la no formulário autenticado. */
@RestController
public class ChampionshipImageController {
    private final JdbcTemplate jdbc;
    public ChampionshipImageController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/public/championship-images/{key}")
    public ResponseEntity<byte[]> image(@PathVariable UUID key) {
        var images = jdbc.query("SELECT content FROM championship_image WHERE image_key = ?",
                (rs, row) -> rs.getBytes(1), key);
        if (images.isEmpty()) throw ApiException.notFound("Imagem não encontrada");
        byte[] content = images.getFirst();
        boolean png = ChampionshipImages.isPng(content);
        return ResponseEntity.ok().contentType(png ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff").header("Content-Disposition", "inline; filename=campeonato." + (png ? "png" : "jpg"))
                .header("Cache-Control", "no-store").body(content);
    }
}
