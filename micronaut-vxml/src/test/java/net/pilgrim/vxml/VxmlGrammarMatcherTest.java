package net.pilgrim.vxml;

import net.pilgrim.vxml.ast.VxmlChoice;
import net.pilgrim.vxml.ast.VxmlField;
import net.pilgrim.vxml.ast.VxmlGrammar;
import net.pilgrim.vxml.grammar.GrammarMatchResult;
import net.pilgrim.vxml.grammar.VxmlGrammarMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class VxmlGrammarMatcherTest {

    private final VxmlGrammarMatcher matcher = new VxmlGrammarMatcher();

    @Test
    void testChoiceMatching() {
        VxmlChoice c1 = new VxmlChoice("1", "#sales", null, null, "Sales");
        VxmlChoice c2 = new VxmlChoice("2", "#support", null, null, "Support");
        VxmlField field = new VxmlField("menu", null, null, null, null, List.of(), List.of(), List.of(c1, c2), null, null, null);

        GrammarMatchResult r1 = matcher.match(field, "1");
        assertTrue(r1.isMatch());
        assertEquals("1", r1.getValue());
        assertEquals("#sales", r1.getNextTarget());

        GrammarMatchResult r2 = matcher.match(field, "2");
        assertTrue(r2.isMatch());
        assertEquals("2", r2.getValue());
        assertEquals("#support", r2.getNextTarget());

        GrammarMatchResult r3 = matcher.match(field, "3");
        assertTrue(r3.isNoMatch());
    }

    @Test
    void testBuiltinBoolean() {
        VxmlField field = new VxmlField("confirm", null, null, "boolean", null, List.of(), List.of(), List.of(), null, null, null);

        GrammarMatchResult r1 = matcher.match(field, "1");
        assertTrue(r1.isMatch());
        assertEquals("true", r1.getValue());

        GrammarMatchResult r2 = matcher.match(field, "2");
        assertTrue(r2.isMatch());
        assertEquals("false", r2.getValue());

        GrammarMatchResult r3 = matcher.match(field, "9");
        assertTrue(r3.isNoMatch());
    }

    @Test
    void testBuiltinDigitsLength() {
        VxmlField field = new VxmlField("pin", null, null, "digits?length=4", null, List.of(), List.of(), List.of(), null, null, null);

        // Incomplete on 1, 2, 3 digits
        assertTrue(matcher.match(field, "1").isIncomplete());
        assertTrue(matcher.match(field, "12").isIncomplete());
        assertTrue(matcher.match(field, "123").isIncomplete());

        // Match on exactly 4 digits
        GrammarMatchResult match = matcher.match(field, "1234");
        assertTrue(match.isMatch());
        assertEquals("1234", match.getValue());
    }

    @Test
    void testBuiltinDigitsHashTermination() {
        VxmlField field = new VxmlField("acc", null, null, "digits?minlength=2;maxlength=6", null, List.of(), List.of(), List.of(), null, null, null);

        // Terminating with #
        GrammarMatchResult match = matcher.match(field, "123#");
        assertTrue(match.isMatch());
        assertEquals("123", match.getValue());
    }

    @Test
    void testInlineGrammarPipeList() {
        VxmlGrammar grammar = new VxmlGrammar("dtmf", null, null, "10 | 20 | 30");
        VxmlField field = new VxmlField("opt", null, null, null, null, List.of(), List.of(grammar), List.of(), null, null, null);

        assertTrue(matcher.match(field, "1").isIncomplete());
        assertTrue(matcher.match(field, "10").isMatch());
        assertTrue(matcher.match(field, "20").isMatch());
        assertTrue(matcher.match(field, "99").isNoMatch());
    }

    @Test
    void testSrgsItemTagGrammar() {
        String srgs = """
                <grammar type="application/srgs+xml" mode="dtmf">
                  <rule id="options">
                    <one-of>
                      <item>1<tag>sales</tag></item>
                      <item>2<tag>billing</tag></item>
                    </one-of>
                  </rule>
                </grammar>
                """;
        VxmlGrammar grammar = new VxmlGrammar("dtmf", "application/srgs+xml", null, srgs);
        VxmlField field = new VxmlField("dept", null, null, null, null, List.of(), List.of(grammar), List.of(), null, null, null);

        GrammarMatchResult r1 = matcher.match(field, "1");
        assertTrue(r1.isMatch());
        assertEquals("sales", r1.getValue());

        GrammarMatchResult r2 = matcher.match(field, "2");
        assertTrue(r2.isMatch());
        assertEquals("billing", r2.getValue());
    }
}
