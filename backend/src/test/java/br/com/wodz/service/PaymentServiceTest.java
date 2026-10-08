package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.TokenCipher;
import br.com.wodz.config.AppProperties;
import br.com.wodz.config.AppProperties.PaymentProps;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.Payment;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.PaymentDtos.CreatePaymentRequest;
import br.com.wodz.dto.PaymentDtos.Payer;
import br.com.wodz.dto.PaymentDtos.PaymentDto;
import br.com.wodz.payment.GatewayPayment;
import br.com.wodz.payment.PaymentCommand;
import br.com.wodz.payment.PaymentGateway;
import br.com.wodz.repository.PaymentRepository;
import br.com.wodz.repository.PayoutAccountRepository;
import br.com.wodz.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final String CODE = "ABCDEFGHJK";
    private static final String CHECKOUT = "https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1";
    private static final Payer PAYER = new Payer(" Ana ", "Souza", "ana@wodz.dev", "529.982.247-25", "(81) 99999-8888");

    private final TeamRepository teams = mock(TeamRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PayoutAccountRepository payoutAccounts = mock(PayoutAccountRepository.class);
    private final TokenCipher tokenCipher = mock(TokenCipher.class);
    private final AppProperties props = new AppProperties(null, null, null,
            new PaymentProps("sandbox", Duration.ofMinutes(30), null), null, null, null, null);
    private final PaymentService service = new PaymentService(teams, payments, payoutAccounts,
            new SandboxPaymentGateway(), props, tokenCipher);
  
    @BeforeEach
    void setUp() {
        Championship championship = new Championship();
        championship.setName("Open WODZ");
        championship.setStartDate(LocalDate.now().plusMonths(2));
        championship.setEndDate(LocalDate.now().plusMonths(2).plusDays(1));
        Category category = new Category();
        category.setName("RX");
        category.setPrice(new BigDecimal("150.00"));
        category.setChampionship(championship);
        team = new Team();
        team.setId(5L);
        team.setName("Equipe Alfa");
        team.setStatus(TeamStatus.PENDING_PAYMENT);
        team.setRegistrationCode(CODE);
        team.setCategory(category);

        when(gateway.providerName()).thenReturn("MERCADO_PAGO");
        when(gateway.createCheckout(any(PaymentCommand.class))).thenReturn(CHECKOUT);
        when(teams.findByRegistrationCode(CODE)).thenReturn(Optional.of(team));
        when(payments.findByTeamIdAndStatus(anyLong(), eq(PaymentStatus.PENDING))).thenReturn(List.of());
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(1L);
            }
            return p;
        });
    }

    private Payment existing(PaymentStatus status, String providerPaymentId) {
        Payment p = new Payment();
        p.setId(1L);
        p.setTeam(team);
        p.setAmount(new BigDecimal("150.00"));
        p.setMethod(PaymentMethod.CHECKOUT_PRO);
        p.setStatus(status);
        p.setProvider("MERCADO_PAGO");
        p.setProviderPaymentId(providerPaymentId);
        p.setCheckoutUrl(CHECKOUT);
        return p;
    }

    private static GatewayPayment result(String id, PaymentStatus status, String type, Integer installments) {
        return new GatewayPayment(id, status, "detalhe", type, installments, "4242", null, "team:5:payment:1");
    }

    private ApiException rejected(Payer payer) {
        ApiException e = assertThrows(ApiException.class, () -> service.create(CODE, new CreatePaymentRequest(payer)));
        verify(payments, never()).saveAndFlush(any(Payment.class));
        verify(gateway, never()).createCheckout(any());
        return e;
    }

    @Test
    void criaCheckoutComDadosDoPagadorNormalizados() {
        PaymentDto dto = service.create(CODE, new CreatePaymentRequest(PAYER));

        assertEquals(PaymentMethod.CHECKOUT_PRO, dto.method());
        assertEquals(PaymentStatus.PENDING, dto.status());
        assertEquals(CHECKOUT, dto.checkoutUrl());

        ArgumentCaptor<PaymentCommand> cmd = ArgumentCaptor.forClass(PaymentCommand.class);
        verify(gateway).createCheckout(cmd.capture());
        assertEquals("Ana", cmd.getValue().payer().firstName());
        assertEquals("52998224725", cmd.getValue().payer().cpf());
        assertEquals("81999998888", cmd.getValue().payer().phone());
        assertEquals("team:5:payment:1", cmd.getValue().externalReference());
        assertEquals("http://localhost:8081/pagamento/" + CODE, cmd.getValue().returnUrl());
        assertEquals(new BigDecimal("150.00"), cmd.getValue().amount());
    }

    @Test
    void recusaCpfInvalido() {
        assertEquals(HttpStatus.BAD_REQUEST,
                rejected(new Payer("Ana", "Souza", "ana@wodz.dev", "111.111.111-11", "81999998888")).getStatus());
    }

    @Test
    void recusaTelefoneSemDdd() {
        assertEquals(HttpStatus.BAD_REQUEST,
                rejected(new Payer("Ana", "Souza", "ana@wodz.dev", "52998224725", "99999-8888")).getStatus());
    }

    @Test
    void telefoneAceitaMascaraECodigoDoPais() {
        assertEquals("81999998888", PaymentService.normalizePhone("+55 (81) 99999-8888"));
        assertEquals("1133334444", PaymentService.normalizePhone("(11) 3333-4444"));
        assertNull(PaymentService.normalizePhone("(01) 99999-8888"));
        assertNull(PaymentService.normalizePhone("8199999888877"));
    }

    @Test
    void naoCriaCheckoutParaInscricaoConfirmada() {
        team.setStatus(TeamStatus.CONFIRMED);
        assertEquals(HttpStatus.CONFLICT, rejected(PAYER).getStatus());
    }

    @Test
    void novoCheckoutCancelaPendentesAnteriores() {
        Payment previous = existing(PaymentStatus.PENDING, null);
        previous.setId(9L);
        when(payments.findByTeamIdAndStatus(5L, PaymentStatus.PENDING)).thenReturn(List.of(previous));

        service.create(CODE, new CreatePaymentRequest(PAYER));

        assertEquals(PaymentStatus.CANCELLED, previous.getStatus());
    }

    @Test
    void pollingAtualizaPeloMercadoPagoComOMeioEscolhido() {
        Payment payment = existing(PaymentStatus.PENDING, null);
        when(payments.findByIdAndTeamId(1L, 5L)).thenReturn(Optional.of(payment));
        when(gateway.findByExternalReference("team:5:payment:1"))
                .thenReturn(Optional.of(result("123", PaymentStatus.APPROVED, "credit_card", 3)));

        PaymentDto dto = service.status(CODE, 1L);

        assertEquals(PaymentStatus.APPROVED, dto.status());
        assertEquals(PaymentMethod.CREDIT_CARD, dto.method());
        assertEquals(3, dto.installments());
        assertEquals("4242", dto.cardLastDigits());
        assertNotNull(dto.paidAt());
        assertEquals("123", payment.getProviderPaymentId());
    }

    @Test
    void debitoEPixGravamParcelasCompativeisComOBanco() {
        Payment payment = existing(PaymentStatus.PENDING, null);
        when(payments.findByIdAndTeamId(1L, 5L)).thenReturn(Optional.of(payment));
        when(gateway.findByExternalReference("team:5:payment:1"))
                .thenReturn(Optional.of(result("123", PaymentStatus.PENDING, "debit_card", null)));
        assertEquals(1, service.status(CODE, 1L).installments());

        when(gateway.findByExternalReference("team:5:payment:1"))
                .thenReturn(Optional.of(result("124", PaymentStatus.PENDING, "bank_transfer", 1)));
        PaymentDto pix = service.status(CODE, 1L);
        assertEquals(PaymentMethod.PIX, pix.method());
        assertNull(pix.installments());
    }

    @Test
    void pollingSemPagamentoNoMercadoPagoContinuaPendente() {
        Payment payment = existing(PaymentStatus.PENDING, null);
        when(payments.findByIdAndTeamId(1L, 5L)).thenReturn(Optional.of(payment));
        when(gateway.findByExternalReference("team:5:payment:1")).thenReturn(Optional.empty());

        PaymentDto dto = service.status(CODE, 1L);

        assertEquals(PaymentStatus.PENDING, dto.status());
        assertEquals(CHECKOUT, dto.checkoutUrl());
    }

    @Test
    void webhookDeOutraTentativaNaoMexeEmPagamentoAprovado() {
        Payment payment = existing(PaymentStatus.APPROVED, "111");
        when(gateway.fetch("222")).thenReturn(result("222", PaymentStatus.REJECTED, "credit_card", 1));
        when(payments.findByProviderAndProviderPaymentId("MERCADO_PAGO", "222")).thenReturn(Optional.empty());
        when(payments.findById(1L)).thenReturn(Optional.of(payment));

        service.syncFromProvider("222");

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals("111", payment.getProviderPaymentId());
        verify(payments, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void webhookAprovaTentativaSeguinteAUmaRecusa() {
        Payment payment = existing(PaymentStatus.REJECTED, "111");
        when(gateway.fetch("222")).thenReturn(result("222", PaymentStatus.APPROVED, "bank_transfer", 1));
        when(payments.findByProviderAndProviderPaymentId("MERCADO_PAGO", "222")).thenReturn(Optional.empty());
        when(payments.findById(1L)).thenReturn(Optional.of(payment));

        service.syncFromProvider("222");

        assertEquals(PaymentStatus.APPROVED, payment.getStatus());
        assertEquals(PaymentMethod.PIX, payment.getMethod());
        assertEquals("222", payment.getProviderPaymentId());
    }

    @Test
    void segundaAprovacaoDaMesmaEquipeNaoEhGravada() {
        Payment payment = existing(PaymentStatus.CANCELLED, null);
        when(gateway.fetch("333")).thenReturn(result("333", PaymentStatus.APPROVED, "credit_card", 1));
        when(payments.findByProviderAndProviderPaymentId("MERCADO_PAGO", "333")).thenReturn(Optional.empty());
        when(payments.findById(1L)).thenReturn(Optional.of(payment));
        when(payments.existsByTeamIdAndStatusAndIdNot(5L, PaymentStatus.APPROVED, 1L)).thenReturn(true);

        service.syncFromProvider("333");

        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        verify(payments, never()).saveAndFlush(any(Payment.class));
    }
}
