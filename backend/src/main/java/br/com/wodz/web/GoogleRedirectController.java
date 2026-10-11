package br.com.wodz.web;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.service.GoogleTokenVerifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Retorno GIS na mesma aba. Nenhuma sessão é criada antes da confirmação no cliente. */
@RestController
public class GoogleRedirectController {
    private final GoogleTokenVerifier google;
    private final AppProperties props;

    public GoogleRedirectController(GoogleTokenVerifier google, AppProperties props) {
        this.google = google;
        this.props = props;
    }

    @PostMapping(value = "/api/public/google/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> callback(
            @CookieValue(name = "g_csrf_token", required = false) String cookie,
            @RequestParam(name = "g_csrf_token", required = false) String csrf,
            @RequestParam(required = false) String credential,
            @RequestParam(required = false) String state) {
        if (!validCsrf(cookie, csrf) || state == null || !state.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                || credential == null || credential.length() > 8192
                || !credential.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) return error();
        try {
            google.verifyRedirect(credential, state);
        } catch (ApiException ex) {
            return error();
        }
        String nonce = UUID.randomUUID().toString();
        // Os valores interpolados contêm apenas o alfabeto JWT/UUID validado acima.
        String html = """
                <!doctype html><html lang="pt-BR"><meta charset="utf-8"><title>WODZ</title>
                <script nonce="%s">(() => {
                  try {
                    const flow = JSON.parse(sessionStorage.getItem('wodz.google-flow'));
                    if (!flow || flow.id !== '%s' || Date.now() - flow.at < 0 || Date.now() - flow.at > 900000) {
                      location.replace('/google/retorno?erro=confirmacao'); return;
                    }
                    sessionStorage.setItem('wodz.google-result', JSON.stringify({state: '%s', credential: '%s'}));
                    location.replace('/google/retorno');
                  } catch { location.replace('/google/retorno?erro=confirmacao'); }
                })();</script></html>
                """.formatted(nonce, state, state, credential);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "'; base-uri 'none'; frame-ancestors 'none'")
                .body(html);
    }

    private ResponseEntity<String> error() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(props.frontendUrl().replaceAll("/+$", "") + "/google/retorno?erro=confirmacao"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer").build();
    }

    static boolean validCsrf(String cookie, String csrf) {
        return cookie != null && csrf != null && !cookie.isBlank() && cookie.length() <= 256 && csrf.length() <= 256
                && MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), csrf.getBytes(StandardCharsets.UTF_8));
    }
}
