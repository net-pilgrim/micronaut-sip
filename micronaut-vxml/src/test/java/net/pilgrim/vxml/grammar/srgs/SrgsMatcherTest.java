package net.pilgrim.vxml.grammar.srgs;

import net.pilgrim.vxml.grammar.GrammarMatchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SrgsMatcherTest {

    private final SrgsXmlParser xmlParser = new SrgsXmlParser();
    private final SrgsAbnfParser abnfParser = new SrgsAbnfParser();
    private final SrgsMatcher matcher = new SrgsMatcher();

    @Test
    void testVoiceMatchWithTags() throws Exception {
        String xml = """
                <grammar mode="voice" root="dept">
                  <rule id="dept">
                    <one-of>
                      <item>sales <tag>out="sales_dept";</tag></item>
                      <item>support <tag>out="support_dept";</tag></item>
                      <item>billing <tag>out="billing_dept";</tag></item>
                    </one-of>
                  </rule>
                </grammar>
                """;

        SrgsGrammar grammar = xmlParser.parse(xml);

        GrammarMatchResult r1 = matcher.match(grammar, "sales", true);
        assertTrue(r1.isMatch());
        assertEquals("sales_dept", r1.getValue());

        GrammarMatchResult r2 = matcher.match(grammar, "SUPPORT", true);
        assertTrue(r2.isMatch());
        assertEquals("support_dept", r2.getValue());

        GrammarMatchResult r3 = matcher.match(grammar, "unknown", true);
        assertTrue(r3.isNoMatch());
    }

    @Test
    void testVoiceMatchMultiWordAndOptionalPrefix() throws Exception {
        String xml = """
                <grammar mode="voice" root="flight">
                  <rule id="flight">
                    <item repeat="0-1">i want to fly to</item>
                    <ruleref uri="#city"/>
                    <item repeat="0-1">please</item>
                  </rule>
                  <rule id="city">
                    <one-of>
                      <item>Boston <tag>BOS</tag></item>
                      <item>New York <tag>NYC</tag></item>
                      <item>San Francisco <tag>SFO</tag></item>
                    </one-of>
                  </rule>
                </grammar>
                """;

        SrgsGrammar grammar = xmlParser.parse(xml);

        // Exact city name
        GrammarMatchResult r1 = matcher.match(grammar, "Boston", true);
        assertTrue(r1.isMatch());
        assertEquals("BOS", r1.getValue());

        // Full sentence with prefix and suffix
        GrammarMatchResult r2 = matcher.match(grammar, "i want to fly to new york please", true);
        assertTrue(r2.isMatch());
        assertEquals("NYC", r2.getValue());

        // With prefix only
        GrammarMatchResult r3 = matcher.match(grammar, "i want to fly to san francisco", true);
        assertTrue(r3.isMatch());
        assertEquals("SFO", r3.getValue());

        // Unmatched city
        GrammarMatchResult r4 = matcher.match(grammar, "i want to fly to chicago", true);
        assertTrue(r4.isNoMatch());
    }

    @Test
    void testDtmfMatchingAndPrefixIncomplete() throws Exception {
        String xml = """
                <grammar mode="dtmf" root="digits">
                  <rule id="digits">
                    <one-of>
                      <item>101 <tag>out="dept101";</tag></item>
                      <item>102 <tag>out="dept102";</tag></item>
                      <item>200 <tag>out="dept200";</tag></item>
                    </one-of>
                  </rule>
                </grammar>
                """;

        SrgsGrammar grammar = xmlParser.parse(xml);

        // Incomplete prefix
        GrammarMatchResult r1 = matcher.match(grammar, "1", false);
        assertTrue(r1.isIncomplete());

        GrammarMatchResult r2 = matcher.match(grammar, "10", false);
        assertTrue(r2.isIncomplete());

        // Exact match
        GrammarMatchResult r3 = matcher.match(grammar, "101", false);
        assertTrue(r3.isMatch());
        assertEquals("dept101", r3.getValue());

        // No match
        GrammarMatchResult r4 = matcher.match(grammar, "9", false);
        assertTrue(r4.isNoMatch());
    }

    @Test
    void testAbnfGrammarMatching() {
        String abnf = """
                #ABNF 1.0 UTF-8;
                mode voice;
                root $confirm;

                $confirm = [yes] (please | thanks) {out="confirmed";} | no {out="declined";};
                """;

        SrgsGrammar grammar = abnfParser.parse(abnf);

        GrammarMatchResult r1 = matcher.match(grammar, "yes please", true);
        assertTrue(r1.isMatch());
        assertEquals("confirmed", r1.getValue());

        GrammarMatchResult r2 = matcher.match(grammar, "thanks", true);
        assertTrue(r2.isMatch());
        assertEquals("confirmed", r2.getValue());

        GrammarMatchResult r3 = matcher.match(grammar, "no", true);
        assertTrue(r3.isMatch());
        assertEquals("declined", r3.getValue());

        GrammarMatchResult r4 = matcher.match(grammar, "maybe", true);
        assertTrue(r4.isNoMatch());
    }
}
