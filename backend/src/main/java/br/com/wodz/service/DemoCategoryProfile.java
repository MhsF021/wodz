package br.com.wodz.service;

import br.com.wodz.domain.CategoryGender;
import br.com.wodz.domain.MovementStandard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;

/** Configuração compartilhada pelos campeonatos de demonstração. */
public record DemoCategoryProfile(List<Definition> categories, Map<String, List<MovementStandard>> standards) {
    public record Definition(String name, String level, int teamSize, CategoryGender genderPolicy, String description) {}

    public static DemoCategoryProfile load(String json) {
        try {
            var mapper = new ObjectMapper();
            DemoCategoryProfile profile;
            if (json != null && !json.isBlank()) profile = mapper.readValue(json, DemoCategoryProfile.class);
            else try (var input = DemoCategoryProfile.class.getResourceAsStream("/demo-categories.json")) {
                profile = mapper.readValue(input, DemoCategoryProfile.class);
            }
            if (profile.categories() == null || profile.categories().isEmpty() || profile.categories().size() > 30 || profile.standards() == null) throw new IllegalArgumentException();
            var names = new java.util.HashSet<String>();
            try (var factory = Validation.buildDefaultValidatorFactory()) {
                var validator = factory.getValidator();
                for (var category : profile.categories()) {
                    if (category.name() == null || category.name().isBlank() || category.name().length() > 80 || !names.add(category.name())
                            || category.genderPolicy() == null || !List.of("INICIANTE", "SCALED", "INTERMEDIARIO", "RX").contains(category.level())
                            || category.teamSize() != (category.level().equals("RX") ? 1 : 2)
                            || (category.teamSize() == 1 && category.genderPolicy() == CategoryGender.MIXED)) throw new IllegalArgumentException();
                    var rows = profile.standards().get(category.level());
                    if (rows == null || rows.isEmpty() || rows.size() > 30 || rows.stream().anyMatch(row -> row == null || !validator.validate(row).isEmpty())) throw new IllegalArgumentException();
                }
            }
            return profile;
        } catch (Exception error) {
            throw new IllegalArgumentException("SEED_DEMO_CATEGORIES_JSON inválido: use categorias femininas, masculinas ou mistas em duplas e RX individual, com tabelas de movimentos", error);
        }
    }
}
