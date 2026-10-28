package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.LockStatus;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.mission.service.IResourceReservationService;
import com.ondemandmonitoring.userschedule.enums.*;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Half-open [start,end) reservations. Reservation writes validate overlap without acquiring row locks. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ResourceReservationServiceImpl implements IResourceReservationService {
    private final JdbcTemplate jdbc;
    private final ResourceTimeLockRepository locks;
    private final UserScheduleRepository schedules;
    private final com.ondemandmonitoring.user.service.AuthenticatedUserResolver actor;

    @Override
    public void lockResources(Collection<String> ids) {
        for (String id : new TreeSet<>(ids)) {
            if (id == null || id.isBlank()) throw new ApiException(ErrorCode.INVALID_REQUEST);
            jdbc.update("insert into resource_reservation_mutex(resource_id) values (?) on conflict do nothing", id);
            jdbc.queryForObject("select resource_id from resource_reservation_mutex where resource_id=? for update", String.class, id);
        }
    }

    @Override
    public void reserve(Mission mission, String id, boolean staff) {
        reserveAll(mission, Map.of(id, staff));
    }

    @Override public void lockMissionResources(String missionId, Collection<String> additional) {
        var ids = new TreeSet<>(additional);
        for (var lock : locks.findByMissionId(missionId)) ids.add(lock.getResourceId());
        lockResources(ids);
    }

    @Override public void reserveAll(Mission mission, Map<String, Boolean> requested) {
        var sorted = new TreeMap<>(requested);
        sorted.forEach((id, staff) -> validate(id, mission.getId(), mission.getScheduledStartAt(), mission.getScheduledEndAt(), staff));
        try {
            sorted.forEach((id, staff) -> {
                if (locks.findAllByResourceIdAndMissionId(id, mission.getId()).stream().noneMatch(l -> l.getReleasedAt() == null))
                    locks.saveAndFlush(ResourceTimeLock.builder().resourceId(id).mission(mission)
                            .startTime(mission.getScheduledStartAt()).endTime(mission.getScheduledEndAt())
                            .resourceType(staff ? "STAFF" : "DEVICE").reservedBy(actor.getCurrentUserId()).lockStatus(LockStatus.HARD_LOCK).build());
            });
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            throw new ApiException(ErrorCode.RESOURCE_TIME_CONFLICT);
        }
    }

    private void validate(String id, String missionId, Instant start, Instant end, boolean staff) {
        if (start == null || end == null || !start.isBefore(end)) throw new ApiException(ErrorCode.INVALID_REQUEST);
        if (locks.existsActiveOverlap(id, missionId, start, end)) throw new ApiException(ErrorCode.RESOURCE_TIME_CONFLICT);
        if (staff && ReservationWindowPolicy.staffHasConflict(schedules.findByStaffId(id), missionId, start, end))
            throw new ApiException(ErrorCode.RESOURCE_TIME_CONFLICT);
    }

    @Override
    public void revalidateSchedule(Mission mission, Instant start, Instant end) {
        var active = locks.findByMissionId(mission.getId()).stream().filter(l -> l.getReleasedAt() == null).toList();
        var owned = schedules.findByReferenceId(mission.getId()).stream()
                .filter(s -> s.getScheduleType() == UserScheduleType.MISSION && s.getStatus() != UserScheduleStatus.CANCELLED
                        && s.getStatus() != UserScheduleStatus.COMPLETED).toList();
        for (var lock : active) validate(lock.getResourceId(), mission.getId(), start, end,
                "STAFF".equals(lock.getResourceType()) || schedules.findByStaffId(lock.getResourceId()).stream()
                        .anyMatch(s -> mission.getId().equals(s.getReferenceId())));
        for (var schedule : owned) validate(schedule.getStaff().getId(), mission.getId(), start, end, true);
        for (var lock : active) { lock.setStartTime(start); lock.setEndTime(end); }
        try { locks.saveAllAndFlush(active); }
        catch (org.springframework.dao.DataIntegrityViolationException conflict) { throw new ApiException(ErrorCode.RESOURCE_TIME_CONFLICT); }
        for (var schedule : owned) { schedule.setStartTime(start); schedule.setEndTime(end); }
        schedules.saveAll(owned);
    }

    @Override public void release(String missionId, String reason) { releaseRows(locks.findByMissionId(missionId), reason); }
    @Override public void releaseResource(String missionId, String id, String reason) {
        releaseRows(locks.findAllByResourceIdAndMissionId(id, missionId), reason);
    }
    private void releaseRows(List<ResourceTimeLock> rows, String reason) {
        Instant now = Instant.now();
        for (var lock : rows) if (lock.getReleasedAt() == null) {
            lock.setReleasedAt(now); lock.setReleaseReason(reason); lock.setReleasedBy(actor.getCurrentUserId());
        }
        locks.saveAllAndFlush(rows);
    }
}
