package com.ondemandmonitoring.description;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name="description_sessions") @Getter @Setter
public class DescriptionSession extends BaseEntity {
    @Column(nullable=false) private String ownerId;
    @Column(nullable=false) private String serviceId;
    @Column(nullable=false) private String status;
    @Column(nullable=false) private long revision;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb", nullable=false)
    private java.util.Map<String,Object> state;
    private Instant confirmedAt;
    private String orderId;
}
