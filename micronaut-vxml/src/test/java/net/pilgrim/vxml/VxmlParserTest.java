package net.pilgrim.vxml;

import net.pilgrim.vxml.ast.*;
import net.pilgrim.vxml.parser.VxmlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class VxmlParserTest {

    private final VxmlParser parser = new VxmlParser();

    @Test
    void testParseBasicDocumentWithVarsAndForms() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <vxml version="2.1" xmlns="http://www.w3.org/2001/vxml">
                  <var name="appState" expr="'init'"/>
                  <form id="welcome">
                    <var name="counter" expr="0"/>
                    <block name="greeting">
                      <prompt bargein="true" timeout="3s">
                        <audio src="tone:440,500"/>
                        Hello and welcome!
                      </prompt>
                      <assign name="counter" expr="counter + 1"/>
                      <if cond="counter == 1">
                        <goto next="#secondForm"/>
                      <else/>
                        <exit/>
                      </if>
                    </block>
                  </form>
                  <form id="secondForm">
                    <block>
                      <exit/>
                    </block>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        assertNotNull(doc);
        assertEquals("2.1", doc.getVersion());
        assertEquals("http://www.w3.org/2001/vxml", doc.getXmlns());

        // Check document vars
        assertEquals(1, doc.getVars().size());
        assertEquals("appState", doc.getVars().get(0).getName());
        assertEquals("'init'", doc.getVars().get(0).getExpr());

        // Check forms
        assertEquals(2, doc.getForms().size());
        VxmlForm welcomeForm = doc.findForm("#welcome").orElse(null);
        assertNotNull(welcomeForm);
        assertEquals("welcome", welcomeForm.getId());

        // Form items
        assertEquals(2, welcomeForm.getItems().size());
        assertInstanceOf(VxmlVar.class, welcomeForm.getItems().get(0));
        assertInstanceOf(VxmlBlock.class, welcomeForm.getItems().get(1));

        VxmlBlock block = (VxmlBlock) welcomeForm.getItems().get(1);
        assertEquals("greeting", block.getName());
        assertEquals(3, block.getExecutables().size());

        assertInstanceOf(VxmlPrompt.class, block.getExecutables().get(0));
        VxmlPrompt prompt = (VxmlPrompt) block.getExecutables().get(0);
        assertTrue(prompt.isBargeIn());
        assertEquals(3000L, prompt.getTimeoutMs());
        assertEquals(2, prompt.getContents().size());
        assertInstanceOf(VxmlAudio.class, prompt.getContents().get(0));
        assertInstanceOf(VxmlTextContent.class, prompt.getContents().get(1));

        assertInstanceOf(VxmlAssign.class, block.getExecutables().get(1));
        assertInstanceOf(VxmlIf.class, block.getExecutables().get(2));
        VxmlIf ifNode = (VxmlIf) block.getExecutables().get(2);
        assertEquals("counter == 1", ifNode.getCond());
        assertEquals(1, ifNode.getThenExecutables().size());
        assertInstanceOf(VxmlGoto.class, ifNode.getThenExecutables().get(0));
        VxmlGoto gotoNode = (VxmlGoto) ifNode.getThenExecutables().get(0);
        assertEquals("#secondForm", gotoNode.getNext());
    }

    @Test
    void testParseMenuDesugarsToFormWithChoices() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <menu id="mainMenu">
                    <prompt>Press 1 for Sales, 2 for Support.</prompt>
                    <choice dtmf="1" next="#sales">Sales</choice>
                    <choice dtmf="2" next="#support">Support</choice>
                  </menu>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        assertNotNull(doc);
        assertEquals(1, doc.getForms().size());

        VxmlForm form = doc.findForm("mainMenu").orElse(null);
        assertNotNull(form);
        assertEquals("mainMenu", form.getId());
        assertEquals(1, form.getItems().size());
        assertInstanceOf(VxmlField.class, form.getItems().get(0));

        VxmlField field = (VxmlField) form.getItems().get(0);
        assertEquals(2, field.getChoices().size());
        assertEquals("1", field.getChoices().get(0).getDtmf());
        assertEquals("#sales", field.getChoices().get(0).getNext());
        assertEquals("2", field.getChoices().get(1).getDtmf());
        assertEquals("#support", field.getChoices().get(1).getNext());
    }

    @Test
    void testParseFieldWithGrammarAndFilled() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <form id="pinForm">
                    <field name="pin" type="digits?length=4">
                      <prompt>Enter your 4 digit pin</prompt>
                      <grammar mode="dtmf">1234 | 5678</grammar>
                      <filled>
                        <assign name="authenticated" expr="true"/>
                        <exit/>
                      </filled>
                      <noinput count="1">
                        <reprompt/>
                      </noinput>
                      <nomatch count="1">
                        <clear namelist="pin"/>
                      </nomatch>
                    </field>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        VxmlForm form = doc.getFirstForm().orElse(null);
        assertNotNull(form);
        VxmlField field = (VxmlField) form.getItems().get(0);
        assertEquals("pin", field.getName());
        assertEquals("digits?length=4", field.getType());
        assertNotNull(field.getFilled());
        assertEquals(2, field.getFilled().getExecutables().size());
        assertNotNull(field.getNoInput());
        assertNotNull(field.getNoMatch());
        assertEquals(1, field.getGrammars().size());
        assertEquals("1234 | 5678", field.getGrammars().get(0).getInlineContent());
    }

    @Test
    void testParseRecordFormItem() throws Exception {
        String xml = """
                <vxml version="2.1">
                  <form id="recordForm">
                    <record name="voiceMsg" beep="true" maxtime="30s" finalsilence="3s" dtmfterm="true" type="audio/x-wav">
                      <prompt bargein="true">Please record your message.</prompt>
                      <filled>
                        <assign name="saved" expr="'true'"/>
                        <exit/>
                      </filled>
                    </record>
                  </form>
                </vxml>
                """;

        VxmlDocument doc = parser.parse(xml);
        VxmlForm form = doc.getFirstForm().orElse(null);
        assertNotNull(form);
        assertEquals(1, form.getItems().size());
        assertInstanceOf(VxmlRecord.class, form.getItems().get(0));

        VxmlRecord record = (VxmlRecord) form.getItems().get(0);
        assertEquals("voiceMsg", record.getName());
        assertTrue(record.isBeep());
        assertEquals(30000L, record.getMaxtimeMs());
        assertEquals(3000L, record.getFinalsilenceMs());
        assertTrue(record.isDtmfterm());
        assertEquals("audio/x-wav", record.getType());
        assertEquals(1, record.getPrompts().size());
        assertNotNull(record.getFilled());
        assertEquals(2, record.getFilled().getExecutables().size());
    }

    @Test
    void testInvalidRootThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("<notvxml/>"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(""));
    }
}
