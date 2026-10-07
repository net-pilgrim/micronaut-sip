package net.pilgrim.sip.rtp.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AudioRecorderTest {

    @Test
    void testAudioRecordingWavExport(@TempDir Path tempDir) throws Exception {
        byte[] pcm = new byte[16000]; // 1 second at 8000 Hz 16-bit mono
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i & 0x7F);
        }

        AudioRecording recording = new AudioRecording("call-rec-1", pcm, 8000, 1, 1000);
        assertEquals("call-rec-1", recording.getCallId());
        assertEquals(8000, recording.getSampleRate());
        assertEquals(1, recording.getChannels());
        assertEquals(1000, recording.getDurationMs());
        assertEquals(8000, recording.getSampleCount());
        assertArrayEquals(pcm, recording.getPcmData());

        byte[] wavBytes = recording.toWavBytes();
        assertEquals(pcm.length + 44, wavBytes.length);

        // Verify WAV header markers
        assertEquals('R', (char) wavBytes[0]);
        assertEquals('I', (char) wavBytes[1]);
        assertEquals('F', (char) wavBytes[2]);
        assertEquals('F', (char) wavBytes[3]);
        assertEquals('W', (char) wavBytes[8]);
        assertEquals('A', (char) wavBytes[9]);
        assertEquals('V', (char) wavBytes[10]);
        assertEquals('E', (char) wavBytes[11]);
        assertEquals('f', (char) wavBytes[12]);
        assertEquals('m', (char) wavBytes[13]);
        assertEquals('t', (char) wavBytes[14]);
        assertEquals('d', (char) wavBytes[36]);
        assertEquals('a', (char) wavBytes[37]);
        assertEquals('t', (char) wavBytes[38]);
        assertEquals('a', (char) wavBytes[39]);

        // Verify save to file
        Path wavFile = tempDir.resolve("test.wav");
        recording.saveWav(wavFile);
        assertTrue(Files.exists(wavFile));
        assertEquals(wavBytes.length, Files.size(wavFile));

        // Verify write to OutputStream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        recording.writeWav(baos);
        assertArrayEquals(wavBytes, baos.toByteArray());
    }

    @Test
    void testAudioRecorderCapturesFrames() {
        AudioRecorder recorder = new AudioRecorder("call-rec-2", AudioRecorder.DirectionFilter.BOTH, 0);
        assertFalse(recorder.isRecording());

        recorder.start();
        assertTrue(recorder.isRecording());

        // Create 2 inbound frames and 1 outbound frame (20ms each = 320 bytes each)
        byte[] pcm1 = new byte[320];
        byte[] pcm2 = new byte[320];
        byte[] pcm3 = new byte[320];

        recorder.process(new AudioFrame("call-rec-2", pcm1, 8000, 1, 0, 1, AudioFrame.Direction.INBOUND));
        recorder.process(new AudioFrame("call-rec-2", pcm2, 8000, 1, 160, 2, AudioFrame.Direction.OUTBOUND));
        recorder.process(new AudioFrame("call-rec-2", pcm3, 8000, 1, 320, 3, AudioFrame.Direction.INBOUND));

        assertEquals(960, recorder.getRecordedByteCount());
        assertEquals(60, recorder.getDurationMs());

        AudioRecording recording = recorder.stop();
        assertFalse(recorder.isRecording());
        assertNotNull(recording);
        assertEquals(960, recording.getPcmData().length);
        assertEquals(60, recording.getDurationMs());
    }

    @Test
    void testDirectionFiltering() {
        AudioRecorder inboundOnly = new AudioRecorder("call-inbound", AudioRecorder.DirectionFilter.INBOUND_ONLY, 0);
        inboundOnly.start();

        byte[] pcm = new byte[320];
        inboundOnly.process(new AudioFrame("call-inbound", pcm, 8000, 1, 0, 1, AudioFrame.Direction.OUTBOUND));
        assertEquals(0, inboundOnly.getRecordedByteCount());

        inboundOnly.process(new AudioFrame("call-inbound", pcm, 8000, 1, 160, 2, AudioFrame.Direction.INBOUND));
        assertEquals(320, inboundOnly.getRecordedByteCount());

        AudioRecorder outboundOnly = new AudioRecorder("call-outbound", AudioRecorder.DirectionFilter.OUTBOUND_ONLY, 0);
        outboundOnly.start();

        outboundOnly.process(new AudioFrame("call-outbound", pcm, 8000, 1, 0, 1, AudioFrame.Direction.INBOUND));
        assertEquals(0, outboundOnly.getRecordedByteCount());

        outboundOnly.process(new AudioFrame("call-outbound", pcm, 8000, 1, 160, 2, AudioFrame.Direction.OUTBOUND));
        assertEquals(320, outboundOnly.getRecordedByteCount());
    }

    @Test
    void testMaxDurationCapAutoStopsRecording() {
        // Limit to 40 ms (640 bytes at 8 kHz mono 16-bit)
        AudioRecorder recorder = new AudioRecorder("call-cap", AudioRecorder.DirectionFilter.BOTH, 40);
        recorder.start();

        byte[] frame = new byte[320]; // 20 ms
        recorder.process(new AudioFrame("call-cap", frame, 8000, 1, 0, 1, AudioFrame.Direction.INBOUND));
        assertTrue(recorder.isRecording());

        recorder.process(new AudioFrame("call-cap", frame, 8000, 1, 160, 2, AudioFrame.Direction.INBOUND));
        // Hit 40ms cap -> recording should auto-stop
        assertFalse(recorder.isRecording());

        // Subsequent frame should not be captured
        recorder.process(new AudioFrame("call-cap", frame, 8000, 1, 320, 3, AudioFrame.Direction.INBOUND));
        assertEquals(640, recorder.getRecordedByteCount());
    }

    @Test
    void testResetRecorder() {
        AudioRecorder recorder = new AudioRecorder("call-reset");
        recorder.start();

        recorder.process(new AudioFrame("call-reset", new byte[320], 8000, 1, 0, 1, AudioFrame.Direction.INBOUND));
        assertEquals(320, recorder.getRecordedByteCount());

        recorder.reset();
        assertEquals(0, recorder.getRecordedByteCount());
        assertFalse(recorder.isRecording());
        assertEquals(0, recorder.getDurationMs());
    }
}
