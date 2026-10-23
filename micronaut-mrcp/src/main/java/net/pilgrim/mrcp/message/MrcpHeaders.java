package net.pilgrim.mrcp.message;

import net.pilgrim.mrcp.resource.MrcpChannelId;

import java.util.*;

/**
 * Case-insensitive container for MRCPv2 header fields (RFC 6787 §6).
 */
public class MrcpHeaders implements Iterable<Map.Entry<String, String>> {

    // Common Generic Headers (RFC 6787 §6.2)
    public static final String CHANNEL_IDENTIFIER = "Channel-Identifier";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String CONTENT_ID = "Content-Id";
    public static final String ACCEPT = "Accept";
    public static final String FETCH_TIMEOUT = "Fetch-Timeout";
    public static final String SET_COOKIE = "Set-Cookie";
    public static final String LOGGING_TAG = "Logging-Tag";

    // Common Synthesizer Headers (RFC 6787 §8.4)
    public static final String JUMP_SIZE = "Jump-Size";
    public static final String KILL_ON_BARGE_IN = "Kill-On-Barge-In";
    public static final String SPEAKER_PROFILE = "Speaker-Profile";
    public static final String COMPLETION_CAUSE = "Completion-Cause";
    public static final String VOICE_GENDER = "Voice-Gender";
    public static final String VOICE_AGE = "Voice-Age";
    public static final String VOICE_NAME = "Voice-Name";
    public static final String PROSODY_PITCH = "Prosody-Pitch";
    public static final String PROSODY_VOLUME = "Prosody-Volume";
    public static final String PROSODY_RATE = "Prosody-Rate";
    public static final String SPEECH_MARKER = "Speech-Marker";
    public static final String SPEECH_LANGUAGE = "Speech-Language";
    public static final String FETCH_HINT = "Fetch-Hint";
    public static final String AUDIO_PROPERTIES = "Audio-Properties";
    public static final String SPEAK_RESTART = "Speak-Restart";

    // Common Recognizer Headers (RFC 6787 §9.4)
    public static final String CONFIDENCE_THRESHOLD = "Confidence-Threshold";
    public static final String SENSITIVITY_LEVEL = "Sensitivity-Level";
    public static final String SPEED_VS_ACCURACY = "Speed-Vs-Accuracy";
    public static final String N_BEST_LIST_LENGTH = "N-Best-List-Length";
    public static final String NO_INPUT_TIMEOUT = "No-Input-Timeout";
    public static final String RECOGNITION_TIMEOUT = "Recognition-Timeout";
    public static final String WAVEFORM_URI = "Waveform-URI";
    public static final String MEDIA_TYPE = "Media-Type";
    public static final String INPUT_TYPE = "Input-Type";
    public static final String INPUT_WAVEFORM = "Input-Waveform";
    public static final String COMPLETION_REASON = "Completion-Reason";
    public static final String SPEECH_COMPLETE_TIMEOUT = "Speech-Complete-Timeout";
    public static final String SPEECH_INCOMPLETE_TIMEOUT = "Speech-Incomplete-Timeout";
    public static final String DTMF_INTERDIGIT_TIMEOUT = "DTMF-Interdigit-Timeout";
    public static final String DTMF_TERM_TIMEOUT = "DTMF-Term-Timeout";
    public static final String DTMF_TERM_CHAR = "DTMF-Term-Char";
    public static final String FAILED_URI = "Failed-URI";
    public static final String FAILED_URI_CAUSE = "Failed-URI-Cause";
    public static final String SAVE_WAVEFORM = "Save-Waveform";
    public static final String NEW_AUDIO_CHANNEL = "New-Audio-Channel";
    public static final String SPEECH_LANGUAGE_RECOGNITION = "Speech-Language";

    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final Map<String, String> normalizedNames = new HashMap<>();

    public MrcpHeaders() {}

