package net.pilgrim.statemachine.model;

import java.util.*;

/**
 * Domain-agnostic event delivered to an event-driven state machine.
 */
public class Event {

    private final String name;
    private final String source;
    private final Map<String, Object> data;
    private final long timestamp;

    public Event(String name) {
        this(name, null, Collections.emptyMap());
    }

    public Event(String name, String source, Map<String, Object> data) {
        this.name = Objects.requireNonNull(name, "Event name must not be null");
        this.source = source;
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }

    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Object getProperty(String key) {
        if ("name".equalsIgnoreCase(key)) {
            return name;
        }
        if ("source".equalsIgnoreCase(key)) {
            return source;
        }
        if ("timestamp".equalsIgnoreCase(key)) {
            return timestamp;
        }
        if ("values".equalsIgnoreCase(key) || "data".equalsIgnoreCase(key) || "payload".equalsIgnoreCase(key)) {
            return Collections.unmodifiableMap(data);
        }
        return data.get(key);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String source;
        private final Map<String, Object> data = new HashMap<>();

        public Builder(String name) {
            this.name = name;
        }

        public Builder source(String source) {
            this.source = source;
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

        public Event build() {
            return new Event(name, source, data);
        }
    }

    @Override
    public String toString() {
        return "Event{" +
                "name='" + name + '\'' +
                ", source='" + source + '\'' +
                ", data=" + data +
                '}';
    }
}
