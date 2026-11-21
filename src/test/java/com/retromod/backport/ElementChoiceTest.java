package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retromod.legacyapi.backport.render.ElementChoice;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks how a named 26.2 vertex attribute picks among 26.1.2 elements that share a layout. */
class ElementChoiceTest {

    /** UV1 (overlay) and UV2 (lightmap) are both two unnormalized shorts in 26.1.2. */
    private static final List<String> TWO_SHORTS = List.of("UV1", "UV2");

    @Test
    void attributeNamedUv2PicksUv2EvenThoughUv1ComesFirst() {
        assertEquals(1, ElementChoice.choose("UV2", TWO_SHORTS, List.of(false, false)));
    }

    @Test
    void attributeNamedUv1PicksUv1() {
        assertEquals(0, ElementChoice.choose("UV1", TWO_SHORTS, List.of(false, false)));
    }

    @Test
    void namesCompareWithoutCaseOrUnderscores() {
        assertEquals(0, ElementChoice.choose("LineWidth", List.of("LINE_WIDTH"), List.of(false)));
    }

    @Test
    void anElementWithTwoNamesAnswersToEither() {
        assertEquals(0, ElementChoice.choose("UV", List.of("UV0|UV"), List.of(false)));
    }

    @Test
    void anUnknownNameTakesTheFirstFreeElement() {
        assertEquals(0, ElementChoice.choose("Light", TWO_SHORTS, List.of(false, false)));
        assertEquals(1, ElementChoice.choose("Light", TWO_SHORTS, List.of(true, false)));
    }

    @Test
    void aTakenElementIsNeverChosenAgainAndNoneFreeGivesMinusOne() {
        assertEquals(0, ElementChoice.choose("UV2", TWO_SHORTS, List.of(false, true)));
        assertEquals(-1, ElementChoice.choose("UV2", TWO_SHORTS, List.of(true, true)));
    }
}
