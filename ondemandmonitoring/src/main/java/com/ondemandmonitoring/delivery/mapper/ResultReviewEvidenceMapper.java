package com.ondemandmonitoring.delivery.mapper;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence;
import com.ondemandmonitoring.delivery.dto.response.ResultReviewResponse;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps published evidence after the calling service has authorized access to its order or ticket. */
@Component
@RequiredArgsConstructor
public class ResultReviewEvidenceMapper {
    private final MediaDerivativeService derivatives;
    private final IResultRetentionAccessService retentionAccess;

    public ResultReviewResponse.Evidence toResponse(ResultReviewRoundEvidence row) {
        var asset = row.getMediaAsset();
        try {
            retentionAccess.requireAccessible(asset);
        } catch (ApiException ex) {
            if (ex.getErrorCode() != ErrorCode.RESULT_RETENTION_EXPIRED && ex.getErrorCode() != ErrorCode.MEDIA_NOT_FOUND) throw ex;
            return new ResultReviewResponse.Evidence(row.getId(), asset.getId(), row.getMediaTypeSnapshot(), row.getFileNameSnapshot(),
                    asset.getFileSize(), row.getCapturedAtSnapshot(), null, null, false,
                    ex.getErrorCode() == ErrorCode.RESULT_RETENTION_EXPIRED ? "RETENTION_EXPIRED" : "MEDIA_UNAVAILABLE",
                    null, null, asset.getDerivativeStatus());
        }
        return new ResultReviewResponse.Evidence(row.getId(), asset.getId(), row.getMediaTypeSnapshot(), row.getFileNameSnapshot(),
                asset.getFileSize(), row.getCapturedAtSnapshot(), null, null, false, null,
                derivatives.thumbnailUrl(asset), derivatives.previewUrl(asset), asset.getDerivativeStatus());
    }
}
