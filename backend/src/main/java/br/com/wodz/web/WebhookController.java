package br.com.wodz.web;

import br.com.wodz.config.AppProperties;
import br.com.wodz.payment.MercadoPagoSignatureVerifier;
import br.com.wodz.service.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private final PaymentService payments;
    private final MercadoPagoSignatureVerifier verifier;

    public WebhookController(PaymentService payments, AppProperties props) {
        this.payments = payments;
        this.verifier = new MercadoPagoSignatureVerifier(props.payment().mercadopago().webhookSecret());
    }

    /** Notificação do Mercado Pago (configure MP_NOTIFICATION_URL apontando para cá). */
    @PostMapping("/mercadopago")
    public ResponseEntity<Void> mercadoPago(
            @RequestParam(name = "data.id", required = false) String dataIdParam,
            @RequestParam(name = "type", required = false) String typeParam,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestHeader(name = "x-request-id", required = false) String requestId,
            @RequestBody(required = false) JsonNode body) {

        String type = typeParam != null ? typeParam : body == null ? null : text(body.path("type"));
        String dataId = dataIdParam != null ? dataIdParam
                : body == null ? null : text(body.path("data").path("id"));

        if (!"payment".equals(type) || dataId == null) {
            return ResponseEntity.ok().build();
        }
        if (!verifier.verify(signature, requestId, dataId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        payments.syncFromProvider(dataId);
        return ResponseEntity.ok().build();
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
