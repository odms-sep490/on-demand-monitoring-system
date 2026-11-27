package com.ondemandmonitoring.mission.workspace;

import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissionWorkspaceVersionService {
    private final NamedParameterJdbcTemplate db;
    private final AuthenticatedUserResolver users;

    // Every subquery shares one PostgreSQL statement snapshot. No entity hydration, presigning,
    // history DTO construction, or counters. Row hashes include version/updated_at AND actual
    // state, so deletions and native writes that do not increment @Version are visible too.
    static final Map<String, String> SOURCES = sources();

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated() and @missionAuthorizationService.canViewMission(#missionId)")
    public WorkspaceVersion get(String missionId) {
        var viewer = users.getCurrentUser(); // also rejects disabled accounts
        var args = new MapSqlParameterSource("missionId", missionId).addValue("viewerId", viewer.getId());
        // These migrations are optional. Detect BEFORE parsing the data query: even a CASE
        // that mentions a missing relation would abort the PostgreSQL read transaction.
        var quality = db.queryForMap("select to_regclass('image_quality_check') is not null as checks, "
                + "to_regclass('image_quality_review') is not null as reviews", args);
        var state = db.queryForMap(sql(Boolean.TRUE.equals(quality.get("checks")), Boolean.TRUE.equals(quality.get("reviews"))), args);
        return tokens(missionId, state);
    }

    static WorkspaceVersion tokens(String missionId, Map<String, Object> s) {
        String media = token(s, "media", "derivatives", "metadata", "attempts", "manual", "order_state");
        String corrections = token(s, "corrections", "correction_items", "correction_events", "correction_executions", "supplements", "supplement_executions", "supplement_results");
        String checklist = token(s, "assignments", "executions", "evidence", "items", "submissions", "media", "derivatives", "metadata", "quality_checks", "quality_reviews");
        String result = token(s, "result_mission", "results", "result_media", "result_assets", "result_derivatives", "result_metadata", "rounds", "round_items", "round_evidence");
        String permissions = hash(token(s, "mission", "staff", "devices", "handovers", "weather", "postflight", "postflight_items", "preflight", "preflight_items", "viewer", "crew", "order_state", "submissions", "assignments", "executions", "evidence", "media", "results"), corrections);
        return new WorkspaceVersion(missionId, checklist, result, permissions, media, corrections);
    }

    private static String token(Map<String, Object> state, String... keys) {
        return hash(Arrays.stream(keys).map(k -> k + "=" + Objects.toString(state.get(k), "[]")).toArray(String[]::new));
    }

    private static String hash(String... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static Map<String, String> sources() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("mission", rows("missions", "t.id = :missionId"));
        s.put("order_state", rows("orders", "t.id in (select order_id from missions where id = :missionId)"));
        s.put("assignments", rows("mission_checklist_assignments", "t.mission_id = :missionId"));
        s.put("executions", rows("mission_checklist_executions", "t.mission_id = :missionId"));
        s.put("evidence", rows("mission_checklist_evidence", "t.mission_id = :missionId"));
        s.put("items", rows("order_checklist_items", "t.id in (select order_checklist_item_id from mission_checklist_assignments where mission_id = :missionId)"));
        s.put("submissions", rows("mission_evidence_submissions", "t.mission_id = :missionId"));
        s.put("media", rows("drone_media", "t.mission_id = :missionId"));
        s.put("derivatives", rows("media_derivatives", "t.media_id in (select id from drone_media where mission_id = :missionId)", "media_id"));
        s.put("metadata", rows("media_capture_metadata", "t.media_id in (select id from drone_media where mission_id = :missionId)", "media_id"));
        s.put("attempts", rows("media_upload_attempts", "t.media_id in (select id from drone_media where mission_id = :missionId)"));
        s.put("manual", rows("manual_upload_tasks", "t.media_id in (select id from drone_media where mission_id = :missionId)"));
        s.put("results", rows("mission_results", "t.mission_id = :missionId"));
        s.put("result_mission", "select md5(coalesce((select jsonb_build_array(mission_code)::text from missions where id=:missionId),'[]'))");
        s.put("result_media", rows("mission_result_media", "t.mission_result_id in (select id from mission_results where mission_id = :missionId)", "media_id"));
        // Result GET falls back to all mission media only when its explicit media list is empty.
        String resultMedia = "t.mission_id=:missionId and (exists(select 1 from mission_result_media rm join mission_results r on r.id=rm.mission_result_id where r.mission_id=:missionId and rm.media_id=t.id) or not exists(select 1 from mission_result_media rm join mission_results r on r.id=rm.mission_result_id where r.mission_id=:missionId))";
        s.put("result_assets", rows("drone_media", resultMedia));
        String resultAssetIds = "select t.id from drone_media t where " + resultMedia;
        s.put("result_derivatives", rows("media_derivatives", "t.media_id in (" + resultAssetIds + ")", "media_id"));
        s.put("result_metadata", rows("media_capture_metadata", "t.media_id in (" + resultAssetIds + ")", "media_id"));
        s.put("quality_checks", rows("image_quality_check", "t.mission_id=:missionId")
                .replace("to_jsonb(t)::text", "(to_jsonb(t)-'lease'-'lease_at')::text"));
        s.put("quality_reviews", rows("image_quality_review", "t.check_id in (select id from image_quality_check where mission_id=:missionId)"));
        s.put("staff", rows("mission_staff_assignments", "t.mission_id = :missionId"));
        s.put("devices", rows("mission_device_assignments", "t.mission_id = :missionId"));
        s.put("handovers", rows("control_handovers", "t.staff_assignment_id in (select id from mission_staff_assignments where mission_id = :missionId)"));
        s.put("weather", rows("mission_weather_checks", "t.mission_id = :missionId"));
        s.put("preflight", rows("persisted_pre_device_check", "t.mission_id = :missionId"));
        s.put("postflight", rows("persisted_post_device_check", "t.mission_id = :missionId"));
        s.put("preflight_items", rows("persisted_pre_device_check_item", "t.pre_device_run_id in (select id from persisted_pre_device_check where mission_id=:missionId)"));
        s.put("postflight_items", rows("persisted_post_device_check_item", "t.post_device_check_id in (select id from persisted_post_device_check where mission_id=:missionId)"));
        s.put("viewer", "select md5(coalesce((select jsonb_build_array(u.id,u.is_active,r.code,r.is_active,r.updated_at)::text from users u join roles r on r.role_id=u.role_id where u.id=:viewerId),'[]'))");
        s.put("crew", "select md5(coalesce(string_agg(jsonb_build_array(u.id,u.is_active,r.code,r.is_active)::text,'' order by u.id),'[]')) from users u join roles r on r.role_id=u.role_id where u.id in (select staff_id from mission_staff_assignments where mission_id=:missionId)");
        s.put("corrections", rows("mission_inspection_corrections", "t.mission_id = :missionId"));
        s.put("correction_items", rows("mission_inspection_correction_items", "t.correction_id in (select id from mission_inspection_corrections where mission_id=:missionId)"));
        s.put("correction_events", rows("mission_inspection_correction_events", "t.correction_id in (select id from mission_inspection_corrections where mission_id=:missionId)"));
        s.put("correction_executions", rows("mission_checklist_executions", "t.id in (select i.execution_id from mission_inspection_correction_items i join mission_inspection_corrections c on c.id=i.correction_id where c.mission_id=:missionId)"));
        s.put("supplement_results", rows("mission_results", "t.mission_id in (select m.id from missions m join mission_inspection_corrections c on c.id=m.inspection_correction_id where c.mission_id=:missionId)"));
        s.put("supplements", rows("missions", "t.inspection_correction_id in (select id from mission_inspection_corrections where mission_id=:missionId)"));
        s.put("supplement_executions", rows("mission_checklist_executions", "t.mission_id in (select m.id from missions m join mission_inspection_corrections c on c.id=m.inspection_correction_id where c.mission_id=:missionId)"));
        s.put("rounds", rows("result_review_rounds", "t.order_id in (select order_id from missions where id=:missionId)"));
        s.put("round_items", rows("result_review_round_items", "t.mission_id=:missionId"));
        s.put("round_evidence", rows("result_review_round_evidence", "t.round_item_id in (select id from result_review_round_items where mission_id=:missionId)"));
        return Collections.unmodifiableMap(s);
    }

    private static String rows(String table, String where) { return rows(table, where, "id"); }
    static String sql(boolean checks, boolean reviews) {
        return "select " + String.join(",\n", SOURCES.entrySet().stream().map(e -> {
            boolean absent = e.getKey().equals("quality_checks") && !checks
                    || e.getKey().equals("quality_reviews") && (!checks || !reviews);
            return "(" + (absent ? "select md5('[]')" : e.getValue()) + ") as " + e.getKey();
        }).toList());
    }
    private static String rows(String table, String where, String key) {
        return "select md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' order by t." + key + "),'[]')) from " + table + " t where " + where;
    }
}
