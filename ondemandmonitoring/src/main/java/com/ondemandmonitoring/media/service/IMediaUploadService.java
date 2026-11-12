package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.dto.request.CompleteMultipartRequest;
import com.ondemandmonitoring.media.dto.request.PrepareMediaUploadRequest;
import com.ondemandmonitoring.media.dto.request.ReportUploadFailureRequest;
import com.ondemandmonitoring.media.dto.response.MediaUploadResponse;
import com.ondemandmonitoring.media.dto.request.ManualMediaFileRequest;
import com.ondemandmonitoring.media.dto.response.ManualMediaUploadResponse;
import java.util.List;

public interface IMediaUploadService {

    List<ManualMediaUploadResponse> manualTasks(String missionId);

    MediaUploadResponse prepareManualFile(String mediaId,
            ManualMediaFileRequest request);

    MediaUploadResponse prepare(String missionId, PrepareMediaUploadRequest request);

    MediaUploadResponse prepareStaffFile(String missionId, PrepareMediaUploadRequest request);

    MediaUploadResponse retry(String mediaId, boolean manual);

    MediaUploadResponse reportFailure(String mediaId, String attemptId, ReportUploadFailureRequest request);

    MediaUploadResponse status(String mediaId);

    MediaUploadResponse presignPart(String mediaId, String attemptId, int partNumber);

    void completeMultipart(String mediaId, String attemptId, CompleteMultipartRequest request);

    void markUploaded(String mediaId, String attemptId);
}
