package br.com.wodz.dto;

import br.com.wodz.domain.Gender;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.ShirtSize;
import br.com.wodz.domain.TeamStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TeamDtos {

    private TeamDtos() {
    }

    public record MemberRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Pattern(regexp = "\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}", message = "CPF inválido") String cpf,
            @NotNull @Past LocalDate birthDate,
            @NotNull Gender gender,
            @NotNull(message = "Selecione o tamanho da camiseta") ShirtSize shirtSize,
            @NotBlank(message = "Informe o telefone do atleta") @Size(max = 20) String phone) {
    }

    public record TeamRegistrationRequest(
            @NotNull Long categoryId,
            @Size(max = 80) String name,
            @Size(max = 120) String boxName,
            @Size(max = 100) String city,
            @NotEmpty @Size(max = 10) List<@Valid MemberRequest> members) {
    }

    public record MemberDto(Long id, String fullName, String email, String cpf, LocalDate birthDate, Gender gender,
                            ShirtSize shirtSize, boolean captain, String phone) {
    }

    public record TeamDto(Long id, String name, String boxName, String city, TeamStatus status,
                          String registrationCode, Instant createdAt, Instant confirmedAt,
                          Refs.ChampionshipRef championship, Refs.CategoryRef category, List<MemberDto> members,
                          BigDecimal registrationAmount, Long captainId) {
    }

    public record LinkCandidate(Long teamId, String teamName, String captainName) {}

    public record TeamNameRequest(@NotBlank @Size(max = 80) String name) {}

    /** Linha da view vw_team_overview. */
    public record TeamOverview(Long teamId, String teamName, String boxName, String city, TeamStatus status,
                               String registrationCode, Instant createdAt, Instant confirmedAt, Long categoryId,
                               String categoryName, BigDecimal price, String captainName, String captainEmail,
                               String captainPhone, int memberCount, String memberNames,
                               PaymentStatus lastPaymentStatus, PaymentMethod lastPaymentMethod, Instant paidAt,
                               List<OverviewMember> members) {
    }

    public record OverviewMember(Long id, String fullName, ShirtSize shirtSize, boolean captain) {}

    public record TeamStatusRequest(@NotNull TeamStatus status) {
    }

    public record MyHeats(List<Long> teamIds, List<HeatDtos.HeatView> heats) {
    }
}
