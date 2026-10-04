package br.com.wodz.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Bateria de uma prova. started_at/finished_at são carimbados por trigger. */
@Entity
@Table(name = "heat")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class Heat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workout_id")
    private Workout workout;

    private int number;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HeatStatus status = HeatStatus.UPCOMING;

    @Column(insertable = false, updatable = false)
    private Instant startedAt;

    @Column(insertable = false, updatable = false)
    private Instant finishedAt;

    @OneToMany(mappedBy = "heat", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lane ASC")
    private List<HeatLane> lanes = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public void addLane(Team team, int lane) {
        HeatLane heatLane = new HeatLane();
        heatLane.setHeat(this);
        heatLane.setTeam(team);
        heatLane.setLane(lane);
        lanes.add(heatLane);
    }
}
