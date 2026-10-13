package br.com.wodz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tentativa de pagamento da inscrição. Ao virar APPROVED, a trigger
 * trg_payment_after_status confirma a equipe automaticamente.
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false, length = 30)
    private String provider;

    private String providerPaymentId;

    private Long checkoutSessionId;

    private String statusDetail;

    /** Vínculo OAuth do Organizador ou conta central da Wodz, conforme credential_source. */
    @Column(name = "payout_account_id")
    private Long payoutAccountId;

    /** Comissão cotada no checkout; os valores das cobranças históricas são preservados. */
    @Column(precision = 10, scale = 2)
    private BigDecimal applicationFee;

    /** Líquido confirmado na resposta autenticada do Mercado Pago; null impede apurar repasse. */
    @Column(precision = 10, scale = 2)
    private BigDecimal providerNetAmount;
    @Column(precision = 10, scale = 2)
    private BigDecimal providerRefundedAmount;

    private Integer installments;

    private Instant expiresAt;

    private Instant paidAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
