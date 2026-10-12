package net.pilgrim.sgw.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * TCAP Dialogue Message envelope (TC_BEGIN, TC_CONTINUE, TC_END, TC_ABORT, TC_UNI).
 */
@Serdeable
@Introspected
public class TcapMessage {

    private TcapMessageType type;

    @Nullable
    private String otid;

    @Nullable
    private String dtid;

    @Nullable
    private TcapDialoguePortion dialogue;

    private List<TcapComponent> components = new ArrayList<>();

    @Nullable
    private String abortReason;

    @Nullable
    private String abortSource;

    public TcapMessage() {
    }

    public TcapMessage(TcapMessageType type) {
        this.type = type;
    }

    public static TcapMessage begin(String otid, List<TcapComponent> components) {
        TcapMessage msg = new TcapMessage(TcapMessageType.TC_BEGIN);
        msg.setOtid(otid);
        if (components != null) {
            msg.setComponents(new ArrayList<>(components));
        }
        return msg;
    }

    public static TcapMessage begin(String otid, String applicationContext, List<TcapComponent> components) {
        TcapMessage msg = begin(otid, components);
        if (applicationContext != null) {
            msg.setDialogue(new TcapDialoguePortion(applicationContext));
        }
        return msg;
    }

    public static TcapMessage continueDialogue(String otid, String dtid, List<TcapComponent> components) {
        TcapMessage msg = new TcapMessage(TcapMessageType.TC_CONTINUE);
        msg.setOtid(otid);
        msg.setDtid(dtid);
        if (components != null) {
            msg.setComponents(new ArrayList<>(components));
        }
        return msg;
    }

    public static TcapMessage end(String dtid, List<TcapComponent> components) {
        TcapMessage msg = new TcapMessage(TcapMessageType.TC_END);
        msg.setDtid(dtid);
        if (components != null) {
            msg.setComponents(new ArrayList<>(components));
        }
        return msg;
    }

    public static TcapMessage abort(String dtid, String abortReason) {
        return abort(dtid, abortReason, "provider");
    }

    public static TcapMessage abort(String dtid, String abortReason, String abortSource) {
        TcapMessage msg = new TcapMessage(TcapMessageType.TC_ABORT);
        msg.setDtid(dtid);
        msg.setAbortReason(abortReason);
        msg.setAbortSource(abortSource != null ? abortSource : "provider");
        return msg;
    }

    public static TcapMessage uni(String applicationContext, List<TcapComponent> components) {
        TcapMessage msg = new TcapMessage(TcapMessageType.TC_UNI);
        if (applicationContext != null) {
            msg.setDialogue(new TcapDialoguePortion(applicationContext));
        }
        if (components != null) {
            msg.setComponents(new ArrayList<>(components));
        }
        return msg;
    }

    public TcapMessageType getType() {
        return type;
    }

    public void setType(TcapMessageType type) {
        this.type = type;
    }

    public String getOtid() {
        return otid;
    }

    public void setOtid(String otid) {
        this.otid = otid;
    }

    public String getDtid() {
        return dtid;
    }

    public void setDtid(String dtid) {
        this.dtid = dtid;
    }

    public TcapDialoguePortion getDialogue() {
        return dialogue;
    }

    public void setDialogue(TcapDialoguePortion dialogue) {
        this.dialogue = dialogue;
    }

    public List<TcapComponent> getComponents() {
        return components;
    }

    public void setComponents(List<TcapComponent> components) {
        this.components = components != null ? components : new ArrayList<>();
    }

    public void addComponent(TcapComponent component) {
        if (component != null) {
            this.components.add(component);
        }
    }

    public String getAbortReason() {
        return abortReason;
    }

    public void setAbortReason(String abortReason) {
        this.abortReason = abortReason;
    }

    public String getAbortSource() {
        return abortSource;
    }

    public void setAbortSource(String abortSource) {
        this.abortSource = abortSource;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TcapMessage that)) return false;
        return type == that.type &&
                Objects.equals(otid, that.otid) &&
                Objects.equals(dtid, that.dtid) &&
                Objects.equals(dialogue, that.dialogue) &&
                Objects.equals(components, that.components) &&
                Objects.equals(abortReason, that.abortReason) &&
                Objects.equals(abortSource, that.abortSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, otid, dtid, dialogue, components, abortReason, abortSource);
    }

    @Override
    public String toString() {
        return "TcapMessage{" +
                "type=" + type +
                ", otid='" + otid + '\'' +
                ", dtid='" + dtid + '\'' +
                ", components=" + components.size() +
                (abortReason != null ? ", abortReason='" + abortReason + '\'' : "") +
                '}';
    }
}
