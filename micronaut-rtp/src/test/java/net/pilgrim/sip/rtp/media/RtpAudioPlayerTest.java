package net.pilgrim.sip.rtp.media;

import net.pilgrim.sip.rtp.config.RtpConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RtpAudioPlayerTest {

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
    void testPlayAudioCompletesCallback() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-player-1");
        RtpMediaSession receiver = mediaManager.createSession("call-receiver-1");
        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));

        byte[] audio = new byte[640]; // 2 frames (40ms)
        CountDownLatch latch = new CountDownLatch(1);

        RtpAudioPlayer player = new RtpAudioPlayer(sender, audio, latch::countDown);
        assertFalse(player.isRunning());
        assertFalse(player.isCompleted());

        player.start();
        assertTrue(player.isRunning());

        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "Player completion callback should be triggered");
        assertTrue(player.isCompleted());
        assertFalse(player.isRunning());
    }

    @Test
    void testStopAudioInterruptsPlayback() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-player-2");
        RtpMediaSession receiver = mediaManager.createSession("call-receiver-2");
        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));

        byte[] longAudio = new byte[320 * 100]; // 100 frames (2 seconds)
        AtomicBoolean completedCalled = new AtomicBoolean(false);

        RtpAudioPlayer player = new RtpAudioPlayer(sender, longAudio, () -> completedCalled.set(true));
        player.start();
        assertTrue(player.isRunning());

        Thread.sleep(50);
        player.stop();

        assertFalse(player.isRunning());
        assertFalse(completedCalled.get());
    }

    @Test
    void testSessionPlayAudioIntegration() throws Exception {
        RtpMediaSession sender = mediaManager.createSession("call-player-3");
        RtpMediaSession receiver = mediaManager.createSession("call-receiver-3");
        sender.setRemoteAddress(new InetSocketAddress("127.0.0.1", receiver.getLocalPort()));

        byte[] audio = new byte[320]; // 1 frame
        CountDownLatch latch = new CountDownLatch(1);

        assertFalse(sender.isAudioPlaying());
        sender.playAudio(audio, latch::countDown);

        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed);

        // Stopping when nothing is playing should be safe
        sender.stopAudio();
        assertFalse(sender.isAudioPlaying());
    }
}
