package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.common.PasswordPolicy;
import br.com.wodz.common.ProfileValidation;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.ChampionshipStatus;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.domain.Role;
import br.com.wodz.dto.AuthDtos.ChangePasswordRequest;
import br.com.wodz.dto.AuthDtos.DeleteAccountRequest;
import br.com.wodz.dto.AuthDtos.UpdateProfileRequest;
import br.com.wodz.dto.AuthDtos.UnlinkGoogleRequest;
import br.com.wodz.dto.AuthDtos.UserDto;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.repository.ChampionshipRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.service.GoogleTokenVerifier.GoogleProfile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Dados de cadastro, troca de senha e exclusão da conta do usuário logado. */
@Service
public class AccountService {

    /** Campeonatos que ainda dependem do organizador para acontecer. */
    private static final Set<ChampionshipStatus> RUNNING = EnumSet.of(
            ChampionshipStatus.PUBLISHED, ChampionshipStatus.IN_PROGRESS);

    private static final Set<ChampionshipStatus> CLOSED = EnumSet.of(
            ChampionshipStatus.FINISHED, ChampionshipStatus.CANCELLED);

    private final AppUserRepository users;
    private final ChampionshipRepository championships;
    private final TeamRepository teams;
    private final PasswordEncoder passwordEncoder;
    private final GoogleTokenVerifier google;
    private final PayoutAccountService payoutAccounts;

    public AccountService(AppUserRepository users, ChampionshipRepository championships, TeamRepository teams,
                          PasswordEncoder passwordEncoder, GoogleTokenVerifier google, PayoutAccountService payoutAccounts) {
        this.users = users;
        this.championships = championships;
        this.teams = teams;
        this.passwordEncoder = passwordEncoder;
        this.google = google;
        this.payoutAccounts = payoutAccounts;
    }

    @Transactional
    public void logout(Long userId) {
        AppUser user = load(userId);
        user.setTokenVersion(user.getTokenVersion() + 1);
        payoutAccounts.cancelPendingAuthorizations(userId);
    }

    @Transactional
    public UserDto update(Long userId, UpdateProfileRequest req) {
        AppUser user = load(userId);
        boolean athlete = user.getRole() == Role.ATHLETE;
        if (athlete && req.gender() == null) throw ApiException.badRequest("Informe o sexo no seu cadastro");
        String document = ProfileValidation.document(req.document());
        if (!ProfileValidation.validCpf(document) && (athlete || !ProfileValidation.validCnpj(document))) {
            throw ApiException.badRequest(athlete ? "Informe um CPF válido" : "Informe um CPF ou CNPJ válido");
        }
        if ((athlete || Codes.blankToNull(req.phone()) != null) && !ProfileValidation.validPhone(req.phone())) {
            throw ApiException.badRequest("Informe um telefone válido com DDD");
        }
        String postalCode = Codes.blankToNull(req.postalCode());
        if (postalCode != null && !postalCode.matches("[0-9]{5}-?[0-9]{3}")) {
            throw ApiException.badRequest("Informe um CEP válido");
        }
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (!email.equalsIgnoreCase(user.getEmail())) {
            if (!user.hasPassword()) {
                throw ApiException.badRequest("Crie uma senha antes de trocar o e-mail: ela confirma que a troca é sua");
            }
            if (req.currentPassword() == null || req.currentPassword().isBlank()) {
                throw ApiException.badRequest("Informe sua senha atual para trocar o e-mail");
            }
            checkPassword(user, req.currentPassword());
            if (users.existsByEmailIgnoreCaseAndIdNot(email, userId)) {
                throw ApiException.conflict("Este e-mail já está em uso por outra conta");
            }
            user.setEmail(email);
        }
        user.setName(Codes.uppercase(req.name().trim()));
        user.setPhone(Codes.blankToNull(req.phone()));
        if (athlete) user.setGender(req.gender());
        user.setDocument(document);
        if (!athlete) {
            user.setStreet(Codes.uppercase(Codes.blankToNull(req.street())));
            user.setAddressNumber(Codes.blankToNull(req.addressNumber()));
            user.setCity(Codes.uppercase(Codes.blankToNull(req.city())));
            user.setPostalCode(Codes.digits(postalCode));
        }
        return Mappers.user(user);
    }

