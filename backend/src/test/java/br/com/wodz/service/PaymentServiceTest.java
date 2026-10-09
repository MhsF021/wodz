package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.config.AppProperties.PaymentProps;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.Payment;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.PaymentDtos.CardData;
import br.com.wodz.dto.PaymentDtos.CreatePaymentRequest;
import br.com.wodz.dto.PaymentDtos.Payer;
import br.com.wodz.dto.PaymentDtos.PaymentDto;
import br.com.wodz.payment.SandboxPaymentGateway;
import br.com.wodz.payment.MercadoPagoGateway;
import br.com.wodz.payment.PaymentCommand;
import br.com.wodz.payment.PaymentGateway;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import br.com.wodz.repository.PaymentRepository;
import br.com.wodz.repository.PayoutAccountRepository;
import br.com.wodz.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    private static final Payer PAYER = new Payer("Ana", "Souza", "ana@wodz.dev", "529.982.247-25");

    private final TeamRepository teams = mock(TeamRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PayoutAccountRepository payoutAccounts = mock(PayoutAccountRepository.class);
    private final AppProperties props = new AppProperties(null, null, null,
            new PaymentProps("sandbox", Duration.ofMinutes(30), null), null, null, null, null);
    private final PaymentService service = new PaymentService(teams, payments, payoutAccounts,
            new SandboxPaymentGateway(), props, mock(PayoutTokenService.class));

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
        Team team = new Team();
        team.setId(5L);
        team.setName("Equipe Alfa");
        team.setStatus(TeamStatus.PENDING_PAYMENT);
        team.setRegistrationCode(CODE);
        team.setCategory(category);

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

    private static CardData card(Integer installments) {
        return new CardData("sandbox-approved", "visa", null, installments, "4242");
    }

    private ApiException rejected(CreatePaymentRequest req) {
        ApiException e = assertThrows(ApiException.class, () -> service.create(CODE, req));
        verify(payments, never()).saveAndFlush(any(Payment.class));
        return e;
    }

    @Test
    void recusaBoletoMesmoSeOClienteEnviar() {
        ApiException e = rejected(new CreatePaymentRequest(PaymentMethod.BOLETO, PAYER, null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void debitoNaoAceitaParcelamento() {
        ApiException e = rejected(new CreatePaymentRequest(PaymentMethod.DEBIT_CARD, PAYER, card(3)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void cartaoExigeDadosDoCartao() {
        assertEquals(HttpStatus.BAD_REQUEST,
                rejected(new CreatePaymentRequest(PaymentMethod.DEBIT_CARD, PAYER, null)).getStatus());
    }

    @Test
    void pixNaoAceitaDadosDeCartao() {
        assertEquals(HttpStatus.BAD_REQUEST,
                rejected(new CreatePaymentRequest(PaymentMethod.PIX, PAYER, card(1))).getStatus());
    }

    @Test
    void debitoEhGravadoAVista() {
        PaymentDto dto = service.create(CODE, new CreatePaymentRequest(PaymentMethod.DEBIT_CARD, PAYER, card(null)));
        assertEquals(PaymentMethod.DEBIT_CARD, dto.method());
        assertEquals(PaymentStatus.APPROVED, dto.status());
        assertEquals(1, dto.installments());
        assertNull(dto.expiresAt());
        assertNull(dto.threeDsChallenge());
    }

    @Test
    void creditoMantemAsParcelasEscolhidas() {
        PaymentDto dto = service.create(CODE, new CreatePaymentRequest(PaymentMethod.CREDIT_CARD, PAYER, card(3)));
        assertEquals(PaymentMethod.CREDIT_CARD, dto.method());
        assertEquals(3, dto.installments());
    }

    @Test
    void normalizaNomeDoPagadorAntesDoGatewayPreservandoEmailCpfEToken() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.providerName()).thenReturn("SANDBOX");
        when(gateway.create(any(PaymentCommand.class)))
                .thenAnswer(invocation -> new SandboxPaymentGateway().create(invocation.getArgument(0)));
        PaymentService checkout = new PaymentService(teams, payments, payoutAccounts, gateway, props,
                mock(PayoutTokenService.class));
        Payer payer = new Payer(" João ", " D'Ávila ", "Joao.DAvila+MP@Example.test", "529.982.247-25");
        CardData card = new CardData("AbCd_123-Test", "visa", "123", 3, "4242");

        checkout.create(CODE, new CreatePaymentRequest(PaymentMethod.CREDIT_CARD, payer, card));

        ArgumentCaptor<PaymentCommand> sent = ArgumentCaptor.forClass(PaymentCommand.class);
        verify(gateway).create(sent.capture());
        PaymentCommand command = sent.getValue();
        assertEquals("JOÃO", command.payer().firstName());
        assertEquals("D'ÁVILA", command.payer().lastName());
        assertEquals(payer.email(), command.payer().email());
        assertEquals(payer.cpf(), command.payer().cpf());
        assertSame(card, command.card());
        assertEquals("AbCd_123-Test", command.card().token());
        assertEquals(3, command.installments());
    }

    @Test
    void pixFicaPendenteComValidade() {
        PaymentDto dto = service.create(CODE, new CreatePaymentRequest(PaymentMethod.PIX, PAYER, null));
        assertEquals(PaymentStatus.PENDING, dto.status());
        assertNotNull(dto.pixQrCode());
        assertNotNull(dto.expiresAt());
        assertNull(dto.installments());
    }

    @Test
    void testeOficialUsaGatewayRemotoENaoPermiteAprovacaoSimulada() {
        AppProperties testProps = new AppProperties(null,null,null,
                new PaymentProps("mercadopago",Duration.ofMinutes(30),
                        new AppProperties.MercadoPagoProps("TEST-public-key","secret",null,true,"client","secret",
                                BigDecimal.ZERO,"platform",true)),null,null,"dev",null);
        PaymentService official = new PaymentService(teams,payments,payoutAccounts,
                new MercadoPagoGateway(testProps,RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory())),
                testProps,mock(PayoutTokenService.class));
        assertEquals("MERCADO_PAGO",official.publicConfig().paymentProvider());
        assertEquals("TEST-public-key",official.publicConfig().mercadoPagoPublicKey());
        assertEquals(false,official.publicConfig().paymentSimulation());
        assertEquals(true,official.publicConfig().mercadoPagoTestMode());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                () -> official.simulateApproval(CODE,1L)).getStatus());
        verify(payments,never()).findByIdAndTeamId(anyLong(),anyLong());
    }
}
