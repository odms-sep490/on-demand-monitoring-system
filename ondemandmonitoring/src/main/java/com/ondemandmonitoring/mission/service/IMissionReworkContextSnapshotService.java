package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import com.ondemandmonitoring.mission.domain.Mission;
import java.util.List;

public interface IMissionReworkContextSnapshotService {
    void capture(Mission mission, List<ResultRevisionCaseItem> caseItems);
}
