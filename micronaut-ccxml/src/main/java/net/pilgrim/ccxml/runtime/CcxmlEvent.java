package net.pilgrim.ccxml.runtime;

import java.util.*;

/**
 * Represents an event delivered to the CCXML event processor (e.g. connection.*, dialog.*, conference.*, user.*).
 */
public class CcxmlEvent {

    private final String name;
    private final String connectionId;
    private final String dialogId;
    private final String conferenceId;
    private final Map<String, Object> data;

    public CcxmlEvent(String name) {
        this(name, null, null, null, Collections.emptyMap());
    }

    public CcxmlEvent(String name, String connectionId) {
        this(name, connectionId, null, null, Collections.emptyMap());
    }

    public CcxmlEvent(String name, String connectionId, String dialogId, String conferenceId, Map<String, Object> data) {
        this.name = Objects.requireNonNull(name, "Event name must not be null");
        this.connectionId = connectionId;
        this.dialogId = dialogId;
        this.conferenceId = conferenceId;
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
    }

    public String getName() {
        return name;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getDialogId() {
        return dialogId;
    }

    public String getConferenceId() {
        return conferenceId;
    }

    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    public Object getProperty(String key) {
        if ("name".equalsIgnoreCase(key)) {
            return name;
        }
        if ("connectionid".equalsIgnoreCase(key) || "connectionId".equals(key)) {
            return connectionId;
        }
        if ("dialogid".equalsIgnoreCase(key) || "dialogId".equals(key)) {
            return dialogId;
        }
        if ("conferenceid".equalsIgnoreCase(key) || "conferenceId".equals(key)) {
            return conferenceId;
        }
        if ("values".equalsIgnoreCase(key) || "data".equalsIgnoreCase(key)) {
            return Collections.unmodifiableMap(data);
        }
        return data.get(key);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String connectionId;
        private String dialogId;
        private String conferenceId;
        private final Map<String, Object> data = new HashMap<>();

        public Builder(String name) {
            this.name = name;
        }

        public Builder connectionId(String connectionId) {
            this.connectionId = connectionId;
            return this;
        }

        public Builder dialogId(String dialogId) {
            this.dialogId = dialogId;
            return this;
        }

        public Builder conferenceId(String conferenceId) {
            this.conferenceId = conferenceId;
            return this;
        }

        public Builder param(String key, Object value) {
            this.data.put(key, value);
            return this;
        }

        public Builder params(Map<String, ?> map) {
            if (map != null) {
                this.data.putAll(map);
            }
            return this;
        }

        public CcxmlEvent build() {
            return new CcxmlEvent(name, connectionId, dialogId, conferenceId, data);
        }
    }

    @Override
    public String toString() {
        return "CcxmlEvent{" +
                "name='" + name + '\'' +
                ", connectionId='" + connectionId + '\'' +
                ", dialogId='" + dialogId + '\'' +
                ", conferenceId='" + conferenceId + '\'' +
                ", data=" + data +
                '}';
    }
}
