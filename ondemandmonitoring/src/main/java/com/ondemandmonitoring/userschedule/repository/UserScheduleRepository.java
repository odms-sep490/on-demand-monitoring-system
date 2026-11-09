package com.ondemandmonitoring.userschedule.repository;

import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserScheduleRepository
        extends JpaRepository<UserSchedule, String>, JpaSpecificationExecutor<UserSchedule> {

    List<UserSchedule> findByStaffId(String staffId);

    List<UserSchedule> findByStaffIdIn(java.util.Collection<String> staffIds);

    List<UserSchedule> findByReferenceId(String referenceId);

}
