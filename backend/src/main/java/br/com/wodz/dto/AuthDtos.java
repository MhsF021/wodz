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

    public record LoginRequest(@NotBlank @Email @Size(max = 160) String email,
                               @NotBlank @Size(max = 72) String password) {
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
            @Size(max = 72) String currentPassword) {
    }

    /** A primeira senha de uma conta Google exige nova confirmação Google; depois exige a senha atual. */
    public record ChangePasswordRequest(
            @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 8, max = 72, message = "A senha precisa ter entre 8 e 72 caracteres") String newPassword,
            @Size(max = 8192) String googleCredential) {
        public ChangePasswordRequest(String currentPassword, String newPassword) {
            this(currentPassword, newPassword, null);
        }
    }

    /** Confirma com a senha atual ou, se vinculada, com um novo login na mesma conta Google. */
    public record DeleteAccountRequest(@Size(max = 72) String password, @Size(max = 8192) String googleCredential) {

        public DeleteAccountRequest(String password) {
            this(password, null);
        }
    }

    /** Desvincular exige confirmação Google; sem senha local, também encerra a conta WODZ. */
    public record UnlinkGoogleRequest(@NotBlank @Size(max = 8192) String googleCredential) {
    }

    public record AuthResponse(String token, Instant expiresAt, UserDto user) {
    }

    /** credential: ID token do botão do Google. role: só no primeiro acesso, quando a conta ainda não existe. */
    public record GoogleLoginRequest(@NotBlank @Size(max = 8192) String credential, Role role) {
    }

    public record PendingGoogleSignup(String email, String name) {
    }

    /** Ou entra (auth), ou é o primeiro acesso e falta escolher o perfil (pending). */
    public record GoogleLoginResult(AuthResponse auth, PendingGoogleSignup pending) {
    }
}
