package br.com.wodz.repository;

import br.com.wodz.domain.CheckoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, Long> {
    Optional<CheckoutSession> findByIdAndTeamId(Long id, Long teamId);
    Optional<CheckoutSession> findByExternalReference(String reference);
    Optional<CheckoutSession> findFirstByTeamIdOrderByIdDesc(Long teamId);
    @Query("""
        select c.id from CheckoutSession c where c.createdAt > :since
        and not exists (select p.id from Payment p where p.checkoutSessionId = c.id
          and p.status in (br.com.wodz.domain.PaymentStatus.APPROVED, br.com.wodz.domain.PaymentStatus.REFUNDED))
        order by c.id
        """)
    List<Long> recentIds(Instant since);
}
