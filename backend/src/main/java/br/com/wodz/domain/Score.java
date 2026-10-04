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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Resultado de uma equipe numa prova. placement e points são calculados
 * no banco (trigger trg_score_rerank -> fn_rank_workout), por isso são
 * somente leitura aqui.
 */
@Entity
@Table(name = "score")
@Getter
@Setter
@NoArgsConstructor
public class Score {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workout_id")
    private Workout workout;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ScoreStatus status = ScoreStatus.VALID;

    private Integer timeMs;

    private Integer reps;

    @Column(precision = 7, scale = 2)
    private BigDecimal loadKg;

    private Integer tiebreakMs;

    @Column(insertable = false, updatable = false)
    private Integer placement;

    @Column(insertable = false, updatable = false, precision = 8, scale = 2)
    private BigDecimal points;

    private String notes;

    private Long createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
