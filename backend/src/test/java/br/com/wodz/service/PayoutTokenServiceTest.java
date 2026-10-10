package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.TokenCipher;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.PayoutAccount;
import br.com.wodz.domain.PayoutAccountStatus;
import br.com.wodz.payment.MercadoPagoOAuthClient;
import br.com.wodz.payment.MercadoPagoOAuthClient.OAuthTokens;
import br.com.wodz.repository.PayoutAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class PayoutTokenServiceTest {
    private final PayoutAccountRepository accounts = mock(PayoutAccountRepository.class);
    private final MercadoPagoOAuthClient oauth = mock(MercadoPagoOAuthClient.class);
    private final TokenCipher cipher = new TokenCipher(new AppProperties(null,null,null,null,null,null,"dev",null));
    private PayoutTokenService service;
    private PayoutAccount account;
    private ObjectProvider<MercadoPagoOAuthClient> provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(oauth);
        service = new PayoutTokenService(accounts,cipher,provider,props(false));
        account = new PayoutAccount(); account.setId(9L); account.setProviderUserId("123"); account.setLiveMode(true);
        account.setAccessToken(cipher.encrypt("APP_USR-old")); account.setRefreshToken(cipher.encrypt("old-refresh"));
        account.setExpiresAt(Instant.now().plusSeconds(10));
        when(accounts.lockById(9L)).thenReturn(Optional.of(account));
        when(oauth.refreshCheckout("old-refresh",false)).thenReturn(new OAuthTokens("APP_USR-new","new-refresh","123",3600,true));
    }

    private AppProperties props(boolean testMode) {
        return new AppProperties(null,null,null,new AppProperties.PaymentProps("mercadopago",null,
                new AppProperties.MercadoPagoProps("secret", null, "id", "secret", BigDecimal.ZERO, testMode)),
                null,null,"dev",null);
    }

    @Test
    void renovaTokenProximoDoVencimentoEGuardaRotacaoCifrada() {
        assertEquals("APP_USR-new",service.seller(9L,true).accessToken());
        assertEquals("new-refresh",cipher.decrypt(account.getRefreshToken()));
        assertNotEquals("APP_USR-new",account.getAccessToken());
        assertTrue(account.getExpiresAt().isAfter(Instant.now().plusSeconds(3500)));
        verify(accounts).saveAndFlush(account);
    }

    @Test
    void naoRenovaEnquantoTokenEstaValido() {
        account.setExpiresAt(Instant.now().plusSeconds(600));
        assertEquals("APP_USR-old",service.seller(9L,true).accessToken());
        verifyNoInteractions(oauth);
    }

    @Test
    void contaDesconectadaSoPodeConciliarPagamentosAnteriores() {
        account.setStatus(PayoutAccountStatus.DISCONNECTED);
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        assertEquals("APP_USR-new",service.seller(9L).accessToken());
    }

    @Test
    void renovacaoDeOutraContaNaoSubstituiCredenciais() {
        when(oauth.refreshCheckout("old-refresh",false)).thenReturn(new OAuthTokens("APP_USR-new","new-refresh","456",3600,true));
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        assertEquals("APP_USR-old",cipher.decrypt(account.getAccessToken()));
        verify(accounts,never()).saveAndFlush(any());
    }

    @Test
    void modoDeTesteBloqueiaNovaCobrancaComTokenDeProducaoSemImpedirConciliacao() {
        service = new PayoutTokenService(accounts,cipher,provider,props(true));
        account.setExpiresAt(Instant.now().plusSeconds(600));
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        assertEquals("APP_USR-old",service.seller(9L).accessToken());
        verifyNoInteractions(oauth);
    }

    @Test
    void contaDeTesteRenovaAppUsrNoMesmoModoEPermiteCheckoutPro() {
        service = new PayoutTokenService(accounts,cipher,provider,props(true));
        account.setLiveMode(false);
        when(oauth.refreshCheckout("old-refresh",true)).thenReturn(
                new OAuthTokens("APP_USR-test","new-refresh","123",3600,false));
        assertEquals("APP_USR-test",service.seller(9L,true).accessToken());
        assertFalse(account.isLiveMode());
        verify(oauth).refreshCheckout("old-refresh",true);
    }

    @Test
    void tokenTestLegadoNaoERenovadoNemUsadoParaConsultarOuCobrar() {
        service = new PayoutTokenService(accounts,cipher,provider,props(true));
        account.setLiveMode(false);
        account.setAccessToken(cipher.encrypt("TEST-old"));
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        assertThrows(ApiException.class, () -> service.seller(9L));
        assertEquals("TEST-old", cipher.decrypt(account.getAccessToken()));
        verifyNoInteractions(oauth);
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void modoDeProducaoExigeReautorizarUmaContaComTokenDeTeste() {
        account.setLiveMode(false);
        account.setExpiresAt(Instant.now().plusSeconds(600));
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        verifyNoInteractions(oauth);
    }
}
