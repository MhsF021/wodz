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

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 160)
    private String email;

    /** Nulo para quem só entra com o Google. */
    @Column(length = 100)
    private String passwordHash;

    /** Claim "sub" do Google: vincula a conta ao login com Google. */
    private String googleSub;

    /** O usuário desvinculou o Google: não vincular novamente só porque o e-mail coincide. */
    @Column(nullable = false)
    private boolean googleLinkDisabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(length = 1)
    private Gender gender;

    private String phone;

    @Column(length = 14)
    private String document;

    @Column(length = 160)
    private String street;

    @Column(length = 20)
    private String addressNumber;

    @Column(length = 100)
    private String city;

    @Column(length = 8)
    private String postalCode;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    /** Preenchido quando o usuário exclui a conta (dados pessoais anonimizados). */
    private Instant deletedAt;

    /** Incrementado quando as credenciais mudam, revogando todos os JWTs anteriores. */
    @Column(nullable = false)
    private long tokenVersion;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }
}
