package br.com.wodz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Conta do organizador que recebe os pagamentos das inscrições (conta Mercado
 * Pago conectada por OAuth). Os tokens ficam cifrados (TokenCipher). Ao desconectar
 * a linha fica como histórico ao desconectar: as credenciais cifradas permitem
 * conciliar pagamentos antigos, mas não podem originar novas cobranças.
 */
@Entity
@Table(name = "payout_account")
@Getter
@Setter
@NoArgsConstructor
public class PayoutAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(nullable = false, length = 40)
    private String providerUserId;

    /** Aplicação que emitiu o grant OAuth; null exige revalidação de um vínculo legado. */
    @Column(name = "application_id", length = 40)
    private String applicationId;

    private String accountLabel;

    /** Cifrado. */
    private String accessToken;

    /** Cifrado. */
    private String refreshToken;

    /** Coluna legada mantida para apagar chaves antigas; não é usada pelo Checkout Pro. */
    private String publicKey;

    /** Modo financeiro explicitamente informado pelo OAuth, independente de test_user. */
    private boolean liveMode;

    /** Tipo da conta validado por /users/me; null identifica vínculos legados ainda não revalidados. */
    private Boolean testAccount;

    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutAccountStatus status = PayoutAccountStatus.ACTIVE;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant connectedAt;

    private Instant disconnectedAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public boolean isActive() {
        return status == PayoutAccountStatus.ACTIVE;
    }
}
