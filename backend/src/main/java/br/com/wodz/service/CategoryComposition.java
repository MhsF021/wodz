package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.CategoryGender;
import br.com.wodz.domain.Gender;
import br.com.wodz.dto.ChampionshipDtos.CategoryRequest;
import java.util.List;

public final class CategoryComposition {
    private CategoryComposition() {}

    public static List<Integer> allowedCounts(CategoryRequest request) {
        if (request.genderPolicy() == null) throw ApiException.badRequest("Informe se a categoria é feminina, masculina ou mista");
        List<Integer> counts = request.allowedMaleCounts() == null ? List.of() : request.allowedMaleCounts();
        if (request.genderPolicy() != CategoryGender.MIXED) {
            if (!counts.isEmpty()) throw ApiException.badRequest("Composições diferentes só são permitidas em categorias mistas");
            return List.of();
        }
        if (counts.isEmpty() || request.teamSize() < 2 || counts.stream().anyMatch(n -> n == null || n < 1 || n >= request.teamSize())) {
            throw ApiException.badRequest("Escolha ao menos uma composição mista com homens e mulheres, somando o tamanho da equipe");
        }
        return counts.stream().distinct().sorted().toList();
    }

    public static void validateTeam(Category category, List<Gender> genders) {
        if (category.getGenderPolicy() == null) throw ApiException.unprocessable("A organização precisa definir a composição desta categoria antes de receber novas inscrições");
        long men = genders.stream().filter(g -> g == Gender.M).count();
        boolean allowed = genders.size() == category.getTeamSize() && genders.stream().noneMatch(java.util.Objects::isNull) && switch (category.getGenderPolicy()) {
            case MALE -> men == genders.size();
            case FEMALE -> men == 0;
            case MIXED -> category.getAllowedMaleCounts().contains((int) men);
        };
        if (!allowed) throw ApiException.unprocessable("A composição da equipe não atende às regras desta categoria");
    }
}
