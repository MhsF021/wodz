package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.TokenCipher;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.*;
import br.com.wodz.payment.MercadoPagoOAuthClient;
import br.com.wodz.payment.MercadoPagoOAuthClient.OAuthTokens;
import br.com.wodz.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayoutAccountServiceTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PayoutAccountRepository accounts = mock(PayoutAccountRepository.class);
    private final PayoutOAuthStateRepository states = mock(PayoutOAuthStateRepository.class);
    private final MercadoPagoOAuthClient oauth = mock(MercadoPagoOAuthClient.class);
    private final PayoutTokenService tokens = mock(PayoutTokenService.class);
    private final AppProperties props = new AppProperties("https://wodz.test/", null, null,
            new AppProperties.PaymentProps("mercadopago", Duration.ofMinutes(30),
                    new AppProperties.MercadoPagoProps("pk", "secret", "https://wodz.test/api/webhooks/mercadopago", true,
                            "client", "client-secret", BigDecimal.ZERO, "platform", false)), null, null, "prod",
            new AppProperties.SecurityProps("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    private final TokenCipher cipher = new TokenCipher(props);
    private PayoutAccountService service;
    private PayoutOAuthState state;
    private final OAuthTokens credential = new OAuthTokens("secret-access", "secret-refresh", "pk", "123", 3600, true);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ObjectProvider<MercadoPagoOAuthClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oauth);
        service = new PayoutAccountService(props, users, accounts, states, cipher, tokens, provider);
        AppUser user = new AppUser(); user.setId(1L); user.setRole(Role.ORGANIZER);
        when(users.lockActiveById(1L)).thenReturn(Optional.of(user));
        state = new PayoutOAuthState(); state.setState("state"); state.setUserId(1L);
        state.setExpiresAt(Instant.now().plusSeconds(600)); state.setCodeVerifier(cipher.encrypt("verifier"));
        when(states.findById("state")).thenReturn(Optional.of(state));
        when(states.consume("state")).thenReturn(1);
        when(oauth.exchangeCode("code","verifier",service.redirectUri())).thenReturn(credential);
        when(oauth.validateAccount(credential)).thenReturn("Organizador (org@test.invalid)");
    }

    @Test
    void soSalvaDepoisDeValidarAContaNoProvedor() {
        service.complete("state","code");
        var order = inOrder(states,oauth,tokens,accounts);
        order.verify(states).consume("state");
        order.verify(oauth).exchangeCode("code","verifier",service.redirectUri());
        order.verify(oauth).validateAccount(credential);
        order.verify(accounts).findFirstByUserIdAndStatus(1L,PayoutAccountStatus.ACTIVE);
        order.verify(tokens).storeTokens(any(PayoutAccount.class),eq(credential));
        order.verify(accounts).saveAndFlush(argThat(a -> a.getUserId().equals(1L) && a.getProviderUserId().equals("123")));
    }

    @Test
    void conectaDiretamenteNoMercadoPagoComStatePersistidoPkceECallback() {
        MercadoPagoOAuthClient provider = new MercadoPagoOAuthClient(props,
                RestClient.builder().requestFactory(mock(ClientHttpRequestFactory.class)));
        when(oauth.authorizationUrl(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                provider.authorizationUrl(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        var authorization = service.connect(1L);
        var saved = org.mockito.ArgumentCaptor.forClass(PayoutOAuthState.class);
        verify(states).save(saved.capture());
        PayoutOAuthState pending = saved.getValue();

        URI uri = URI.create(authorization.authorizationUrl());
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        assertEquals("https", uri.getScheme());
        assertEquals("auth.mercadopago.com", uri.getHost());
        assertEquals("/authorization", uri.getPath());
        assertEquals("client", query.getFirst("client_id"));
        assertEquals("code", query.getFirst("response_type"));
        assertEquals("mp", query.getFirst("platform_id"));
        assertEquals(pending.getState(), query.getFirst("state"));
        assertEquals(service.redirectUri(), URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8));
        String verifier = cipher.decrypt(pending.getCodeVerifier());
        assertEquals(PayoutAccountService.challenge(verifier), query.getFirst("code_challenge"));
        assertEquals("S256", query.getFirst("code_challenge_method"));
        assertEquals(1L, pending.getUserId());
        assertTrue(pending.getState().matches("[A-Za-z0-9_-]{43}"));
        assertTrue(verifier.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(verifier, pending.getCodeVerifier());
        assertTrue(pending.getExpiresAt().isAfter(Instant.now()));
        verify(oauth).authorizationUrl(eq(pending.getState()),
                eq(PayoutAccountService.challenge(cipher.decrypt(pending.getCodeVerifier()))), eq(service.redirectUri()));
        verify(states, never()).consume(anyString());
    }

    @Test
    void stateExpiradoOuReutilizadoNaoTrocaCodigoNemSalvaConta() {
        state.setExpiresAt(Instant.now().minusSeconds(1));
        assertThrows(ApiException.class, () -> service.complete("state","code"));
        state.setExpiresAt(Instant.now().plusSeconds(600));
        when(states.consume("state")).thenReturn(0);
        assertThrows(ApiException.class, () -> service.complete("state","code"));
        verifyNoInteractions(oauth,tokens,accounts);
    }

    @Test
    void contaRecusadaNaoFicaAtiva() {
        when(oauth.validateAccount(credential)).thenThrow(ApiException.unprocessable("Conta suspensa"));
        assertThrows(ApiException.class, () -> service.complete("state","code"));
        verifyNoInteractions(tokens,accounts);
    }

    @Test
    void recusaContaDeTesteEmProducao() {
        when(oauth.exchangeCode(anyString(),anyString(),anyString())).thenReturn(
                new OAuthTokens("access","refresh","pk","123",3600,false));
        assertThrows(ApiException.class, () -> service.complete("state","code"));
        verify(oauth,never()).validateAccount(any());
        verifyNoInteractions(tokens,accounts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void modoDeTesteAceitaSomenteCredenciaisDeTesteConfirmadasNoProvedor() {
        AppProperties testProps = new AppProperties(props.frontendUrl(),null,null,
                new AppProperties.PaymentProps("mercadopago",Duration.ofMinutes(30),
                        new AppProperties.MercadoPagoProps("TEST-pk","secret",null,true,"client","client-secret",
                                BigDecimal.ZERO,"platform",true)),null,null,"dev",props.security());
        ObjectProvider<MercadoPagoOAuthClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oauth);
        service = new PayoutAccountService(testProps,users,accounts,states,cipher,tokens,provider);
        assertTrue(service.status(1L).testMode());
        assertFalse(service.status(1L).sandbox());

        // Um retorno de produção não pode habilitar cobranças no ambiente de testes.
        assertThrows(ApiException.class, () -> service.complete("state","code"));
        verifyNoInteractions(tokens);
        verify(oauth,never()).validateAccount(credential);

        OAuthTokens testCredential = new OAuthTokens("TEST-access","refresh","TEST-pk","123",3600,false);
        when(oauth.exchangeCode("code","verifier",service.redirectUri())).thenReturn(testCredential);
        when(oauth.validateAccount(testCredential)).thenReturn("Conta autorizada para testes");
        service.complete("state","code");
        verify(oauth).validateAccount(testCredential);
        verify(tokens).storeTokens(any(PayoutAccount.class),eq(testCredential));
    }

    @Test
    @SuppressWarnings("unchecked")
    void alternarModoPreservaCredenciaisDasCobrancasAnteriores() {
        AppProperties testProps = new AppProperties(props.frontendUrl(),null,null,
                new AppProperties.PaymentProps("mercadopago",Duration.ofMinutes(30),
                        new AppProperties.MercadoPagoProps("TEST-pk","secret",null,true,"client","client-secret",
                                BigDecimal.ZERO,"platform",true)),null,null,"dev",props.security());
        ObjectProvider<MercadoPagoOAuthClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oauth);
        service = new PayoutAccountService(testProps,users,accounts,states,cipher,tokens,provider);
        PayoutAccount previous = new PayoutAccount();
        previous.setId(9L); previous.setUserId(1L); previous.setProviderUserId("123");
        previous.setLiveMode(true); previous.setAccessToken("encrypted-production-access");
        previous.setRefreshToken("encrypted-production-refresh");
        when(accounts.findFirstByUserIdAndStatus(1L,PayoutAccountStatus.ACTIVE)).thenReturn(Optional.of(previous));
        OAuthTokens testCredential = new OAuthTokens("TEST-access","refresh","TEST-pk","123",3600,false);
        when(oauth.exchangeCode("code","verifier",service.redirectUri())).thenReturn(testCredential);
        when(oauth.validateAccount(testCredential)).thenReturn("Conta autorizada para testes");

        service.complete("state","code");

        assertEquals(PayoutAccountStatus.DISCONNECTED,previous.getStatus());
        assertEquals("encrypted-production-access",previous.getAccessToken());
        assertEquals("encrypted-production-refresh",previous.getRefreshToken());
        assertTrue(previous.isLiveMode());
        var order = inOrder(accounts,tokens);
        order.verify(accounts).findFirstByUserIdAndStatus(1L,PayoutAccountStatus.ACTIVE);
        order.verify(accounts).saveAndFlush(previous);
        order.verify(tokens).storeTokens(argThat(a -> a != previous && a.getId() == null
                && a.getProviderUserId().equals("123")),eq(testCredential));
        order.verify(accounts).saveAndFlush(argThat(a -> a != previous && a.getId() == null));
    }

    @Test
    void recusaTrocarContaSemDesconectar() {
        PayoutAccount previous = new PayoutAccount(); previous.setProviderUserId("456");
        when(accounts.findFirstByUserIdAndStatus(1L,PayoutAccountStatus.ACTIVE)).thenReturn(Optional.of(previous));
        assertThrows(ApiException.class, () -> service.complete("state","code"));
        verify(accounts,never()).saveAndFlush(any());
        verifyNoInteractions(tokens);
    }

    @Test
    void pkceSegueExemploRfc7636ETokensSaoCifrados() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                PayoutAccountService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        String a = cipher.encrypt("token");
        assertNotEquals(a,cipher.encrypt("token"));
        assertFalse(a.contains("token"));
        assertEquals("token",cipher.decrypt(a));
        assertEquals("https://wodz.test/api/public/mercadopago/callback",service.redirectUri());
    }

    @Test
    void retornoDaJanelaUsaApenasResultadoGenericoSemCredenciais() {
        assertEquals("https://wodz.test/mercadopago/retorno?recebimentos=connected",service.profileResultUrl(true));
        assertEquals("https://wodz.test/mercadopago/retorno?recebimentos=error",service.profileResultUrl(false));
    }

    @Test
    void statusRetornaVersaoAtualizadaParaConfirmarRenovacao() {
        PayoutAccount account = new PayoutAccount();
        account.setProviderUserId("123");
        account.setLiveMode(true);
        account.setConnectedAt(Instant.parse("2026-01-01T00:00:00Z"));
        account.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        when(accounts.findFirstByUserIdAndStatus(1L,PayoutAccountStatus.ACTIVE)).thenReturn(Optional.of(account));

        var status = service.status(1L);

        assertTrue(status.connected());
        assertEquals(account.getUpdatedAt(),status.updatedAt());
        assertEquals(account.getConnectedAt(),status.connectedAt());
        assertNotEquals(status.connectedAt(),status.updatedAt());
    }
}
