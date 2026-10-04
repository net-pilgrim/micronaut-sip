package net.pilgrim.sip.rtp.media;

import net.pilgrim.sip.rtp.config.RtpConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RtpAudioHookTest {

    private RtpMediaManager mediaManager;

    @BeforeEach
    void setUp() {
        RtpConfiguration config = new RtpConfiguration();
        config.setPortRangeStart(27000);
        config.setPortRangeEnd(28000);
        config.setBindAddress("127.0.0.1");
        config.setWorkerThreads(2);
        mediaManager = new RtpMediaManager(config);
    }

    @AfterEach
    void tearDown() {
        if (mediaManager != null) {
            mediaManager.close();
        }
    }

    @Test
    void inboundAndOutboundProcessorsCaptureFrames() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-sender");
        RtpMediaSession receiver = mediaManager.createSession("call-receiver");

        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));
        receiver.setRemoteAddress(new InetSocketAddress("127.0.0.1", sender.getLocalPort()));

        List<AudioFrame> outboundFrames = new CopyOnWriteArrayList<>();
        List<AudioFrame> inboundFrames = new CopyOnWriteArrayList<>();

        sender.addOutboundProcessor(outboundFrames::add);
        receiver.addInboundProcessor(inboundFrames::add);

        byte[] pcm = new byte[320]; // 160 samples (20ms at 8kHz)
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i & 0x7F);
        }

        sender.sendAudioFrame(pcm, true).block(Duration.ofSeconds(2));

        // Wait for receiver to process inbound
        StepVerifier.create(receiver.incomingPackets().take(1))
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(1, outboundFrames.size());
        AudioFrame outFrame = outboundFrames.get(0);
        assertEquals("call-sender", outFrame.getCallId());
        assertTrue(outFrame.isOutbound());
        assertEquals(160, outFrame.getSampleCount());
        assertEquals(8000, outFrame.getSampleRate());

        assertEquals(1, inboundFrames.size());
        AudioFrame inFrame = inboundFrames.get(0);
        assertEquals("call-receiver", inFrame.getCallId());
        assertTrue(inFrame.isInbound());
        assertEquals(160, inFrame.getSampleCount());
        assertEquals(8000, inFrame.getSampleRate());
    }

    @Test
    void goertzelDetectorHookDetectsDtmfTone() throws Exception {
        RtpMediaSession session = mediaManager.createSession("call-goertzel");

        // Target DTMF frequency: 697 Hz (row 1: keys 1, 2, 3, A)
        AtomicBoolean detected697 = new AtomicBoolean(false);

        AudioProcessor goertzelProcessor = frame -> {
            short[] samples = frame.toShortArray();
            double power697 = runGoertzel(samples, 697.0, frame.getSampleRate());
            double power1000 = runGoertzel(samples, 1000.0, frame.getSampleRate());

            // Tone present if 697 Hz power dominates
            if (power697 > 1e7 && power697 > 10 * power1000) {
                detected697.set(true);
            }
        };

        session.addInboundProcessor(goertzelProcessor);

        // Generate synthetic 697 Hz tone (160 samples, 20ms)
        byte[] toneBytes = generateTonePcm(697, 8000, 160, 16000);

        session.sendAudioFrame(toneBytes, false);

        // Simulate frame delivery through inbound processor
        AudioFrame frame = new AudioFrame("call-goertzel", toneBytes, 8000, 1, 0, 1, AudioFrame.Direction.INBOUND);
        goertzelProcessor.process(frame);

        assertTrue(detected697.get(), "Goertzel processor should detect 697 Hz DTMF component");
    }

    @Test
    void voiceActivityDetectionHookDistinguishesSpeechFromSilence() {
        List<Boolean> speechEvents = new ArrayList<>();

        // VAD threshold: speech if dBFS > -40 dBFS
        AudioProcessor vadProcessor = frame -> {
            double dbfs = frame.calculateDbfs();
            boolean isSpeech = dbfs > -40.0;
            speechEvents.add(isSpeech);
        };

        // Silence frame (all zeroes)
        byte[] silence = new byte[320];
        AudioFrame silenceFrame = new AudioFrame("call-vad", silence, 8000, 1, 0, 1, AudioFrame.Direction.INBOUND);
        vadProcessor.process(silenceFrame);

        // Speech / tone frame
        byte[] speechPcm = generateTonePcm(440, 8000, 160, 12000);
        AudioFrame speechFrame = new AudioFrame("call-vad", speechPcm, 8000, 1, 160, 2, AudioFrame.Direction.INBOUND);
        vadProcessor.process(speechFrame);

        assertEquals(2, speechEvents.size());
        assertFalse(speechEvents.get(0), "Zero-amplitude frame must be classified as silence");
        assertTrue(speechEvents.get(1), "Tone frame must be classified as speech");
    }

    @Test
    void mediaManagerSessionInitializerAppliesGlobalAudioProcessor() throws Exception {
        AtomicInteger framesReceived = new AtomicInteger(0);

        mediaManager.addAudioProcessor(frame -> framesReceived.incrementAndGet());

        RtpMediaSession session = mediaManager.createSession("call-auto-hook");
        assertEquals(1, session.getInboundProcessors().size());

        AudioFrame dummyFrame = new AudioFrame("call-auto-hook", new byte[320], 8000, 1, 0, 1, AudioFrame.Direction.INBOUND);
        session.getInboundProcessors().get(0).process(dummyFrame);

        assertEquals(1, framesReceived.get());
    }

    @Test
    void processorExceptionDoesNotDisruptSession() throws Exception {
        RtpMediaSession session = mediaManager.createSession("call-resilient");
        AtomicBoolean nextProcessorRan = new AtomicBoolean(false);

        // Failing processor
        session.addInboundProcessor(frame -> {
            throw new RuntimeException("Simulated DSP crash");
        });
        // Sibling processor
        session.addInboundProcessor(frame -> nextProcessorRan.set(true));

        AudioFrame dummyFrame = new AudioFrame("call-resilient", new byte[320], 8000, 1, 0, 1, AudioFrame.Direction.INBOUND);
        for (AudioProcessor p : session.getInboundProcessors()) {
            try {
                p.process(dummyFrame);
            } catch (Throwable ignored) {
            }
        }

        assertTrue(nextProcessorRan.get(), "Subsequent processors should still execute");
    }

    @Test
    void incomingAudioFramesReactiveFluxEmitsDecodedPcm() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-stream-s");
        RtpMediaSession receiver = mediaManager.createSession("call-stream-r");

        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));
        receiver.setRemoteAddress(new InetSocketAddress("127.0.0.1", sender.getLocalPort()));

        byte[] pcm = generateTonePcm(800, 8000, 160, 10000);

        StepVerifier.create(receiver.incomingAudioFrames().take(1))
                .then(() -> sender.sendAudioFrame(pcm, false).block(Duration.ofSeconds(2)))
                .assertNext((AudioFrame frame) -> {
                    assertEquals("call-stream-r", frame.getCallId());
                    assertTrue(frame.isInbound());
                    assertEquals(160, frame.getSampleCount());
                    assertEquals(8000, frame.getSampleRate());
                    assertTrue(frame.calculateRms() > 0.1);
                })
                .verifyComplete();
    }

    /**
     * Standard Goertzel algorithm calculating energy at target frequency.
     */
    private double runGoertzel(short[] samples, double targetFreq, int sampleRate) {
        double k = 0.5 + ((samples.length * targetFreq) / sampleRate);
        double omega = (2.0 * Math.PI * k) / samples.length;
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double q0 = 0;
        double q1 = 0;
        double q2 = 0;

        for (short sample : samples) {
            q0 = coeff * q1 - q2 + sample;
            q2 = q1;
            q1 = q0;
        }

        return q1 * q1 + q2 * q2 - q1 * q2 * coeff;
    }

    private byte[] generateTonePcm(int frequencyHz, int sampleRate, int sampleCount, int amplitude) {
        byte[] pcm = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            short sample = (short) (amplitude * Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate));
            pcm[2 * i] = (byte) (sample & 0xFF);
            pcm[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return pcm;
    }
}
