package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records usage for missions that finished before automatic recording existed. Safe to run on every
 * start because {@link MissionUsageRecorder} skips resources already recorded for a mission.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionUsageBackfill {
    private static final List<MissionStatus> FINISHED = List.of(
            MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW,
            MissionStatus.COMPLETED, MissionStatus.FAILED);

    private final MissionRepository missions;
    private final MissionUsageRecorder recorder;
    private final TransactionTemplate transactions;

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        List<String> ids = missions.findByStatusIn(FINISHED).stream()
                // IN_PROGRESS missions count only once the inspector accepted them (completedAt set).
                .filter(m -> m.getStatus() != MissionStatus.IN_PROGRESS || m.getCompletedAt() != null)
                .filter(m -> m.getActualStartAt() != null && m.getActualEndAt() != null)
                .map(Mission::getId)
                .toList();
        int failures = 0;
        for (String id : ids) {
            try {
                transactions.executeWithoutResult(status -> missions.findById(id).ifPresent(recorder::recordFinishedMission));
            } catch (RuntimeException e) {
                failures++;
                log.warn("Usage backfill failed for mission {}: {}", id, e.getMessage());
            }
        }
        log.info("Mission usage backfill checked {} finished missions ({} failures)", ids.size(), failures);
    }
}
