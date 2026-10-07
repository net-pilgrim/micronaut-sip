package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <prompt bargein="true|false" timeout="..." cond="..." count="...">} element.
 */
public class VxmlPrompt implements VxmlExecutable {

    private final boolean bargeIn;
    private final long timeoutMs;
    private final String cond;
    private final int count;
    private final List<VxmlPromptContent> contents;

    public VxmlPrompt(boolean bargeIn, long timeoutMs, String cond, int count, List<VxmlPromptContent> contents) {
        this.bargeIn = bargeIn;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 5000L;
        this.cond = cond != null ? cond.trim() : null;
        this.count = count > 0 ? count : 1;
        this.contents = contents != null ? new ArrayList<>(contents) : new ArrayList<>();
    }

    public boolean isBargeIn() {
        return bargeIn;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public String getCond() {
        return cond;
    }

    public int getCount() {
        return count;
    }

    public List<VxmlPromptContent> getContents() {
        return Collections.unmodifiableList(contents);
    }

    @Override
    public String toString() {
        return "VxmlPrompt{bargeIn=" + bargeIn + ", timeoutMs=" + timeoutMs + ", cond='" + cond + "', count=" + count + ", contents=" + contents + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlPrompt that)) return false;
        return bargeIn == that.bargeIn && timeoutMs == that.timeoutMs && count == that.count && Objects.equals(cond, that.cond) && Objects.equals(contents, that.contents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bargeIn, timeoutMs, cond, count, contents);
    }
}
