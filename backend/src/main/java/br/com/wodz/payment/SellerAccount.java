package br.com.wodz.payment;

/**
 * Conta do organizador que recebe a cobrança. O token está decifrado e só existe
 * em memória durante a chamada ao provedor; toString não o expõe em logs.
 */
public record SellerAccount(Long payoutAccountId, String providerUserId, String accessToken, boolean platformDirect) {
    public SellerAccount(Long payoutAccountId, String providerUserId, String accessToken) {
        this(payoutAccountId, providerUserId, accessToken, false);
    }

    @Override
    public String toString() {
        return "SellerAccount[payoutAccountId=" + payoutAccountId + ", providerUserId=" + providerUserId + "]";
    }
}
