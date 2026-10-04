package br.com.wodz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Entity
@Table(name = "championship")
@Getter
@Setter
@NoArgsConstructor
public class Championship {

    /** Fuso de referência das datas de início e término. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Organizador dono do campeonato. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 160)
    private String slug;

    private String description;

    @Column(nullable = false, length = 150)
    private String venueName;

    private String address;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 2)
    private String state;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    private Instant registrationOpensAt;

    private Instant registrationClosesAt;

    /** Formulário de inscrição publicado pelo organizador. */
    private boolean registrationOpen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScoringSystem scoringSystem = ScoringSystem.POINTS_TABLE;

    private int pointsFirstPlace = 100;

    private int pointsStep = 5;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChampionshipStatus status = ChampionshipStatus.DRAFT;

    private String bannerUrl;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    /** O campeonato começa à 0h do dia de início, no horário de Brasília (o mesmo de fn_registration_is_open). */
    public boolean hasStarted(Instant now) {
        return !now.atZone(ZONE).toLocalDate().isBefore(startDate);
    }

    public Instant startsAt() {
        return startDate.atStartOfDay(ZONE).toInstant();
    }

    /** Espelha fn_registration_is_open (a trigger do banco é a fonte de verdade). */
    public boolean isAcceptingRegistrations(Instant now) {
        return registrationOpen
                && status == ChampionshipStatus.PUBLISHED
                && !hasStarted(now)
                && (registrationOpensAt == null || !now.isBefore(registrationOpensAt))
                && (registrationClosesAt == null || !now.isAfter(registrationClosesAt));
    }
}
