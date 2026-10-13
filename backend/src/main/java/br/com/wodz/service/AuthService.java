package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.common.PasswordPolicy;
import br.com.wodz.common.ProfileValidation;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Role;
import br.com.wodz.dto.AuthDtos.AuthResponse;
import br.com.wodz.dto.AuthDtos.GoogleLoginRequest;
import br.com.wodz.dto.AuthDtos.GoogleLoginResult;
import br.com.wodz.dto.AuthDtos.LoginRequest;
import br.com.wodz.dto.AuthDtos.PendingGoogleSignup;
import br.com.wodz.dto.AuthDtos.RegisterRequest;
import br.com.wodz.dto.AuthDtos.UserDto;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.service.GoogleTokenVerifier.GoogleProfile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final GoogleTokenVerifier google;
    private final String unknownAccountHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                       GoogleTokenVerifier google) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.google = google;
        this.unknownAccountHash = passwordEncoder.encode("unused-account-password-check");
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (req.role() == Role.ATHLETE && req.gender() == null) throw ApiException.badRequest("Informe o sexo para criar sua conta");
        if (!ProfileValidation.validPhone(req.phone())) throw ApiException.badRequest("Informe um telefone válido com DDD");
        PasswordPolicy.validateNewPassword(req.password());
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("Este e-mail já tem cadastro. Entre com sua senha.");
        }
        AppUser user = new AppUser();
        user.setName(Codes.uppercase(req.name().trim()));
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole(req.role());
        user.setGender(req.role() == Role.ATHLETE ? req.gender() : null);
        user.setPhone(Codes.blankToNull(req.phone()));
        users.save(user);
        return tokens.issue(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        if (!PasswordPolicy.fitsBcrypt(req.password())) {
            throw ApiException.unauthorized("E-mail ou senha incorretos");
        }
        AppUser user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> !u.isDeleted())
                .orElse(null);
        if (user == null || !user.hasPassword()) {
            // Executa o mesmo BCrypt para reduzir a diferença de tempo entre e-mails existentes e inexistentes.
            passwordEncoder.matches(req.password(), unknownAccountHash);
            throw ApiException.unauthorized("E-mail ou senha incorretos");
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("E-mail ou senha incorretos");
        }
        return tokens.issue(user);
    }

    /**
     * Login com Google. Conta já vinculada: entra. Mesmo e-mail com cadastro por
     * senha: vincula somente quando Google também atesta a titularidade atual do e-mail. Primeiro
     * acesso: sem role, devolve "pending" para o front perguntar o perfil; com role,
     * cria a conta sem senha.
     */
    @Transactional
    public GoogleLoginResult google(GoogleLoginRequest req) {
        GoogleProfile profile = google.verify(req.credential());

        Optional<AppUser> linked = users.findByGoogleSub(profile.sub())
                .filter(u -> !u.isDeleted() && !u.isGoogleLinkDisabled());
        if (linked.isPresent()) {
            return signedIn(linked.get());
        }
        // A primeira leitura por e-mail já adquire o lock, sem reutilizar um estado carregado antes de desvincular.
        Optional<AppUser> byEmail = users.lockActiveByEmail(profile.email());
        if (byEmail.isPresent()) {
            AppUser user = byEmail.get();
            if (user.isGoogleLinkDisabled()) {
                throw ApiException.conflict("O Google foi desvinculado desta conta. Entre com seu e-mail e senha.");
            }
            if (user.getGoogleSub() != null) {
                throw ApiException.conflict("Este e-mail já está vinculado a outra conta Google");
            }
            if (!profile.authoritativeEmail()) {
                throw ApiException.conflict("Este e-mail já tem cadastro. Entre com sua senha para acessar a conta.");
            }
            user.setGoogleSub(profile.sub());
            return signedIn(user);
        }
        if (req.role() == null || (req.role() == Role.ATHLETE && req.gender() == null)) {
            return new GoogleLoginResult(null, new PendingGoogleSignup(profile.email(), Codes.uppercase(profile.name())));
        }
        AppUser user = new AppUser();
        user.setName(Codes.uppercase(profile.name()));
        user.setEmail(profile.email());
        user.setGoogleSub(profile.sub());
        user.setRole(req.role());
        user.setGender(req.role() == Role.ATHLETE ? req.gender() : null);
        users.save(user);
        return signedIn(user);
    }

    @Transactional(readOnly = true)
    public UserDto me(Long userId) {
        return users.findById(userId)
                .filter(u -> !u.isDeleted())
                .map(Mappers::user)
                .orElseThrow(() -> ApiException.unauthorized("Sessão expirada. Entre novamente."));
    }

    private GoogleLoginResult signedIn(AppUser user) {
        return new GoogleLoginResult(tokens.issue(user), null);
    }
}
