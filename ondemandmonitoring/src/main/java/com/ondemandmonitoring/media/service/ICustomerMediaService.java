package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.dto.response.CustomerMediaResponse;
import java.util.List;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.media.dto.response.CustomerMissionMediaStatusResponse;

public interface ICustomerMediaService {
    CustomerMissionMediaStatusResponse getMissionMediaStatus(String missionId);
    PageResponse<CustomerMediaResponse> listAvailablePage(
            String missionId, int page, int size);

    CustomerMediaResponse getAvailableInMission(String missionId, String mediaId);

    List<CustomerMediaResponse> listAvailable(String missionId);

    List<CustomerMediaResponse> listAllAvailable();

    CustomerMediaResponse getAvailable(String mediaId);


}
