package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.TokenCipher;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.PayoutAccount;
import br.com.wodz.payment.MercadoPagoOAuthClient;
import br.com.wodz.payment.MercadoPagoOAuthClient.OAuthTokens;
import br.com.wodz.payment.SellerAccount;
import br.com.wodz.repository.PayoutAccountRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PayoutTokenService {
    private final PayoutAccountRepository accounts;
    private final TokenCipher cipher;
    private final ObjectProvider<MercadoPagoOAuthClient> oauth;
    private final boolean testMode;

    public PayoutTokenService(PayoutAccountRepository accounts, TokenCipher cipher,
                             ObjectProvider<MercadoPagoOAuthClient> oauth, AppProperties props) {
        this.accounts = accounts;
        this.cipher = cipher;
        this.oauth = oauth;
        this.testMode = props.payment().mercadopago().testMode();
    }

    // A rotação do refresh token persiste mesmo se a tentativa de pagamento falhar depois.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SellerAccount seller(Long accountId) {
        return seller(accountId, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SellerAccount seller(Long accountId, boolean requireActive) {
        PayoutAccount account = accounts.lockById(accountId)
                .orElseThrow(() -> ApiException.unprocessable("Conta de recebimento não encontrada."));
        if ((requireActive && !account.isActive()) || account.getAccessToken() == null) {
            throw ApiException.unprocessable("A conta Mercado Pago precisa ser conectada novamente.");
        }
        String accessToken = cipher.decrypt(account.getAccessToken());
        if (!accessToken.startsWith("APP_USR-")) {
            throw ApiException.unprocessable("O organizador precisa reconectar sua conta Mercado Pago para usar Checkout Pro.");
        }
        if (requireActive && account.isLiveMode() == testMode) {
            throw ApiException.unprocessable(testMode
                    ? "Renove a autorização da conta Mercado Pago em Minha conta para usar credenciais de teste."
                    : "Renove a autorização da conta Mercado Pago em Minha conta para usar credenciais de produção.");
        }
        if (account.getExpiresAt() == null || account.getExpiresAt().isBefore(Instant.now().plusSeconds(120))) {
            MercadoPagoOAuthClient client = oauth.getIfAvailable();
            if (client == null || account.getRefreshToken() == null) {
                throw ApiException.unprocessable("Conecte a conta Mercado Pago novamente para renovar a autorização.");
            }
            OAuthTokens tokens = client.refreshCheckout(cipher.decrypt(account.getRefreshToken()), !account.isLiveMode());
            if (!account.getProviderUserId().equals(tokens.userId()) || tokens.liveMode() != account.isLiveMode()) {
                throw ApiException.unprocessable("O Mercado Pago não confirmou a mesma conta. Conecte novamente.");
            }
            storeTokens(account, tokens);
            accounts.saveAndFlush(account);
            accessToken = tokens.accessToken();
        }
        if (requireActive && !accessToken.startsWith("APP_USR-"))
            throw ApiException.unprocessable("O organizador precisa reconectar sua conta Mercado Pago para usar Checkout Pro.");
        return new SellerAccount(account.getId(), account.getProviderUserId(), accessToken);
    }

    public void storeTokens(PayoutAccount account, OAuthTokens tokens) {
        account.setAccessToken(cipher.encrypt(tokens.accessToken()));
        account.setRefreshToken(cipher.encrypt(tokens.refreshToken()));
        // Checkout Pro não tokeniza cartões no WODZ nem usa chave pública no navegador.
        account.setPublicKey(null);
        account.setExpiresAt(Instant.now().plusSeconds(tokens.expiresInSeconds()));
        account.setLiveMode(tokens.liveMode());
    }
}
