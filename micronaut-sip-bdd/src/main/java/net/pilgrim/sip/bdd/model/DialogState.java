package net.pilgrim.sip.bdd.model;

/**
 * High-level state of a SIP dialog as defined in RFC 3261 Section 12.
 */
public enum DialogState {
    NONE,
    EARLY,
    CONFIRMED,
    TERMINATED
}
