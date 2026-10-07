package net.pilgrim.sip.session.state;

import reactor.core.publisher.Sinks;

/**
 * Encapsulates the orthogonal state machine and data context for RFC 3262 reliable provisional responses (PRACK).
 */
public class ReliableProvisionalContext {

    private volatile ReliableProvisionalState state = ReliableProvisionalState.NONE;
    private volatile Long rseq;
    private volatile Long cseqNumber;
    private volatile String cseqMethod;
    private volatile Sinks.One<Void> prackSink;

    public ReliableProvisionalState getState() {
        return state;
    }

    public void setState(ReliableProvisionalState state) {
        this.state = state;
    }

    public Long getRSeq() {
        return rseq;
    }

    public void setRSeq(Long rseq) {
        this.rseq = rseq;
    }

    public Long getCSeqNumber() {
        return cseqNumber;
    }

    public void setCSeqNumber(Long cseqNumber) {
        this.cseqNumber = cseqNumber;
    }

    public String getCSeqMethod() {
        return cseqMethod;
    }

    public void setCSeqMethod(String cseqMethod) {
        this.cseqMethod = cseqMethod;
    }

    public Sinks.One<Void> getPrackSink() {
        return prackSink;
    }

    public void setPrackSink(Sinks.One<Void> prackSink) {
        this.prackSink = prackSink;
    }

    public void initiate(long rseq, long cseqNumber, String cseqMethod, Sinks.One<Void> sink) {
        this.rseq = rseq;
        this.cseqNumber = cseqNumber;
        this.cseqMethod = cseqMethod;
        this.prackSink = sink;
        this.state = ReliableProvisionalState.AWAITING_PRACK;
    }

    /**
     * Validates an incoming RAck header against pending 100rel context.
     * RAck format: "<rseq> <cseq-number> <cseq-method>"
     */
    public boolean validateRack(String rack) {
        if (state != ReliableProvisionalState.AWAITING_PRACK || rack == null || rack.isBlank()) {
            return false;
        }
        String[] parts = rack.trim().split("\\s+");
        if (parts.length != 3) {
            return false;
        }
        try {
            long reqRSeq = Long.parseLong(parts[0]);
            if (rseq != null && rseq != reqRSeq) {
                return false;
            }
            long reqCSeq = Long.parseLong(parts[1]);
            if (cseqNumber != null && cseqNumber != reqCSeq) {
                return false;
            }
            String reqMethod = parts[2];
            if (cseqMethod != null && !cseqMethod.equalsIgnoreCase(reqMethod)) {
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void completePrack() {
        if (prackSink != null) {
            prackSink.tryEmitEmpty();
        }
        this.state = ReliableProvisionalState.PRACK_RESOLVED;
    }

    public void cancel() {
        if (prackSink != null) {
            prackSink.tryEmitEmpty();
            prackSink = null;
        }
        this.state = ReliableProvisionalState.NONE;
    }

    public void reset() {
        cancel();
        this.rseq = null;
        this.cseqNumber = null;
        this.cseqMethod = null;
    }
}
