package com.ondemandmonitoring.imagequality;

import com.ondemandmonitoring.media.domain.MediaAsset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DeliveryImageQualityReader {
    private static final String PROFILE = "PANORAMA";
    private static final String PROFILE_VERSION = "1-provisional";
    private static final String ALGORITHM_VERSION = "quality-metrics-1.0";
    private final NamedParameterJdbcTemplate db;

    public record Signal(String checkId, String humanDecision, String technicalDecision, Long hash) {}

    public Map<String, Signal> read(String missionId, List<MediaAsset> assets) {
        if (assets.isEmpty() || !Boolean.TRUE.equals(db.queryForObject("""
                select to_regclass('image_quality_check') is not null
                   and to_regclass('image_quality_review') is not null
                """, Map.of(), Boolean.class))) return Map.of();

        Map<String, MediaAsset> assetsById = new HashMap<>();
        assets.forEach(asset -> assetsById.put(asset.getId(), asset));
        return db.query("""
                select m.id as media_id, a.id as auto_id, a.status as auto_status,
                       a.started_at as auto_started_at, a.decision as auto_decision,
                       a.profile_version, a.result->>'profile' as result_profile,
                       a.result->>'profileVersion' as result_version, a.result->>'decision' as result_decision,
                       a.result->>'algorithmVersion' as auto_algorithm,
                       a.result->'metrics'->>'checksumSha256' as auto_checksum,
                       a.result->'metrics'->>'perceptualHash' as auto_hash,
                       h.check_id as human_id, h.decision as human_decision, h.started_at as human_started_at,
                       h.result->>'algorithmVersion' as human_algorithm,
                       h.result->'metrics'->>'checksumSha256' as human_checksum,
                       h.result->'metrics'->>'perceptualHash' as human_hash
                  from drone_media m
                  left join lateral (
                      select q.* from image_quality_check q
                       where q.media_id = m.id and q.mission_id = :missionId
                         and q.profile = :profile and not q.customer_request
                       order by q.created_at desc, q.id desc limit 1
                  ) a on true
                  left join lateral (
                      select q.id as check_id, r.decision, q.started_at, q.result
                        from image_quality_check q join image_quality_review r on r.check_id = q.id
                       where q.media_id = m.id and q.mission_id = :missionId and q.status = 'COMPLETED'
                         and not q.customer_request
                         and q.started_at >= m.validated_at
                         and (nullif(btrim(m.checksum_sha256), '') is null
                              or lower(q.result->'metrics'->>'checksumSha256') = lower(m.checksum_sha256))
                       order by r.reviewed_at desc, r.id desc limit 1
                  ) h on true
                 where m.mission_id = :missionId and m.id in (:mediaIds)
                   and (a.id is not null or h.check_id is not null)
                """, Map.of("missionId", missionId, "profile", PROFILE, "mediaIds", assetsById.keySet()), rows -> {
            Map<String, Signal> signals = new HashMap<>();
            while (rows.next()) {
                String id = rows.getString("media_id");
                signals.put(id, signal(rows, assetsById.get(id)));
            }
            return signals;
        });
    }

    private Signal signal(ResultSet row, MediaAsset asset) throws SQLException {
        String autoDecision = row.getString("auto_decision");
        boolean currentAuto = "COMPLETED".equals(row.getString("auto_status"))
                && PROFILE_VERSION.equals(row.getString("profile_version"))
                && PROFILE.equals(row.getString("result_profile"))
                && PROFILE_VERSION.equals(row.getString("result_version"))
                && ALGORITHM_VERSION.equals(row.getString("auto_algorithm"))
                && autoDecision != null && autoDecision.equals(row.getString("result_decision"))
                && matchesMedia(row, asset, "auto");
        Long autoHash = currentAuto ? ImagePerceptualHash.parse(row.getString("auto_hash")) : null;

        // Staff reviews apply to the image itself, including reviews made under another profile.
        String humanId = row.getString("human_id");
        if (humanId != null && matchesMedia(row, asset, "human")) {
            Long humanHash = ALGORITHM_VERSION.equals(row.getString("human_algorithm"))
                    ? ImagePerceptualHash.parse(row.getString("human_hash")) : null;
            return new Signal(humanId, row.getString("human_decision"), null, autoHash != null ? autoHash : humanHash);
        }
        return new Signal(row.getString("auto_id"), null, currentAuto ? autoDecision : null, autoHash);
    }

    private boolean matchesMedia(ResultSet row, MediaAsset asset, String prefix) throws SQLException {
        var startedAt = row.getTimestamp(prefix + "_started_at");
        Instant validatedAt = asset.getValidatedAt();
        if (startedAt == null || validatedAt == null || startedAt.toInstant().isBefore(validatedAt)) return false;
        String checksum = asset.getChecksumSha256();
        return checksum == null || checksum.isBlank() || checksum.equalsIgnoreCase(row.getString(prefix + "_checksum"));
    }
}
