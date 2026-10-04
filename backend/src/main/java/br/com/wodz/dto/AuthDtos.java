package br.com.wodz.dto;

import br.com.wodz.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 72, message = "A senha precisa ter entre 8 e 72 caracteres") String password,
            @NotNull Role role,
            @Size(max = 20) String phone) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    /** hasPassword: falso para quem só entra com o Google; googleLinked: conta vinculada ao Google. */
    public record UserDto(Long id, String name, String email, Role role, String phone, boolean hasPassword,
                          boolean googleLinked) {
    }

    /** A senha atual só é exigida quando o e-mail muda. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 160) String email,
            @Size(max = 20) String phone,
            String currentPassword) {
    }

    /** Sem senha ainda (conta criada com o Google), currentPassword pode vir vazia: é a primeira senha. */
    public record ChangePasswordRequest(
            String currentPassword,
            @NotBlank @Size(min = 8, max = 72, message = "A senha precisa ter entre 8 e 72 caracteres") String newPassword) {
    }

    /** Contas com senha confirmam com ela; contas só com Google, com um novo login no Google. */
    public record DeleteAccountRequest(String password, String googleCredential) {

        public DeleteAccountRequest(String password) {
            this(password, null);
        }
    }

    public record AuthResponse(String token, Instant expiresAt, UserDto user) {
    }

    /** credential: ID token do botão do Google. role: só no primeiro acesso, quando a conta ainda não existe. */
    public record GoogleLoginRequest(@NotBlank String credential, Role role) {
    }

    public record PendingGoogleSignup(String email, String name) {
    }

    /** Ou entra (auth), ou é o primeiro acesso e falta escolher o perfil (pending). */
    public record GoogleLoginResult(AuthResponse auth, PendingGoogleSignup pending) {
    }
}
