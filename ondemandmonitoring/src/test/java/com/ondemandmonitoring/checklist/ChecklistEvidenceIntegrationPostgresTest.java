package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.media.service.impl.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.order.enums.OrderStatus;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import jakarta.persistence.EntityManagerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual evidence/readiness/result/media-review services, JPA versions and real PostgreSQL transactions. */
@SpringJUnitConfig(ChecklistEvidenceIntegrationPostgresTest.EvidenceConfig.class)
class ChecklistEvidenceIntegrationPostgresTest extends OrderMissionPostgresTest {
    @Autowired IChecklistEvidenceService evidenceService;
    @Autowired IMissionChecklistExecutionService executionService;
    @Autowired IMissionAuthorizationService auth;
    @Autowired IMissionResultService resultService;
    @Autowired IManagerMediaApprovalService approvals;
    @Autowired MediaAssetRepository mediaRepository;
    @Override @BeforeEach void prepare() {
        super.prepare(); reset(auth);
        when(auth.canViewMission(anyString())).thenReturn(true); when(auth.canExecuteMonitoringChecklist(anyString())).thenReturn(true);
        when(auth.canReviewMissionMedia(anyString())).thenReturn(true);
        when(auth.canSubmitMissionResult(anyString())).thenReturn(true);
        when(auth.canAttachChecklistEvidence(anyString())).thenReturn(true); when(auth.canDetachChecklistEvidence(anyString())).thenReturn(true);
    }
    String mission(int count) {
        var request=orderRequest(service("Evidence service")); request.setChecklistItems(java.util.stream.IntStream.range(0,count).mapToObj(i -> {
            var item = selected(null,"Historical evidence item " + i);
            item.setMinimumEvidenceCount(1);
            item.setRequiredEvidenceType(com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE_OR_VIDEO);
            return item;
        }).toList());
        String order=orderService.createOrder(request).getId();
        var user = resolver.getCurrentUser();
        when(resolver.getCurrentUserId()).thenReturn(user.getId());
        transaction.executeWithoutResult(tx->orders.findById(order).orElseThrow().setOrderStatus(OrderStatus.APPROVED));
        String mission=legacyMission(order, request(order)).getId(); setStatus(mission,MissionStatus.PENDING_REVIEW);return mission;
    }
    void setStatus(String mission,MissionStatus status) { transaction.executeWithoutResult(tx->{Mission entity=missions.findByIdForUpdate(mission).orElseThrow();entity.setStatus(status);if(status==MissionStatus.COMPLETED)entity.setCompletedAt(java.time.Instant.now());}); }
    String asset(String mission,MediaStatus status) { return asset(mission,status,"IMAGE"); }
    String asset(String mission,MediaStatus status,String mediaType) {
        String id=UUID.randomUUID().toString(),device=UUID.randomUUID().toString(),model=UUID.randomUUID().toString();var jdbc=new JdbcTemplate(dataSource);
        jdbc.update("insert into device_models(id,created_at,version,code,model_name) values (?,now(),0,?,'Test model')",model,model);
        jdbc.update("insert into devices(id,created_at,version,serial_number,name,model_id,status) values (?,now(),0,?,'Test camera',?,'AVAILABLE')",device,device,model);
        String deviceRole = jdbc.queryForObject("select count(*) from mission_device_assignments where mission_id=? and is_current and device_role='MAIN'", Integer.class, mission) == 0 ? "MAIN" : "SUPPORT";
        jdbc.update("insert into mission_device_assignments(id,created_at,version,mission_id,device_id,device_role,checkup_status,is_current) values (?,now(),0,?,?,?,'PENDING',true)",UUID.randomUUID().toString(),mission,device,deviceRole);
        String contentType = mediaType.equals("VIDEO") ? "video/mp4" : "image/jpeg";
        String filename = mediaType.equals("VIDEO") ? "capture.mp4" : "capture.jpg";
        jdbc.update("insert into drone_media(id,created_at,version,mission_id,device_id,media_type,storage_provider,original_file_name,content_type,file_size,s3_bucket,s3_key,s3_url,captured_at,media_status,source_type,validated_at,preview_selected,storage_status) values (?,now(),0,?,?,?,'S3',?,?,20,'test',?,?,now(),?,'DRONE_CAMERA',now(),false,'AVAILABLE')",id,mission,device,mediaType,filename,contentType,id,"s3://test/"+id,status.name());return id;
    }
    ChecklistExecutionResponse row(String mission) {return executionService.getByMissionId(mission).executions().getFirst();}
    void complete(String mission) {
        var row=row(mission);var request=new ChecklistExecutionUpdateRequest();request.setExpectedVersion(row.getVersion());request.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);request.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
        executionService.update(mission,row.getId(),request);
    }
    BatchChecklistEvidenceRequest attachRequest(String mission,String asset) {var row=row(mission);return new BatchChecklistEvidenceRequest(asset,List.of(new BatchChecklistEvidenceRequest.Target(row.getId(),row.getVersion())),null);}
    @Test void newSnapshotSubmitsValidatedEvidenceButFinalResultRequiresMediaApproval() {
        String mission=mission(1); assertEquals(2,row(mission).getEvidencePolicyVersion());
        assertEquals(1,row(mission).getMinimumEvidenceCount());
        assertEquals(com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE_OR_VIDEO,row(mission).getRequiredEvidenceType());
        assertFalse(executionService.getByMissionId(mission).checklistEvidenceReady());String media=asset(mission,MediaStatus.PENDING_MANAGER_APPROVAL);
        evidenceService.attach(mission,attachRequest(mission,media));complete(mission);assertTrue(executionService.getByMissionId(mission).readyForMissionCompletion());
        setStatus(mission,MissionStatus.COMPLETED);var result=resultService.upsert(mission,new MissionResultRequest());
        assertThrows(ApiException.class, () -> resultService.approve(result.getId(),new MissionResultReviewRequest()));
        approvals.approve(mission, media);
        resultService.approve(result.getId(),new MissionResultReviewRequest());
        assertThrows(ApiException.class,()->evidenceService.attach(mission,attachRequest(mission,media)));
        assertEquals(ErrorCode.CHECKLIST_EXECUTION_LOCKED, assertThrows(ApiException.class, () -> approvals.reject(mission, media)).getErrorCode());
    }
    @Test void configuredImageRequirementPropagatesToEveryMissionAndFinalApprovalRechecksIt() {
        var request = orderRequest(service("multi-mission evidence service"));
        var user = resolver.getCurrentUser();
        when(resolver.getCurrentUserId()).thenReturn(user.getId());
        var selection = selected(null, "Two images required");
        selection.setMinimumEvidenceCount(2);
        selection.setRequiredEvidenceType(com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE);
        request.setChecklistItems(List.of(selection));
        String order = orderService.createOrder(request).getId();
        var item = orderService.getOrderById(order).getChecklistItems().getFirst();
        assertEquals(2, item.getMinimumEvidenceCount());
        assertEquals(com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE, item.getRequiredEvidenceType());
        transaction.executeWithoutResult(tx -> orders.findById(order).orElseThrow().setOrderStatus(OrderStatus.APPROVED));
        String first = legacyMission(order, request(order)).getId();
        String second = legacyMission(order, request(order)).getId();
        setStatus(first, MissionStatus.PENDING_REVIEW); setStatus(second, MissionStatus.PENDING_REVIEW);
        assertEquals(2, row(first).getMinimumEvidenceCount());
        assertEquals(2, row(second).getMinimumEvidenceCount());
        String video = asset(first, MediaStatus.PENDING_MANAGER_APPROVAL, "VIDEO");
        assertEquals(ErrorCode.EVIDENCE_NOT_ELIGIBLE, assertThrows(ApiException.class,
                () -> evidenceService.attach(first, attachRequest(first, video))).getErrorCode());
        String image1 = asset(first, MediaStatus.PENDING_MANAGER_APPROVAL, "IMAGE");
        evidenceService.attach(first, attachRequest(first, image1));
        setStatus(first, MissionStatus.COMPLETED);
        assertThrows(ApiException.class, () -> resultService.upsert(first, new MissionResultRequest()));
        String image2 = asset(first, MediaStatus.PENDING_MANAGER_APPROVAL, "IMAGE");
        evidenceService.attach(first, new BatchChecklistEvidenceRequest(image2, List.of(
                new BatchChecklistEvidenceRequest.Target(row(first).getId(), row(first).getVersion())), null));
        complete(first);
        assertTrue(executionService.getByMissionId(first).readyForSubmission());
        var submitted = resultService.upsert(first, new MissionResultRequest());
        assertThrows(ApiException.class, () -> resultService.approve(submitted.getId(), new MissionResultReviewRequest()));
        approvals.approve(first, image1); approvals.approve(first, image2);
        assertEquals(MissionResultApprovalStatus.APPROVED, resultService.approve(submitted.getId(), new MissionResultReviewRequest()).getApprovalStatus());
        assertFalse(executionService.getByMissionId(second).readyForSubmission());
    }

    @Test void batchIsAtomicAndSameMediaSupportsManyExecutions() {
        String mission=mission(2),media=asset(mission,MediaStatus.PENDING_MANAGER_APPROVAL);var rows=executionService.getByMissionId(mission).executions();
        var bad=new BatchChecklistEvidenceRequest(media,List.of(new BatchChecklistEvidenceRequest.Target(rows.getFirst().getId(),0L),new BatchChecklistEvidenceRequest.Target(rows.getLast().getId(),9L)),null);
        assertThrows(ApiException.class,()->evidenceService.attach(mission,bad));assertEquals(0,new JdbcTemplate(dataSource).queryForObject("select count(*) from mission_checklist_evidence",Integer.class));
        assertEquals(2,evidenceService.attach(mission,new BatchChecklistEvidenceRequest(media,rows.stream().map(r->new BatchChecklistEvidenceRequest.Target(r.getId(),r.getVersion())).toList(),null)).size());
    }
    @Test void concurrentDuplicateAttachIsIdempotentAndConsumesOneVersion() throws Exception {
        String mission=mission(1),media=asset(mission,MediaStatus.PENDING_MANAGER_APPROVAL);var request=attachRequest(mission,media);
        var outcomes=race(()->evidenceService.attach(mission,request));assertTrue(outcomes.stream().allMatch(List.class::isInstance));
        assertEquals(1,row(mission).getEvidence().size());assertEquals(1L,row(mission).getVersion());
    }
    @Test void rejectionInvalidatesSubmittedPackageAndExplicitResultRejectReopens() throws Exception {
String mission=mission(1),media=asset(mission,MediaStatus.PENDING_MANAGER_APPROVAL);evidenceService.attach(mission,attachRequest(mission,media));complete(mission);setStatus(mission,MissionStatus.COMPLETED);
        var calls=new AtomicInteger();race(()->calls.getAndIncrement()==0?resultService.upsert(mission,new MissionResultRequest()):approvals.reject(mission,media));
        assertFalse(executionService.getByMissionId(mission).checklistEvidenceReady());
        var jdbc=new JdbcTemplate(dataSource);var pending=jdbc.queryForList("select id from mission_results where mission_id=? and approval_status='PENDING_MANAGER_APPROVAL'",String.class,mission);
        if(!pending.isEmpty()) {assertThrows(ApiException.class,()->evidenceService.detach(mission,row(mission).getId(),row(mission).getEvidence().getFirst().getEvidenceId(),row(mission).getVersion(),null));resultService.reject(pending.getFirst(),new MissionResultReviewRequest());}
        var current=row(mission);evidenceService.detach(mission,current.getId(),current.getEvidence().getFirst().getEvidenceId(),current.getVersion(),"replace");assertTrue(row(mission).getEvidence().isEmpty());
    }
    @Test void detachAndSubmitSerializeWithoutSilentlyApprovingIncompletePackage() throws Exception {
        String mission=mission(1),media=asset(mission,MediaStatus.PENDING_MANAGER_APPROVAL);evidenceService.attach(mission,attachRequest(mission,media));complete(mission);setStatus(mission,MissionStatus.COMPLETED);
        var row=row(mission);var calls=new AtomicInteger();var outcomes=race(()->{if(calls.getAndIncrement()==0)return resultService.upsert(mission,new MissionResultRequest());evidenceService.detach(mission,row.getId(),row.getEvidence().getFirst().getEvidenceId(),row.getVersion(),null);return "detached";});
        assertEquals(1,outcomes.stream().filter(ApiException.class::isInstance).count());
    }
    @Test void finalResultApprovalRequiresPerMediaApprovalAndDoesNotPromoteMediaImplicitly() {
        String mission=mission(1),media=asset(mission,MediaStatus.PENDING_MANAGER_APPROVAL);evidenceService.attach(mission,attachRequest(mission,media));complete(mission);setStatus(mission,MissionStatus.COMPLETED);
        var result=resultService.upsert(mission,new MissionResultRequest());
        assertThrows(ApiException.class, () -> resultService.approve(result.getId(),new MissionResultReviewRequest()));
        assertEquals(MediaStatus.PENDING_MANAGER_APPROVAL,mediaRepository.findById(media).orElseThrow().getMediaStatus());
        approvals.approve(mission, media);
        assertEquals(MissionResultApprovalStatus.APPROVED,resultService.approve(result.getId(),new MissionResultReviewRequest()).getApprovalStatus());
    }
    @Configuration @Import(MissionChecklistPostgresTest.ExecutionConfig.class)
    static class EvidenceConfig {

        @Bean com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard mediaLock(MediaAssetRepository media,MissionRepository missions,MissionResultRepository results,MissionChecklistEvidenceRepository evidence) {return new com.ondemandmonitoring.mission.service.impl.MissionMediaEvidenceGuardImpl(media,missions,results,evidence);}
        @Bean IManagerMediaApprovalService approvalService(MediaAssetRepository media,IMediaObjectStorage storage,com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard lock) {
            return new ManagerMediaApprovalServiceImpl(media,storage,org.mapstruct.factory.Mappers.getMapper(MediaWorkflowMapper.class),lock);
        }
    }
}
