package net.pilgrim.vxml.ast;

/**
 * VoiceXML {@code <disconnect/>} element.
 */
public class VxmlDisconnect implements VxmlExecutable {

    @Override
    public String toString() {
        return "VxmlDisconnect{}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VxmlDisconnect;
    }

    @Override
    public int hashCode() {
        return VxmlDisconnect.class.hashCode();
    }
}
