package net.pilgrim.sip.transport;

import net.pilgrim.sip.model.SipHeaders;
import net.pilgrim.sip.model.SipResponse;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Routes incoming SIP responses to registered reactive client transaction listeners.
 * Matches by Via branch or Call-ID + CSeq as defined in RFC 3261 Section 17.1.3.
 */
@Singleton
public class SipResponseRouter {

    private static final Logger LOG = LoggerFactory.getLogger(SipResponseRouter.class);

    private final Map<String, Consumer<SipResponse>> listeners = new ConcurrentHashMap<>();

    public void registerListener(String transactionKey, Consumer<SipResponse> listener) {
        listeners.put(transactionKey, listener);
    }

    public void unregisterListener(String transactionKey) {
        listeners.remove(transactionKey);
    }

    public boolean handleResponse(SipResponse response) {
        String branch = extractTransactionKey(response);
        String cseqMethod = extractCSeqMethod(response);

        // 1. Match by branch:method (RFC 3261 §17.1.3)
        if (branch != null && cseqMethod != null) {
            String typedKey = branch + ":" + cseqMethod;
            Consumer<SipResponse> listener = listeners.get(typedKey);
            if (listener != null) {
                listener.accept(response);
                if (response.isFinal()) {
                    listeners.remove(typedKey);
                }
                return true;
            }
        }

        // 2. Match by branch alone
        if (branch != null) {
            Consumer<SipResponse> listener = listeners.get(branch);
            if (listener != null) {
                listener.accept(response);
                if (response.isFinal()) {
                    // Final response completes client transaction
                    listeners.remove(branch);
                }
                return true;
            }
        }

        // 3. Fallback to Call-ID lookup
        String callId = response.getCallId();
        if (callId != null) {
            Consumer<SipResponse> listener = listeners.get(callId);
            if (listener != null) {
                listener.accept(response);
                if (response.isFinal()) {
                    listeners.remove(callId);
                }
                return true;
            }
        }

        LOG.warn("No active transaction found for incoming SIP response: {} {} (Call-ID: {})",
                response.getStatusCode(), response.getReasonPhrase(), response.getCallId());
        return false;
    }

    public static String extractTransactionKey(SipResponse response) {
        String via = response.getVia();
        if (via != null) {
            int branchIdx = via.indexOf("branch=");
            if (branchIdx != -1) {
                int end = via.indexOf(';', branchIdx);
                String branch = (end != -1) ? via.substring(branchIdx + 7, end) : via.substring(branchIdx + 7);
                return branch.trim();
            }
        }
        return null;
    }

    public static String extractCSeqMethod(SipResponse response) {
        String cseq = response.getCSeq();
        if (cseq != null) {
            int space = cseq.trim().indexOf(' ');
            if (space != -1) {
                return cseq.trim().substring(space + 1).trim();
            }
        }
        return null;
    }

    public int getActiveListenerCount() {
        return listeners.size();
    }

    public boolean hasListener(String key) {
        return listeners.containsKey(key);
    }
}
