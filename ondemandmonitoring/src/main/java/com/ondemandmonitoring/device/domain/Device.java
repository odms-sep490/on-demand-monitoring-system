package com.ondemandmonitoring.device.domain;

import java.time.LocalDateTime;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Device extends BaseEntity {

    @Column(name = "serial_number", nullable = false, unique = true, length = 100)
    private String serialNumber;

    @Column(name = "name", nullable = false, length = 100)
    private String name;
    @Column(name = "device_code", length = 50)
    private String deviceCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private DeviceModel deviceModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private DeviceStatus status;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** S3 object identity is persisted; API responses expose a short-lived signed URL. */
    @Column(name = "image_s3_key", length = 500)
    private String imageS3Key;

    @Column(name = "image_s3_bucket", length = 255)
    private String imageS3Bucket;

    /** Seed/catalog fallback image; uploaded S3 images take precedence when present. */
    @Column(name = "image_url", length = 1000)
    private String imageUrl;
}
