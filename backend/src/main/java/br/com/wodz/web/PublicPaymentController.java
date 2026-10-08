package br.com.wodz.web;

import br.com.wodz.dto.PaymentDtos.CreatePaymentRequest;
import br.com.wodz.dto.PaymentDtos.PaymentDto;
import br.com.wodz.dto.PaymentDtos.RegistrationSummary;
import br.com.wodz.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Link de pagamento da inscrição: /pagamento/{código}. O código é aleatório
 * (10 caracteres) e pode ser compartilhado pelo organizador com a equipe.
 */
@RestController
@RequestMapping("/api/public/registrations/{code}")
@Tag(name = "Pagamento da inscrição")
public class PublicPaymentController {

    private final PaymentService payments;

    public PublicPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    public RegistrationSummary summary(@PathVariable String code) {
        return payments.summary(code);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria o Checkout Pro com os dados de quem paga; o navegador segue para checkoutUrl")
    public PaymentDto create(@PathVariable String code, @Valid @RequestBody CreatePaymentRequest req) {
        return payments.create(code, req);
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "Status do pagamento (usado em polling pela página de pagamento)")
    public PaymentDto status(@PathVariable String code, @PathVariable Long paymentId) {
        return payments.status(code, paymentId);
    }
}
