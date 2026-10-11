package br.com.wodz.service;
import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.*;
import br.com.wodz.payment.*;
import br.com.wodz.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final CheckoutSessionRepository sessions = mock(CheckoutSessionRepository.class);
    private final PayoutAccountRepository accounts = mock(PayoutAccountRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PayoutTokenService tokens = mock(PayoutTokenService.class);
    private final AppProperties props = new AppProperties("https://wodz.test",null,null,
            new AppProperties.PaymentProps("mercadopago",Duration.ofMinutes(30),
                    new AppProperties.MercadoPagoProps("secret","https://wodz.test/api/webhooks/mercadopago",
                            "client","secret",new BigDecimal("5"),true)),null,null,"dev",null);
    private final PaymentService service = new PaymentService(teams,payments,sessions,accounts,gateway,props,tokens);
    private Team team;
    private PayoutAccount account;
    private CheckoutSession session;
    @BeforeEach void fixture() {
        AppUser owner = new AppUser(); owner.setId(10L);
        Championship c = new Championship(); c.setOwner(owner); c.setStartDate(LocalDate.now().plusMonths(2));
        c.setName("EVENTO"); c.setSlug("evento");
        Category category = new Category(); category.setChampionship(c); category.setPrice(new BigDecimal("150.00")); category.setName("RX");
        team = new Team(); team.setId(5L); team.setRegistrationCode("ABC"); team.setName("EQUIPE");
        team.setCategory(category); team.setStatus(TeamStatus.PENDING_PAYMENT);
        account = new PayoutAccount(); account.setId(7L); account.setProviderUserId("123"); account.setLiveMode(false);
        account.setAccessToken("encrypted"); account.setStatus(PayoutAccountStatus.ACTIVE);
        session = new CheckoutSession(); session.setId(20L); session.setTeam(team); session.setPayoutAccountId(7L);
        session.setAmount(new BigDecimal("150.00")); session.setExternalReference("reference");
        session.setProviderPreferenceId("pref");
        session.setCheckoutUrl("https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=1");
        session.setExpiresAt(Instant.now().plusSeconds(600));
        when(teams.findByRegistrationCode("ABC")).thenReturn(Optional.of(team));
        when(teams.lockById(5L)).thenReturn(Optional.of(team));
        when(gateway.providerName()).thenReturn("MERCADO_PAGO"); when(gateway.supportsRemoteFetch()).thenReturn(true);
        when(accounts.findFirstByUserIdAndStatus(10L,PayoutAccountStatus.ACTIVE)).thenReturn(Optional.of(account));
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(tokens.seller(7L,true)).thenReturn(new SellerAccount(7L,"123","APP_USR-test"));
        when(tokens.seller(7L)).thenReturn(new SellerAccount(7L,"123","APP_USR-test"));
        when(sessions.saveAndFlush(any())).thenAnswer(i -> { CheckoutSession s=i.getArgument(0);s.setId(20L);return s; });
        when(gateway.createCheckout(any())).thenReturn(new GatewayCheckout("pref",session.getCheckoutUrl()));
        when(sessions.findByIdAndTeamId(20L,5L)).thenReturn(Optional.of(session));
        when(sessions.findByExternalReference("reference")).thenReturn(Optional.of(session));
        when(sessions.findById(20L)).thenReturn(Optional.of(session));
    }
    private GatewayPayment payment(String id, PaymentStatus status) {
        return new GatewayPayment(id,status,"detail",new BigDecimal("150.00"),"BRL","reference","123",
                false,PaymentMethod.PIX,null,status==PaymentStatus.APPROVED?Instant.now():null);
    }
    @Test void checkoutUsaValorDoServidorContaOAuthETaxaDaPreferencia() {
        var result=service.createCheckout("abc");
        ArgumentCaptor<CheckoutCommand> capture=ArgumentCaptor.forClass(CheckoutCommand.class);
        verify(gateway).createCheckout(capture.capture()); CheckoutCommand c=capture.getValue();
        assertEquals(new BigDecimal("150.00"),c.amount()); assertEquals(new BigDecimal("7.50"),c.marketplaceFee());
        assertEquals(7L,c.seller().payoutAccountId()); assertTrue(c.testMode());
        assertEquals("https://wodz.test/pagamento/ABC?checkout=20",c.returnUrl());
        assertDoesNotThrow(()->UUID.fromString(c.externalReference())); assertEquals(20L,result.id());
        verify(payments,never()).saveAndFlush(any());
    }
    @Test void cliquesRepetidosReutilizamPreferenciaSemCriarTentativasFicticias() {
        when(sessions.findFirstByTeamIdOrderByIdDesc(5L)).thenReturn(Optional.of(session));
        when(gateway.resumeCheckout(eq("pref"),any())).thenReturn(new GatewayCheckout("pref",session.getCheckoutUrl()));
        assertEquals(session.getCheckoutUrl(),service.createCheckout("ABC").url());
        verify(gateway).resumeCheckout(eq("pref"),any());
        verify(gateway,never()).createCheckout(any()); verify(payments,never()).saveAndFlush(any());
    }
    @Test void preferenciaExpiradaNoProvedorGeraNovaSomenteAposConsultarPagamentos() {
        when(sessions.findFirstByTeamIdOrderByIdDesc(5L)).thenReturn(Optional.of(session));
        when(gateway.resumeCheckout(eq("pref"),any())).thenReturn(null);
        service.createCheckout("ABC");
        var order = inOrder(gateway);
        order.verify(gateway).search(eq("reference"),any());
        order.verify(gateway).resumeCheckout(eq("pref"),any());
        order.verify(gateway).createCheckout(any());
        verify(payments,never()).saveAndFlush(any());
    }
    @Test void falhaAoValidarPreferenciaNaoCriaOutraCobranca() {
        when(sessions.findFirstByTeamIdOrderByIdDesc(5L)).thenReturn(Optional.of(session));
        when(gateway.resumeCheckout(eq("pref"),any())).thenThrow(ApiException.unprocessable("Aplicação incompatível"));
        assertThrows(ApiException.class, () -> service.createCheckout("ABC"));
        verify(gateway,never()).createCheckout(any());
        verify(payments,never()).saveAndFlush(any());
    }
    @Test void checkoutAntigoSandboxNaoEReutilizadoDepoisDaReautorizacao() {
        session.setCheckoutUrl("https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=old");
        when(sessions.findFirstByTeamIdOrderByIdDesc(5L)).thenReturn(Optional.of(session));
        String currentUrl = "https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=new";
        when(gateway.createCheckout(any())).thenReturn(new GatewayCheckout("new", currentUrl));
        assertEquals(currentUrl, service.createCheckout("ABC").url());
        verify(gateway).createCheckout(any());
    }
    @Test void naoReabreCheckoutSeAprovacaoAindaNaoChegouPeloWebhook() {
        when(sessions.findFirstByTeamIdOrderByIdDesc(5L)).thenReturn(Optional.of(session));
        when(gateway.search(eq("reference"),any())).thenReturn(List.of(payment("300",PaymentStatus.APPROVED)));
        assertThrows(ApiException.class,()->service.createCheckout("ABC"));verify(gateway,never()).createCheckout(any());
    }
    @Test void resumoMantemValorCotadoESomenteTentativasDoCheckoutAtual() {
        team.getCategory().setPrice(new BigDecimal("200.00"));
        when(sessions.findFirstByTeamIdOrderByIdDesc(5L)).thenReturn(Optional.of(session));
        assertEquals(new BigDecimal("150.00"),service.summary("ABC").amount());
        verify(payments).findFirstByCheckoutSessionIdOrderByIdDesc(20L);
    }
    @Test void naoCriaCheckoutParaInscricaoPagaCanceladaOuEventoIniciado() {
        for(TeamStatus status:List.of(TeamStatus.CONFIRMED,TeamStatus.CANCELLED)) {
            team.setStatus(status); assertThrows(ApiException.class,()->service.createCheckout("ABC"));
        }
        team.setStatus(TeamStatus.PENDING_PAYMENT);team.getCategory().getChampionship().setStartDate(LocalDate.now().minusDays(1));
        assertThrows(ApiException.class,()->service.createCheckout("ABC"));verify(gateway,never()).createCheckout(any());
    }
    @Test void naoAceitaOrganizadorSemContaOuModoNaoValidado() {
        when(accounts.findFirstByUserIdAndStatus(10L,PayoutAccountStatus.ACTIVE)).thenReturn(Optional.empty());
        assertThrows(ApiException.class,()->service.createCheckout("ABC")); verify(gateway,never()).createCheckout(any());
    }
    @Test void retornoDeOutraInscricaoNaoConsultaNemConfirmaPagamento() {
        when(sessions.findByIdAndTeamId(20L,5L)).thenReturn(Optional.empty());
        assertThrows(ApiException.class,()->service.checkoutStatus("ABC",20L,"300")); verify(gateway,never()).fetch(any(),any());
    }
    @Test void validaValorMoedaReferenciaRecebedorEModoAntesDeSalvar() {
        List<GatewayPayment> invalid = List.of(
            new GatewayPayment("300",PaymentStatus.APPROVED,"",BigDecimal.ONE,"BRL","reference","123",false,PaymentMethod.PIX,null,null),
            new GatewayPayment("300",PaymentStatus.APPROVED,"",session.getAmount(),"USD","reference","123",false,PaymentMethod.PIX,null,null),
            new GatewayPayment("300",PaymentStatus.APPROVED,"",session.getAmount(),"BRL","other","123",false,PaymentMethod.PIX,null,null),
            new GatewayPayment("300",PaymentStatus.APPROVED,"",session.getAmount(),"BRL","reference","456",false,PaymentMethod.PIX,null,null),
            new GatewayPayment("300",PaymentStatus.APPROVED,"",session.getAmount(),"BRL","reference","123",true,PaymentMethod.PIX,null,null),
            new GatewayPayment("300",PaymentStatus.APPROVED,"",session.getAmount(),"BRL","reference","123",null,PaymentMethod.PIX,null,null));
        for(GatewayPayment p:invalid) {
            when(gateway.fetch(eq("300"),any())).thenReturn(p);
            assertThrows(ApiException.class,()->service.checkoutStatus("ABC",20L,"300"));
        }
        verify(payments,never()).saveAndFlush(any());
    }
    @Test void retornoDePagamentoPendenteNuncaEInterpretadoComoAprovado() {
        when(gateway.fetch(eq("300"),any())).thenReturn(payment("300",PaymentStatus.PENDING));
        service.checkoutStatus("ABC",20L,"300");
        ArgumentCaptor<Payment> saved=ArgumentCaptor.forClass(Payment.class);verify(payments).saveAndFlush(saved.capture());
        assertEquals(PaymentStatus.PENDING,saved.getValue().getStatus());assertNull(saved.getValue().getPaidAt());
        assertEquals(20L,saved.getValue().getCheckoutSessionId());
    }
    @Test void recusasEAprovacaoDaMesmaPreferenciaFicamEmRegistrosSeparados() {
        when(gateway.search(eq("reference"),any())).thenReturn(List.of(payment("300",PaymentStatus.REJECTED),payment("301",PaymentStatus.APPROVED)));
        service.checkoutStatus("ABC",20L,null);
        ArgumentCaptor<Payment> saved=ArgumentCaptor.forClass(Payment.class);verify(payments,times(2)).saveAndFlush(saved.capture());
        assertEquals(List.of("300","301"),saved.getAllValues().stream().map(Payment::getProviderPaymentId).toList());
        assertEquals(PaymentStatus.APPROVED,saved.getAllValues().get(1).getStatus());
        assertNotSame(saved.getAllValues().get(0),saved.getAllValues().get(1));
    }
    @Test void webhookDescobrePagamentoNovoPelaReferenciaVerificadaNaApi() {
        when(accounts.findByProviderAndProviderUserId("MERCADO_PAGO","123")).thenReturn(List.of(account));
        when(gateway.fetch(eq("300"),any())).thenReturn(payment("300",PaymentStatus.APPROVED));
        service.syncFromProvider("300","123");verify(payments).saveAndFlush(any(Payment.class));
    }
    @Test void webhookSemDicaNaoTentaAprovarCorpoNaoVerificado() {
        service.syncFromProvider("300",null);verify(payments,never()).saveAndFlush(any());
    }
    @Test void webhookLocalizaCredencialMesmoComVinculoDeOutroModoNoHistorico() {
        PayoutAccount old = new PayoutAccount(); old.setId(6L); old.setAccessToken("encrypted");
        when(accounts.findByProviderAndProviderUserId("MERCADO_PAGO","123")).thenReturn(List.of(old,account));
        when(tokens.seller(6L)).thenThrow(ApiException.unprocessable("Conta anterior indisponível"));
        when(gateway.fetch(eq("300"),any())).thenReturn(payment("300",PaymentStatus.APPROVED));
        service.syncFromProvider("300","123");verify(payments).saveAndFlush(any(Payment.class));
    }
    @Test void consultaSempreUsaContaOriginalMesmoDepoisDaDesconexao() {
        account.setStatus(PayoutAccountStatus.DISCONNECTED);
        when(gateway.search(eq("reference"),any())).thenReturn(List.of(payment("300",PaymentStatus.APPROVED)));
        service.reconcileCheckout(20L); verify(tokens).seller(7L);verify(tokens,never()).seller(7L,true);
    }
}
