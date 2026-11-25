package com.ondemandmonitoring.description;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component("descriptionPurposeAuthorization") @RequiredArgsConstructor
public class DescriptionPurposeAuthorization {
    private final MissionRepository missions;
    private final MissionStaffAssignmentRepository assignments;
    private final IMissionAuthorizationService access;
    private final AuthenticatedUserResolver users;
    public boolean canReview(String missionId) {
        var mission=missions.findByIdWithOrder(missionId);
        if(mission.isEmpty() || !Set.of("PLANNING","PENDING_CONFIRMATION","READY").contains(mission.get().getStatus().name())) return false;
        if(access.canManageMissions()) return true;
        String user=users.getCurrentUserId();
        return assignments.findAllByMissionIdAndIsCurrentTrue(missionId).stream()
            .anyMatch(a->a.getStaff()!=null && user.equals(a.getStaff().getId())
                && !Set.of("REJECTED","DECLINED").contains(a.getResponseStatus().name()));
    }
}
