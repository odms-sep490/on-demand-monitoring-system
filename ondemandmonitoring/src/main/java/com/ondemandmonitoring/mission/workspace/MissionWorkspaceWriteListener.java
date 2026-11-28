package com.ondemandmonitoring.mission.workspace;

import com.ondemandmonitoring.common.entity.BaseEntity;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hibernate.event.spi.*;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bounded persistence-to-domain adapter: only the named workspace aggregates below.
 * Uses flush events rather than service return values: dirty checking, cascades, background
 * TransactionTemplates and physical deletion are covered. No lazy graph traversal in flush.
 * Native SQL is NOT intercepted; its explicit command hook lives in checklist update.
 */
@Component
@RequiredArgsConstructor
public class MissionWorkspaceWriteListener implements PostInsertEventListener, PostUpdateEventListener,
        PreDeleteEventListener, PostCollectionUpdateEventListener, PostCollectionRecreateEventListener,
        PreCollectionRemoveEventListener {
    private static final Set<String> CHECKLIST = Set.of("checklist", "result", "permissions", "corrections");
    private static final Set<String> RESULT = Set.of("result", "permissions");
    private static final Set<String> PERMISSIONS = Set.of("permissions");
    private final JdbcTemplate db;
    private final MissionWorkspaceEvents events;

    @Override public void onPostInsert(PostInsertEvent e) {
        changed(e.getPersister().getEntityName(), e.getId(), state(e.getPersister().getPropertyNames(), e.getState()), Map.of());
    }
    @Override public void onPostUpdate(PostUpdateEvent e) {
        changed(e.getPersister().getEntityName(), e.getId(), state(e.getPersister().getPropertyNames(), e.getState()),
                state(e.getPersister().getPropertyNames(), e.getOldState()));
    }
    @Override public boolean onPreDelete(PreDeleteEvent e) {
        changed(e.getPersister().getEntityName(), e.getId(), state(e.getPersister().getPropertyNames(), e.getDeletedState()), Map.of());
        return false;
    }
    @Override public void onPostUpdateCollection(PostCollectionUpdateEvent e) { collection(e); }
    @Override public void onPostRecreateCollection(PostCollectionRecreateEvent e) { collection(e); }
    @Override public void onPreRemoveCollection(PreCollectionRemoveEvent e) { collection(e); }

    private void collection(AbstractCollectionEvent e) {
        // Many-to-many writes do not necessarily increment the owner's version.
        if ("com.ondemandmonitoring.mission.domain.MissionResult".equals(e.getAffectedOwnerEntityName()))
            query("select mission_id from mission_results where id=?", e.getAffectedOwnerIdOrNull(), RESULT, Set.of());
    }

    void changed(String type, Object id, Map<String, Object> s, Map<String, Object> old) {
        if (!type.startsWith("com.ondemandmonitoring.")) return;
        String name = type.substring(type.lastIndexOf('.') + 1);
        switch (name) {
            case "Mission" -> {
                // Capture recipients while the mission still exists (also handles deletion).
                Set<String> previous = new HashSet<>(db.queryForList("""
                        select u.id from users u join roles r on r.role_id=u.role_id
                        where u.is_active and r.is_active and (r.code in ('MANAGER','ADMIN') or
                        (r.code='STAFF' and exists(select 1 from mission_staff_assignments a where a.mission_id=? and a.staff_id=u.id)))
                        """, String.class, id));
                record(Objects.toString(id, null), MissionWorkspaceEvents.ALL, previous);
                Object correction = id(s.get("inspectionCorrection"));
                if (correction != null) query("select mission_id from mission_inspection_corrections where id=?", correction, CHECKLIST, Set.of());
            }
            case "MissionChecklistExecution", "MissionChecklistAssignment", "MissionChecklistEvidence" ->
                record((String) s.get("missionId"), CHECKLIST, Set.of());
            case "MissionEvidenceSubmission" -> record(stringId(s.get("mission")), CHECKLIST, Set.of());
            case "MissionResult" -> record(stringId(s.get("mission")), RESULT, Set.of());
            case "MediaAsset" -> {
                String mission = stringId(s.get("mission"));
                if (mission != null) record(mission, MissionWorkspaceEvents.ALL, Set.of());
                else query("select mission_id from mission_device_assignments where id=?", id(s.get("deviceAssignment")), MissionWorkspaceEvents.ALL, Set.of());
            }
            case "MediaDerivative", "MediaCaptureMetadata", "MediaUploadAttempt", "ManualUploadTask" ->
                query("select mission_id from drone_media where id=?", id(s.get("media")), MissionWorkspaceEvents.ALL, Set.of());
            case "MissionStaffAssignment" -> {
                String staff = stringId(s.get("staff"));
                record(stringId(s.get("mission")), PERMISSIONS, staff == null ? Set.of() : Set.of(staff));
            }
            case "MissionDeviceAssignment", "MissionWeatherCheck", "PersistedPreDeviceCheck", "PersistedPostDeviceCheck" ->
                record(stringId(s.get("mission")), PERMISSIONS, Set.of());
            case "ControlHandover" -> query("select mission_id from mission_staff_assignments where id=?", id(s.get("staffAssignment")), PERMISSIONS, Set.of());
            case "PersistedPreDeviceCheckItem" -> query("select mission_id from persisted_pre_device_check where id=?", id(s.get("preDeviceCheck")), PERMISSIONS, Set.of());
            case "PersistedPostDeviceCheckItem" -> query("select mission_id from persisted_post_device_check where id=?", id(s.get("postDeviceCheck")), PERMISSIONS, Set.of());
            case "InspectionCorrection" -> record(stringId(s.get("mission")), CHECKLIST, Set.of());
            case "InspectionCorrectionItem", "InspectionCorrectionEvent" -> query("select mission_id from mission_inspection_corrections where id=?", id(s.get("correction")), CHECKLIST, Set.of());
            case "OrderChecklistItem" -> query("select mission_id from mission_checklist_assignments where order_checklist_item_id=?", id, CHECKLIST, Set.of());
            case "Order" -> query("select id from missions where order_id=?", id, MissionWorkspaceEvents.ALL, Set.of());
            case "ResultReviewRound" -> query("select id from missions where order_id=?", id(s.get("order")), RESULT, Set.of());
            case "ResultReviewRoundItem" -> record((String) s.get("missionId"), RESULT, Set.of());
            case "ResultReviewRoundEvidence" -> query("select mission_id from result_review_round_items where id=?", id(s.get("roundItem")), RESULT, Set.of());
            case "User" -> {
                // Ignore profile/login-only writes. Only active/role transitions affect access.
                if (!old.isEmpty() && Objects.equals(s.get("isActive"), old.get("isActive"))
                        && Objects.equals(id(s.get("role")), id(old.get("role")))) return;
                Set<String> previous = Set.of(id.toString());
                query("select mission_id from mission_staff_assignments where staff_id=?", id, PERMISSIONS, previous);
                for (Object role : new Object[]{s.get("role"), old.get("role")}) {
                    if (role != null) query("select m.id from missions m where exists(select 1 from roles r where r.role_id=? and r.code in ('MANAGER','ADMIN'))",
                            id(role), PERMISSIONS, previous);
                }
            }
            case "Role" -> {
                if (!old.isEmpty() && Objects.equals(s.get("active"), old.get("active")) && Objects.equals(s.get("code"), old.get("code"))) return;
                var viewers = db.queryForList("select id from users where role_id=?", String.class, id);
                for (String viewer : viewers) {
                    boolean manager = Set.of("MANAGER", "ADMIN").contains(Objects.toString(s.get("code")))
                            || Set.of("MANAGER", "ADMIN").contains(Objects.toString(old.get("code")));
                    if (manager) query("select id from missions where ? is not null", viewer, PERMISSIONS, Set.of(viewer));
                    else query("select mission_id from mission_staff_assignments where staff_id=?", viewer, PERMISSIONS, Set.of(viewer));
                }
            }
            default -> { /* Explicit boundary: unrelated persistence never produces a mission event. */ }
        }
    }

    private void record(String missionId, Set<String> components, Set<String> previous) {
        if (missionId == null) return;
        events.changed(missionId, components, previous);
        // A supplemental mission changes its source correction's review token/status too.
        db.queryForList("""
                select c.mission_id from missions m join mission_inspection_corrections c
                  on c.id=m.inspection_correction_id where m.id=?
                """, String.class, missionId).forEach(parent -> events.changed(parent, CHECKLIST));
    }

    private void query(String sql, Object key, Set<String> components, Set<String> previous) {
        if (key != null) db.queryForList(sql, String.class, key).forEach(mission -> record(mission, components, previous));
    }
    private static Map<String, Object> state(String[] names, Object[] values) {
        Map<String, Object> result = new HashMap<>();
        if (values != null) for (int i = 0; i < names.length; i++) result.put(names[i], values[i]);
        return result;
    }
    private static String stringId(Object entity) { return Objects.toString(id(entity), null); }
    private static Object id(Object entity) {
        if (entity instanceof HibernateProxy proxy) return proxy.getHibernateLazyInitializer().getIdentifier();
        if (entity instanceof BaseEntity base) return base.getId();
        if (entity instanceof com.ondemandmonitoring.role.domain.Role role) return role.getId();
        return entity;
    }
}
