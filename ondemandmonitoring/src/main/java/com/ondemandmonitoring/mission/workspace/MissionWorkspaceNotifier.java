package com.ondemandmonitoring.mission.workspace;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class MissionWorkspaceNotifier {
    private final NamedParameterJdbcTemplate db;
    private final SimpMessagingTemplate messaging;

    // Mirrors canViewMission: active managers/admins and active staff with ANY mission
    // assignment, including history. Never include customers or unrelated crew.
    static final String RECIPIENTS = """
            select distinct i.cognito_sub from user_identities i
            join users u on u.id=i.user_id join roles r on r.role_id=u.role_id
            where i.cognito_sub is not null and i.cognito_sub<>'' and (
              (u.is_active and r.is_active and exists(select 1 from missions where id=:missionId)
                and (r.code in ('MANAGER','ADMIN') or (r.code='STAFF' and exists(
                  select 1 from mission_staff_assignments a where a.mission_id=:missionId and a.staff_id=u.id))))
              or u.id in (:previousViewers))
            """;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void notify(MissionWorkspaceEvents.Changed event) {
        var args = new MapSqlParameterSource("missionId", event.missionId())
                .addValue("previousViewers", event.previousViewers().isEmpty() ? List.of("") : event.previousViewers());
        var message = new MissionWorkspaceEvents.Message(UUID.randomUUID(), event.missionId(),
                event.components().stream().sorted().toList(), Instant.now());
        for (String subject : db.queryForList(RECIPIENTS, args, String.class)) {
            try {
                messaging.convertAndSendToUser(subject, "/queue/mission-events", message);
            } catch (RuntimeException failure) {
                log.warn("Unable to deliver mission workspace invalidation for {}", event.missionId(), failure);
            }
        }
    }
}
