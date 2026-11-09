package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.MissionResultRequest;
import com.ondemandmonitoring.mission.dto.request.MissionResultReviewRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResultResponse;

public interface IMissionResultService {

    MissionResultResponse getByMissionId(String missionId);

    MissionResultResponse upsert(String missionId, MissionResultRequest request);

    PageResponse<MissionResultResponse> listPendingManagerApproval(int page, int size);

    MissionResultResponse approve(String resultId, MissionResultReviewRequest request);

    MissionResultResponse reject(String resultId, MissionResultReviewRequest request);

    void ensureCompletedResult(Mission mission);
}
