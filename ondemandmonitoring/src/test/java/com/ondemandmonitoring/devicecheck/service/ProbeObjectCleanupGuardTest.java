package com.ondemandmonitoring.devicecheck.service;

import static org.assertj.core.api.Assertions.*;
import com.ondemandmonitoring.devicecheck.service.impl.ProbeObjectCleanupGuard;
import com.ondemandmonitoring.media.cleanup.ObjectCleanupGuard;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProbeObjectCleanupGuardTest {
    @Test
    void acceptsOnlyTheCapturedOwnersUniqueProbe() {
        var guard = new ProbeObjectCleanupGuard();
        String key = "drone-media/diagnostics/media-probes/run-1/" + UUID.randomUUID() + ".jpg";
        assertThat(guard.type()).isEqualTo("MEDIA_PROBE");
        assertThat(guard.inspect("bucket", key, "run-1")).isEqualTo(ObjectCleanupGuard.Decision.DELETE);
        assertThatThrownBy(() -> guard.inspect("bucket", key, "run-2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.inspect("bucket", key, "../run-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.inspect("bucket", "drone-media/original.jpg", "run-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.inspect("bucket", key.replace(".jpg", "/other.jpg"), "run-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.inspect("bucket", key.replace(".jpg", ".mp4"), "run-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.inspect("", key, "run-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsCapturedCustomPrefixesWithoutAnyDeploymentConfiguration() {
        var guard = new ProbeObjectCleanupGuard();
        String execution = UUID.randomUUID() + ".jpg";
        assertThat(guard.inspect("old-bucket", "old-storage/custom-old-probes/run-1/" + execution, "run-1"))
                .isEqualTo(ObjectCleanupGuard.Decision.DELETE);
        assertThat(guard.inspect("new-bucket", "new-storage/custom-new-probes/run-1/" + execution, "run-1"))
                .isEqualTo(ObjectCleanupGuard.Decision.DELETE);
        assertThatThrownBy(() -> guard.inspect("bucket", "run-1/" + execution, "run-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
