package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.domain.ManualUploadTask;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.ManualUploadTaskStatus;
import com.ondemandmonitoring.media.repository.ManualUploadTaskRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

/**
 * Single owner of Staff manual-upload task creation.
 * <p>
 * Both escalation paths (a reported transfer failure and a failed object validation)
 * must produce an {@code OPEN} task, because the Staff list query filters on
 * {@code status = OPEN}. Media-level idempotency comes from the
 * {@code uk_manual_upload_media} unique constraint plus this find-or-create, so a
 * duplicate or concurrent escalation always reuses the same row.
 * <p>
 * A task that has already been {@code RESOLVED} is never reopened: a late duplicate
 * callback must not undo the resolution recorded by the successful validation pipeline.
 * Callers must already hold the Mission → MediaAsset lock.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ManualUploadTaskServiceImpl {

    ManualUploadTaskRepository tasks;

    /** Finds or creates the single OPEN task for a media asset and stamps the latest reason. */
    public ManualUploadTask requireOpen(MediaAsset captured, String reason) {
        ManualUploadTask task = tasks.findByMediaId(captured.getId()).orElseGet(() -> newTask(captured, reason));
        if (task.getStatus() == ManualUploadTaskStatus.RESOLVED) {
            return task;
        }
        task.setReason(reason);
        task.setStatus(ManualUploadTaskStatus.OPEN);
        return tasks.save(task);
    }

    private ManualUploadTask newTask(MediaAsset captured, String reason) {
        // Constructed through the no-args constructor, so the status is set explicitly below.
        ManualUploadTask created = new ManualUploadTask();
        created.setMedia(captured);
        created.setAssignedOperatorId(captured.getOperatorId());
        created.setReason(reason);
        created.setStatus(ManualUploadTaskStatus.OPEN);
        return created;
    }
}