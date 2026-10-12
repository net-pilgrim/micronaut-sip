package net.pilgrim.msml.model;

/**
 * Audio mixing configuration for conference bridges in MSML &lt;createconference&gt; and &lt;modifyconference&gt;.
 */
public record AudioMixConfig(String sampleRate, int channels, String gain) {

    public AudioMixConfig {
        if (sampleRate == null || sampleRate.isBlank()) sampleRate = "8000";
        if (channels <= 0) channels = 1;
        if (gain == null || gain.isBlank()) gain = "0";
    }

    public static AudioMixConfig standardPcm() {
        return new AudioMixConfig("8000", 1, "0");
    }

    public static AudioMixConfig wideband() {
        return new AudioMixConfig("16000", 1, "0");
    }
}
