package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.ChampionshipStatus;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.AuthDtos.ChangePasswordRequest;
import br.com.wodz.dto.AuthDtos.DeleteAccountRequest;
import br.com.wodz.dto.AuthDtos.UpdateProfileRequest;
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

    public AccountService(AppUserRepository users, ChampionshipRepository championships, TeamRepository teams,
                          PasswordEncoder passwordEncoder, GoogleTokenVerifier google) {
        this.users = users;
        this.championships = championships;
        this.teams = teams;
        this.passwordEncoder = passwordEncoder;
        this.google = google;
    }

    @Transactional
    public UserDto update(Long userId, UpdateProfileRequest req) {
        AppUser user = load(userId);
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
        user.setName(req.name().trim());
        user.setPhone(Codes.blankToNull(req.phone()));
        return Mappers.user(user);
    }

    /** Conta criada com o Google ainda sem senha: é a primeira, não pede a atual. */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        AppUser user = load(userId);
        if (user.hasPassword()) {
            if (req.currentPassword() == null || req.currentPassword().isBlank()) {
                throw ApiException.badRequest("Informe sua senha atual");
            }
            checkPassword(user, req.currentPassword());
            if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
                throw ApiException.badRequest("A nova senha precisa ser diferente da atual");
            }
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
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

        user.setName("Conta excluída");
        user.setEmail("excluida-" + user.getId() + "@wodz.invalid");
        user.setPhone(null);
        user.setGoogleSub(null);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setDeletedAt(Instant.now());
    }

    private AppUser load(Long userId) {
        return users.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.unauthorized("Sessão expirada. Entre novamente."));
    }

    /** Com senha, confirma com ela; conta só com Google, com um novo login na mesma conta Google. */
    private void confirmIdentity(AppUser user, DeleteAccountRequest req) {
        if (user.hasPassword()) {
            if (req.password() == null || req.password().isBlank()) {
                throw ApiException.badRequest("Digite sua senha para confirmar");
            }
            checkPassword(user, req.password());
            return;
        }
        if (req.googleCredential() == null || req.googleCredential().isBlank()) {
            throw ApiException.badRequest("Confirme com o Google para excluir a conta");
        }
        GoogleProfile profile = google.verify(req.googleCredential());
        if (!profile.sub().equals(user.getGoogleSub())) {
            throw ApiException.unprocessable("Entre com a mesma conta Google desta conta para confirmar");
        }
    }

    /** 422 (e não 401) para o front não tratar senha errada como sessão expirada. */
    private void checkPassword(AppUser user, String password) {
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.unprocessable("Senha incorreta");
        }
    }
}
