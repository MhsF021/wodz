package br.com.wodz.web;
import br.com.wodz.dto.PaymentDtos.*;
import br.com.wodz.service.PaymentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
@RestController
@RequestMapping("/api/public/registrations/{code}")
@Tag(name = "Checkout da inscrição")
public class PublicPaymentController {
    private final PaymentService payments;
    public PublicPaymentController(PaymentService payments) { this.payments = payments; }
    @GetMapping
    public RegistrationSummary summary(@PathVariable String code) { return payments.summary(code); }
    @PostMapping("/checkout") @ResponseStatus(HttpStatus.CREATED)
    public CheckoutDto checkout(@PathVariable String code, @Valid @RequestBody CheckoutRequest request) {
        return payments.createCheckout(code, request);
    }
    @GetMapping("/checkout/{checkoutId}/details")
    public CheckoutDto checkoutDetails(@PathVariable String code, @PathVariable Long checkoutId) {
        return payments.checkoutDetails(code, checkoutId);
    }
    @GetMapping("/checkout/{checkoutId}")
    public RegistrationSummary checkoutStatus(@PathVariable String code, @PathVariable Long checkoutId,
            @RequestParam(required = false) String paymentId) {
        return payments.checkoutStatus(code, checkoutId, paymentId);
    }
    /** Consulta de registros antigos; não permite criar cobranças dentro do WODZ. */
    @GetMapping("/payments/{paymentId}")
    public PaymentDto status(@PathVariable String code, @PathVariable Long paymentId) { return payments.status(code, paymentId); }
}
