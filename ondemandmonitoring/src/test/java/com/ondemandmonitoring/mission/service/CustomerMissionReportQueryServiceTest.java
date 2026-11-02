package com.ondemandmonitoring.mission.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.CustomerMissionReportMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.CustomerMissionReportQueryService;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.*;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerMissionReportQueryServiceTest {
    final OrderRepository orders = mock(OrderRepository.class);
    final MissionRepository missions = mock(MissionRepository.class);
    final MissionResultRepository results = mock(MissionResultRepository.class);
    final MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
    final AuthenticatedUserResolver actor = mock(AuthenticatedUserResolver.class);
    final CustomerMissionReportQueryService service = new CustomerMissionReportQueryService(orders, missions, results,
            executions, actor, Mappers.getMapper(CustomerMissionReportMapper.class));
    Order order;
    User customer;
    Mission first, second;

    @BeforeEach void setup() {
        customer = User.builder().isActive(true).role(Role.builder().code(RoleCode.CUSTOMER).active(true).build()).build();
        customer.setId("a"); when(actor.getCurrentUser()).thenReturn(customer);
        order = Order.builder().customer(customer).build(); order.setId("oa");
        when(orders.findById("oa")).thenReturn(Optional.of(order));
        first = mission("m1"); second = mission("m2");
    }
    Mission mission(String id) {
        var m = new Mission(); m.setId(id); m.setMissionCode(id.toUpperCase()); m.setOrder(order);
        m.setScopeLabel("Scope " + id); m.setStatus(MissionStatus.COMPLETED);
        when(missions.findById(id)).thenReturn(Optional.of(m)); return m;
    }
    MissionResult result(Mission m, MissionResultApprovalStatus approval) {
        var r = new MissionResult(); r.setId("result-" + m.getId()); r.setMission(m);
        r.setApprovalStatus(approval); r.setSummary("Summary " + m.getId());
        r.setReviewNote("INTERNAL_REVIEW"); r.setNotes("INTERNAL_NOTES");
        when(results.findByMissionId(m.getId())).thenReturn(Optional.of(r)); return r;
    }
    MissionChecklistExecution execution(Mission m, String content) {
        var snapshot = new OrderChecklistItem(); snapshot.setId(content); snapshot.setContent(content); snapshot.setOrder(order);
        snapshot.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        var a = new MissionChecklistAssignment(); a.setId(m.getId() + content); a.setMissionId(m.getId());
        a.setOrderId(order.getId()); a.setOrderChecklistItemId(content);
        var e = new MissionChecklistExecution(); e.setId("execution-" + a.getId()); e.setMissionId(m.getId());
        e.setOrderId(order.getId()); e.setOrderChecklistItemId(content); e.setOrderChecklistItem(snapshot);
        e.setMissionChecklistAssignmentId(a.getId()); e.setMissionChecklistAssignment(a);
        e.setExecutionStatus(ChecklistExecutionStatus.COMPLETED); e.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
        return e;
    }
    @Test void noResultIsUnavailableAndDoesNotReadChecklist() {
        var response = service.report("oa", "m1");
        assertFalse(response.reportAvailable()); assertNull(response.result()); assertTrue(response.checklistItems().isEmpty());
        verifyNoInteractions(executions);
    }
    @ParameterizedTest @EnumSource(value=MissionResultApprovalStatus.class, names={"DRAFT","PENDING_MANAGER_APPROVAL","REJECTED"})
    void unpublishedStatesNeverExposeContentOrChecklist(MissionResultApprovalStatus approval) throws Exception {
        result(first, approval);
        var response = service.report("oa", "m1");
        assertFalse(response.reportAvailable()); assertNull(response.result()); assertTrue(response.checklistItems().isEmpty());
        assertFalse(new ObjectMapper().writeValueAsString(response).contains("INTERNAL")); verifyNoInteractions(executions);
    }
    @Test void approvedReportUsesWhitelistAndImmutableMissionScopedSnapshots() throws Exception {
        result(first, MissionResultApprovalStatus.APPROVED); result(second, MissionResultApprovalStatus.APPROVED);
        when(executions.findOrderedByMissionId("m1")).thenReturn(List.of(execution(first,"A"),execution(first,"C"),execution(first,"E")));
        var unable = execution(second,"E"); unable.setExecutionStatus(ChecklistExecutionStatus.UNABLE_TO_VERIFY);
        unable.setUnableToVerifyReason("No clear view");
        when(executions.findOrderedByMissionId("m2")).thenReturn(List.of(execution(second,"B"),execution(second,"D"),unable));
        var r1 = service.report("oa","m1"); var r2 = service.report("oa","m2");
        assertEquals(List.of("A","C","E"),r1.checklistItems().stream().map(i -> i.content()).toList());
        assertEquals(List.of("B","D","E"),r2.checklistItems().stream().map(i -> i.content()).toList());
        assertNotEquals(r1.checklistItems().getLast().executionId(),r2.checklistItems().getLast().executionId());
        assertEquals("No clear view",r2.checklistItems().getLast().unableToVerifyReason());
        assertEquals(ChecklistAssessmentStatus.NON_COMPLIANT,r1.checklistItems().getFirst().assessment());
        var json = new ObjectMapper().writeValueAsString(r1);
        for (String hidden : List.of("INTERNAL","approvalStatus","reviewNote","mediaFiles","version","staffAssignments","executionPlanRevision"))
            assertFalse(json.contains(hidden),hidden);
    }
    @Test void approvedEmptyChecklistIsValidAndReadsNeverWrite() {
        result(first,MissionResultApprovalStatus.APPROVED);
        assertTrue(service.report("oa","m1").reportAvailable());
        assertTrue(service.report("oa","m1").checklistItems().isEmpty());
        verify(missions,never()).save(any()); verify(results,never()).save(any()); verify(executions,never()).save(any());
        verify(missions,never()).findByIdForUpdate(any()); verify(orders,never()).findByIdForUpdate(any());
    }
    @Test void crossCustomerAndMismatchedPairDeniedBeforeReadingReports() {
        var other = User.builder().build(); other.setId("b");
        var foreign = Order.builder().customer(other).build(); foreign.setId("ob");
        when(orders.findById("ob")).thenReturn(Optional.of(foreign)); second.setOrder(foreign);
        assertThrows(ApiException.class,() -> service.report("ob","m2"));
        assertThrows(ApiException.class,() -> service.list("ob"));
        assertThrows(ApiException.class,() -> service.report("oa","m2"));
        verifyNoInteractions(results,executions);
    }
    @Test void inactiveOrNonCustomerActorDeniedEvenWithoutProxy() {
        customer.setIsActive(false); assertThrows(ApiException.class,() -> service.list("oa"));
        customer.setIsActive(true); customer.getRole().setCode(RoleCode.MANAGER);
        assertThrows(ApiException.class,() -> service.report("oa","m1")); verifyNoInteractions(orders,results,executions);
    }
    @Test void malformedExecutionScopeFailsClosed() {
        result(first,MissionResultApprovalStatus.APPROVED);
        when(executions.findOrderedByMissionId("m1")).thenReturn(List.of(execution(second,"B")));
        assertThrows(ApiException.class,() -> service.report("oa","m1"));
    }
    @Test void orderedCollectionContainsHistoryRelationsAndOnlyPublicAvailability() {
        first.setStatus(MissionStatus.CANCELLED); second.setReplacesMissionId("m1");
        var third = mission("m3"); third.setReplacesMissionId("m2");
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("oa")).thenReturn(List.of(first,second,third));
        when(results.findApprovedMissionIds(Set.of("m1","m2","m3"))).thenReturn(Set.of("m3"));
        var list = service.list("oa"); assertEquals(List.of("m1","m2","m3"),list.stream().map(m -> m.missionId()).toList());
        assertNull(list.getFirst().replacesMissionId()); assertEquals("m1",list.get(1).replacesMissionId());
        assertEquals("m2",list.getLast().replacesMissionId()); assertFalse(list.get(1).reportAvailable()); assertTrue(list.getLast().reportAvailable());
        result(third,MissionResultApprovalStatus.APPROVED);
        assertTrue(service.report("oa","m3").checklistItems().isEmpty());
    }
    @Test void foreignReplacementReferenceIsNotExposed() {
        first.setReplacesMissionId("outside"); assertNull(service.report("oa","m1").mission().replacesMissionId());
    }
    @Test void zeroMissionCollectionDoesNotQueryResults() {
        assertTrue(service.list("oa").isEmpty()); verifyNoInteractions(results,executions);
    }
    @Test void unpublishedReportDoesNotGateExistingAvailableOnlyMediaPolicy() {
        result(first,MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        assertFalse(service.report("oa","m1").reportAvailable());
        var access = mock(IMissionMediaAccessService.class); when(access.authorizeCustomer("m1")).thenReturn("m1");
        var media = mock(com.ondemandmonitoring.media.repository.MediaAssetRepository.class);
        var asset = new com.ondemandmonitoring.media.domain.MediaAsset(); asset.setId("X"); asset.setMissionId("m1");
        asset.setMediaStatus(MediaStatus.AVAILABLE); asset.setFileSize(42L);
        when(media.findByMissionIdAndMediaStatus(eq("m1"),eq(MediaStatus.AVAILABLE),any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(i -> new org.springframework.data.domain.PageImpl<>(List.of(asset),i.getArgument(2),1));
        var storage = mock(com.ondemandmonitoring.media.service.IMediaObjectStorage.class);
        var gallery = new com.ondemandmonitoring.media.service.impl.CustomerMediaServiceImpl(access,media,
                mock(com.ondemandmonitoring.media.repository.MediaNotificationOutboxRepository.class),storage,
                Mappers.getMapper(com.ondemandmonitoring.media.mapper.MediaWorkflowMapper.class),
                mock(com.ondemandmonitoring.media.service.IResultRetentionAccessService.class));
        assertEquals("X",gallery.listAvailablePage("m1",0,12).getItems().getFirst().getMediaId());
        verify(media).findByMissionIdAndMediaStatus(eq("m1"),eq(MediaStatus.AVAILABLE),any(org.springframework.data.domain.Pageable.class));
        verifyNoInteractions(executions);
    }
}
