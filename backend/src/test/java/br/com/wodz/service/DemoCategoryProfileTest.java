package br.com.wodz.service;

import br.com.wodz.domain.CategoryGender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DemoCategoryProfileTest {
    @Test void defaultProfileHasEveryCompositionWithIndividualRxAndReferenceLoads() {
        var profile = DemoCategoryProfile.load("");
        assertEquals(11, profile.categories().size());
        assertEquals(9, profile.categories().stream().filter(c -> c.teamSize() == 2).count());
        var rx = profile.categories().stream().filter(c -> c.level().equals("RX")).toList();
        assertEquals(2, rx.size());
        assertTrue(rx.stream().allMatch(c -> c.teamSize() == 1 && c.genderPolicy() != CategoryGender.MIXED));
        for (String level : new String[]{"INICIANTE", "SCALED", "INTERMEDIARIO"}) {
            assertEquals(3, profile.categories().stream().filter(c -> c.level().equals(level)).map(DemoCategoryProfile.Definition::genderPolicy).distinct().count());
        }
        assertEquals("30 kg", profile.standards().get("INICIANTE").getFirst().male());
        assertEquals("15 kg", profile.standards().get("INICIANTE").getFirst().female());
        assertEquals("45 kg", profile.standards().get("SCALED").getFirst().male());
        assertEquals("25 kg", profile.standards().get("SCALED").getFirst().female());
        var toesToBar = profile.standards().get("SCALED").stream().filter(row -> row.movement().equals("Toes to bar")).findFirst().orElseThrow();
        assertFalse(toesToBar.forGender(CategoryGender.FEMALE).allowed());
        assertTrue(toesToBar.forGender(CategoryGender.MALE).allowed());
    }
    @Test void envJsonRoundTripsAndRejectsMixedOrDoublesRx() throws Exception {
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(DemoCategoryProfile.load(""));
        assertEquals(11, DemoCategoryProfile.load(json).categories().size());
        var tree = mapper.readTree(json);
        var rx = (com.fasterxml.jackson.databind.node.ObjectNode) tree.get("categories").get(9);
        rx.put("teamSize", 2);
        assertThrows(IllegalArgumentException.class, () -> DemoCategoryProfile.load(tree.toString()));
        rx.put("teamSize", 1); rx.put("genderPolicy", "MIXED");
        assertThrows(IllegalArgumentException.class, () -> DemoCategoryProfile.load(tree.toString()));
    }
}
