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
    private final String applicationId;

    public PayoutTokenService(PayoutAccountRepository accounts, TokenCipher cipher,
                             ObjectProvider<MercadoPagoOAuthClient> oauth, AppProperties props) {
        this.accounts = accounts;
        this.cipher = cipher;
        this.oauth = oauth;
        this.testMode = props.payment().mercadopago().testMode();
        this.applicationId = props.payment().mercadopago().clientId();
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
        if (requireActive) {
            requireApplicationConfigured();
            if (account.getApplicationId() != null && !applicationId.equals(account.getApplicationId())) {
                throw ApiException.unprocessable("A conta de recebimento foi autorizada por outra aplicação Mercado Pago. Reconecte a conta em Recebimentos.");
            }
        }
        String accessToken = cipher.decrypt(account.getAccessToken());
        if (!accessToken.startsWith("APP_USR-")) {
            throw ApiException.unprocessable("O organizador precisa reconectar sua conta Mercado Pago para usar Checkout Pro.");
        }
        if (requireActive && account.getTestAccount() != null && account.getTestAccount() != testMode) {
            throw ApiException.unprocessable(testMode
                    ? "Conecte uma conta de vendedor de teste do Mercado Pago neste ambiente."
                    : "Conecte uma conta real de vendedor do Mercado Pago neste ambiente.");
        }
        if (requireActive && account.getTestAccount() != null && !testMode && !account.isLiveMode())
            throw ApiException.unprocessable("Conecte uma autorização de produção para receber inscrições neste ambiente.");
        boolean legacy = account.getTestAccount() == null;
        boolean legacyApplication = account.getApplicationId() == null;
        if (legacy || legacyApplication || account.getExpiresAt() == null || account.getExpiresAt().isBefore(Instant.now().plusSeconds(120))) {
            requireApplicationConfigured();
            if (!legacyApplication && !applicationId.equals(account.getApplicationId())) {
                // Uma consulta histórica pode usar o acesso antigo ainda válido, mas
                // não renovar seu grant com as credenciais de outra aplicação.
                throw ApiException.unprocessable("A autorização histórica pertence a outra aplicação Mercado Pago e precisa ser renovada com a aplicação original.");
            }
            MercadoPagoOAuthClient client = oauth.getIfAvailable();
            if (client == null || account.getRefreshToken() == null) {
                throw ApiException.unprocessable("Conecte a conta Mercado Pago novamente para renovar a autorização.");
            }
            Boolean expectedTestMode = requireActive ? Boolean.valueOf(testMode) : account.getTestAccount();
            MercadoPagoOAuthClient.CheckoutAccount verified = client.refreshCheckout(
                    cipher.decrypt(account.getRefreshToken()), expectedTestMode);
            OAuthTokens tokens = verified.tokens();
            if (!account.getProviderUserId().equals(tokens.userId())
                    || (expectedTestMode != null && verified.testAccount() != expectedTestMode)
                    || (!legacy && (tokens.liveMode() != account.isLiveMode()
                    || verified.testAccount() != account.getTestAccount()))) {
                throw ApiException.unprocessable("O Mercado Pago não confirmou a mesma conta. Conecte novamente.");
            }
            storeTokens(account, tokens);
            account.setTestAccount(verified.testAccount());
            accounts.saveAndFlush(account);
            accessToken = tokens.accessToken();
        }
        if (requireActive && !accessToken.startsWith("APP_USR-"))
            throw ApiException.unprocessable("O organizador precisa reconectar sua conta Mercado Pago para usar Checkout Pro.");
        if (requireActive && !testMode && !account.isLiveMode())
            throw ApiException.unprocessable("Conecte uma autorização de produção para receber inscrições neste ambiente.");
        return new SellerAccount(account.getId(), account.getProviderUserId(), accessToken);
    }

    public void storeTokens(PayoutAccount account, OAuthTokens tokens) {
        requireApplicationConfigured();
        account.setAccessToken(cipher.encrypt(tokens.accessToken()));
        account.setRefreshToken(cipher.encrypt(tokens.refreshToken()));
        // Checkout Pro não tokeniza cartões no WODZ nem usa chave pública no navegador.
        account.setPublicKey(null);
        account.setExpiresAt(Instant.now().plusSeconds(tokens.expiresInSeconds()));
        account.setLiveMode(tokens.liveMode());
        account.setApplicationId(applicationId);
    }

    private void requireApplicationConfigured() {
        if (applicationId == null || applicationId.isBlank()) {
            throw ApiException.unprocessable("A aplicação Mercado Pago ainda não foi configurada neste ambiente.");
        }
    }
}
