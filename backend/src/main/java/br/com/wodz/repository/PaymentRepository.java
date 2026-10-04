package br.com.wodz.repository;

import br.com.wodz.domain.Payment;
import br.com.wodz.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);

    List<Payment> findByTeamIdAndStatus(Long teamId, PaymentStatus status);

    Optional<Payment> findFirstByTeamIdOrderByIdDesc(Long teamId);

    Optional<Payment> findByIdAndTeamId(Long id, Long teamId);
}
