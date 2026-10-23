package net.pilgrim.webrtc;

import io.micronaut.websocket.WebSocketSession;
import net.pilgrim.vxml.media.VxmlMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class WebRtcVxmlMedia implements VxmlMedia {

    private static final Logger LOG = LoggerFactory.getLogger(WebRtcVxmlMedia.class);

    private final WebSocketSession session;
    private volatile boolean bargeInAllowed;
    private final AtomicReference<AudioStreamThread> activeStream = new AtomicReference<>();
    private final AtomicBoolean recording = new AtomicBoolean();
    private final AtomicReference<ByteBuffer> recordBuffer = new AtomicReference<>();
    private Consumer<Character> dtmfListener;

    public WebRtcVxmlMedia(WebSocketSession session) {
        this.session = session;
    }

    @Override
    public void playAudio(byte[] pcmAudio, boolean bargeIn, Runnable onFinished) {
        stopAudio();
        if (pcmAudio == null || pcmAudio.length == 0) {
            if (onFinished != null) onFinished.run();
            return;
        }

        bargeInAllowed = bargeIn;
        AudioStreamThread stream = new AudioStreamThread(pcmAudio, onFinished);
        activeStream.set(stream);
        stream.start();
    }

    @Override
    public void stopAudio() {
        AudioStreamThread stream = activeStream.getAndSet(null);
        if (stream != null) {
            stream.halt();
        }
    }

    @Override
    public boolean isAudioPlaying() {
        AudioStreamThread stream = activeStream.get();
        return stream != null && stream.running.get();
    }

    @Override
    public boolean isBargeInAllowed() {
        return bargeInAllowed;
    }

    @Override
    public void showVideo(byte[] imageData, String mimeType) {
        if (session.isOpen() && imageData != null) {
            String b64 = Base64.getEncoder().encodeToString(imageData);
            String msg = "{\"type\":\"video\",\"data\":\"" + b64 + "\",\"mimeType\":\"" + mimeType + "\"}";
            session.sendSync(msg);
        }
    }

    @Override
    public void clearVideo() {
        if (session.isOpen()) {
            session.sendSync("{\"type\":\"clearVideo\"}");
        }
    }

    @Override
    public void setDtmfListener(Consumer<Character> listener) {
        this.dtmfListener = listener;
    }

    @Override
    public void startRecording() {
        recording.set(true);
        recordBuffer.set(ByteBuffer.allocate(8000 * 2 * 30)); // 30s max
    }

    @Override
    public byte[] stopRecording() {
        recording.set(false);
        ByteBuffer buf = recordBuffer.getAndSet(null);
        if (buf == null) return new byte[0];
        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }

    @Override
    public boolean isRecording() {
        return recording.get();
    }

    public void onIncomingAudio(byte[] pcm16Le) {
        if (recording.get()) {
            ByteBuffer buf = recordBuffer.get();
            if (buf != null && buf.remaining() >= pcm16Le.length) {
                buf.put(pcm16Le);
            }
        }
    }

    public void onDtmf(char digit) {
        if (dtmfListener != null) {
            dtmfListener.accept(digit);
        }
    }

    private class AudioStreamThread extends Thread {
        private final byte[] pcm;
        private final Runnable onFinished;
        private final AtomicBoolean running = new AtomicBoolean(true);

        AudioStreamThread(byte[] pcm, Runnable onFinished) {
            this.pcm = pcm;
            this.onFinished = onFinished;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                int chunkSize = 320; // 20ms at 8kHz
                for (int offset = 0; offset < pcm.length && running.get(); offset += chunkSize) {
                    int len = Math.min(chunkSize, pcm.length - offset);
                    byte[] chunk = new byte[len];
                    System.arraycopy(pcm, offset, chunk, 0, len);

                    if (session.isOpen()) {
                        String b64 = Base64.getEncoder().encodeToString(chunk);
                        session.sendSync("{\"type\":\"audio\",\"data\":\"" + b64 + "\"}");
                    }

                    Thread.sleep(20);
                }
            } catch (Exception e) {
                LOG.warn("Audio streaming error", e);
            } finally {
                running.set(false);
                if (onFinished != null) onFinished.run();
            }
        }

        void halt() {
            running.set(false);
            interrupt();
        }
    }
}

