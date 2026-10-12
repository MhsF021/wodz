package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.common.JdbcSupport;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.ChampionshipFilter;
import br.com.wodz.domain.ChampionshipStatus;
import br.com.wodz.domain.ScoringSystem;
import br.com.wodz.dto.ChampionshipDtos.AdminChampionshipSummary;
import br.com.wodz.dto.ChampionshipDtos.CategoryDto;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipDetail;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipRequest;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipSummary;
import br.com.wodz.dto.ChampionshipDtos.WorkoutDto;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.repository.CategoryRepository;
import br.com.wodz.repository.ChampionshipRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChampionshipService {

    private final ChampionshipRepository championships;
    private final CategoryRepository categories;
    private final WorkoutRepository workouts;
    private final AppUserRepository users;
    private final OwnershipGuard guard;
    private final JdbcTemplate jdbc;
    private final PayoutAccountService payoutAccounts;

    public ChampionshipService(ChampionshipRepository championships, CategoryRepository categories,
                               WorkoutRepository workouts, AppUserRepository users, OwnershipGuard guard,
                               JdbcTemplate jdbc, PayoutAccountService payoutAccounts) {
        this.championships = championships;
        this.categories = categories;
        this.workouts = workouts;
        this.users = users;
        this.guard = guard;
        this.jdbc = jdbc;
        this.payoutAccounts = payoutAccounts;
    }

    // ------------------------------------------------------------------ público

    @Transactional(readOnly = true)
    public List<ChampionshipSummary> search(String term, String state) {
        return search(term, state, ChampionshipFilter.ALL);
    }

    @Transactional(readOnly = true)
    public List<ChampionshipSummary> search(String term, String state, ChampionshipFilter filter) {
        Instant now = Instant.now();
        return championships.searchFiltered(term == null ? "" : term.trim(), state == null ? "" : state.trim(), filter.name())
                .stream()
                .map(c -> Mappers.summary(c, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChampionshipDetail publicDetail(String slug) {
        return toDetail(findPublic(slug));
    }

    /** Campeonatos em rascunho não são visíveis para o público. */
    @Transactional(readOnly = true)
    public Championship findPublic(String slug) {
        Championship c = championships.findBySlug(slug)
                .orElseThrow(() -> ApiException.notFound("Campeonato não encontrado"));
        if (c.getStatus() == ChampionshipStatus.DRAFT) {
            throw ApiException.notFound("Campeonato não encontrado");
        }
        return c;
    }

    // ------------------------------------------------------------- organizador

    @Transactional(readOnly = true)
    public List<AdminChampionshipSummary> listMine(Long userId) {
        Map<Long, Object[]> stats = new HashMap<>();
        jdbc.query("""
                        SELECT s.* FROM vw_championship_summary s
                          JOIN championship c ON c.id = s.championship_id
                         WHERE c.owner_id = ?""",
                (RowCallbackHandler) rs -> stats.put(rs.getLong("championship_id"), new Object[]{
                        rs.getInt("categories"), rs.getInt("teams_confirmed"),
                        rs.getInt("teams_pending"), rs.getBigDecimal("revenue")}),
                userId);

        Instant now = Instant.now();
        return championships.findByOwnerIdOrderByStartDateDesc(userId).stream().map(c -> {
            Object[] s = stats.getOrDefault(c.getId(), new Object[]{0, 0, 0, BigDecimal.ZERO});
            return new AdminChampionshipSummary(c.getId(), c.getSlug(), c.getName(), c.getVenueName(), c.getCity(),
                    c.getState(), c.getStartDate(), c.getEndDate(), c.getStatus(), c.isAcceptingRegistrations(now),
                    (Integer) s[0], (Integer) s[1], (Integer) s[2], (BigDecimal) s[3]);
        }).toList();
    }

    @Transactional(readOnly = true)
    public ChampionshipDetail adminDetail(Long id, Long userId) {
        return toDetail(guard.championship(id, userId));
    }

    @Transactional
    public ChampionshipDetail create(Long userId, ChampionshipRequest req) {
        validate(req);
        Championship c = new Championship();
        c.setOwner(users.getReferenceById(userId));
        apply(c, req);
        c.setSlug(uniqueSlug(req.name(), req.startDate()));
        championships.saveAndFlush(c);
        applyImage(c, req.imageData(), "COVER");
        applyImage(c, req.profileImageData(), "PROFILE");
        return toDetail(c);
    }

    @Transactional
    public ChampionshipDetail update(Long id, Long userId, ChampionshipRequest req) {
        validate(req);
        Championship c = guard.championship(id, userId);
        apply(c, req);
        applyImage(c, req.imageData(), "COVER");
        applyImage(c, req.profileImageData(), "PROFILE");
        championships.saveAndFlush(c);
        return toDetail(c);
    }

    /** Publica/fecha o formulário de inscrição. Abrir publica o campeonato, se ainda for rascunho. */
    @Transactional
    public ChampionshipDetail setRegistrationOpen(Long id, Long userId, boolean open) {
        Championship c = guard.championship(id, userId);
        if (open) {
            payoutAccounts.requireConnected(userId);
            if (categories.findByChampionshipIdOrderByDisplayOrderAscNameAsc(id).isEmpty()) {
                throw ApiException.unprocessable("Cadastre ao menos uma categoria antes de abrir as inscrições");
            }
            if (c.hasStarted(Instant.now())) {
                throw ApiException.unprocessable("O campeonato já começou. As inscrições só podem ser abertas antes do dia de início.");
            }
            if (c.getStatus() == ChampionshipStatus.DRAFT) {
                c.setStatus(ChampionshipStatus.PUBLISHED);
            } else if (c.getStatus() != ChampionshipStatus.PUBLISHED) {
                throw ApiException.unprocessable("Inscrições só podem ser abertas antes do campeonato começar");
            }
        }
        c.setRegistrationOpen(open);
        championships.saveAndFlush(c);
        return toDetail(c);
    }

    @Transactional
    public ChampionshipDetail setStatus(Long id, Long userId, ChampionshipStatus status) {
        Championship c = guard.championship(id, userId);
        if (status == ChampionshipStatus.PUBLISHED) payoutAccounts.requireConnected(userId);
        c.setStatus(status);
        if (status != ChampionshipStatus.PUBLISHED) {
            c.setRegistrationOpen(false);
        }
        championships.saveAndFlush(c);
        return toDetail(c);
    }

    // ---------------------------------------------------------------- helpers

    static void validate(ChampionshipRequest req) {
        if (req.endDate().isBefore(req.startDate())) {
            throw ApiException.badRequest("A data de término não pode ser antes da data de início");
        }
        if (req.registrationOpensAt() != null && req.registrationClosesAt() != null
                && !req.registrationClosesAt().isAfter(req.registrationOpensAt())) {
            throw ApiException.badRequest("O fim das inscrições precisa ser depois do início");
        }
        Instant startsAt = req.startDate().atStartOfDay(Championship.ZONE).toInstant();
        if (req.registrationOpensAt() != null && !req.registrationOpensAt().isBefore(startsAt)) {
            throw ApiException.badRequest("As inscrições precisam abrir antes do dia de início do campeonato");
        }
        if (req.registrationClosesAt() != null && req.registrationClosesAt().isAfter(startsAt)) {
            throw ApiException.badRequest("As inscrições precisam fechar até o início do campeonato (0h do dia de início)");
        }
    }

    private static void apply(Championship c, ChampionshipRequest req) {
        c.setName(Codes.uppercase(req.name().trim()));
        c.setDescription(Codes.uppercase(Codes.blankToNull(req.description())));
        c.setVenueName(Codes.uppercase(req.venueName().trim()));
        c.setAddress(Codes.uppercase(Codes.blankToNull(req.address())));
        c.setCity(Codes.uppercase(req.city().trim()));
        c.setState(req.state().toUpperCase(Locale.ROOT));
        c.setStartDate(req.startDate());
        c.setEndDate(req.endDate());
        c.setRegistrationOpensAt(req.registrationOpensAt());
        c.setRegistrationClosesAt(req.registrationClosesAt());
        c.setScoringSystem(req.scoringSystem() == null ? ScoringSystem.POINTS_TABLE : req.scoringSystem());
        c.setPointsFirstPlace(req.pointsFirstPlace() == null ? 100 : req.pointsFirstPlace());
        c.setPointsStep(req.pointsStep() == null ? 5 : req.pointsStep());
        c.setBannerUrl(ChampionshipImages.safeUrl(req.bannerUrl()));
    }

    private void applyImage(Championship c, String data, String kind) {
        if (data == null) return;
        if (data.isEmpty()) {
            jdbc.update("DELETE FROM championship_image WHERE championship_id = ? AND kind = ?", c.getId(), kind);
            if (kind.equals("PROFILE")) c.setProfileUrl(null); else c.setBannerUrl(null);
            return;
        }
        byte[] pixels = ChampionshipImages.sanitize(data);
        java.util.UUID key = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO championship_image (championship_id, kind, image_key, content) VALUES (?, ?, ?, ?)
                ON CONFLICT (championship_id, kind) DO UPDATE SET image_key = EXCLUDED.image_key, content = EXCLUDED.content
                """, c.getId(), kind, key, pixels);
        String url = "/api/public/championship-images/" + key;
        if (kind.equals("PROFILE")) c.setProfileUrl(url); else c.setBannerUrl(url);
    }

    private String uniqueSlug(String name, LocalDate start) {
        String base = Codes.slugify(name);
        if (!base.matches(".*\\d{4}.*")) {
            base = base + "-" + start.getYear();
        }
        String slug = base;
        int i = 2;
        while (championships.existsBySlug(slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }

    private ChampionshipDetail toDetail(Championship c) {
        List<Category> cats = categories.findByChampionshipIdOrderByDisplayOrderAscNameAsc(c.getId());

        Map<Long, List<WorkoutDto>> workoutsByCategory = workouts
                .findByCategoryChampionshipIdOrderByCategoryDisplayOrderAscNumberAsc(c.getId())
                .stream()
                .collect(Collectors.groupingBy(w -> w.getCategory().getId(), LinkedHashMap::new,
                        Collectors.mapping(Mappers::workout, Collectors.toList())));

        Map<Long, Integer> slotsLeft = new HashMap<>();
        jdbc.query("SELECT id, fn_category_slots_left(id) AS slots FROM category WHERE championship_id = ?",
                (RowCallbackHandler) rs -> slotsLeft.put(rs.getLong("id"), JdbcSupport.integer(rs, "slots")),
                c.getId());

        List<CategoryDto> categoryDtos = cats.stream()
                .map(cat -> Mappers.category(cat, slotsLeft.get(cat.getId()),
                        workoutsByCategory.getOrDefault(cat.getId(), List.of())))
                .toList();

        Instant now = Instant.now();
        return new ChampionshipDetail(c.getId(), c.getSlug(), c.getName(), c.getDescription(), c.getVenueName(),
                c.getAddress(), c.getCity(), c.getState(), c.getStartDate(), c.getEndDate(), c.getStatus(),
                c.isAcceptingRegistrations(now), c.isRegistrationOpen(), c.hasStarted(now),
                c.getRegistrationOpensAt(), c.getRegistrationClosesAt(), c.getScoringSystem(), c.getPointsFirstPlace(), c.getPointsStep(),
                c.getBannerUrl(), c.getProfileUrl(), categoryDtos);
    }
}
