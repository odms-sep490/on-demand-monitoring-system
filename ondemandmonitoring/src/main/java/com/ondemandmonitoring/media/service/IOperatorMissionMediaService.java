package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.media.dto.response.OperatorMissionMediaResponse;
import com.ondemandmonitoring.media.dto.response.MediaViewingResponse;

public interface IOperatorMissionMediaService {

    PageResponse<OperatorMissionMediaResponse> listAvailable(String missionId, int page, int size);

    PageResponse<OperatorMissionMediaResponse> listWorkspace(String missionId, int page, int size);

    MediaViewingResponse createOriginalView(String missionId, String mediaId);

    OperatorMissionMediaResponse getAvailable(String missionId, String mediaId);
}
