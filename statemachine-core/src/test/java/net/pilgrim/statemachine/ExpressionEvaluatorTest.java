package net.pilgrim.statemachine;

import net.pilgrim.statemachine.eval.ExpressionEvaluator;
import net.pilgrim.statemachine.model.Event;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    @Test
    void testBasicArithmeticAndComparison() {
        assertTrue(ExpressionEvaluator.evaluateCondition("10 + 20 == 30"));
        assertTrue(ExpressionEvaluator.evaluateCondition("15 * 2 > 20"));
        assertFalse(ExpressionEvaluator.evaluateCondition("50 / 2 < 20"));
        assertTrue(ExpressionEvaluator.evaluateCondition("100 >= 100"));
        assertTrue(ExpressionEvaluator.evaluateCondition("5 != 6"));
    }

    @Test
    void testLogicalOperatorsAndTernary() {
        assertTrue(ExpressionEvaluator.evaluateCondition("true && !false"));
        assertTrue(ExpressionEvaluator.evaluateCondition("false || true"));
        assertFalse(ExpressionEvaluator.evaluateCondition("true && false"));

        Object ternaryResult = ExpressionEvaluator.evaluate("10 > 5 ? 'greater' : 'lesser'");
        assertEquals("greater", ternaryResult);
    }

    @Test
    void testVariableAndDottedEventLookup() {
        Map<String, Object> vars = Map.of("userId", "usr_123", "score", 95);
        Event event = Event.builder("auth.success")
                .param("token", "secret-token")
                .param("role", "admin")
                .build();

        Map<String, Object> eventScope = Map.of("event$", event);

        assertTrue(ExpressionEvaluator.evaluateCondition("score >= 90", vars));
        assertEquals("usr_123", ExpressionEvaluator.evaluate("userId", vars));

        assertTrue(ExpressionEvaluator.evaluateCondition("event$.values.role == 'admin'", vars, eventScope));
        assertEquals("secret-token", ExpressionEvaluator.evaluate("event$.values.token", vars, eventScope));
        assertEquals("auth.success", ExpressionEvaluator.evaluate("event$.name", vars, eventScope));
    }
}
