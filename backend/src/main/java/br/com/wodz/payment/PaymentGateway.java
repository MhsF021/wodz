package br.com.wodz.payment;
import java.util.List;
public interface PaymentGateway {
    String providerName();
    GatewayCheckout createCheckout(CheckoutCommand command);
    default GatewayCheckout resumeCheckout(String preferenceId, CheckoutCommand command) { return null; }
    default boolean supportsRemoteFetch() { return false; }
    default GatewayPayment fetch(String id, SellerAccount seller) { throw new UnsupportedOperationException(); }
    default List<GatewayPayment> search(String reference, SellerAccount seller) { return List.of(); }
}
