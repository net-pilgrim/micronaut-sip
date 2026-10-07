package net.pilgrim.vxml.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * VoiceXML {@code <record name="..." cond="..." expr="..." beep="..." maxtime="..." finalsilence="..." dtmfterm="..." type="...">}
 * form item per W3C VoiceXML 2.1 §2.3.6.
 * <p>
 * Captures audio input from the user/caller into a dialog-scope variable, supports prompt playback,
 * lead-in beeps, maximum time limits, and DTMF key termination.
 */
public class VxmlRecord implements VxmlFormItem {

    private final String name;
    private final String cond;
    private final String expr;
    private final boolean beep;
    private final long maxtimeMs;
    private final long finalsilenceMs;
    private final boolean dtmfterm;
    private final String type;
    private final List<VxmlPrompt> prompts;
    private final VxmlFilled filled;
    private final VxmlNoInput noInput;

    public VxmlRecord(String name,
                      String cond,
                      String expr,
                      boolean beep,
                      long maxtimeMs,
                      long finalsilenceMs,
                      boolean dtmfterm,
                      String type,
                      List<VxmlPrompt> prompts,
                      VxmlFilled filled,
                      VxmlNoInput noInput) {
        this.name = name != null && !name.isBlank() ? name.trim() : "recording";
        this.cond = cond != null ? cond.trim() : null;
        this.expr = expr != null ? expr.trim() : null;
        this.beep = beep;
        this.maxtimeMs = maxtimeMs > 0 ? maxtimeMs : 30_000L;
        this.finalsilenceMs = finalsilenceMs > 0 ? finalsilenceMs : 3_000L;
        this.dtmfterm = dtmfterm;
        this.type = type != null ? type.trim() : "audio/x-wav";
        this.prompts = prompts != null ? new ArrayList<>(prompts) : new ArrayList<>();
        this.filled = filled;
        this.noInput = noInput;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCond() {
        return cond;
    }

    public String getExpr() {
        return expr;
    }

    public boolean isBeep() {
        return beep;
    }

    public long getMaxtimeMs() {
        return maxtimeMs;
    }

    public long getFinalsilenceMs() {
        return finalsilenceMs;
    }

    public boolean isDtmfterm() {
        return dtmfterm;
    }

    public String getType() {
        return type;
    }

    public List<VxmlPrompt> getPrompts() {
        return Collections.unmodifiableList(prompts);
    }

    public VxmlFilled getFilled() {
        return filled;
    }

    public VxmlNoInput getNoInput() {
        return noInput;
    }

    /**
     * Parses standard VoiceXML duration strings (e.g. "30s", "5000ms", "0.5s").
     */
    public static long parseDuration(String val, long defaultMs) {
        if (val == null || val.isBlank()) {
            return defaultMs;
        }
        String s = val.trim().toLowerCase();
        try {
            if (s.endsWith("ms")) {
                return (long) Double.parseDouble(s.substring(0, s.length() - 2).trim());
            } else if (s.endsWith("s")) {
                return (long) (Double.parseDouble(s.substring(0, s.length() - 1).trim()) * 1000.0);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return defaultMs;
        }
    }

    @Override
    public String toString() {
        return "VxmlRecord{" +
                "name='" + name + '\'' +
                ", beep=" + beep +
                ", maxtimeMs=" + maxtimeMs +
                ", dtmfterm=" + dtmfterm +
                ", type='" + type + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VxmlRecord that)) return false;
        return beep == that.beep &&
                maxtimeMs == that.maxtimeMs &&
                finalsilenceMs == that.finalsilenceMs &&
                dtmfterm == that.dtmfterm &&
                Objects.equals(name, that.name) &&
                Objects.equals(cond, that.cond) &&
                Objects.equals(expr, that.expr) &&
                Objects.equals(type, that.type) &&
                Objects.equals(prompts, that.prompts) &&
                Objects.equals(filled, that.filled) &&
                Objects.equals(noInput, that.noInput);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, cond, expr, beep, maxtimeMs, finalsilenceMs, dtmfterm, type, prompts, filled, noInput);
    }
}
