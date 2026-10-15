package br.com.wodz.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementStandardTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void legacySymbolsBecomeMovementAvailabilityInsteadOfLoads() throws Exception {
        var requested = mapper.readValue("""
                {"movement":"Toes to bar","male":"✓","female":"✓"}
                """, MovementStandard.class).forGender(CategoryGender.MIXED);
        assertEquals(MovementStandard.Kind.MOVEMENT, requested.kind());
        assertTrue(requested.allowed());
        assertEquals("—", requested.male()); assertEquals("—", requested.female());
        var excluded = new MovementStandard("Handstand walk", "✕", "✕").forGender(CategoryGender.MIXED);
        assertEquals(MovementStandard.Kind.MOVEMENT, excluded.kind()); assertFalse(excluded.allowed());
        var genderFlags = new MovementStandard("Toes to bar", "✓", "✕");
        assertFalse(genderFlags.forGender(CategoryGender.FEMALE).allowed());
        assertTrue(genderFlags.forGender(CategoryGender.MALE).allowed());
        assertEquals("—", genderFlags.forGender(CategoryGender.FEMALE).female());
    }

    @Test void checkboxAndExplicitTypeRoundTripWithoutWeightOrSymbols() throws Exception {
        for (boolean allowed : new boolean[]{true, false}) {
            var item = new MovementStandard("Toes to bar", "45 kg", "25 kg", allowed, MovementStandard.Kind.MOVEMENT);
            String json = mapper.writeValueAsString(item);
            assertEquals(item, mapper.readValue(json, MovementStandard.class));
            assertEquals(allowed, item.allowed()); assertEquals("—", item.male()); assertEquals("—", item.female());
            assertFalse(json.contains("45 kg")); assertFalse(json.contains("25 kg"));
        }
    }

    @Test void uncheckingLoadsPreservesTheirValuesAndLegacyLoadsKeepTheirType() throws Exception {
        var item = mapper.readValue("""
                {"movement":"Clean and jerk","male":"45 kg","female":"25 kg","allowed":false}
                """, MovementStandard.class);
        assertEquals(MovementStandard.Kind.LOAD, item.kind()); assertFalse(item.allowed());
        assertEquals("45 kg", item.male()); assertEquals("25 kg", item.female());
    }
}
