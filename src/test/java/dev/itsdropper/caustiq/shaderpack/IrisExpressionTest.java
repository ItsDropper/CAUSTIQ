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
    void rejectsUnknownVariablesAndMalformedExpressions() {
        assertThrows(IllegalArgumentException.class, () -> IrisExpression.evaluate("unknown + 1", ignored -> null));
        assertThrows(IllegalArgumentException.class, () -> IrisExpression.evaluate("sin(", ignored -> null));
    }
}
