package net.pilgrim.vxml.grammar.srgs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SrgsXmlParserTest {

    private final SrgsXmlParser parser = new SrgsXmlParser();

    @Test
    void testParseBasicGrXml() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <grammar xmlns="http://www.w3.org/2001/06/grammar"
                         xml:lang="en-US"
                         version="1.0"
                         mode="voice"
                         root="yesno">
                  <rule id="yesno" scope="public">
                    <one-of>
                      <item>yes <tag>out="true";</tag></item>
                      <item>yeah <tag>out="true";</tag></item>
                      <item>no <tag>out="false";</tag></item>
                      <item>nope <tag>out="false";</tag></item>
                    </one-of>
                  </rule>
                </grammar>
                """;

        SrgsGrammar grammar = parser.parse(xml);
        assertNotNull(grammar);
        assertEquals("voice", grammar.getMode());
        assertEquals("yesno", grammar.getRootRuleId());
        assertEquals("en-US", grammar.getXmlLang());

        SrgsRule rootRule = grammar.getRootRule();
        assertNotNull(rootRule);
        assertEquals("yesno", rootRule.getId());
        assertTrue(rootRule.isPublic());

        assertTrue(rootRule.getNode() instanceof SrgsOneOf);
        SrgsOneOf oneOf = (SrgsOneOf) rootRule.getNode();
        assertEquals(4, oneOf.getItems().size());

        SrgsItem firstItem = (SrgsItem) oneOf.getItems().get(0);
        assertEquals("out=\"true\";", firstItem.getTag());
        assertTrue(firstItem.getChild() instanceof SrgsToken);
        assertEquals("yes", ((SrgsToken) firstItem.getChild()).getText());
    }

    @Test
    void testParseRulerefAndRepeats() throws Exception {
        String xml = """
                <grammar mode="voice" root="order">
                  <rule id="order">
                    <item repeat="0-1">i want a</item>
                    <ruleref uri="#drink"/>
                    <item repeat="0-1">please</item>
                  </rule>
                  <rule id="drink" scope="public">
                    <one-of>
                      <item>coffee <tag>out="coffee";</tag></item>
                      <item>tea <tag>out="tea";</tag></item>
                    </one-of>
                  </rule>
                </grammar>
                """;

        SrgsGrammar grammar = parser.parse(xml);
        assertEquals("order", grammar.getRootRuleId());
        assertEquals(2, grammar.getRules().size());

        SrgsRule orderRule = grammar.getRule("order");
        assertNotNull(orderRule);
        assertTrue(orderRule.getNode() instanceof SrgsSequence);

        SrgsSequence seq = (SrgsSequence) orderRule.getNode();
        assertEquals(3, seq.getNodes().size());

        SrgsItem optionalPrefix = (SrgsItem) seq.getNodes().get(0);
        assertEquals(0, optionalPrefix.getMinRepeat());
        assertEquals(1, optionalPrefix.getMaxRepeat());

        SrgsRuleRef ref = (SrgsRuleRef) seq.getNodes().get(1);
        assertEquals("drink", ref.getRuleId());
    }

    @Test
    void testParseDtmfGrammar() throws Exception {
        String xml = """
                <grammar mode="dtmf" version="1.0" root="pin">
                  <rule id="pin" scope="public">
                    <item repeat="4">
                      <one-of>
                        <item>1</item>
                        <item>2</item>
                        <item>3</item>
                      </one-of>
                    </item>
                  </rule>
                </grammar>
                """;

        SrgsGrammar grammar = parser.parse(xml);
        assertTrue(grammar.isDtmfMode());
        SrgsRule pinRule = grammar.getRootRule();
        assertNotNull(pinRule);

        assertTrue(pinRule.getNode() instanceof SrgsItem);
        SrgsItem item = (SrgsItem) pinRule.getNode();
        assertEquals(4, item.getMinRepeat());
        assertEquals(4, item.getMaxRepeat());
    }
}