    public MrcpHeaders(MrcpHeaders other) {
        if (other != null) {
            for (Map.Entry<String, List<String>> entry : other.headers.entrySet()) {
                this.headers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                this.normalizedNames.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getKey());
            }
        }
    }

    public synchronized void set(String name, String value) {
        if (name == null || value == null) return;
        String key = name.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        String canonical = normalizedNames.getOrDefault(lower, key);
        normalizedNames.put(lower, canonical);

        List<String> values = new ArrayList<>();
        values.add(value.trim());
        headers.put(canonical, values);
    }

    public synchronized void add(String name, String value) {
        if (name == null || value == null) return;
        String key = name.trim();
        String lower = key.toLowerCase(Locale.ROOT);
        String canonical = normalizedNames.getOrDefault(lower, key);
        normalizedNames.put(lower, canonical);

        headers.computeIfAbsent(canonical, k -> new ArrayList<>()).add(value.trim());
    }

    public synchronized String get(String name) {
        if (name == null) return null;
        String canonical = normalizedNames.get(name.trim().toLowerCase(Locale.ROOT));
        if (canonical == null) return null;
        List<String> values = headers.get(canonical);
        return (values != null && !values.isEmpty()) ? values.getFirst() : null;
    }

    public synchronized List<String> getAll(String name) {
        if (name == null) return Collections.emptyList();
        String canonical = normalizedNames.get(name.trim().toLowerCase(Locale.ROOT));
        if (canonical == null) return Collections.emptyList();
        List<String> values = headers.get(canonical);
        return values != null ? Collections.unmodifiableList(new ArrayList<>(values)) : Collections.emptyList();
    }

    public synchronized boolean contains(String name) {
        if (name == null) return false;
        return normalizedNames.containsKey(name.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized void remove(String name) {
        if (name == null) return;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        String canonical = normalizedNames.remove(lower);
        if (canonical != null) {
            headers.remove(canonical);
        }
    }

    public synchronized void clear() {
        headers.clear();
        normalizedNames.clear();
    }

    // Typed convenience accessors

    public MrcpChannelId getChannelIdentifier() {
        String val = get(CHANNEL_IDENTIFIER);
        return val != null ? MrcpChannelId.parse(val) : null;
    }

    public void setChannelIdentifier(MrcpChannelId channelId) {
        if (channelId != null) {
            set(CHANNEL_IDENTIFIER, channelId.toString());
        } else {
            remove(CHANNEL_IDENTIFIER);
        }
    }

    public String getContentType() {
        return get(CONTENT_TYPE);
    }

    public void setContentType(String contentType) {
        if (contentType != null) {
            set(CONTENT_TYPE, contentType);
        } else {
            remove(CONTENT_TYPE);
        }
    }

    public String getContentId() {
        return get(CONTENT_ID);
    }

    public void setContentId(String contentId) {
        if (contentId != null) {
            set(CONTENT_ID, contentId);
        } else {
            remove(CONTENT_ID);
        }
    }

    public int getContentLength() {
        String val = get(CONTENT_LENGTH);
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    public void setContentLength(int length) {
        set(CONTENT_LENGTH, String.valueOf(length));
    }

    public String getSpeechLanguage() {
        return get(SPEECH_LANGUAGE);
    }

    public void setSpeechLanguage(String language) {
        set(SPEECH_LANGUAGE, language);
    }

    public String getVoiceName() {
        return get(VOICE_NAME);
    }

    public void setVoiceName(String voiceName) {
        set(VOICE_NAME, voiceName);
    }

    public String getCompletionCause() {
        return get(COMPLETION_CAUSE);
    }

    public void setCompletionCause(String completionCause) {
        set(COMPLETION_CAUSE, completionCause);
    }

    public boolean isKillOnBargeIn() {
        String val = get(KILL_ON_BARGE_IN);
        return "true".equalsIgnoreCase(val);
    }

    public void setKillOnBargeIn(boolean killOnBargeIn) {
        set(KILL_ON_BARGE_IN, String.valueOf(killOnBargeIn));
    }

    public float getConfidenceThreshold() {
        String val = get(CONFIDENCE_THRESHOLD);
        if (val != null) {
            try {
                return Float.parseFloat(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0.5f;
    }

    public void setConfidenceThreshold(float threshold) {
        set(CONFIDENCE_THRESHOLD, String.format(Locale.ROOT, "%.2f", threshold));
    }

    public long getNoInputTimeout() {
        String val = get(NO_INPUT_TIMEOUT);
        if (val != null) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 5000L;
    }

    public void setNoInputTimeout(long timeoutMs) {
        set(NO_INPUT_TIMEOUT, String.valueOf(timeoutMs));
    }

    @Override
    public synchronized Iterator<Map.Entry<String, String>> iterator() {
        List<Map.Entry<String, String>> flatList = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String v : entry.getValue()) {
                flatList.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), v));
            }
        }
        return flatList.iterator();
    }

    public synchronized Map<String, String> asSingleValueMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            map.put(entry.getKey(), String.join(", ", entry.getValue()));
        }
        return map;
    }

    @Override
    public String toString() {
        return asSingleValueMap().toString();
    }
}
