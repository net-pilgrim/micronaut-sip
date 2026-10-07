package net.pilgrim.vxml;

import net.pilgrim.vxml.eval.VxmlExpressionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class VxmlExpressionEvaluatorTest {

    @Test
    void testBasicLiteralsAndArithmetic() {
        assertEquals(3, VxmlExpressionEvaluator.evaluate("1 + 2"));
        assertEquals(6, VxmlExpressionEvaluator.evaluate("10 - 4"));
        assertEquals(15.0, ((Number) VxmlExpressionEvaluator.evaluate("3 * 5")).doubleValue());
        assertEquals(5.0, ((Number) VxmlExpressionEvaluator.evaluate("20 / 4")).doubleValue());
        assertEquals(1.0, ((Number) VxmlExpressionEvaluator.evaluate("7 % 3")).doubleValue());
    }

    @Test
    void testStringConcatenation() {
        assertEquals("hello world", VxmlExpressionEvaluator.evaluate("'hello ' + 'world'"));
        assertEquals("pin1234", VxmlExpressionEvaluator.evaluate("'pin' + 1234"));
    }

    @Test
    void testLooseEqualityAndRelational() {
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("'1' == 1"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("1 == '1'"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("5 > 3"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("3 <= 3"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("10 >= 8"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("4 < 9"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("1 != 2"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("'abc' == 'abc'"));
    }

    @Test
    void testLogicalOperatorsAndConditions() {
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("true && true"));
        assertFalse((Boolean) VxmlExpressionEvaluator.evaluate("true && false"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("false || true"));
        assertFalse((Boolean) VxmlExpressionEvaluator.evaluate("false || false"));
        assertTrue((Boolean) VxmlExpressionEvaluator.evaluate("!false"));
        assertFalse((Boolean) VxmlExpressionEvaluator.evaluate("!true"));

        // Condition evaluation
        assertTrue(VxmlExpressionEvaluator.evaluateCondition("true"));
        assertFalse(VxmlExpressionEvaluator.evaluateCondition("false"));
        assertTrue(VxmlExpressionEvaluator.evaluateCondition(null)); // default true
        assertTrue(VxmlExpressionEvaluator.evaluateCondition(""));   // default true
    }

    @Test
    void testTernaryOperator() {
        assertEquals("yes", VxmlExpressionEvaluator.evaluate("true ? 'yes' : 'no'"));
        assertEquals("no", VxmlExpressionEvaluator.evaluate("false ? 'yes' : 'no'"));
    }

    @Test
    void testScopeLookups() {
        Map<String, Object> docScope = Map.of("appName", "IVR", "version", 2);
        Map<String, Object> dialogScope = Map.of("userInput", "1", "attempts", 3);

        assertEquals("IVR", VxmlExpressionEvaluator.evaluate("appName", dialogScope, docScope));
        assertEquals("1", VxmlExpressionEvaluator.evaluate("userInput", dialogScope, docScope));
        assertTrue(VxmlExpressionEvaluator.evaluateCondition("userInput == '1'", dialogScope, docScope));
        assertTrue(VxmlExpressionEvaluator.evaluateCondition("attempts > 2", dialogScope, docScope));
        assertTrue(VxmlExpressionEvaluator.evaluateCondition("version == 2 && attempts == 3", dialogScope, docScope));
    }
}
