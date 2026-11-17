package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.order.domain.Order;
import java.time.Instant;

/** Shared initialization for template, manual, customer rework and inspection drafts. */
public final class MissionPlanningDraftFactory {
    private MissionPlanningDraftFactory() {}

    public static Mission create(Order order, String code, MissionPurpose purpose,
            String scopeLabel, Instant start, Instant end) {
        var mission = new Mission();
        mission.setOrder(order);
        mission.setMissionCode(code);
        mission.setPurpose(purpose);
        mission.setStatus(MissionStatus.PLANNING);
        mission.setScopeLabel(scopeLabel);
        mission.setScheduledStartAt(start);
        mission.setScheduledEndAt(end);
        FlightPermit.pendingFor(mission);
        return mission;
    }
}
