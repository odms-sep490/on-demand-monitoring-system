package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import com.ondemandmonitoring.delivery.repository.ResultRevisionRequestItemRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionReworkChecklistContext;
import com.ondemandmonitoring.mission.repository.MissionReworkChecklistContextRepository;
import com.ondemandmonitoring.mission.service.IMissionReworkContextSnapshotService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissionReworkContextSnapshotService implements IMissionReworkContextSnapshotService {
    private final ResultRevisionRequestItemRepository requestItems;
    private final MissionReworkChecklistContextRepository contexts;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(Mission mission, List<ResultRevisionCaseItem> caseItems) {
        var snapshots = caseItems.stream().map(item -> {
            var feedback = requestItems.findByCaseItemIdOrderByCreatedAtAsc(item.getId());
            var latestRoundItem = feedback.isEmpty() ? null : feedback.getLast().getRoundItem();
            MissionReworkChecklistContext snapshot = new MissionReworkChecklistContext();
            snapshot.setMission(mission);
            snapshot.setCaseItem(item);
            snapshot.setOrderChecklistItemIdSnapshot(item.getOrderChecklistItem().getId());
            snapshot.setChecklistTitleSnapshot(item.getOrderChecklistItem().getContent());
            snapshot.setManagerInstructionsSnapshot(item.getManagerInstructions());
            snapshot.setSourceFeedbackItemIds(feedback.stream().map(row -> row.getId()).toList());
            snapshot.setGuidanceSnapshot(latestRoundItem == null ? null : latestRoundItem.getGuidanceSnapshot());
            return snapshot;
        }).toList();
        contexts.saveAllAndFlush(snapshots);
    }
}
