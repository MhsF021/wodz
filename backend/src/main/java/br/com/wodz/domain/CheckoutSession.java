package br.com.wodz.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.math.BigDecimal;
import java.time.Instant;

/** Preferência hospedada. Cada tentativa financeira continua sendo um Payment separado. */
@Entity @Table(name = "checkout_session")
@Getter @Setter @NoArgsConstructor
public class CheckoutSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "team_id")
    private Team team;
    @Column(nullable = false)
    private Long payoutAccountId;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;
    @Column(precision = 10, scale = 2)
    private BigDecimal applicationFee;
    @Column(nullable = false, unique = true, length = 64)
    private String externalReference;
    private String providerPreferenceId;
    private String checkoutUrl;
    /** String preserva métodos históricos sem habilitá-los no contrato do checkout atual. */
    @Column(nullable = false, length = 10)
    private String method = "CARD";
    private String providerPaymentId;
    private Instant termsAcceptedAt;
    @Column(length = 40)
    private String termsVersion;
    @Column(nullable = false)
    private Instant expiresAt;
    @CreationTimestamp @Column(updatable = false)
    private Instant createdAt;
}
