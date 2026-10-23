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
    private final AtomicReference<AudioStreamTask> activeStream = new AtomicReference<>();
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
        AudioStreamTask stream = new AudioStreamTask(pcmAudio, onFinished);
        activeStream.set(stream);
        Thread vt = Thread.ofVirtual().name("webrtc-vxml-audio-", 0).start(stream);
        stream.setThread(vt);
    }

    @Override
    public void stopAudio() {
        AudioStreamTask stream = activeStream.getAndSet(null);
        if (stream != null) {
            stream.halt();
        }
    }

    @Override
    public boolean isAudioPlaying() {
        AudioStreamTask stream = activeStream.get();
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

    private class AudioStreamTask implements Runnable {
        private final byte[] pcm;
        private final Runnable onFinished;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Thread thread;

        AudioStreamTask(byte[] pcm, Runnable onFinished) {
            this.pcm = pcm;
            this.onFinished = onFinished;
        }

        void setThread(Thread thread) {
            this.thread = thread;
        }

        @Override
        public void run() {
            try {
                int chunkSize = 320; // 20ms at 8kHz
                byte[] chunk = new byte[chunkSize];
                for (int offset = 0; offset < pcm.length && running.get(); offset += chunkSize) {
                    int len = Math.min(chunkSize, pcm.length - offset);
                    byte[] slice = (len == chunkSize) ? chunk : new byte[len];
                    System.arraycopy(pcm, offset, slice, 0, len);

                    if (session.isOpen()) {
                        String b64 = Base64.getEncoder().encodeToString(slice);
                        session.sendAsync("{\"type\":\"audio\",\"data\":\"" + b64 + "\"}");
                    }

                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                // Thread interrupted on stop, normal shutdown
            } catch (Exception e) {
                LOG.warn("Audio streaming error", e);
            } finally {
                running.set(false);
                if (onFinished != null) onFinished.run();
            }
        }

        void halt() {
            running.set(false);
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }
}