    /** Conta criada com o Google confirma a primeira senha com a mesma identidade Google. */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        PasswordPolicy.validateNewPassword(req.newPassword());
        AppUser user = load(userId);
        if (user.hasPassword()) {
            if (req.currentPassword() == null || req.currentPassword().isBlank()) {
                throw ApiException.badRequest("Informe sua senha atual");
            }
            checkPassword(user, req.currentPassword());
            if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
                throw ApiException.badRequest("A nova senha precisa ser diferente da atual");
            }
        } else {
            confirmGoogleIdentity(user, req.googleCredential(), "Confirme com o Google para criar uma senha");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
    }

    /**
     * Anonimiza a conta em vez de apagá-la: campeonatos, equipes e resultados
     * continuam apontando para o usuário. Inscrições aguardando pagamento são
     * canceladas (a trigger cancela as cobranças pendentes).
     */
    @Transactional
    public void delete(Long userId, DeleteAccountRequest req) {
        AppUser user = load(userId);
        confirmIdentity(user, req);
        eraseAccount(user);
    }

    /** Mantém o acesso local quando há senha; sem outra credencial, encerra a conta WODZ. */
    @Transactional
    public void unlinkGoogle(Long userId, UnlinkGoogleRequest req) {
        AppUser user = load(userId);
        confirmGoogleIdentity(user, req.googleCredential(), "Confirme com o Google para desvincular a conta");
        if (!user.hasPassword()) {
            eraseAccount(user);
            return;
        }
        user.setGoogleSub(null);
        user.setGoogleLinkDisabled(true);
        user.setTokenVersion(user.getTokenVersion() + 1);
    }

    private void eraseAccount(AppUser user) {
        Long userId = user.getId();
        if (championships.existsByOwnerIdAndStatusIn(userId, RUNNING)) {
            throw ApiException.conflict("Você tem campeonatos publicados ou em andamento. "
                    + "Encerre ou cancele esses campeonatos antes de excluir a conta.");
        }
        if (teams.existsByCaptainIdAndStatusAndCategory_Championship_StatusNotIn(userId, TeamStatus.CONFIRMED, CLOSED)) {
            throw ApiException.conflict("Você é capitão de uma equipe confirmada em um campeonato que ainda não terminou. "
                    + "Peça ao organizador para cancelar a inscrição antes de excluir a conta.");
        }
        teams.findByCaptainIdAndStatus(userId, TeamStatus.PENDING_PAYMENT)
                .forEach(team -> team.setStatus(TeamStatus.CANCELLED));

        payoutAccounts.eraseUserData(userId);

        user.setName("Conta excluída");
        user.setEmail("excluida-" + user.getId() + "@wodz.invalid");
        user.setPhone(null);
        user.setDocument(null);
        user.setStreet(null);
        user.setAddressNumber(null);
        user.setCity(null);
        user.setPostalCode(null);
        user.setGender(null);
        user.setGoogleSub(null);
        user.setGoogleLinkDisabled(true);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setDeletedAt(Instant.now());
        user.setTokenVersion(user.getTokenVersion() + 1);
    }

    private AppUser load(Long userId) {
        return users.lockActiveById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.unauthorized("Sessão expirada. Entre novamente."));
    }

    /** A confirmação Google também pode excluir contas vinculadas que já tenham senha local. */
    private void confirmIdentity(AppUser user, DeleteAccountRequest req) {
        if (req.googleCredential() != null && !req.googleCredential().isBlank()) {
            confirmGoogleIdentity(user, req.googleCredential(), "Confirme com o Google para excluir a conta");
            return;
        }
        if (user.hasPassword()) {
            if (req.password() == null || req.password().isBlank()) {
                throw ApiException.badRequest("Digite sua senha para confirmar");
            }
            checkPassword(user, req.password());
            return;
        }
        confirmGoogleIdentity(user, req.googleCredential(), "Confirme com o Google para excluir a conta");
    }

    private void confirmGoogleIdentity(AppUser user, String credential, String prompt) {
        if (user.getGoogleSub() == null) {
            throw ApiException.badRequest("Esta conta não tem vínculo com o Google");
        }
        if (credential == null || credential.isBlank()) {
            throw ApiException.badRequest(prompt);
        }
        GoogleProfile profile = google.verify(credential);
        if (profile.sub() == null || !profile.sub().equals(user.getGoogleSub())) {
            throw ApiException.unprocessable("Entre com a mesma conta Google desta conta para confirmar");
        }
    }

    /** 422 (e não 401) para o front não tratar senha errada como sessão expirada. */
    private void checkPassword(AppUser user, String password) {
        if (!PasswordPolicy.fitsBcrypt(password) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.unprocessable("Senha incorreta");
        }
    }
}
