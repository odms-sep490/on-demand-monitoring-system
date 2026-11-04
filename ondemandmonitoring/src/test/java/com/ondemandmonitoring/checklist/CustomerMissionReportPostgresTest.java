package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.mapper.CustomerMissionReportMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.ICustomerMissionReportQueryService;
import com.ondemandmonitoring.mission.service.impl.CustomerMissionReportQueryService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Existing isolated opt-in fixture; no project/shared database access. */
@SpringJUnitConfig(CustomerMissionReportPostgresTest.Beans.class)
class CustomerMissionReportPostgresTest extends OrderAggregateCompletionPostgresTest {
    @Autowired ICustomerMissionReportQueryService customerReports;

    @Test void publishedReadPreservesSnapshotSplitAndDatabaseState() {
        fixture(5); choose(first,0,2,4); choose(second,1,3,4); finalizePlan();
        jdbc.update("update missions set status='COMPLETED',completed_at=now() where order_id=?",orderId);
        jdbc.update("update mission_checklist_executions set execution_status='UNABLE_TO_VERIFY',unable_to_verify_reason='Cloud cover',started_at=now(),completed_at=now() where order_id=?",orderId);
        result(first,MissionResultApprovalStatus.APPROVED); String otherResult=result(second,MissionResultApprovalStatus.DRAFT);
        var owner=User.builder().isActive(true).role(Role.builder().code(RoleCode.CUSTOMER).active(true).build()).build();
        owner.setId(transaction.execute(tx -> orders.findById(orderId).orElseThrow().getCustomer().getId()));
        when(resolver.getCurrentUser()).thenReturn(owner);
        when(resolver.getCurrentUserId()).thenReturn(owner.getId());
        var before=jdbc.queryForList("select * from orders where id=?",orderId);
        var executionsBefore=jdbc.queryForList("select * from mission_checklist_executions where order_id=? order by id",orderId);
        var list=customerReports.list(orderId);
        assertEquals(2,list.size()); assertTrue(list.stream().filter(m -> m.missionId().equals(first)).findFirst().orElseThrow().reportAvailable());
        assertFalse(customerReports.report(orderId,second).reportAvailable());
        assertEquals(List.of("Requirement 0","Requirement 2","Requirement 4"),customerReports.report(orderId,first).checklistItems().stream().map(i -> i.content()).toList());
        assertEquals(before,jdbc.queryForList("select * from orders where id=?",orderId));
        assertEquals(executionsBefore,jdbc.queryForList("select * from mission_checklist_executions where order_id=? order by id",orderId));
        String available=asset(second), pending=asset(second), rejected=asset(second), sibling=asset(first);
        jdbc.update("update drone_media set media_status='AVAILABLE' where id in (?,?)",available,sibling);
        jdbc.update("update drone_media set media_status='REJECTED' where id=?",rejected);
        var mediaRepository=new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(
                org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
                .getRepository(com.ondemandmonitoring.media.repository.MediaAssetRepository.class);
        var access=new com.ondemandmonitoring.mission.service.impl.MissionMediaAccessServiceImpl(missions,
                org.mockito.Mockito.mock(MissionDeviceAssignmentRepository.class),resolver,auth);
        var gallery=new com.ondemandmonitoring.media.service.impl.CustomerMediaServiceImpl(access,mediaRepository,
                org.mockito.Mockito.
                org.mockito.Mockito.mock(com.ondemandmonitoring.media.service.IMediaObjectStorage.class),
                Mappers.getMapper(com.ondemandmonitoring.media.mapper.MediaWorkflowMapper.class),
                org.mockito.Mockito.mock(com.ondemandmonitoring.media.service.IResultRetentionAccessService.class));
        assertFalse(customerReports.report(orderId,second).reportAvailable());
        transaction.executeWithoutResult(tx -> {
            var media=gallery.listAvailablePage(second,0,12);
            assertEquals(List.of(available),media.getItems().stream().map(m -> m.getMediaId()).toList());
            for (String hidden:List.of(pending,rejected,sibling))
                assertThrows(com.ondemandmonitoring.common.exception.ApiException.class,() -> gallery.getAvailableInMission(second,hidden));
        });
        // Publication depends only on actual approval, never Order aggregate completion.
        jdbc.update("update mission_results set approval_status='APPROVED' where id=?",otherResult);
        assertEquals(List.of("Requirement 1","Requirement 3","Requirement 4"),customerReports.report(orderId,second).checklistItems().stream().map(i -> i.content()).toList());
        assertEquals(before,jdbc.queryForList("select * from orders where id=?",orderId));
    }
    @Configuration @Import(OrderAggregateCompletionPostgresTest.Beans.class)
    static class Beans {
        @Bean ICustomerMissionReportQueryService customerReports(OrderRepository orders,MissionRepository missions,
                MissionResultRepository results,MissionChecklistExecutionRepository executions,AuthenticatedUserResolver actor) {
            return new CustomerMissionReportQueryService(orders,missions,results,executions,actor,Mappers.getMapper(CustomerMissionReportMapper.class));
        }
    }
}
