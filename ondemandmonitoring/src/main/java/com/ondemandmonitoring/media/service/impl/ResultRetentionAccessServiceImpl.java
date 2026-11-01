package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

@Service
public class ResultRetentionAccessServiceImpl implements IResultRetentionAccessService {
    private final Clock clock;

    public ResultRetentionAccessServiceImpl() {
        this(Clock.systemUTC());
    }

    public ResultRetentionAccessServiceImpl(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void requireAccessible(MediaAsset asset) {
        Instant expiresAt = asset.getMission().getOrder().getResultExpiresAt();
        if (expiresAt != null && !Instant.now(clock).isBefore(expiresAt)) {
            String message = asset.getStorageStatus() == MediaStorageStatus.DELETED
                    ? "Kết quả đã hết thời hạn lưu trữ và đã được xóa khỏi hệ thống lưu trữ."
                    : "Kết quả đã hết thời hạn lưu trữ.";
            throw new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED, message);
        }
        if (asset.getStorageStatus() == MediaStorageStatus.DELETED) {
            throw new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED,
                    "Kết quả đã được xóa khỏi hệ thống lưu trữ.");
        }
        if (asset.getStorageStatus() != MediaStorageStatus.AVAILABLE) {
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND, "Media storage cleanup is in progress.");
        }
    }
}
