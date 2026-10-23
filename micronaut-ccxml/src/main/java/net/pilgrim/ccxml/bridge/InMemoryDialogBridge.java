package net.pilgrim.ccxml.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory dialog bridge for testing and lightweight CCXML execution.
 */
public class InMemoryDialogBridge implements DialogBridge {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryDialogBridge.class);

    private final AtomicInteger counter = new AtomicInteger(2000);
    private final Map<String, DialogRecord> dialogs = new ConcurrentHashMap<>();
    private final List<String> dialogLog = Collections.synchronizedList(new ArrayList<>());

    public record DialogRecord(
            String dialogId,
            String src,
            String type,
            String connectionId,
            String conferenceId,
            Map<String, Object> params,
            String state
    ) {}

    @Override
    public Mono<Void> prepareDialog(String dialogId, String src, String type) {
        dialogLog.add("PREPARE:" + dialogId + ":" + src);
        dialogs.put(dialogId, new DialogRecord(dialogId, src, type, null, null, Collections.emptyMap(), "PREPARED"));
        LOG.debug("Prepared dialog: {} from {}", dialogId, src);
        return Mono.empty();
    }

    @Override
    public Mono<String> startDialog(String src, String type, String connectionId, String conferenceId, Map<String, Object> params) {
        String id = "dlg_" + counter.incrementAndGet();
        dialogLog.add("START:" + id + ":" + src + ":" + connectionId);
        dialogs.put(id, new DialogRecord(id, src, type, connectionId, conferenceId, params != null ? params : Collections.emptyMap(), "STARTED"));
        LOG.debug("Started dialog: {} (src={}, conn={}, conf={})", id, src, connectionId, conferenceId);
        return Mono.just(id);
    }

    @Override
    public Mono<Void> terminateDialog(String dialogId, boolean immediate) {
        dialogLog.add("TERMINATE:" + dialogId + ":" + immediate);
        if (dialogId != null) {
            dialogs.remove(dialogId);
        }
        LOG.debug("Terminated dialog: {} (immediate={})", dialogId, immediate);
        return Mono.empty();
    }

    public List<String> getDialogLog() {
        return Collections.unmodifiableList(new ArrayList<>(dialogLog));
    }

    public Map<String, DialogRecord> getDialogs() {
        return Collections.unmodifiableMap(dialogs);
    }
}
