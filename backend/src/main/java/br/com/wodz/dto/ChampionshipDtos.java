package br.com.wodz.dto;

import br.com.wodz.domain.ChampionshipStatus;
import br.com.wodz.domain.CategoryGender;
import br.com.wodz.domain.MovementStandard;
import jakarta.validation.Valid;
import br.com.wodz.domain.ScoringSystem;
import br.com.wodz.domain.WorkoutType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ChampionshipDtos {

    private ChampionshipDtos() {
    }

    public record ChampionshipRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 20000) String description,
            @NotBlank @Size(max = 150) String venueName,
            @Size(max = 255) String address,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "Use a sigla do estado (ex.: PE)") String state,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            Instant registrationOpensAt,
            Instant registrationClosesAt,
            @NotNull ScoringSystem scoringSystem,
            @Min(1) @Max(1000) Integer pointsFirstPlace,
            @Min(0) @Max(100) Integer pointsStep,
            @Size(max = 500) String bannerUrl,
            @Size(max = 1400000) String imageData,
            @Size(max = 1400000) String profileImageData) {
        public ChampionshipRequest(String name, String description, String venueName, String address, String city,
                String state, LocalDate startDate, LocalDate endDate, Instant registrationOpensAt, Instant registrationClosesAt,
                ScoringSystem scoringSystem, Integer pointsFirstPlace, Integer pointsStep, String bannerUrl, String imageData) {
            this(name, description, venueName, address, city, state, startDate, endDate, registrationOpensAt,
                    registrationClosesAt, scoringSystem, pointsFirstPlace, pointsStep, bannerUrl, imageData, null);
        }
        public ChampionshipRequest(String name, String description, String venueName, String address, String city,
                String state, LocalDate startDate, LocalDate endDate, Instant registrationOpensAt, Instant registrationClosesAt,
                ScoringSystem scoringSystem, Integer pointsFirstPlace, Integer pointsStep, String bannerUrl) {
            this(name, description, venueName, address, city, state, startDate, endDate, registrationOpensAt,
                    registrationClosesAt, scoringSystem, pointsFirstPlace, pointsStep, bannerUrl, null);
        }
    }

    public record RegistrationToggleRequest(boolean open) {
    }

    public record StatusRequest(@NotNull ChampionshipStatus status) {
    }

    public record ChampionshipSummary(Long id, String slug, String name, String venueName, String city, String state,
                                      LocalDate startDate, LocalDate endDate, ChampionshipStatus status,
                                      boolean registrationOpen, String bannerUrl) {
    }

    public record AdminChampionshipSummary(Long id, String slug, String name, String venueName, String city,
                                           String state, LocalDate startDate, LocalDate endDate,
                                           ChampionshipStatus status, boolean registrationOpen,
                                           int categories, int teamsConfirmed, int teamsPending,
                                           BigDecimal revenue) {
    }

    public record ChampionshipDetail(Long id, String slug, String name, String description, String venueName,
                                     String address, String city, String state, LocalDate startDate,
                                     LocalDate endDate, ChampionshipStatus status,
                                     /* inscrições aceitando equipes agora (flag + janela + status + início) */
                                     boolean registrationOpen,
                                     /* chave "formulário publicado" controlada pelo organizador */
                                     boolean registrationFlag,
                                     /* já é o dia de início ou depois: não aceita mais inscrições */
                                     boolean started,
                                     Instant registrationOpensAt, Instant registrationClosesAt,
                                     ScoringSystem scoringSystem, int pointsFirstPlace, int pointsStep,
                                     String bannerUrl, String profileUrl, List<CategoryDto> categories,
                                     Integer heatLanes, Instant heatFirstStart, Integer heatIntervalMinutes,
                                     boolean heatsRequireReview) {
    }

    public record CategoryRequest(
            @NotBlank @Size(max = 80) String name,
            String description,
            @NotNull @Min(1) @Max(10) Integer teamSize,
            @Min(1) Integer maxTeams,
            @NotNull @DecimalMin("0.00") BigDecimal price,
            Integer displayOrder,
            @NotNull CategoryGender genderPolicy,
            @Size(max = 9) List<@NotNull @Min(1) @Max(9) Integer> allowedMaleCounts,
            @Size(max = 30) List<@NotNull @Valid MovementStandard> movementStandards) {
        public CategoryRequest(String name, String description, Integer teamSize, Integer maxTeams, BigDecimal price,
                               Integer displayOrder, CategoryGender genderPolicy, List<Integer> allowedMaleCounts) {
            this(name, description, teamSize, maxTeams, price, displayOrder, genderPolicy, allowedMaleCounts, null);
        }
    }

    public record CategoryDto(Long id, String name, String description, int teamSize, Integer maxTeams,
                              BigDecimal price, int displayOrder, Integer slotsLeft, List<WorkoutDto> workouts,
                              CategoryGender genderPolicy, List<Integer> allowedMaleCounts, List<MovementStandard> movementStandards) {
    }

    /** categoryIds: na criação, a mesma prova é criada para cada categoria informada. */
    public record WorkoutRequest(
            List<Long> categoryIds,
            @NotNull @Min(1) @Max(99) Integer number,
            @NotBlank @Size(max = 120) String name,
            String description,
            @NotNull WorkoutType type,
            @Min(1) Integer timeCapSeconds,
            @Min(1) Integer repsPerRound,
            boolean hasTiebreak,
            @Min(1) Integer maxReps) {
        public WorkoutRequest(List<Long> categoryIds, Integer number, String name, String description,
                              WorkoutType type, Integer timeCapSeconds, Integer repsPerRound, boolean hasTiebreak) {
            this(categoryIds, number, name, description, type, timeCapSeconds, repsPerRound, hasTiebreak, null);
        }
    }

    public record WorkoutDto(Long id, Long categoryId, int number, String name, String description,
                             WorkoutType type, Integer timeCapSeconds, Integer repsPerRound, boolean hasTiebreak, Integer maxReps) {
    }
}
