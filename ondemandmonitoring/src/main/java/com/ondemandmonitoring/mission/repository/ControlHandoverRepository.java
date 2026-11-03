package com.ondemandmonitoring.mission.repository;

import com.ondemandmonitoring.mission.domain.ControlHandover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ControlHandoverRepository extends JpaRepository<ControlHandover, String> {

    List<ControlHandover> findByStaffAssignmentStaffId(String staffId);

    Optional<ControlHandover> findTopByStaffAssignment_Mission_IdAndStatusOrderByConfirmedAtDesc(
            String missionId, String status);
}
