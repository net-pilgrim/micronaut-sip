package net.pilgrim.vxml.grammar.srgs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SrgsAbnfParserTest {

    private final SrgsAbnfParser parser = new SrgsAbnfParser();

    @Test
    void testParseBasicAbnf() {
        String abnf = """
                #ABNF 1.0 UTF-8;
                language en-US;
                mode voice;
                root $command;

                $command = yes {out="true";} | no {out="false";};
                """;

        SrgsGrammar grammar = parser.parse(abnf);
        assertNotNull(grammar);
        assertEquals("voice", grammar.getMode());
        assertEquals("command", grammar.getRootRuleId());
        assertEquals("en-US", grammar.getXmlLang());

        SrgsRule rootRule = grammar.getRootRule();
        assertNotNull(rootRule);
        assertTrue(rootRule.getNode() instanceof SrgsOneOf);

        SrgsOneOf oneOf = (SrgsOneOf) rootRule.getNode();
        assertEquals(2, oneOf.getItems().size());
    }

    @Test
    void testParseAbnfWithOptionsAndReferences() {
        String abnf = """
                #ABNF 1.0 UTF-8;
                mode voice;
                root $order;

                $order = [i want] $drink;
                $drink = coffee {out="coffee";} | tea {out="tea";};
                """;

        SrgsGrammar grammar = parser.parse(abnf);
        assertEquals("order", grammar.getRootRuleId());
        assertEquals(2, grammar.getRules().size());

        SrgsRule orderRule = grammar.getRule("order");
        assertNotNull(orderRule);
        assertTrue(orderRule.getNode() instanceof SrgsSequence);

        SrgsSequence seq = (SrgsSequence) orderRule.getNode();
        assertEquals(2, seq.getNodes().size());

        SrgsItem optionalPrefix = (SrgsItem) seq.getNodes().get(0);
        assertEquals(0, optionalPrefix.getMinRepeat());
        assertEquals(1, optionalPrefix.getMaxRepeat());

        SrgsRuleRef ref = (SrgsRuleRef) seq.getNodes().get(1);
        assertEquals("drink", ref.getRuleId());
    }
}
