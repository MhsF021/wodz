package br.com.wodz.web;

import br.com.wodz.common.CurrentUser;
import br.com.wodz.dto.AuthDtos.AuthResponse;
import br.com.wodz.dto.AuthDtos.ChangePasswordRequest;
import br.com.wodz.dto.AuthDtos.DeleteAccountRequest;
import br.com.wodz.dto.AuthDtos.GoogleLoginRequest;
import br.com.wodz.dto.AuthDtos.GoogleLoginResult;
import br.com.wodz.dto.AuthDtos.LoginRequest;
import br.com.wodz.dto.AuthDtos.RegisterRequest;
import br.com.wodz.dto.AuthDtos.UpdateProfileRequest;
import br.com.wodz.dto.AuthDtos.UserDto;
import br.com.wodz.service.AccountService;
import br.com.wodz.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação")
public class AuthController {

    private final AuthService auth;
    private final AccountService account;

    public AuthController(AuthService auth, AccountService account) {
        this.auth = auth;
        this.account = account;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/google")
    @Operation(summary = "Login com Google (ID token do botão). Primeiro acesso sem role devolve pending para escolher o perfil")
    public GoogleLoginResult google(@Valid @RequestBody GoogleLoginRequest req) {
        return auth.google(req);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearer")
    public UserDto me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(CurrentUser.id(jwt));
    }

    @PutMapping("/me")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Atualiza nome, e-mail e celular; trocar o e-mail exige a senha atual")
    public UserDto updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest req) {
        return account.update(CurrentUser.id(jwt), req);
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer")
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
        account.changePassword(CurrentUser.id(jwt), req);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Exclui a conta: anonimiza os dados pessoais e mantém o histórico de equipes e resultados")
    public void deleteMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest req) {
        account.delete(CurrentUser.id(jwt), req);
    }
}
