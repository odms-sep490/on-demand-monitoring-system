package com.ondemandmonitoring.mission.workspace;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction-local domain invalidations. No entity or authentication context escapes the write. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MissionWorkspaceEvents {
    public static final Set<String> ALL = Set.of("checklist", "result", "permissions", "media", "corrections");
    private final ApplicationEventPublisher publisher;

    public record Changed(String missionId, Set<String> components, Set<String> previousViewers) {}
    public record Message(UUID eventId, String missionId, List<String> components, Instant occurredAt) {}

    public void changed(String missionId, Set<String> components) { changed(missionId, components, Set.of()); }

    public void changed(String missionId, Set<String> components, Set<String> previousViewers) {
        if (missionId == null || missionId.isBlank()) return;
        if (!ALL.containsAll(components)) throw new IllegalArgumentException("Unknown workspace component");
        // Fail closed outside a write transaction; never send an uncommitted change.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return;
        var batch = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(Batch.class::isInstance).map(Batch.class::cast).findFirst().orElseGet(() -> {
                    var created = new Batch();
                    TransactionSynchronizationManager.registerSynchronization(created);
                    return created;
                });
        batch.components.computeIfAbsent(missionId, k -> new TreeSet<>()).addAll(components);
        batch.viewers.computeIfAbsent(missionId, k -> new HashSet<>()).addAll(previousViewers);
    }

    private class Batch implements TransactionSynchronization {
        final Map<String, Set<String>> components = new LinkedHashMap<>();
        final Map<String, Set<String>> viewers = new HashMap<>();

        @Override public void afterCommit() {
            components.forEach((missionId, changed) -> {
                try {
                    publisher.publishEvent(new Changed(missionId, Set.copyOf(changed), Set.copyOf(viewers.get(missionId))));
                } catch (RuntimeException failure) {
                    // Delivery is a hint. A broker outage must not turn a committed command into an error.
                    log.warn("Mission workspace notification delivery failed for {}", missionId, failure);
                }
            });
        }
    }
}
