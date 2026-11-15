package dev.itsdropper.caustiq.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrisExpressionTest {
    @Test
    void evaluatesArithmeticFunctionsAndVariables() {
        assertEquals(0.75, IrisExpression.evaluate("clamp(sin(pi / 2) * amount, 0, 1)", name ->
                name.equals("amount") ? 0.75 : null), 1.0e-9);
    }

    @Test
    void evaluatesConditionalsAndBooleanOperators() {
        assertEquals(12.0, IrisExpression.evaluate("if(worldTime > 6000 && rainStrength == 0, 12, 4)", name ->
                Map.of("worldTime", 9000.0, "rainStrength", 0.0).get(name)), 1.0e-9);
        assertEquals(3.0, IrisExpression.evaluate("frameCounter < 4 ? 3 : 8", name ->
                name.equals("frameCounter") ? 2.0 : null), 1.0e-9);
    }

    @Test
    void resolvesDottedUniformVariablesAndVariadicFunctions() {
        assertEquals(6.0, IrisExpression.evaluate("max(cameraPosition.x, 2, 6)", name ->
                name.equals("cameraPosition.x") ? 4.0 : null), 1.0e-9);
        assertEquals(0.5, IrisExpression.evaluate("saturate(torad(90) / pi)", ignored -> null), 1.0e-9);
    }

    @Test
    void supportsIrisVariadicIfEqualsMembershipAndBetweenFunctions() {
        assertEquals(7.0, IrisExpression.evaluate("if(0, 2, 0, 4, 1, 7, 9)", ignored -> null), 1.0e-9);
        assertEquals(1.0, IrisExpression.evaluate("equals(0.5, 0.505, 0.01)", ignored -> null), 1.0e-9);
        assertEquals(1.0, IrisExpression.evaluate("in(5, 1, 3, 5, 7)", ignored -> null), 1.0e-9);
        assertEquals(1.0, IrisExpression.evaluate("between(5, 5, 10)", ignored -> null), 1.0e-9);
        assertEquals(1.0, IrisExpression.evaluate("and(1, true, !false)", ignored -> null), 1.0e-9);
        assertEquals(1.0, IrisExpression.evaluate("or(0, false, 1)", ignored -> null), 1.0e-9);
    }

    @Test
    void evaluatesSmoothAsAStatefulHalfLifeFilter() {
        assertEquals(0.75, IrisExpression.evaluate("smooth(0.75, 10, 10)", ignored -> null), 1.0e-9);
    }

    @Test
    void supportsComplementaryFourArgumentSmoothWithExplicitStateIds() {
        assertEquals(0.625, IrisExpression.evaluate("smooth(101, 0.625, 20, 10)", ignored -> null), 1.0e-9);
        assertEquals(1.0, IrisExpression.evaluate("in(2, 0, 1, 2)", ignored -> null), 1.0e-9);
    }

    @Test
    void rejectsUnknownVariablesAndMalformedExpressions() {
        assertThrows(IllegalArgumentException.class, () -> IrisExpression.evaluate("unknown + 1", ignored -> null));
        assertThrows(IllegalArgumentException.class, () -> IrisExpression.evaluate("sin(", ignored -> null));
    }
}
