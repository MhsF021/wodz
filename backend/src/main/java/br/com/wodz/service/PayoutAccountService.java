package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.TokenCipher;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.*;
import br.com.wodz.dto.PayoutDtos.AuthorizationView;
import br.com.wodz.dto.PayoutDtos.PayoutView;
import br.com.wodz.payment.MercadoPagoOAuthClient;
import br.com.wodz.payment.MercadoPagoOAuthClient.OAuthTokens;
import br.com.wodz.payment.PayoutAuthorizationException;
import br.com.wodz.payment.PayoutAuthorizationException.Reason;
import br.com.wodz.repository.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class PayoutAccountService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AppProperties props;
    private final AppUserRepository users;
    private final PayoutAccountRepository accounts;
    private final PayoutOAuthStateRepository states;
    private final TokenCipher cipher;
    private final PayoutTokenService tokens;
    private final ObjectProvider<MercadoPagoOAuthClient> oauth;

    public PayoutAccountService(AppProperties props, AppUserRepository users, PayoutAccountRepository accounts,
                               PayoutOAuthStateRepository states, TokenCipher cipher, PayoutTokenService tokens,
                               ObjectProvider<MercadoPagoOAuthClient> oauth) {
        this.props = props;
        this.users = users;
        this.accounts = accounts;
        this.states = states;
        this.cipher = cipher;
        this.tokens = tokens;
        this.oauth = oauth;
    }

    @Transactional(readOnly = true)
    public PayoutView status(Long userId) {
        PayoutAccount account = accounts.findFirstByUserIdAndStatus(userId, PayoutAccountStatus.ACTIVE).orElse(null);
        boolean paymentUnavailable = !"mercadopago".equalsIgnoreCase(props.payment().provider());
        AppProperties.MercadoPagoProps mp = props.payment().mercadopago();
        boolean configured = !paymentUnavailable && present(mp.clientId()) && present(mp.clientSecret());
        return new PayoutView(configured, paymentUnavailable, account != null,
                account == null ? null : account.getAccountLabel(),
                account == null ? null : account.getProviderUserId(), account != null && account.isLiveMode(),
                account == null ? null : account.getConnectedAt(), !paymentUnavailable && mp.testMode(),
                account == null ? null : account.getUpdatedAt(), account == null ? null : account.getTestAccount());
    }

    @Transactional
    public AuthorizationView connect(Long userId, long tokenVersion) {
        AppUser user = organizer(userId);
        if (user.getTokenVersion() != tokenVersion) throw ApiException.unauthorized("Sessão expirada. Entre novamente.");
        MercadoPagoOAuthClient client = client();
        String state = random();
        String verifier = random();
        // Valida a configuração antes de registrar a tentativa e abre o provedor diretamente.
        String authorizationUrl = client.authorizationUrl(state, challenge(verifier), redirectUri());
        states.deleteExpiredOrOwnedBy(Instant.now(), userId);
        PayoutOAuthState pending = new PayoutOAuthState();
        pending.setState(state);
        pending.setUserId(userId);
        pending.setCodeVerifier(cipher.encrypt(verifier));
        pending.setExpiresAt(Instant.now().plusSeconds(600));
        states.save(pending);
        return new AuthorizationView(authorizationUrl);
    }

    // O state é descartado também em caso de recusa/erro do provedor: retorno de uso único.
    @Transactional(noRollbackFor = ApiException.class)
    public void complete(String state, String code) {
        if (state == null || state.length() > 64) throw invalidState();
        PayoutOAuthState pending = states.findById(state).orElseThrow(PayoutAccountService::invalidState);
        organizer(pending.getUserId());
        if (states.consume(state) != 1 || !pending.getExpiresAt().isAfter(Instant.now())) throw invalidState();
        if (!present(code)) throw new PayoutAuthorizationException(Reason.MISSING_CODE,
                "O retorno não trouxe um código de autorização. Sua conta anterior foi mantida.");
        OAuthTokens returned = client().exchangeCode(code, cipher.decrypt(pending.getCodeVerifier()), redirectUri());
        MercadoPagoOAuthClient.CheckoutAccount verified = client().validateCheckoutAccount(returned,
                props.payment().mercadopago().testMode());
        OAuthTokens credential = verified.tokens();
        if ("prod".equalsIgnoreCase(props.environment()) && (verified.testAccount() || !credential.liveMode())) {
            throw new PayoutAuthorizationException(Reason.MODE_MISMATCH, "Use uma conta de produção do Mercado Pago.");
        }
        if (verified.testAccount() != props.payment().mercadopago().testMode()) {
            throw new PayoutAuthorizationException(Reason.MODE_MISMATCH,
                    "O Mercado Pago não confirmou credenciais no modo configurado. Conecte novamente.");
        }
        String label = verified.label();
        PayoutAccount account = accounts.findFirstByUserIdAndStatus(pending.getUserId(), PayoutAccountStatus.ACTIVE)
                .orElse(null);
        // Reautorizar na mesma aplicação e modo preserva a associação dos pagamentos existentes.
        if (account != null && !account.getProviderUserId().equals(credential.userId())) {
            throw new PayoutAuthorizationException(Reason.ACCOUNT_CHANGE,
                    "Desconecte a conta atual antes de conectar outra conta Mercado Pago.");
        }
        if (account != null && (account.getApplicationId() == null
                || !account.getApplicationId().equals(props.payment().mercadopago().clientId())
                || (account.getTestAccount() != null
                && (account.getTestAccount() != verified.testAccount() || account.isLiveMode() != credential.liveMode())))) {
            // Um novo grant não comprova a origem do grant legado. Pagamentos anteriores
            // mantêm suas credenciais, inclusive quando a aplicação original é desconhecida.
            account.setStatus(PayoutAccountStatus.DISCONNECTED);
            account.setDisconnectedAt(Instant.now());
            accounts.saveAndFlush(account);
            account = null;
        }
        if (account == null) {
            account = new PayoutAccount();
            account.setUserId(pending.getUserId());
            account.setProvider("MERCADO_PAGO");
            account.setProviderUserId(credential.userId());
        }
        account.setAccountLabel(label);
        tokens.storeTokens(account, credential);
        account.setTestAccount(verified.testAccount());
        accounts.saveAndFlush(account);
    }

    @Transactional
    public void disconnect(Long userId) {
        organizer(userId);
        PayoutAccount account = accounts.findFirstByUserIdAndStatus(userId, PayoutAccountStatus.ACTIVE).orElse(null);
        if (account != null) {
            // Preserva credenciais cifradas para conciliar/estornar pagamentos já existentes.
            account.setStatus(PayoutAccountStatus.DISCONNECTED);
            account.setDisconnectedAt(Instant.now());
            accounts.saveAndFlush(account);
        }
        states.deleteExpiredOrOwnedBy(Instant.now(), userId);
    }

    public String profileResultUrl(boolean success) {
        return frontendBase() + "/mercadopago/retorno?recebimentos=" + (success ? "connected" : "error");
    }

    public void requireConnected(Long userId) {
        if (!"mercadopago".equalsIgnoreCase(props.payment().provider())) return;
        PayoutAccount account = accounts.findFirstByUserIdAndStatus(userId, PayoutAccountStatus.ACTIVE)
                .orElseThrow(() -> ApiException.unprocessable("Conecte sua conta Mercado Pago em Minha conta antes de abrir inscrições."));
        tokens.seller(account.getId(), true);
    }

    @Transactional
    public void cancelPendingAuthorizations(Long userId) {
        states.deleteExpiredOrOwnedBy(Instant.now(), userId);
    }

    @Transactional
    public void eraseUserData(Long userId) {
        users.lockActiveById(userId).orElseThrow(PayoutAccountService::invalidState);
        accounts.eraseUserData(userId, Instant.now());
        states.deleteExpiredOrOwnedBy(Instant.now(), userId);
    }

    public String redirectUri() { return frontendBase() + "/api/public/mercadopago/callback"; }
    private String frontendBase() { return props.frontendUrl().replaceAll("/+$", ""); }

    private AppUser organizer(Long userId) {
        AppUser user = users.lockActiveById(userId).orElseThrow(PayoutAccountService::invalidState);
        if (user.getRole() != Role.ORGANIZER) throw ApiException.unprocessable("Recebimentos disponíveis apenas para organizadores.");
        return user;
    }

    private MercadoPagoOAuthClient client() {
        MercadoPagoOAuthClient client = oauth.getIfAvailable();
        if (client == null) throw new PayoutAuthorizationException(Reason.CONFIGURATION,
                "O Mercado Pago não está configurado neste ambiente.");
        return client;
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static ApiException invalidState() {
        return new PayoutAuthorizationException(Reason.INVALID_STATE, "Autorização inválida ou expirada. Conecte novamente.");
    }
    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
