package net.pilgrim.sip.rtp.media;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Encapsulates recorded audio from a media stream, with support for PCM-16LE and standard WAV export.
 */
public class AudioRecording {

    private final String callId;
    private final byte[] pcmData;
    private final int sampleRate;
    private final int channels;
    private final long durationMs;

    public AudioRecording(String callId, byte[] pcmData, int sampleRate, int channels, long durationMs) {
        this.callId = callId;
        this.pcmData = pcmData != null ? pcmData : new byte[0];
        this.sampleRate = sampleRate > 0 ? sampleRate : 8000;
        this.channels = channels > 0 ? channels : 1;
        this.durationMs = durationMs;
    }

    public String getCallId() {
        return callId;
    }

    public byte[] getPcmData() {
        return pcmData;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getSampleCount() {
        return pcmData.length / (2 * channels);
    }

    /**
     * Converts the recorded PCM-16LE audio into standard RIFF WAVE (.wav) container bytes.
     *
     * @return 8000 Hz, 16-bit mono RIFF WAVE byte array
     */
    public byte[] toWavBytes() {
        byte[] header = createWavHeader(pcmData.length, sampleRate, channels, 16);
        byte[] wav = new byte[header.length + pcmData.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(pcmData, 0, wav, header.length, pcmData.length);
        return wav;
    }

    /**
     * Saves the recording to disk in standard WAV format.
     */
    public void saveWav(Path path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");
        Files.write(path, toWavBytes());
    }

    /**
     * Writes the recording to the specified output stream in standard WAV format.
     */
    public void writeWav(OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out cannot be null");
        out.write(toWavBytes());
    }

    /**
     * Generates a 44-byte standard RIFF WAVE header for linear PCM audio.
     */
    public static byte[] createWavHeader(int pcmDataLength, int sampleRate, int channels, int bitsPerSample) {
        byte[] header = new byte[44];
        int totalDataLen = pcmDataLength + 36;
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // SubChunk1Size = 16 (PCM)
        header[20] = 1; header[21] = 0; // AudioFormat = 1 (linear PCM)
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign; header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmDataLength & 0xff);
        header[41] = (byte) ((pcmDataLength >> 8) & 0xff);
        header[42] = (byte) ((pcmDataLength >> 16) & 0xff);
        header[43] = (byte) ((pcmDataLength >> 24) & 0xff);
        return header;
    }
}
