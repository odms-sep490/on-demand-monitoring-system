package com.ondemandmonitoring.devicecheck.service;

import com.ondemandmonitoring.devicecheck.dto.request.MediaProbeRequest;
import com.ondemandmonitoring.devicecheck.dto.response.MediaProbeResponse;

public interface IMediaProbeService {

    MediaProbeResponse verify(String runId, MediaProbeRequest request);
    MediaProbeResponse verifyAutomatic(String runId);
}
