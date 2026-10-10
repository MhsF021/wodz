package br.com.wodz.service;

import br.com.wodz.common.Codes;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.ChampionshipStatus;
import br.com.wodz.domain.Gender;
import br.com.wodz.domain.Role;
import br.com.wodz.domain.ScoreStatus;
import br.com.wodz.domain.ShirtSize;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamMember;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.domain.Workout;
import br.com.wodz.domain.WorkoutType;
import br.com.wodz.dto.HeatDtos.GenerateAllHeatsRequest;
import br.com.wodz.dto.HeatDtos.HeatView;
import br.com.wodz.dto.HeatDtos.LaneView;
import br.com.wodz.dto.ScoreDtos.ScoreRequest;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.repository.CategoryRepository;
import br.com.wodz.repository.ChampionshipRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dados de demonstração (SEED_DEMO=true), todos do organizador organizador@wodz.dev:
 * <ul>
 *   <li>Vitória Games - Ano 6: encerrado há algumas semanas e 100% concluído
 *       (todas as baterias concluídas e todos os resultados lançados, com judge).</li>
 *   <li>Arena 911 - 2026: acontecendo hoje. Prova 1 concluída com resultados,
 *       1ª bateria da Prova 2 concluída aguardando resultados e a 2ª em andamento.</li>
 *   <li>TCB 2026 (São Paulo/SP): inscrições abertas e começa daqui a 30 dias, para testar
 *       Checkout Pro. As equipes aguardam pagamento; nenhum pagamento é criado pelo seed.</li>
 * </ul>
 * Nos dois primeiros, cada categoria tem 16 duplas. Ninguém aparece em duas equipes
 * de um mesmo campeonato: nome, e-mail e CPF são únicos em todo o seed (só a atleta
 * de demonstração se repete). atleta@wodz.dev é capitã do "Time da Ana" no Arena 911
 * e no TCB 2026. Baterias e resultados passam pelos mesmos serviços usados pelo
 * organizador na interface.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String PASSWORD = "wodz2026";
    private static final int TEAMS_PER_CATEGORY = 16;
    private static final int HEAT_INTERVAL_MINUTES = 15;

    private static final String[] CATEGORY_NAMES = {"Iniciante", "Scaled", "RX"};

    private static final String[] FEMALE_NAMES = {"Ana", "Beatriz", "Camila", "Carolina", "Clara", "Daniela",
            "Eduarda", "Fernanda", "Gabriela", "Helena", "Isabela", "Joana", "Juliana", "Larissa", "Letícia",
            "Luana", "Manuela", "Mariana", "Natália", "Paula", "Rafaela", "Renata", "Sofia", "Tatiana", "Vitória"};
    private static final String[] MALE_NAMES = {"André", "Arthur", "Bruno", "Caio", "Daniel", "Diego", "Eduardo",
            "Felipe", "Gabriel", "Gustavo", "Heitor", "Igor", "João", "Leonardo", "Lucas", "Marcelo", "Mateus",
            "Nicolas", "Otávio", "Pedro", "Rafael", "Renan", "Samuel", "Thiago", "Vinícius"};
    private static final String[] SURNAMES = {"Almeida", "Alves", "Andrade", "Araújo", "Barbosa", "Barros",
            "Batista", "Cardoso", "Carvalho", "Cavalcanti", "Costa", "Dias", "Farias", "Ferreira", "Freitas",
            "Gomes", "Lima", "Lins", "Macedo", "Martins", "Melo", "Monteiro", "Moraes", "Nascimento", "Oliveira",
            "Pereira", "Pinto", "Queiroz", "Ramos", "Ribeiro", "Rocha", "Santos", "Siqueira", "Silva", "Souza",
            "Teixeira", "Vasconcelos", "Vieira"};

    private static final String[] TEAM_NOUNS = {"Tubarões", "Leões", "Titãs", "Lobos", "Falcões", "Panteras",
            "Tigres", "Águias", "Guerreiros", "Espartanos", "Vikings", "Gladiadores", "Centauros", "Dragões",
            "Touros", "Corsários"};
    private static final String[] TEAM_PLACES = {"da Maré", "do Capibaribe", "de Olinda", "do Recife Antigo",
            "de Boa Viagem", "da Várzea"};
    private static final String[] BOXES = {"CrossFit Capibaribe", "Box Olinda", "CrossFit Mangue",
            "Recife Strength", "Boa Viagem Athletics", "CrossFit Casa Forte", "Box Jaboatão", "CrossFit Graças",
            "Paulista Training", "CrossFit Espinheiro", "Box Candeias", "CrossFit Torre"};
    private static final String[] CITIES = {"Recife", "Olinda", "Jaboatão dos Guararapes", "Paulista", "Camaragibe"};

    private static final Region RECIFE = new Region(TEAM_PLACES, BOXES, CITIES);
    private static final Region SAO_PAULO = new Region(
            new String[]{"da Paulista", "do Ibirapuera", "da Mooca", "de Pinheiros", "do Tatuapé", "da Vila Madalena"},
            new String[]{"CrossFit Paulista", "Box Vila Mariana", "CrossFit Pinheiros", "Moema Strength",
                    "CrossFit Mooca", "Box Santana", "CrossFit Tatuapé", "Ibirapuera Athletics"},
            new String[]{"São Paulo", "Santo André", "São Bernardo do Campo", "Osasco", "Guarulhos", "Campinas"});

    /** Um judge fixo por raia (1 a 8) em cada campeonato. */
    private static final String[] JUDGES_PAST = {"Carlos Mendes", "Patrícia Rocha", "Rodrigo Nunes",
            "Aline Duarte", "Fábio Correia", "Simone Brandão", "Márcio Tavares", "Cristina Leal"};
    private static final String[] JUDGES_LIVE = {"Henrique Prado", "Débora Fontes", "Alexandre Bastos",
            "Viviane Moura", "Ricardo Sales", "Luciana Paiva", "Fernando Pacheco", "Mônica Arruda"};

    private final AppUserRepository users;
    private final ChampionshipRepository championships;
    private final CategoryRepository categories;
    private final WorkoutRepository workouts;
    private final TeamRepository teams;
    private final HeatService heatService;
    private final ScoreService scoreService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DemoDataSeeder(AppUserRepository users, ChampionshipRepository championships,
                          CategoryRepository categories, WorkoutRepository workouts, TeamRepository teams,
                          HeatService heatService, ScoreService scoreService, PasswordEncoder passwordEncoder,
                          JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.championships = championships;
        this.categories = categories;
        this.workouts = workouts;
        this.teams = teams;
        this.heatService = heatService;
        this.scoreService = scoreService;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            log.info("Seed de demonstração ignorado: o banco já tem usuários");
            return;
        }
        tx.executeWithoutResult(status -> seed());
        log.info("Dados de demonstração criados. Organizador: organizador@wodz.dev | "
                + "Atleta: atleta@wodz.dev | senha: {}", PASSWORD);
    }

    private void seed() {
        Random random = new Random(42);
        // Um hash para todos: um bcrypt por usuário deixaria o seed lento à toa.
        String passwordHash = passwordEncoder.encode(PASSWORD);
        AppUser organizer = user("Liga Nordeste de CrossFit", "organizador@wodz.dev", Role.ORGANIZER, passwordHash, random);
        AppUser athlete = user("Ana Beatriz Lima", "atleta@wodz.dev", Role.ATHLETE, passwordHash, random);
        Roster roster = new Roster(random, passwordHash, athlete.getName());
        LocalDate today = LocalDate.now(Championship.ZONE);

        seedFinished(organizer, roster, random, today.minusDays(45));
        seedInProgress(organizer, athlete, roster, random, today);
        seedRegistrationOpen(organizer, athlete, roster, today);
    }

    // ------------------------------------------------------------ campeonatos

    /** Encerrado e 100% concluído: todas as baterias concluídas e todos os resultados lançados. */
    private void seedFinished(AppUser organizer, Roster roster, Random random, LocalDate start) {
        Championship c = openForRegistration(organizer, "Vitória Games - Ano 6", "vitoria-games-2026",
                "Campeonato de verão em duplas no Sítio Histórico. Três provas em dois dias, com premiação para os "
                        + "três primeiros de cada categoria.",
                "Ginásio Olinda Arena", "Rua do Sol, 250 - Carmo", "Olinda", "PE");
        List<Category> cats = categories(c, new String[]{"0.00", "0.00", "0.00"});
        List<Workout> ws = new ArrayList<>();
        for (Category cat : cats) {
            ws.add(workout(cat, 1, "Largada", WorkoutType.FOR_TIME, 10 * 60, null,
                    "For time em dupla\n50 wall balls\n40 burpees over the bar\n30 power cleans\n\nTime cap: 10 min"));
            ws.add(workout(cat, 2, "Ladeira da Misericórdia", WorkoutType.AMRAP, 12 * 60, 30,
                    "AMRAP 12 min\n10 toes-to-bar\n10 box jumps\n10 dumbbell snatches"));
            ws.add(workout(cat, 3, "Snatch do Alto da Sé", WorkoutType.MAX_LOAD, 10 * 60, null,
                    "Em 10 minutos, cada atleta busca o maior snatch.\nResultado: soma das cargas da dupla."));
        }
        Instant registeredAt = start.minusDays(20).atTime(LocalTime.NOON).atZone(Championship.ZONE).toInstant();
        for (Category cat : cats) {
            for (int i = 0; i < TEAMS_PER_CATEGORY; i++) {
                roster.team(cat, null, registeredAt);
            }
        }
        close(c, start, ChampionshipStatus.FINISHED);

        Map<Long, Workout> byId = ws.stream().collect(Collectors.toMap(Workout::getId, Function.identity()));
        List<HeatView> heats = heatService.generateAll(c.getId(), organizer.getId(),
                new GenerateAllHeatsRequest(at(start, 8), HEAT_INTERVAL_MINUTES));
        // Uma dupla do RX não compareceu à última prova.
        Long noShow = heats.stream()
                .filter(h -> h.workoutNumber() == 3 && h.categoryName().equals("RX"))
                .findFirst().map(h -> h.lanes().get(0).teamId()).orElse(null);
        for (HeatView heat : heats) {
            finish(heat);
            for (LaneView lane : heat.lanes()) {
                ScoreRequest result = heat.workoutNumber() == 3 && lane.teamId().equals(noShow)
                        ? new ScoreRequest(ScoreStatus.DNS, null, null, null, null, null, JUDGES_PAST[lane.lane() - 1])
                        : result(byId.get(heat.workoutId()), level(heat.categoryName()), random,
                                JUDGES_PAST[lane.lane() - 1]);
                scoreService.save(heat.workoutId(), lane.teamId(), result, organizer.getId());
            }
        }
        restoreSchedule(heats);
    }

    /**
     * Acontecendo hoje: Prova 1 concluída com resultados, a 1ª bateria da Prova 2
     * concluída aguardando resultados (para lançar pela interface) e a 2ª em andamento.
     */
    private void seedInProgress(AppUser organizer, AppUser athlete, Roster roster, Random random, LocalDate today) {
        Championship c = openForRegistration(organizer, "Arena 911 - 2026", "arena-911-2026",
                "Campeonato em duplas com três provas em dois dias. Kit atleta com camiseta e squeeze. "
                        + "Aquecimento liberado 30 minutos antes de cada bateria.",
                "Arena Boa Viagem", "Av. Boa Viagem, 1000 - Boa Viagem", "Recife", "PE");
        List<Category> cats = categories(c, new String[]{"0.00", "0.00", "0.00"});
        List<Workout> ws = new ArrayList<>();
        for (Category cat : cats) {
                        ws.add(workout(cat, 1, "The Grind", WorkoutType.FOR_TIME, 12 * 60, null,
                    "21-15-9\nThrusters\nPull-ups"));
            ws.add(workout(cat, 2, "Chipper da Maré", WorkoutType.AMRAP, 15 * 60, 45,
                    "15 wall balls\n15 box jumps\n15 kettlebell swings"));
            ws.add(workout(cat, 3, "Last Dance", WorkoutType.MAX_LOAD, 13 * 60, null,
                    "For time - 5 minutos (0' a 5')\n1RM de clean e jerk\n\nRest 2 minutos (5' a 7')\n\nFor time - 5 minutos (7' a 12')\n1RM de snatch"));
        }
        Instant registeredAt = today.minusDays(10).atTime(LocalTime.NOON).atZone(Championship.ZONE).toInstant();
        for (Category cat : cats) {
            for (int i = 0; i < TEAMS_PER_CATEGORY; i++) {
                boolean anasTeam = cat.getName().equals("Scaled") && i == 0;
                roster.team(cat, anasTeam ? athlete : null, registeredAt);
            }
        }
        close(c, today, ChampionshipStatus.IN_PROGRESS);

        Map<Long, Workout> byId = ws.stream().collect(Collectors.toMap(Workout::getId, Function.identity()));
        List<HeatView> heats = heatService.generateAll(c.getId(), organizer.getId(),
                new GenerateAllHeatsRequest(at(today, 8), HEAT_INTERVAL_MINUTES));
        for (HeatView heat : heats.stream().filter(h -> h.workoutNumber() == 1).toList()) {
            finish(heat);
            for (LaneView lane : heat.lanes()) {
                scoreService.save(heat.workoutId(), lane.teamId(),
                        result(byId.get(heat.workoutId()), level(heat.categoryName()), random,
                                JUDGES_LIVE[lane.lane() - 1]),
                        organizer.getId());
            }
        }
        List<HeatView> secondWorkout = heats.stream().filter(h -> h.workoutNumber() == 2).toList();
        finish(secondWorkout.get(0));
        restoreSchedule(heats);
        jdbc.update("CALL sp_start_heat(?, ?)", secondWorkout.get(1).heatId(), false);
    }

    /**
     * Inscrições abertas e ainda não começou (início daqui a 30 dias), para testar pagamentos:
     * metade das vagas com duplas aguardando pagamento e o "Time da Ana" (Scaled), com o
     * botão de pagar em "Minhas equipes". Ainda há vagas para inscrever equipes novas.
     */
    private void seedRegistrationOpen(AppUser organizer, AppUser athlete, Roster roster, LocalDate today) {
        Championship c = openForRegistration(organizer, "TCB 2026", "tcb-2026",
                "Campeonato em duplas em São Paulo. Três provas em dois dias, com premiação para os três "
                        + "primeiros de cada categoria.",
                "Arena TCB", "Rua Vergueiro, 1500 - Vila Mariana", "São Paulo", "SP");
        List<Category> cats = categories(c, new String[]{"170.00", "210.00", "250.00"});
        for (Category cat : cats) {
            workout(cat, 1, "Avenida Paulista", WorkoutType.FOR_TIME, 10 * 60, null,
                    "For time em dupla\n40 cal row\n30 thrusters\n20 bar muscle-ups\n\nTime cap: 10 min");
            workout(cat, 2, "Garoa", WorkoutType.AMRAP, 12 * 60, 30,
                    "AMRAP 12 min\n10 wall balls\n10 burpees\n10 kettlebell swings");
            workout(cat, 3, "Terraço Itália", WorkoutType.MAX_LOAD, 10 * 60, null,
                    "Em 10 minutos, cada atleta busca o maior clean.\nResultado: soma das cargas da dupla.");
        }
        Instant registeredAt = today.minusDays(3).atTime(LocalTime.NOON).atZone(Championship.ZONE).toInstant();
        for (Category cat : cats) {
            for (int i = 0; i < TEAMS_PER_CATEGORY / 2; i++) {
                roster.team(cat, null, registeredAt, SAO_PAULO);
            }
        }
        roster.team(cats.get(1), athlete, null, SAO_PAULO);
    }

    // --------------------------------------------------------------- helpers

    /** Inscrições só entram antes do início (trigger): o campeonato nasce marcado para daqui a 30 dias. */
    private Championship openForRegistration(AppUser organizer, String name, String slug, String description,
                                             String venue, String address, String city, String state) {
        LocalDate future = LocalDate.now(Championship.ZONE).plusDays(30);
        Championship c = new Championship();
        c.setOwner(organizer);
        c.setName(name);
        c.setSlug(slug);
        c.setDescription(description);
        c.setVenueName(venue);
        c.setAddress(address);
        c.setCity(city);
        c.setState(state);
        c.setStartDate(future);
        c.setEndDate(future.plusDays(1));
        c.setStatus(ChampionshipStatus.PUBLISHED);
        c.setRegistrationOpen(true);
        return championships.save(c);
    }

    /** Depois das inscrições: datas reais, status final e formulário fechado. */
    private void close(Championship c, LocalDate start, ChampionshipStatus status) {
        c.setStartDate(start);
        c.setEndDate(start.plusDays(1));
        c.setStatus(status);
        c.setRegistrationOpen(false);
        championships.saveAndFlush(c);
    }

    private List<Category> categories(Championship c, String[] prices) {
        List<Category> list = new ArrayList<>();
        for (int k = 0; k < CATEGORY_NAMES.length; k++) {
            Category category = new Category();
            category.setChampionship(c);
            category.setName(CATEGORY_NAMES[k]);
            category.setTeamSize(2);
            category.setMaxTeams(20);
            category.setPrice(new BigDecimal(prices[k]));
            category.setDisplayOrder(k);
            list.add(categories.save(category));
        }
        return list;
    }

    private Workout workout(Category category, int number, String name, WorkoutType type, Integer timeCap,
                            Integer repsPerRound, String description) {
        Workout workout = new Workout();
        workout.setCategory(category);
        workout.setNumber(number);
        workout.setName(name);
        workout.setType(type);
        workout.setTimeCapSeconds(timeCap);
        workout.setRepsPerRound(repsPerRound);
        workout.setDescription(description);
        return workouts.save(workout);
    }

    /** Os eventos passados do seed têm início e término fictícios na grade original. */
    private void finish(HeatView heat) {
        jdbc.update("UPDATE heat SET status = 'FINISHED' WHERE id = ?", heat.heatId());
        OffsetDateTime start = OffsetDateTime.ofInstant(heat.scheduledAt(), ZoneOffset.UTC);
        jdbc.update("UPDATE heat SET scheduled_at = ?, started_at = ?, finished_at = ?, schedule_end_at = ? WHERE id = ?",
                start, start, start.plusSeconds(heat.durationSeconds()), start.plusSeconds(heat.durationSeconds()), heat.heatId());
    }

    /** O replay dos eventos históricos não deve adiar a grade de demonstração para a data do seed. */
    private void restoreSchedule(List<HeatView> heats) {
        for (HeatView heat : heats) {
            jdbc.update("UPDATE heat SET scheduled_at = ? WHERE id = ?",
                    OffsetDateTime.ofInstant(heat.scheduledAt(), ZoneOffset.UTC), heat.heatId());
        }
    }

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(Championship.ZONE).toInstant();
    }

    private static int level(String categoryName) {
        return switch (categoryName) {
            case "RX" -> 2;
            case "Scaled" -> 1;
            default -> 0;
        };
    }

    /** Resultado plausível para o tipo da prova; categorias mais fortes rendem mais. */
    private static ScoreRequest result(Workout w, int level, Random random, String judge) {
        return switch (w.getType()) {
            case FOR_TIME -> {
                if (random.nextInt(8) == 0) {
                    yield new ScoreRequest(ScoreStatus.CAPPED, null, 50 + random.nextInt(35), null, null, null, judge);
                }
                int capMs = w.getTimeCapSeconds() * 1000;
                int ms = capMs - 20_000 - random.nextInt(capMs / 2) + (2 - level) * 10_000;
                yield new ScoreRequest(ScoreStatus.VALID, Math.min(ms, capMs - 1_000) / 100 * 100, null, null, null,
                        null, judge);
            }
            case AMRAP, MAX_REPS -> new ScoreRequest(ScoreStatus.VALID, null,
                    150 + level * 40 + random.nextInt(160), null, null, null, judge);
            case MAX_LOAD -> new ScoreRequest(ScoreStatus.VALID, null, null,
                    BigDecimal.valueOf(100 + level * 45 + random.nextInt(20) * 5L), null, null, judge);
        };
    }

    private AppUser user(String name, String email, Role role, String passwordHash, Random random) {
        AppUser user = new AppUser();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setPhone("819" + (10_000_000 + random.nextInt(90_000_000)));
        return users.save(user);
    }

    /**
     * Gera equipes com pessoas que nunca se repetem: nome, e-mail e CPF únicos
     * em todo o seed, então ninguém cai em duas equipes nem em duas categorias.
     */
    private final class Roster {

        private final Random random;
        private final String passwordHash;
        private final Set<String> names = new HashSet<>();
        private final Set<String> cpfs = new HashSet<>();
        private final Map<Region, List<String>> teamNames = new HashMap<>();
        private Member athleteMember;
        private int seq;

        Roster(Random random, String passwordHash, String... reservedNames) {
            this.random = random;
            this.passwordHash = passwordHash;
            names.addAll(List.of(reservedNames));
            teamNames(RECIFE);
        }

        Team team(Category category, AppUser athlete, Instant confirmedAt) {
            return team(category, athlete, confirmedAt, RECIFE);
        }

        /**
         * Dupla da região: inscrição gratuita confirmada; inscrição paga aguarda Checkout Pro.
         * Com athlete, ele é o capitão (o "Time da Ana"), sempre com o mesmo CPF.
         */
        Team team(Category category, AppUser athlete, Instant confirmedAt, Region region) {
            List<String> regionTeamNames = teamNames(region);
            Member captain = athlete == null ? person() : athlete(athlete);
            AppUser captainUser = athlete != null ? athlete : user(captain.name(), captain.email(), Role.ATHLETE,
                    passwordHash, random);

            Team team = new Team();
            team.setCategory(category);
            team.setCaptain(captainUser);
            team.setName(athlete != null ? "Time da Ana" : regionTeamNames.remove(regionTeamNames.size() - 1));
            team.setBoxName(region.boxes()[random.nextInt(region.boxes().length)]);
            team.setCity(region.cities()[random.nextInt(region.cities().length)]);
            team.setRegistrationCode(Codes.registrationCode());
            boolean free = category.getPrice().signum() == 0;
            team.setStatus(free ? TeamStatus.CONFIRMED : TeamStatus.PENDING_PAYMENT);
            team.setConfirmedAt(free ? confirmedAt : null);
            List<Member> members = List.of(captain, person());
            for (int m = 0; m < members.size(); m++) {
                Member p = members.get(m);
                TeamMember member = new TeamMember();
                member.setFullName(p.name());
                member.setEmail(p.email());
                member.setCpf(p.cpf());
                member.setBirthDate(LocalDate.of(1980 + random.nextInt(24), 1 + random.nextInt(12), 1 + random.nextInt(28)));
                member.setGender(p.gender());
                member.setShirtSize(ShirtSize.values()[1 + random.nextInt(4)]);
                member.setCaptain(m == 0);
                team.addMember(member);
            }
            return teams.save(team);
        }

        private Member person() {
            while (true) {
                boolean female = random.nextBoolean();
                String first = pick(female ? FEMALE_NAMES : MALE_NAMES);
                String middle = pick(SURNAMES);
                String last = pick(SURNAMES);
                String name = first + " " + middle + " " + last;
                if (middle.equals(last) || !names.add(name)) {
                    continue;
                }
                String email = Codes.slugify(first + " " + last).replace('-', '.') + "." + (++seq) + "@exemplo.com";
                return member(name, email, female ? Gender.F : Gender.M);
            }
        }

        /** Nomes de equipe da região, embaralhados na primeira vez que a região aparece. */
        private List<String> teamNames(Region region) {
            return teamNames.computeIfAbsent(region, r -> {
                List<String> list = new ArrayList<>();
                for (String noun : TEAM_NOUNS) {
                    for (String place : r.places()) {
                        list.add(noun + " " + place);
                    }
                }
                Collections.shuffle(list, random);
                return list;
            });
        }

        private Member athlete(AppUser athlete) {
            if (athleteMember == null) {
                athleteMember = member(athlete.getName(), athlete.getEmail(), Gender.F);
            }
            return athleteMember;
        }

        private Member member(String name, String email, Gender gender) {
            String cpf;
            do {
                cpf = cpf(random);
            } while (!cpfs.add(cpf));
            return new Member(name, email, cpf, gender);
        }

        private String pick(String[] values) {
            return values[random.nextInt(values.length)];
        }
    }

    private record Member(String name, String email, String cpf, Gender gender) {
    }

    /** Praça das equipes: complemento do nome da equipe, boxes e cidades. */
    private record Region(String[] places, String[] boxes, String[] cities) {
    }

    /** CPF com dígitos verificadores válidos (fictício). */
    private static String cpf(Random random) {
        int[] d = new int[11];
        do {
            for (int i = 0; i < 9; i++) {
                d[i] = random.nextInt(10);
            }
        } while (allEqual(d));
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += d[i] * (10 - i);
        }
        int r = (sum * 10) % 11;
        d[9] = r == 10 ? 0 : r;
        sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += d[i] * (11 - i);
        }
        r = (sum * 10) % 11;
        d[10] = r == 10 ? 0 : r;
        StringBuilder sb = new StringBuilder();
        for (int digit : d) {
            sb.append(digit);
        }
        return sb.toString();
    }

    private static boolean allEqual(int[] d) {
        for (int i = 1; i < 9; i++) {
            if (d[i] != d[0]) {
                return false;
            }
        }
        return true;
    }
}
