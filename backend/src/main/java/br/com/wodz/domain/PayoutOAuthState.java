package br.com.wodz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Tentativa de conexão OAuth em andamento: o state amarra o retorno do Mercado
 * Pago ao organizador que iniciou (anti-CSRF) e guarda o code_verifier do PKCE,
 * cifrado. Uso único, vale 10 minutos.
 */
@Entity
@Table(name = "payout_oauth_state")
@Getter
@Setter
@NoArgsConstructor
public class PayoutOAuthState {

    @Id
    private String state;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String codeVerifier;

    @Column(nullable = false)
    private Instant expiresAt;
}
