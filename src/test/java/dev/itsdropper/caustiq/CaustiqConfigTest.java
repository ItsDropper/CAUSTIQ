package dev.itsdropper.caustiq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaustiqConfigTest {
    @Test
    void advancesThroughEverySupportedCompileBudget() {
        for (int budget = CompileBudget.MIN; budget < CompileBudget.MAX; budget++) {
            assertEquals(budget + 1, CompileBudget.next(budget));
        }
    }

    @Test
    void wrapsMaximumCompileBudgetToMinimum() {
        assertEquals(CompileBudget.MIN, CompileBudget.next(CompileBudget.MAX));
    }

    @Test
    void recoversValuesBelowTheSupportedRange() {
        assertEquals(CompileBudget.MIN, CompileBudget.next(0));
        assertEquals(CompileBudget.MIN, CompileBudget.next(-1));
    }
}
