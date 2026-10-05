package br.com.wodz.repository;

import br.com.wodz.domain.PayoutOAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface PayoutOAuthStateRepository extends JpaRepository<PayoutOAuthState, String> {

    @Modifying
    @Query("DELETE FROM PayoutOAuthState s WHERE s.expiresAt < :now OR s.userId = :userId")
    void deleteExpiredOrOwnedBy(Instant now, Long userId);

    @Modifying
    @Query("DELETE FROM PayoutOAuthState s WHERE s.state = :state")
    int consume(String state);
}
