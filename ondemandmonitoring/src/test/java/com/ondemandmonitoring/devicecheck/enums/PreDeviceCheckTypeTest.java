package com.ondemandmonitoring.devicecheck.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreDeviceCheckTypeTest {

    @Test
    void mediaStorageProbeIsFlightCritical() {
        assertThat(PreDeviceCheckType.MEDIA_STORAGE.displayName()).isEqualTo("Lưu trữ hình ảnh và video");
        assertThat(PreDeviceCheckType.MEDIA_STORAGE.level()).isEqualTo(PreDeviceCheckLevel.CRITICAL);
    }
}
