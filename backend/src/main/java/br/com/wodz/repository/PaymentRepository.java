package br.com.wodz.repository;

import br.com.wodz.domain.Payment;
import br.com.wodz.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);

    List<Payment> findByTeamIdAndStatus(Long teamId, PaymentStatus status);

    Optional<Payment> findFirstByTeamIdOrderByIdDesc(Long teamId);

    Optional<Payment> findFirstByCheckoutSessionIdOrderByIdDesc(Long checkoutSessionId);

    Optional<Payment> findByIdAndTeamId(Long id, Long teamId);

    /** Pendentes recentes do provedor, para conciliar quando o webhook não chega. */
    @Query("""
            SELECT p.id FROM Payment p
             WHERE p.provider = :provider AND p.status = br.com.wodz.domain.PaymentStatus.PENDING
               AND p.providerPaymentId IS NOT NULL AND p.createdAt > :since
             ORDER BY p.id""")
    List<Long> findPendingIdsToReconcile(String provider, Instant since);
}
