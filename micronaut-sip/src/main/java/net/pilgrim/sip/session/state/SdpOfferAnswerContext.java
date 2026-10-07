package net.pilgrim.sip.session.state;

import io.micronaut.core.annotation.Nullable;

/**
 * Encapsulates the orthogonal state machine and data context for RFC 3264 Offer/Answer negotiation.
 */
public class SdpOfferAnswerContext {

    private volatile SdpOfferAnswerState state = SdpOfferAnswerState.IDLE;
    private volatile String offer;
    private volatile String answer;
    private volatile String localOffer;

    public SdpOfferAnswerState getState() {
        return state;
    }

    public void setState(SdpOfferAnswerState state) {
        this.state = state;
    }

    public String getOffer() {
        return offer != null ? offer : localOffer;
    }

    public void setOffer(String offer) {
        this.offer = offer;
        if (offer != null && !offer.isBlank()) {
            this.state = SdpOfferAnswerState.OFFER_RECEIVED;
        }
    }

    public String getLocalOffer() {
        return localOffer;
    }

    public void setLocalOffer(String localOffer) {
        this.localOffer = localOffer;
        if (localOffer != null && !localOffer.isBlank()) {
            this.state = SdpOfferAnswerState.AWAITING_ACK_ANSWER;
        }
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
        if (answer != null && !answer.isBlank()) {
            this.state = SdpOfferAnswerState.STABLE;
        }
    }

    public boolean isAwaitingAckAnswer() {
        return state == SdpOfferAnswerState.AWAITING_ACK_ANSWER;
    }

    public boolean isStable() {
        return state == SdpOfferAnswerState.STABLE || state == SdpOfferAnswerState.ON_HOLD;
    }

    public boolean isHold() {
        return state == SdpOfferAnswerState.ON_HOLD;
    }

    public void setHold(boolean hold) {
        if (hold) {
            this.state = SdpOfferAnswerState.ON_HOLD;
        } else if (state == SdpOfferAnswerState.ON_HOLD) {
            this.state = SdpOfferAnswerState.STABLE;
        }
    }

    public void reset() {
        this.state = SdpOfferAnswerState.IDLE;
        this.offer = null;
        this.answer = null;
        this.localOffer = null;
    }
}
