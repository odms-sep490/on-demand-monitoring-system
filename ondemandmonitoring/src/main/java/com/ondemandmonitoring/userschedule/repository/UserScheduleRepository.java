package com.ondemandmonitoring.userschedule.repository;

import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface UserScheduleRepository
        extends JpaRepository<UserSchedule, String>, JpaSpecificationExecutor<UserSchedule> {

    List<UserSchedule> findByStaffId(String staffId);

    List<UserSchedule> findByStaffIdIn(java.util.Collection<String> staffIds);

    List<UserSchedule> findByReferenceId(String referenceId);

    List<UserSchedule> findByStaffIdAndScheduleTypeAndStatusNotInAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
            String staffId, UserScheduleType scheduleType, Collection<UserScheduleStatus> excludedStatuses,
            Instant from, Instant to);

    List<UserSchedule> findByStaffIdInAndScheduleTypeAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(
            Collection<String> staffIds, UserScheduleType scheduleType, Collection<UserScheduleStatus> excludedStatuses,
            Instant end, Instant start);

    List<UserSchedule> findByStaffIdAndStatusNotInAndStartTimeLessThanAndEndTimeGreaterThan(
            String staffId, Collection<UserScheduleStatus> excludedStatuses, Instant end, Instant start);

}
