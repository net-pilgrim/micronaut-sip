package net.pilgrim.ccxml.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory media and conference bridge for testing and lightweight CCXML execution.
 */
public class InMemoryMediaBridge implements MediaBridge {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMediaBridge.class);

    private final AtomicInteger counter = new AtomicInteger(3000);
    private final Map<String, ConferenceRecord> conferences = new ConcurrentHashMap<>();
    private final Map<String, JoinRecord> joins = new ConcurrentHashMap<>();
    private final List<String> mediaLog = Collections.synchronizedList(new ArrayList<>());

    public record ConferenceRecord(
            String conferenceId,
            String confName,
            Map<String, Object> attributes
    ) {}

    public record JoinRecord(
            String id1,
            String id2,
            DuplexMode duplex
    ) {}

    @Override
    public Mono<String> createConference(String confName, Map<String, Object> attributes) {
        String confId = "conf_" + counter.incrementAndGet();
        conferences.put(confId, new ConferenceRecord(confId, confName, attributes != null ? attributes : Collections.emptyMap()));
        mediaLog.add("CREATE_CONF:" + confId + ":" + confName);
        LOG.debug("Created conference: {} ({})", confId, confName);
        return Mono.just(confId);
    }

    @Override
    public Mono<Void> destroyConference(String conferenceId) {
        mediaLog.add("DESTROY_CONF:" + conferenceId);
        if (conferenceId != null) {
            conferences.remove(conferenceId);
            joins.keySet().removeIf(k -> k.contains(conferenceId));
        }
        LOG.debug("Destroyed conference: {}", conferenceId);
        return Mono.empty();
    }

    @Override
    public Mono<Void> join(String id1, String id2, DuplexMode duplex) {
        String key = makeJoinKey(id1, id2);
        DuplexMode mode = duplex != null ? duplex : DuplexMode.FULL;
        joins.put(key, new JoinRecord(id1, id2, mode));
        mediaLog.add("JOIN:" + id1 + "<->" + id2 + ":" + mode);
        LOG.debug("Joined {} and {} (duplex={})", id1, id2, mode);
        return Mono.empty();
    }

    @Override
    public Mono<Void> unjoin(String id1, String id2) {
        String key = makeJoinKey(id1, id2);
        joins.remove(key);
        mediaLog.add("UNJOIN:" + id1 + "<->" + id2);
        LOG.debug("Unjoined {} and {}", id1, id2);
        return Mono.empty();
    }

    private String makeJoinKey(String id1, String id2) {
        return id1 + ":" + id2;
    }

    public boolean isJoined(String id1, String id2) {
        return joins.containsKey(makeJoinKey(id1, id2)) || joins.containsKey(makeJoinKey(id2, id1));
    }

    public DuplexMode getDuplex(String id1, String id2) {
        JoinRecord r = joins.get(makeJoinKey(id1, id2));
        if (r == null) {
            r = joins.get(makeJoinKey(id2, id1));
        }
        return r != null ? r.duplex() : null;
    }

    public List<String> getMediaLog() {
        return Collections.unmodifiableList(new ArrayList<>(mediaLog));
    }

    public Map<String, ConferenceRecord> getConferences() {
        return Collections.unmodifiableMap(conferences);
    }

    public Map<String, JoinRecord> getJoins() {
        return Collections.unmodifiableMap(joins);
    }
}
