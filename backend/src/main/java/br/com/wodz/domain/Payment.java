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

    private String checkoutUrl;

    private String statusDetail;

    private String pixQrCode;

    private String pixQrCodeBase64;

    private String cardLastDigits;

    /** Conta do organizador que recebeu (split); nula em cobranças anteriores ao split. */
    @Column(name = "payout_account_id")
    private Long payoutAccountId;

    /** Parte retida pela plataforma (application_fee do Mercado Pago). */
    @Column(precision = 10, scale = 2)
    private BigDecimal applicationFee;

    private Integer installments;

    private Instant expiresAt;

    private Instant paidAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
