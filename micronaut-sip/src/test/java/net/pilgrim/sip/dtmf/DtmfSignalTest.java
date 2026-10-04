package net.pilgrim.sip.dtmf;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DtmfSignalTest {

    @Test
    void testValidDigitsAndNormalization() {
        for (char c = '0'; c <= '9'; c++) {
            assertTrue(DtmfSignal.isValidDigit(c));
            assertEquals(c, DtmfSignal.of(c).getDigit());
        }
        assertTrue(DtmfSignal.isValidDigit('*'));
        assertEquals('*', DtmfSignal.of('*').getDigit());

        assertTrue(DtmfSignal.isValidDigit('#'));
        assertEquals('#', DtmfSignal.of('#').getDigit());

        char[] alpha = {'A', 'B', 'C', 'D'};
        char[] lowerAlpha = {'a', 'b', 'c', 'd'};
        for (int i = 0; i < alpha.length; i++) {
            assertTrue(DtmfSignal.isValidDigit(alpha[i]));
            assertTrue(DtmfSignal.isValidDigit(lowerAlpha[i]));
            assertEquals(alpha[i], DtmfSignal.of(alpha[i]).getDigit());
            assertEquals(alpha[i], DtmfSignal.of(lowerAlpha[i]).getDigit(), "Lower case letter should normalize to uppercase");
        }
    }

    @Test
    void testInvalidDigitsThrowException() {
        char[] invalid = {'E', 'e', 'Z', 'x', ' ', '!', '@', '$', '\n', '\0'};
        for (char c : invalid) {
            assertFalse(DtmfSignal.isValidDigit(c));
            assertThrows(IllegalArgumentException.class, () -> DtmfSignal.of(c));
        }
    }

    @Test
    void testDurationAndVolumeDefaults() {
        DtmfSignal s = DtmfSignal.of('5');
        assertEquals('5', s.getDigit());
        assertEquals(160, s.getDuration());
        assertEquals(0, s.getVolume());

        DtmfSignal custom = DtmfSignal.of('*', 250, 6);
        assertEquals('*', custom.getDigit());
        assertEquals(250, custom.getDuration());
        assertEquals(6, custom.getVolume());
    }

    @Test
    void testToRelayBody() {
        DtmfSignal s = DtmfSignal.of('9', 200);
        String relay = s.toRelayBody();
        assertTrue(relay.contains("Signal=9"));
        assertTrue(relay.contains("Duration=200"));
        assertFalse(relay.contains("Volume="));

        DtmfSignal withVol = DtmfSignal.of('A', 150, 10);
        String relayWithVol = withVol.toRelayBody();
        assertTrue(relayWithVol.contains("Signal=A"));
        assertTrue(relayWithVol.contains("Duration=150"));
        assertTrue(relayWithVol.contains("Volume=10"));
    }

    @Test
    void testToDtmfBody() {
        DtmfSignal s = DtmfSignal.of('#');
        assertEquals("#", s.toDtmfBody());
    }

    @Test
    void testParseApplicationDtmfRelay() {
        String body = "Signal=5\r\nDuration=160\r\n";
        DtmfSignal parsed = DtmfSignal.parse(body, "application/dtmf-relay");
        assertEquals('5', parsed.getDigit());
        assertEquals(160, parsed.getDuration());
        assertEquals(0, parsed.getVolume());

        String multilineCaseInsensitive = "  signal = * \n duration = 300 \n volume = 12 \n";
        DtmfSignal parsed2 = DtmfSignal.parse(multilineCaseInsensitive, "application/dtmf-relay");
        assertEquals('*', parsed2.getDigit());
        assertEquals(300, parsed2.getDuration());
        assertEquals(12, parsed2.getVolume());

        // Short form s= and d=
        String shortForm = "s=B\r\nd=180\r\nv=4";
        DtmfSignal parsed3 = DtmfSignal.parse(shortForm, "application/dtmf-relay");
        assertEquals('B', parsed3.getDigit());
        assertEquals(180, parsed3.getDuration());
        assertEquals(4, parsed3.getVolume());
    }

    @Test
    void testParseApplicationDtmf() {
        DtmfSignal parsed = DtmfSignal.parse("7", "application/dtmf");
        assertEquals('7', parsed.getDigit());
        assertEquals(160, parsed.getDuration());

        DtmfSignal parsedWithSignal = DtmfSignal.parse("Signal=#", "application/dtmf");
        assertEquals('#', parsedWithSignal.getDigit());
    }

    @Test
    void testParseTextPlainAndLooseFormats() {
        DtmfSignal parsed1 = DtmfSignal.parse("dtmf: 3", "text/plain");
        assertEquals('3', parsed1.getDigit());

        DtmfSignal parsed2 = DtmfSignal.parse("DTMF: *", null);
        assertEquals('*', parsed2.getDigit());

        DtmfSignal parsed3 = DtmfSignal.parse("Signal=D", null);
        assertEquals('D', parsed3.getDigit());
    }

    @Test
    void testNonDtmfContentReturnsEmpty() {
        assertTrue(DtmfSignal.tryParse(null, null).isEmpty());
        assertTrue(DtmfSignal.tryParse("", null).isEmpty());
        assertTrue(DtmfSignal.tryParse("   ", null).isEmpty());
        assertTrue(DtmfSignal.tryParse("Hello world, how are you?", "text/plain").isEmpty());
        assertTrue(DtmfSignal.tryParse("Signal=Invalid", "application/dtmf-relay").isEmpty());
        assertTrue(DtmfSignal.tryParse("some=random&key=val", null).isEmpty());
    }

    @Test
    void testEqualsAndHashCode() {
        DtmfSignal s1 = DtmfSignal.of('5', 160, 0);
        DtmfSignal s2 = DtmfSignal.of('5', 160, 0);
        DtmfSignal s3 = DtmfSignal.of('5', 200, 0);
        DtmfSignal s4 = DtmfSignal.of('6', 160, 0);

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
        assertNotEquals(s1, s3);
        assertNotEquals(s1, s4);
        assertNotNull(s1.toString());
        assertTrue(s1.toString().contains("digit=5"));
    }
}
