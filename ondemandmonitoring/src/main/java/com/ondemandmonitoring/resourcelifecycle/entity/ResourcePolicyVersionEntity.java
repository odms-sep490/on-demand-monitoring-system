package com.ondemandmonitoring.resourcelifecycle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "resource_policy_versions")
@IdClass(ResourcePolicyVersionEntity.PolicyVersionKey.class)
@Getter
@Setter
@NoArgsConstructor
public class ResourcePolicyVersionEntity {

    @Id
    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Id
    @Column(name = "revision", nullable = false, updatable = false)
    private long revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private String snapshotJson;

    @Column(name = "actor_id", nullable = false, length = 255)
    private String actorId;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class PolicyVersionKey implements Serializable {
        private UUID policyId;
        private long revision;

        public PolicyVersionKey(UUID policyId, long revision) {
            this.policyId = policyId;
            this.revision = revision;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PolicyVersionKey that)) {
                return false;
            }
            return revision == that.revision && Objects.equals(policyId, that.policyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(policyId, revision);
        }
    }
}
