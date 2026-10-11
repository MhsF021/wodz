package br.com.wodz.repository;

import br.com.wodz.domain.PayoutAccount;
import br.com.wodz.domain.PayoutAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, Long> {

    @Modifying
    @Query("""
            update PayoutAccount a set a.status = br.com.wodz.domain.PayoutAccountStatus.DISCONNECTED,
                a.disconnectedAt = :now, a.accountLabel = null, a.accessToken = null,
                a.refreshToken = null, a.publicKey = null where a.userId = :userId
            """)
    void eraseUserData(Long userId, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PayoutAccount a where a.id = :id")
    Optional<PayoutAccount> lockById(Long id);

    Optional<PayoutAccount> findFirstByUserIdAndStatus(Long userId, PayoutAccountStatus status);

    boolean existsByUserIdAndStatus(Long userId, PayoutAccountStatus status);

    List<PayoutAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<PayoutAccount> findByProviderAndStatusAndExpiresAtBefore(String provider, PayoutAccountStatus status,
                                                                 Instant limit);
}
