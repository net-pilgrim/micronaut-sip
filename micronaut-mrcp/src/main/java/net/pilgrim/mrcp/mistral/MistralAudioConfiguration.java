package net.pilgrim.mrcp.mistral;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

/**
 * Configuration properties for Mistral Audio models (ASR and TTS).
 */
@ConfigurationProperties("mistral.audio")
public class MistralAudioConfiguration {

    private String apiKey = "";
    private String baseUrl = "https://api.mistral.ai/v1";
    private String asrModel = "voxtral-mini-latest";
    private String ttsModel = "voxtral-mini-tts-latest";
    private String defaultVoice = "en_paul_neutral";
    private String defaultLanguage = "en-US";
    private int sampleRate = 8000;
    private boolean mock = false;

    public String getApiKey() {
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("${")) {
            return apiKey.trim();
        }
        String envKey = System.getenv("MISTRAL_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        String audioKey = System.getenv("MISTRAL_AUDIO_API_KEY");
        if (audioKey != null && !audioKey.isBlank()) {
            return audioKey.trim();
        }
        return "";
    }

    public void setApiKey(@Nullable String apiKey) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            this.baseUrl = baseUrl.trim();
        }
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        if (asrModel != null && !asrModel.isBlank()) {
            this.asrModel = asrModel.trim();
        }
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public void setTtsModel(String ttsModel) {
        if (ttsModel != null && !ttsModel.isBlank()) {
            this.ttsModel = ttsModel.trim();
        }
    }

    public String getDefaultVoice() {
        return defaultVoice;
    }

    public void setDefaultVoice(String defaultVoice) {
        if (defaultVoice != null && !defaultVoice.isBlank()) {
            this.defaultVoice = defaultVoice.trim();
        }
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        if (defaultLanguage != null && !defaultLanguage.isBlank()) {
            this.defaultLanguage = defaultLanguage.trim();
        }
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate > 0 ? sampleRate : 8000;
    }

    public boolean isMock() {
        if (mock) {
            return true;
        }
        return getApiKey().isBlank();
    }

    public void setMock(boolean mock) {
        this.mock = mock;
    }
}
