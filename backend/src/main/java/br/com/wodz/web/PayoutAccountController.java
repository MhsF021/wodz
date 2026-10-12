package br.com.wodz.web;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.CurrentUser;
import br.com.wodz.dto.PayoutDtos.AuthorizationView;
import br.com.wodz.dto.PayoutDtos.PayoutView;
import br.com.wodz.payment.PayoutAuthorizationException;
import br.com.wodz.payment.PayoutAuthorizationException.Reason;
import br.com.wodz.service.PayoutAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class PayoutAccountController {
    private static final Logger log = LoggerFactory.getLogger(PayoutAccountController.class);
    private final PayoutAccountService accounts;
    public PayoutAccountController(PayoutAccountService accounts) { this.accounts = accounts; }

    @GetMapping("/api/admin/payout-account")
    public PayoutView status(@AuthenticationPrincipal Jwt jwt) { return accounts.status(CurrentUser.id(jwt)); }

    @PostMapping("/api/admin/payout-account/connect")
    public AuthorizationView connect(@AuthenticationPrincipal Jwt jwt) {
        Number version = jwt.getClaim("token_version");
        return accounts.connect(CurrentUser.id(jwt), version == null ? 0L : version.longValue());
    }

    @DeleteMapping("/api/admin/payout-account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@AuthenticationPrincipal Jwt jwt) { accounts.disconnect(CurrentUser.id(jwt)); }

    @GetMapping("/api/public/mercadopago/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error) {
        boolean success = false;
        Reason reason = Reason.UNEXPECTED;
        try {
            accounts.complete(state, error == null ? code : null);
            success = true;
        } catch (ApiException rejected) {
            if (rejected instanceof PayoutAuthorizationException failure) reason = failure.getReason();
            if (reason == Reason.MISSING_CODE && error != null) {
                reason = "access_denied".equals(error) ? Reason.CANCELLED : Reason.AUTHORIZATION_REJECTED;
            }
            // Somente motivos fixos vão à URL/log; a mensagem e o corpo externos ficam de fora.
            log.warn("Vinculação Mercado Pago não concluída: motivo={} HTTP={}", reason.code(), rejected.getStatus().value());
        } catch (RuntimeException failed) {
            // Mensagens e causas de infraestrutura podem conter credenciais do provedor.
            log.error("Falha inesperada na vinculação Mercado Pago: {}", failed.getClass().getSimpleName());
        }
        String resultUrl = accounts.profileResultUrl(success);
        return redirect(success ? resultUrl : resultUrl + "&motivo=" + reason.code());
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(url))
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build();
    }
}
