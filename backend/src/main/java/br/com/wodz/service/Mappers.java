package br.com.wodz.service;

import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamMember;
import br.com.wodz.domain.Workout;
import br.com.wodz.dto.AuthDtos.UserDto;
import br.com.wodz.dto.ChampionshipDtos.CategoryDto;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipSummary;
import br.com.wodz.dto.ChampionshipDtos.WorkoutDto;
import br.com.wodz.dto.Refs;
import br.com.wodz.dto.TeamDtos.MemberDto;
import br.com.wodz.dto.TeamDtos.TeamDto;

import java.time.Instant;
import java.util.List;

/** Conversões entidade -> DTO. Chamar dentro de transação (associações LAZY). */
public final class Mappers {

    private Mappers() {
    }

    public static UserDto user(AppUser u) {
        return new UserDto(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.getPhone(), u.hasPassword(),
                u.getGoogleSub() != null, u.getGender(), u.getDocument(), u.getStreet(),
                u.getAddressNumber(), u.getCity(), u.getPostalCode());
    }

    public static WorkoutDto workout(Workout w) {
        return new WorkoutDto(w.getId(), w.getCategory().getId(), w.getNumber(), w.getName(), w.getDescription(),
                w.getType(), w.getTimeCapSeconds(), w.getRepsPerRound(), w.isHasTiebreak());
    }

    public static CategoryDto category(Category c, Integer slotsLeft, List<WorkoutDto> workouts) {
        return new CategoryDto(c.getId(), c.getName(), c.getDescription(), c.getTeamSize(), c.getMaxTeams(),
                c.getPrice(), c.getDisplayOrder(), slotsLeft, workouts, c.getGenderPolicy(), c.getAllowedMaleCounts(), c.getMovementStandards());
    }

    public static ChampionshipSummary summary(Championship c, Instant now) {
        return new ChampionshipSummary(c.getId(), c.getSlug(), c.getName(), c.getVenueName(), c.getCity(),
                c.getState(), c.getStartDate(), c.getEndDate(), c.getStatus(), c.isAcceptingRegistrations(now),
                c.getBannerUrl());
    }

    public static Refs.ChampionshipRef championshipRef(Championship c) {
        return new Refs.ChampionshipRef(c.getId(), c.getSlug(), c.getName(), c.getStartDate(), c.getEndDate(),
                c.getVenueName(), c.getCity(), c.getState());
    }

    public static Refs.CategoryRef categoryRef(Category c) {
        return new Refs.CategoryRef(c.getId(), c.getName(), c.getPrice());
    }

    public static MemberDto member(TeamMember m) {
        return new MemberDto(m.getId(), m.getFullName(), m.getEmail(), m.getCpf(), m.getBirthDate(), m.getGender(),
                m.getShirtSize(), m.isCaptain(), m.getPhone());
    }

    public static TeamDto team(Team t) {
        Category category = t.getCategory();
        return new TeamDto(t.getId(), t.getName(), t.getBoxName(), t.getCity(), t.getStatus(),
                t.getRegistrationCode(), t.getCreatedAt(), t.getConfirmedAt(),
                championshipRef(category.getChampionship()), categoryRef(category),
                t.getMembers().stream().map(Mappers::member).toList(), t.getRegistrationAmount(), t.getCaptain().getId());
    }
}
