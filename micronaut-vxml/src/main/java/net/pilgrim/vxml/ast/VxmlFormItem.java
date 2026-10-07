package net.pilgrim.vxml.ast;

/**
 * Interface implemented by items inside a VoiceXML {@code <form>}
 * (e.g. block, field, var).
 */
public interface VxmlFormItem extends VxmlNode {

    String getName();

    String getCond();
}
