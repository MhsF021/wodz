package br.com.wodz.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Item publicado pela organização, com tipo e disponibilidade independentes da carga. */
public record MovementStandard(@NotBlank @Size(max = 80) String movement,
                               @NotBlank @Size(max = 60) String male,
                               @NotBlank @Size(max = 60) String female,
                               Boolean allowed,
                               Kind kind) {
    public enum Kind { LOAD, MOVEMENT }

    public MovementStandard {
        boolean legacy = kind == null;
        if (kind == null) kind = hasLoad(male) || hasLoad(female) ? Kind.LOAD : Kind.MOVEMENT;
        if (allowed == null) allowed = !(unavailable(male) && unavailable(female));
        // Preserve legacy gender flags until the owning category is known.
        if (!legacy) {
            male = kind == Kind.MOVEMENT ? "—" : cleanLoad(male);
            female = kind == Kind.MOVEMENT ? "—" : cleanLoad(female);
        }
    }

    public MovementStandard(String movement, String male, String female, Boolean allowed) {
        this(movement, male, female, allowed, null);
    }

    public MovementStandard(String movement, String male, String female) {
        this(movement, male, female, null, null);
    }

    public MovementStandard forGender(CategoryGender gender) {
        boolean selected = gender == CategoryGender.MALE ? !unavailable(male)
                : gender == CategoryGender.FEMALE ? !unavailable(female) : !(unavailable(male) && unavailable(female));
        return new MovementStandard(movement, male, female, allowed && selected, kind);
    }

    private static boolean unavailable(String value) {
        return value != null && value.trim().matches("[✕✗×]");
    }

    private static boolean hasLoad(String value) {
        return value != null && !value.isBlank() && !value.trim().matches("[✓✔✕✗×—-]");
    }

    private static String cleanLoad(String value) {
        return value != null && value.trim().matches("[✓✔✕✗×]") ? "—" : value == null ? null : value.trim();
    }
}
