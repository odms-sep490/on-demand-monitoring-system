package com.ondemandmonitoring.conversation;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Entity @Table(name="order_conversation_sessions") @Getter @Setter
public class OrderConversationSession extends BaseEntity {
    @Column(nullable=false) private String ownerId;
    @Column(nullable=false) private long revision;
    @Column(nullable=false) private Instant expiresAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb")
    private Map<String,Object> state;
}
