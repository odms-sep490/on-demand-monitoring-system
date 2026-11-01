package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.domain.MissionResult;
import com.ondemandmonitoring.mission.dto.request.MissionResultRequest;
import com.ondemandmonitoring.mission.dto.request.MissionResultReviewRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResultResponse;
import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.enums.MissionResultStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.mapper.ChecklistEvidenceMapper;
import com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper;
import com.ondemandmonitoring.mission.mapper.MissionResultMapper;
import com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository;
import com.ondemandmonitoring.mission.repository.MissionChecklistExecutionRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidenceServiceImpl;
import com.ondemandmonitoring.mission.service.impl.MissionChecklistExecutionServiceImpl;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MissionResultEvidencePolicyV2Test {

    MissionRepository missions = mock(MissionRepository.class);
    MissionResultRepository results = mock(MissionResultRepository.class);
    MediaAssetRepository mediaRepo = mock(MediaAssetRepository.class);
    IMediaAssetService mediaService = mock(IMediaAssetService.class);
    MissionChecklistExecutionRepository executionRepo = mock(MissionChecklistExecutionRepository.class);
    MissionChecklistEvidenceRepository evidenceRepo = mock(MissionChecklistEvidenceRepository.class);
    IMissionChecklistScopeService scope = mock(IMissionChecklistScopeService.class);
    IMissionAuthorizationService auth = mock(IMissionAuthorizationService.class);
    AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    IOrderExecutionCompletionPolicy completionPolicy = mock(IOrderExecutionCompletionPolicy.class);
    IMediaObjectStorage storage = mock(IMediaObjectStorage.class);

    ChecklistEvidencePolicy policy = new ChecklistEvidencePolicy();
    ChecklistEvidenceServiceImpl evidenceService;
    MissionChecklistExecutionServiceImpl executionService;
    MissionResultService resultService;

    Order order;
    Mission mission;
    OrderChecklistItem item;
    MissionChecklistExecution execution;
    List<MissionChecklistEvidence> activeLinks = new ArrayList<>();
    List<MediaAsset> missionMediaList = new ArrayList<>();

    MissionResultMapper resultMapper = mock(MissionResultMapper.class);

    @BeforeEach
    void setUp() {
        activeLinks.clear();
        missionMediaList.clear();

        when(resultMapper.toResponse(any(), any(), any())).thenAnswer(inv -> {
            MissionResult res = inv.getArgument(0);
            var resp = new MissionResultResponse();
            resp.setApprovalStatus(res.getApprovalStatus());
            return resp;
        });

        evidenceService = new ChecklistEvidenceServiceImpl(
                missions, executionRepo, evidenceRepo, mediaRepo, results, auth, resolver,
                policy, Mappers.getMapper(ChecklistEvidenceMapper.class), storage);

        executionService = new MissionChecklistExecutionServiceImpl(
                missions, scope, executionRepo, results, auth, resolver,
                Mappers.getMapper(MissionChecklistExecutionMapper.class), evidenceService,
                mock(com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService.class));

        resultService = new MissionResultService(
                results, missions, mediaRepo, mediaService,
                resultMapper,
                completionPolicy, executionService, resolver, auth);

        order = new Order();
        order.setId("order-1");
        order.setExecutionPlanRevision(1L);

        mission = new Mission();
        mission.setId("mission-1");
        mission.setOrder(order);
        mission.setStatus(MissionStatus.COMPLETED);

        item = new OrderChecklistItem();
        item.setId("item-1");
        item.setOrder(order);
        item.setContent("Check water spillway cracks");
        item.setEvidencePolicyVersion(2);
        item.setMinimumEvidenceCount(2);
        item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE);

        execution = new MissionChecklistExecution();
        execution.setId("exec-1");
        execution.setMission(mission);
        execution.setMissionId("mission-1");
        execution.setOrderId("order-1");
        execution.setOrderChecklistItem(item);
        execution.setOrderChecklistItemId("item-1");
        execution.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
        execution.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
        execution.setVersion(0L);
        ScopeTestFixture.link(execution, mission);

        User manager = new User();
        manager.setId("manager-1");
        manager.setRole(Role.builder().code(RoleCode.MANAGER).active(true).build());

        when(resolver.getCurrentUser()).thenReturn(manager);
        when(resolver.getCurrentUserId()).thenReturn(manager.getId());
        when(auth.canSubmitMissionResult("mission-1")).thenReturn(true);
        when(auth.canViewMission("mission-1")).thenReturn(true);
        when(scope.isMaterialized(mission)).thenReturn(true);

        when(missions.findById("mission-1")).thenReturn(Optional.of(mission));
        when(missions.findByIdForUpdate("mission-1")).thenReturn(Optional.of(mission));
        when(executionRepo.findOrderedByMissionId("mission-1")).thenAnswer(inv -> List.of(execution));
        when(evidenceRepo.findActiveByMissionId("mission-1")).thenAnswer(inv -> activeLinks);
        when(mediaRepo.findByMissionIdOrderByCapturedAtDesc("mission-1")).thenAnswer(inv -> missionMediaList);

        when(results.save(any(MissionResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(results.saveAndFlush(any(MissionResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(completionPolicy.lockAggregate("order-1")).thenReturn(
                new IOrderExecutionCompletionPolicy.LockedExecution(order, List.of(mission)));
    }

    private MediaAsset createAsset(String id, String type, MediaStatus status) {
        MediaAsset asset = new MediaAsset();
        asset.setId(id);
        asset.setMission(mission);
        asset.setType(type);
        asset.setSourceType("DRONE_CAMERA");
        asset.setMediaStatus(status);
        asset.setValidatedAt(status == MediaStatus.UPLOAD_PENDING ? null : Instant.now());
        asset.setS3Bucket("bucket");
        asset.setS3Key("key-" + id);
        asset.setOriginalFileName(id + "." + (type.equals("VIDEO") ? "mp4" : "jpg"));
        return asset;
    }

    private MissionChecklistEvidence attach(MediaAsset asset) {
        MissionChecklistEvidence link = new MissionChecklistEvidence();
        link.setId("link-" + asset.getId());
        link.setMissionId("mission-1");
        link.setMissionChecklistExecution(execution);
        link.setMediaAsset(asset);
        link.setAttachedAt(Instant.now());
        activeLinks.add(link);
        if (!missionMediaList.contains(asset)) {
            missionMediaList.add(asset);
        }
        return link;
    }

    @Test
    void upsert_RejectsInsufficientEvidenceCount() {
        // Item requires 2 IMAGEs, attach only 1
        MediaAsset img1 = createAsset("img-1", "IMAGE", MediaStatus.PENDING_MANAGER_APPROVAL);
        attach(img1);

        MissionResultRequest req = new MissionResultRequest();
        req.setStatus(MissionResultStatus.COMPLETED);

        ApiException ex = assertThrows(ApiException.class, () -> resultService.upsert("mission-1", req));
        assertEquals(ErrorCode.CHECKLIST_NOT_READY, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("requires at least 2 IMAGE"), ex.getMessage());
        assertTrue(ex.getMessage().contains("current eligible count is 1"), ex.getMessage());
    }

    @Test
    void upsert_RejectsWrongMediaType() {
        // Item requires 2 IMAGEs, attach 2 VIDEOs
        MediaAsset vid1 = createAsset("vid-1", "VIDEO", MediaStatus.PENDING_MANAGER_APPROVAL);
        MediaAsset vid2 = createAsset("vid-2", "VIDEO", MediaStatus.PENDING_MANAGER_APPROVAL);
        attach(vid1);
        attach(vid2);

        MissionResultRequest req = new MissionResultRequest();
        req.setStatus(MissionResultStatus.COMPLETED);

        ApiException ex = assertThrows(ApiException.class, () -> resultService.upsert("mission-1", req));
        assertEquals(ErrorCode.CHECKLIST_NOT_READY, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("EVIDENCE_TYPE_NOT_ELIGIBLE"), ex.getMessage());
        assertTrue(ex.getMessage().contains("current eligible count is 0"), ex.getMessage());
    }

    @Test
    void upsert_RejectsRejectedOrUnvalidatedMedia() {
        // Item requires 2 IMAGEs, attach 1 valid, 1 REJECTED, 1 UPLOAD_PENDING (unvalidated)
        MediaAsset img1 = createAsset("img-1", "IMAGE", MediaStatus.PENDING_MANAGER_APPROVAL);
        MediaAsset img2 = createAsset("img-2", "IMAGE", MediaStatus.REJECTED);
        MediaAsset img3 = createAsset("img-3", "IMAGE", MediaStatus.UPLOAD_PENDING);
        attach(img1);
        attach(img2);
        attach(img3);

        MissionResultRequest req = new MissionResultRequest();
        req.setStatus(MissionResultStatus.COMPLETED);

        ApiException ex = assertThrows(ApiException.class, () -> resultService.upsert("mission-1", req));
        assertEquals(ErrorCode.CHECKLIST_NOT_READY, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("current eligible count is 1"), ex.getMessage());
    }

    @Test
    void upsert_SucceedsWithSufficientEligibleEvidence() {
        // Item requires 2 IMAGEs, attach 2 valid PENDING_MANAGER_APPROVAL IMAGEs
        MediaAsset img1 = createAsset("img-1", "IMAGE", MediaStatus.PENDING_MANAGER_APPROVAL);
        MediaAsset img2 = createAsset("img-2", "IMAGE", MediaStatus.PENDING_MANAGER_APPROVAL);
        attach(img1);
        attach(img2);

        MissionResultRequest req = new MissionResultRequest();
        req.setStatus(MissionResultStatus.COMPLETED);

        var response = resultService.upsert("mission-1", req);
        assertNotNull(response);
        assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, response.getApprovalStatus());
    }

    @Test
    void approve_RejectsWhenEvidenceIsStillPendingManagerApproval() {
        // For final approval, evidence media must be AVAILABLE (not just PENDING_MANAGER_APPROVAL)
        MediaAsset img1 = createAsset("img-1", "IMAGE", MediaStatus.PENDING_MANAGER_APPROVAL);
        MediaAsset img2 = createAsset("img-2", "IMAGE", MediaStatus.PENDING_MANAGER_APPROVAL);
        attach(img1);
        attach(img2);

        MissionResult pendingResult = new MissionResult();
        pendingResult.setId("res-1");
        pendingResult.setMission(mission);
        pendingResult.setStatus(MissionResultStatus.COMPLETED);
        pendingResult.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);

        when(results.findById("res-1")).thenReturn(Optional.of(pendingResult));
        when(results.findOrderIdByResultId("res-1")).thenReturn(Optional.of("order-1"));

        ApiException ex = assertThrows(ApiException.class, () ->
                resultService.approve("res-1", new MissionResultReviewRequest()));
        assertTrue(ex.getErrorCode() == ErrorCode.CHECKLIST_NOT_READY || ex.getErrorCode() == ErrorCode.DELIVERY_NOT_READY);
    }

    @Test
    void approve_RejectsWhenEvidenceHasWrongMediaTypeEvenIfAvailable() {
        // Item requires VIDEO, but attached media are AVAILABLE IMAGEs
        item.setRequiredEvidenceType(RequiredEvidenceType.VIDEO);
        item.setMinimumEvidenceCount(1);

        MediaAsset img1 = createAsset("img-1", "IMAGE", MediaStatus.AVAILABLE);
        attach(img1);

        MissionResult pendingResult = new MissionResult();
        pendingResult.setId("res-1");
        pendingResult.setMission(mission);
        pendingResult.setStatus(MissionResultStatus.COMPLETED);
        pendingResult.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);

        when(results.findById("res-1")).thenReturn(Optional.of(pendingResult));
        when(results.findOrderIdByResultId("res-1")).thenReturn(Optional.of("order-1"));

        ApiException ex = assertThrows(ApiException.class, () ->
                resultService.approve("res-1", new MissionResultReviewRequest()));
        assertEquals(ErrorCode.CHECKLIST_NOT_READY, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("requires at least 1 VIDEO"), ex.getMessage());
    }

    @Test
    void approve_SucceedsWithSufficientAvailableEvidence() {
        // Item requires 2 IMAGEs, attach 2 AVAILABLE IMAGEs
        MediaAsset img1 = createAsset("img-1", "IMAGE", MediaStatus.AVAILABLE);
        MediaAsset img2 = createAsset("img-2", "IMAGE", MediaStatus.AVAILABLE);
        attach(img1);
        attach(img2);

        MissionResult pendingResult = new MissionResult();
        pendingResult.setId("res-1");
        pendingResult.setMission(mission);
        pendingResult.setStatus(MissionResultStatus.COMPLETED);
        pendingResult.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);

        when(results.findById("res-1")).thenReturn(Optional.of(pendingResult));
        when(results.findOrderIdByResultId("res-1")).thenReturn(Optional.of("order-1"));

        var approved = resultService.approve("res-1", new MissionResultReviewRequest());
        assertNotNull(approved);
        assertEquals(MissionResultApprovalStatus.APPROVED, approved.getApprovalStatus());
    }

    @Test
    void upsertAndApprove_AllowUnableToVerifyWithValidReasonWithoutEvidence() {
        // Item requires 2 IMAGEs, but execution is UNABLE_TO_VERIFY with reason
        execution.setExecutionStatus(ChecklistExecutionStatus.UNABLE_TO_VERIFY);
        execution.setUnableToVerifyReason("Dense smoke prevented visibility");

        MissionResultRequest req = new MissionResultRequest();
        req.setStatus(MissionResultStatus.COMPLETED);

        var upsertResponse = resultService.upsert("mission-1", req);
        assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, upsertResponse.getApprovalStatus());

        MissionResult pendingResult = new MissionResult();
        pendingResult.setId("res-1");
        pendingResult.setMission(mission);
        pendingResult.setStatus(MissionResultStatus.COMPLETED);
        pendingResult.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);

        when(results.findById("res-1")).thenReturn(Optional.of(pendingResult));
        when(results.findOrderIdByResultId("res-1")).thenReturn(Optional.of("order-1"));

        var approveResponse = resultService.approve("res-1", new MissionResultReviewRequest());
        assertEquals(MissionResultApprovalStatus.APPROVED, approveResponse.getApprovalStatus());
    }
}
