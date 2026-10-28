package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionResult;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.dto.response.MissionResultResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionResultMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionAuthorizationServiceImpl;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

/** Real submit service and actor policy; only persistence/readiness infrastructure is mocked. */
public final class MissionResultSubmissionFixture {
    public enum Scenario {
        OPERATOR(false), PILOT_FALLBACK(false), PILOT_WITH_OPERATOR(false), INSPECTOR(true),
        MAINTAINER(false), CUSTOMER(false), MANAGER(false), ADMIN(false), UNASSIGNED(false),
        SIBLING_OPERATOR(false), REPLACED_OPERATOR(false), UNACCEPTED_OPERATOR(false), INACTIVE_OPERATOR(false);
        public final boolean allowed;
        Scenario(boolean allowed) { this.allowed = allowed; }
    }

    public final MissionRepository missions = mock(MissionRepository.class);
    public final MissionStaffAssignmentRepository assignments = mock(MissionStaffAssignmentRepository.class);
    public final MissionResultRepository results = mock(MissionResultRepository.class);
    public final AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    public final IMissionChecklistExecutionService checklist = mock(IMissionChecklistExecutionService.class);
    public final IOrderExecutionCompletionPolicy completion = mock(IOrderExecutionCompletionPolicy.class);
    public final IMissionAuthorizationService authorization = new MissionAuthorizationServiceImpl(
            assignments, missions, resolver, results, ScopeTestFixture.validScope());
    public final MissionResultService service;
    public Mission mission;
    public MissionResult result;
    public User actor;
    public List<MissionStaffAssignment> crew;
    public String targetId;

    public MissionResultSubmissionFixture() {
        var media = mock(MediaAssetRepository.class);
        var mediaService = mock(IMediaAssetService.class);
        var mapper = mock(MissionResultMapper.class);
        when(mapper.toResponse(any(), anyList(), eq(mediaService))).thenAnswer(call -> {
            MissionResult value = call.getArgument(0);
            return MissionResultResponse.builder().id(value.getId()).missionId(value.getMission().getId())
                    .approvalStatus(value.getApprovalStatus()).summary(value.getSummary()).build();
        });
        service = new MissionResultService(results, missions, media, mediaService, mapper,
                completion, checklist, resolver, authorization);
    }

    public void prepare(Scenario scenario) {
        reset(missions, assignments, results, resolver, checklist, completion);
        var order = new Order(); order.setId("order-1"); order.setExecutionPlanRevision(2);
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        mission = mission("mission-1", order);
        var sibling = mission("mission-2", order);
        crew = new ArrayList<>();
        for (var role : MissionStaffRole.values()) crew.add(assignment(mission, role, "staff-" + role.name()));
        var operator = crew.stream().filter(a -> a.getAssignedRole() == MissionStaffRole.OPERATOR).findFirst().orElseThrow();
        var pilot = crew.stream().filter(a -> a.getAssignedRole() == MissionStaffRole.PILOT).findFirst().orElseThrow();
        targetId = mission.getId(); actor = operator.getStaff();
        switch (scenario) {
            case PILOT_FALLBACK -> { crew.remove(operator); actor = pilot.getStaff(); }
            case PILOT_WITH_OPERATOR -> actor = pilot.getStaff();
            case INSPECTOR -> actor = crew.stream().filter(a -> a.getAssignedRole() == MissionStaffRole.INSPECTOR).findFirst().orElseThrow().getStaff();
            case MAINTAINER -> actor = crew.stream().filter(a -> a.getAssignedRole() == MissionStaffRole.MAINTAINER).findFirst().orElseThrow().getStaff();
            case CUSTOMER -> actor = user("customer", RoleCode.CUSTOMER);
            case MANAGER -> actor = user("manager", RoleCode.MANAGER);
            case ADMIN -> actor = user("admin", RoleCode.ADMIN);
            case UNASSIGNED -> actor = user("unassigned", RoleCode.STAFF);
            case SIBLING_OPERATOR -> targetId = sibling.getId();
            case REPLACED_OPERATOR -> operator.setReleaseReason("REPLACED");
            case UNACCEPTED_OPERATOR -> operator.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
            case INACTIVE_OPERATOR -> actor.setIsActive(false);
            default -> { }
        }
        var siblingCrew = List.of(assignment(sibling, MissionStaffRole.OPERATOR, "sibling-operator"));
        when(assignments.findByMissionId(mission.getId())).thenReturn(crew);
        when(assignments.findByMissionId(sibling.getId())).thenReturn(siblingCrew);
        when(assignments.existsByMissionIdAndStaffId(anyString(), anyString())).thenAnswer(call -> {
            List<MissionStaffAssignment> members = mission.getId().equals(call.getArgument(0)) ? crew : siblingCrew;
            return members.stream().anyMatch(a -> a.getStaff().getId().equals(call.getArgument(1)));
        });
        when(resolver.getCurrentUser()).thenAnswer(call -> actor);
        when(resolver.getCurrentUserId()).thenAnswer(call -> actor.getId());
        result = new MissionResult(); result.setId("result-1"); result.setMission(mission);
        result.setApprovalStatus(MissionResultApprovalStatus.DRAFT);
        when(results.findByMissionId(mission.getId())).thenAnswer(call -> Optional.of(result));
        when(results.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Mission mission(String id, Order order) {
        var value = new Mission(); value.setId(id); value.setMissionCode(id); value.setOrder(order);
        value.setStatus(MissionStatus.COMPLETED);
        when(missions.findById(id)).thenReturn(Optional.of(value));
        when(missions.findByIdForUpdate(id)).thenReturn(Optional.of(value));
        return value;
    }

    private MissionStaffAssignment assignment(Mission mission, MissionStaffRole role, String id) {
        return MissionStaffAssignment.builder().mission(mission).staff(user(id, RoleCode.STAFF))
                .assignedRole(role).isCurrent(true).responseStatus(MissionStaffAssignmentStatus.ACCEPTED)
                .releasedAt(Instant.now()).releaseReason("MISSION_COMPLETE").build();
    }

    private User user(String id, RoleCode role) {
        var value = User.builder().isActive(true).role(Role.builder().code(role).active(true).build()).build();
        value.setId(id); return value;
    }
}
