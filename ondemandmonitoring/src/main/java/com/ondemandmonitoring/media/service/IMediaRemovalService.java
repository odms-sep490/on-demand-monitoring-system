package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.dto.request.RemoveMediaRequest;
import com.ondemandmonitoring.media.dto.response.MediaRemovalResponse;

public interface IMediaRemovalService {
    MediaRemovalResponse remove(String missionId, String mediaId, RemoveMediaRequest request);
}
