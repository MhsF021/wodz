package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.common.JdbcSupport;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamMember;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.HeatDtos.HeatView;
import br.com.wodz.dto.TeamDtos.MemberRequest;
import br.com.wodz.dto.TeamDtos.MyHeats;
import br.com.wodz.dto.TeamDtos.TeamDto;
import br.com.wodz.dto.TeamDtos.TeamOverview;
import br.com.wodz.dto.TeamDtos.TeamRegistrationRequest;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.repository.CategoryRepository;
import br.com.wodz.repository.TeamRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TeamService {

    private final TeamRepository teams;
    private final CategoryRepository categories;
    private final AppUserRepository users;
    private final ChampionshipService championshipService;
    private final ScheduleQueryService scheduleQuery;
    private final OwnershipGuard guard;
    private final JdbcTemplate jdbc;

    public TeamService(TeamRepository teams, CategoryRepository categories, AppUserRepository users,
                       ChampionshipService championshipService, ScheduleQueryService scheduleQuery,
                       OwnershipGuard guard, JdbcTemplate jdbc) {
        this.teams = teams;
        this.categories = categories;
        this.users = users;
        this.championshipService = championshipService;
        this.scheduleQuery = scheduleQuery;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- atleta

    /**
     * Inscreve a equipe. O primeiro atleta da lista é o capitão (o usuário logado).
     * Vagas, janela de inscrição, CPF e duplicidade de atleta são garantidos
     * também pelas triggers do banco.
     */
    @Transactional
    public TeamDto register(String slug, Long userId, TeamRegistrationRequest req) {
        Championship championship = championshipService.findPublic(slug);
        // Serializa tentativas da mesma conta, inclusive para categorias diferentes.
        var captain = users.lockActiveById(userId)
                .orElseThrow(() -> ApiException.notFound("Conta de atleta não encontrada"));
        if (teams.existsByCaptainIdAndCategory_Championship_IdAndStatusNot(userId, championship.getId(), TeamStatus.CANCELLED)) {
            throw ApiException.conflict("Você já possui uma inscrição para esse campeonato");
        }
        Instant now = Instant.now();
        if (championship.hasStarted(now)) {
            throw ApiException.unprocessable("Este campeonato já começou e não aceita mais inscrições");
        }
        if (!championship.isAcceptingRegistrations(now)) {
            throw ApiException.unprocessable("As inscrições deste campeonato estão fechadas");
        }
        Category category = categories.lockById(req.categoryId())
                .filter(c -> c.getChampionship().getId().equals(championship.getId()))
                .orElseThrow(() -> ApiException.badRequest("Categoria inválida para este campeonato"));

        if (req.members().size() != category.getTeamSize()) {
            throw ApiException.badRequest("A categoria " + category.getName() + " exige "
                    + category.getTeamSize() + " atleta(s) por equipe");
        }
        CategoryComposition.validateTeam(category, req.members().stream().map(MemberRequest::gender).toList());
        Set<String> cpfs = new HashSet<>();
        for (MemberRequest m : req.members()) {
            if (!cpfs.add(Codes.digits(m.cpf()))) {
                throw ApiException.badRequest("O mesmo CPF aparece duas vezes na equipe");
            }
        }

        Team team = new Team();
        team.setCategory(category);
        team.setCaptain(captain);
        team.setName(Codes.uppercase(req.name().trim()));
        team.setBoxName(Codes.uppercase(Codes.blankToNull(req.boxName())));
        team.setCity(Codes.uppercase(Codes.blankToNull(req.city())));
        team.setRegistrationCode(Codes.registrationCode());
        if (category.getPrice().signum() == 0) {
            team.setStatus(TeamStatus.CONFIRMED);
            team.setConfirmedAt(Instant.now());
        }
        for (int i = 0; i < req.members().size(); i++) {
            MemberRequest m = req.members().get(i);
            TeamMember member = new TeamMember();
            member.setFullName(Codes.uppercase(m.fullName().trim()));
            member.setEmail(m.email().trim().toLowerCase());
            member.setCpf(Codes.digits(m.cpf()));
            member.setBirthDate(m.birthDate());
            member.setGender(m.gender());
            member.setShirtSize(m.shirtSize());
            member.setCaptain(i == 0);
            team.addMember(member);
        }
        teams.saveAndFlush(team);
        return Mappers.team(team);
    }

    @Transactional(readOnly = true)
    public List<TeamDto> myTeams(Long userId) {
        return teams.findByCaptainIdOrderByCreatedAtDesc(userId).stream().map(Mappers::team).toList();
    }

    /** O capitão pode desistir da inscrição enquanto ela aguarda pagamento. */
    @Transactional
    public TeamDto cancelRegistration(Long teamId, Long userId) {
        // O mesmo lock usado nos pagamentos evita cancelar uma equipe já confirmada.
        Team team = teams.lockById(teamId)
                .filter(t -> t.getCaptain().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Inscrição não encontrada"));
        if (team.getStatus() == TeamStatus.CANCELLED) return Mappers.team(team);
        if (team.getStatus() != TeamStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("Somente inscrições aguardando pagamento podem ser canceladas pelo capitão. Fale com a organização.");
        }
        team.setStatus(TeamStatus.CANCELLED);
        teams.saveAndFlush(team);
        return Mappers.team(team);
    }

    @Transactional(readOnly = true)
    public MyHeats myHeats(Long userId) {
        List<Long> teamIds = teams.findByCaptainIdOrderByCreatedAtDesc(userId).stream()
                .filter(t -> t.getStatus() != TeamStatus.CANCELLED)
                .map(Team::getId)
                .toList();
        List<HeatView> heats = scheduleQuery.forTeams(teamIds);
        return new MyHeats(teamIds, heats);
    }

    // -------------------------------------------------------------- organizador

    @Transactional(readOnly = true)
    public List<TeamOverview> adminList(Long championshipId, Long categoryId, Long userId) {
        guard.championship(championshipId, userId);
        List<Object> args = new ArrayList<>();
        String sql = "SELECT * FROM vw_team_overview WHERE championship_id = ?";
        args.add(championshipId);
        if (categoryId != null) {
            sql += " AND category_id = ?";
            args.add(categoryId);
        }
        sql += " ORDER BY category_name, status, team_name";
        return jdbc.query(sql, TeamService::mapOverview, args.toArray());
    }

    @Transactional(readOnly = true)
    public TeamDto adminDetail(Long teamId, Long userId) {
        return Mappers.team(guard.team(teamId, userId));
    }

    /** Ex.: confirmar manualmente quem pagou em dinheiro, ou cancelar uma inscrição. */
    @Transactional
    public TeamDto changeStatus(Long teamId, Long userId, TeamStatus status) {
        Team team = guard.team(teamId, userId);
        team.setStatus(status);
        if (status == TeamStatus.CONFIRMED && team.getConfirmedAt() == null) {
            team.setConfirmedAt(Instant.now());
        }
        teams.saveAndFlush(team);
        return Mappers.team(team);
    }

    private static TeamOverview mapOverview(ResultSet rs, int rowNum) throws SQLException {
        String paymentStatus = rs.getString("last_payment_status");
        String paymentMethod = rs.getString("last_payment_method");
        return new TeamOverview(
                rs.getLong("team_id"),
                rs.getString("team_name"),
                rs.getString("box_name"),
                rs.getString("city"),
                TeamStatus.valueOf(rs.getString("status")),
                rs.getString("registration_code"),
                JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "confirmed_at"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getBigDecimal("price"),
                rs.getString("captain_name"),
                rs.getString("captain_email"),
                rs.getString("captain_phone"),
                rs.getInt("member_count"),
                rs.getString("member_names"),
                paymentStatus == null ? null : PaymentStatus.valueOf(paymentStatus),
                paymentMethod == null ? null : PaymentMethod.valueOf(paymentMethod),
                JdbcSupport.instant(rs, "paid_at"));
    }
}
