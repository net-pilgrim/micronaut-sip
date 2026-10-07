package net.pilgrim.vxml.ast;

/**
 * VoiceXML {@code <reprompt/>} element.
 */
public class VxmlReprompt implements VxmlExecutable {

    @Override
    public String toString() {
        return "VxmlReprompt{}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VxmlReprompt;
    }

    @Override
    public int hashCode() {
        return VxmlReprompt.class.hashCode();
    }
}
