package com.ondemandmonitoring.media.cleanup;

import com.ondemandmonitoring.media.service.IMediaObjectStorage;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ObjectCleanupWorker {
    private final ObjectCleanupOutbox outbox;
    private final IMediaObjectStorage storage;
    private final ObjectCleanupProperties properties;
    private final Map<String, ObjectCleanupGuard> guards;

    public ObjectCleanupWorker(
            ObjectCleanupOutbox outbox,
            IMediaObjectStorage storage,
            ObjectCleanupProperties properties,
            List<ObjectCleanupGuard> guards) {
        this.outbox = outbox;
        this.storage = storage;
        this.properties = properties;
        this.guards =
                guards.stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ObjectCleanupGuard::type, Function.identity()));
    }

    public int poll() {
        if (!properties.isEnabled()) return 0;
        int count = 0;
        for (int i = 0; i < properties.getBatchSize(); i++) {
            var next = outbox.claimNext();
            if (next.isEmpty()) break;
            process(next.get());
            count++;
        }
        outbox.purgeCompleted();
        return count;
    }

    private void process(ObjectCleanupOutbox.Claim task) {
        try {
            var guard = guards.get(task.guardType());
            if (guard == null) throw new IllegalStateException("Unknown object cleanup guard");
            var decision =
                    java.util.Objects.requireNonNull(
                            outbox.inspect(task, guard), "Missing cleanup decision");
            if (decision == ObjectCleanupGuard.Decision.DEFER) {
                outbox.defer(task);
                return;
            }
            if (decision == ObjectCleanupGuard.Decision.REFERENCED) {
                outbox.cancel(task);
                return;
            }
            // The throwing storage API is essential: deleteQuietly would acknowledge failed
            // deletion.
            try {
                storage.delete(task.bucket(), task.key());
            } catch (S3Exception missing) {
                if (missing.awsErrorDetails() == null
                        || !"NoSuchKey".equals(missing.awsErrorDetails().errorCode()))
                    throw missing;
            }
            outbox.complete(task);
        } catch (RuntimeException failure) {
            outbox.fail(task, failure);
            log.warn(
                    "Object cleanup attempt failed; taskId={}, attempt={}, terminal={}, failure={}",
                    task.id(),
                    task.attempts(),
                    task.attempts() >= properties.getMaxAttempts(),
                    failure.getClass().getSimpleName());
        }
    }
}
