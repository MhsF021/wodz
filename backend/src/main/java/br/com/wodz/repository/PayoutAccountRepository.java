package br.com.wodz.repository;

import br.com.wodz.domain.PayoutAccount;
import br.com.wodz.domain.PayoutAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, Long> {

    Optional<PayoutAccount> findFirstByUserIdAndStatus(Long userId, PayoutAccountStatus status);

    boolean existsByUserIdAndStatus(Long userId, PayoutAccountStatus status);

    List<PayoutAccount> findByProviderAndStatusAndExpiresAtBefore(String provider, PayoutAccountStatus status,
                                                                 Instant limit);
}
