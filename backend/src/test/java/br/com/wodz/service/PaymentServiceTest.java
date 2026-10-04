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
import br.com.wodz.repository.PaymentRepository;
import br.com.wodz.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
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
    private static final Payer PAYER = new Payer("Ana", "Souza", "ana@wodz.dev", "529.982.247-25");

    private final TeamRepository teams = mock(TeamRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final AppProperties props = new AppProperties(null, null, null,
            new PaymentProps("sandbox", Duration.ofMinutes(30), null), null, null);
    private final PaymentService service = new PaymentService(teams, payments, new SandboxPaymentGateway(), props);

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
    void pixFicaPendenteComValidade() {
        PaymentDto dto = service.create(CODE, new CreatePaymentRequest(PaymentMethod.PIX, PAYER, null));
        assertEquals(PaymentStatus.PENDING, dto.status());
        assertNotNull(dto.pixQrCode());
        assertNotNull(dto.expiresAt());
        assertNull(dto.installments());
    }
}
