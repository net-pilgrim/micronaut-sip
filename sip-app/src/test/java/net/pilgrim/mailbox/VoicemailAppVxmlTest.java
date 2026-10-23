package net.pilgrim.mailbox;

import net.pilgrim.vxml.ast.VxmlDocument;
import net.pilgrim.vxml.ast.VxmlForm;
import net.pilgrim.vxml.loader.VxmlDocumentLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class VoicemailAppVxmlTest {

    private final VxmlDocumentLoader documentLoader = new VxmlDocumentLoader();

    @Test
    void testParseVoicemailAppVxml() throws Exception {
        VxmlDocument doc = documentLoader.loadDocument("classpath:vxml/voicemail_app.vxml");
        assertNotNull(doc);
        assertEquals("2.1", doc.getVersion());

        // Verify key forms and menus exist
        assertTrue(doc.findForm("#router").isPresent(), "#router form must be present");
        assertTrue(doc.findForm("#deposit_form").isPresent(), "#deposit_form must be present");
        assertTrue(doc.findForm("#owner_portal").isPresent(), "#owner_portal must be present");
        assertTrue(doc.findForm("#greeting_menu").isPresent(), "#greeting_menu must be present");
        assertTrue(doc.findForm("#record_greeting_form").isPresent(), "#record_greeting_form must be present");
        assertTrue(doc.findForm("#reset_default_greeting").isPresent(), "#reset_default_greeting must be present");
        assertTrue(doc.findForm("#inbox_menu").isPresent(), "#inbox_menu must be present");
    }
}
