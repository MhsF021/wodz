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
        account.setAccessToken(cipher.encrypt("old-access")); account.setRefreshToken(cipher.encrypt("old-refresh"));
        account.setExpiresAt(Instant.now().plusSeconds(10));
        when(accounts.lockById(9L)).thenReturn(Optional.of(account));
        when(oauth.refresh("old-refresh",false)).thenReturn(new OAuthTokens("new-access","new-refresh","pk","123",3600,true));
    }

    private AppProperties props(boolean testMode) {
        return new AppProperties(null,null,null,new AppProperties.PaymentProps("mercadopago",null,
                new AppProperties.MercadoPagoProps("pk","secret",null,true,"id","secret",BigDecimal.ZERO,"platform",testMode)),
                null,null,"dev",null);
    }

    @Test
    void renovaTokenProximoDoVencimentoEGuardaRotacaoCifrada() {
        assertEquals("new-access",service.seller(9L,true).accessToken());
        assertEquals("new-refresh",cipher.decrypt(account.getRefreshToken()));
        assertNotEquals("new-access",account.getAccessToken());
        assertTrue(account.getExpiresAt().isAfter(Instant.now().plusSeconds(3500)));
        verify(accounts).saveAndFlush(account);
    }

    @Test
    void naoRenovaEnquantoTokenEstaValido() {
        account.setExpiresAt(Instant.now().plusSeconds(600));
        assertEquals("old-access",service.seller(9L,true).accessToken());
        verifyNoInteractions(oauth);
    }

    @Test
    void contaDesconectadaSoPodeConciliarPagamentosAnteriores() {
        account.setStatus(PayoutAccountStatus.DISCONNECTED);
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        assertEquals("new-access",service.seller(9L).accessToken());
    }

    @Test
    void renovacaoDeOutraContaNaoSubstituiCredenciais() {
        when(oauth.refresh("old-refresh",false)).thenReturn(new OAuthTokens("new-access","new-refresh","pk","456",3600,true));
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        assertEquals("old-access",cipher.decrypt(account.getAccessToken()));
        verify(accounts,never()).saveAndFlush(any());
    }

    @Test
    void modoDeTesteBloqueiaNovaCobrancaComTokenDeProducaoSemImpedirConciliacao() {
        service = new PayoutTokenService(accounts,cipher,provider,props(true));
        account.setExpiresAt(Instant.now().plusSeconds(600));
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        assertEquals("old-access",service.seller(9L).accessToken());
        verifyNoInteractions(oauth);
    }

    @Test
    void tokenDeTesteRenovaNoMesmoModoEPermiteNovaCobranca() {
        service = new PayoutTokenService(accounts,cipher,provider,props(true));
        account.setLiveMode(false);
        when(oauth.refresh("old-refresh",true)).thenReturn(
                new OAuthTokens("TEST-access","TEST-refresh","TEST-pk","123",3600,false));
        assertEquals("TEST-access",service.seller(9L,true).accessToken());
        assertFalse(account.isLiveMode());
        verify(oauth).refresh("old-refresh",true);
    }

    @Test
    void modoDeProducaoExigeReautorizarUmaContaComTokenDeTeste() {
        account.setLiveMode(false);
        account.setExpiresAt(Instant.now().plusSeconds(600));
        assertThrows(ApiException.class, () -> service.seller(9L,true));
        verifyNoInteractions(oauth);
    }
}
