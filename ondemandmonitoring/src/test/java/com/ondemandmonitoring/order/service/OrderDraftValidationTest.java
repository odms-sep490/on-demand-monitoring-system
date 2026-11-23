package com.ondemandmonitoring.order.service;
import com.ondemandmonitoring.order.service.impl.OrderService;
import com.ondemandmonitoring.order.dto.request.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.mapper.OrderMapper;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.service.repository.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import java.util.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderDraftValidationTest {
    final OrderRepository repository=mock(OrderRepository.class);
    final ServiceRepository services=mock(ServiceRepository.class);
    final DeliverableTypeRepository types=mock(DeliverableTypeRepository.class);
    final ServiceDeliverableRepository associated=mock(ServiceDeliverableRepository.class);
    final PreferredTimeRepository times=mock(PreferredTimeRepository.class);
    final AuthenticatedUserResolver users=mock(AuthenticatedUserResolver.class);
    final IOrderChecklistSnapshotService checklists=mock(IOrderChecklistSnapshotService.class);
    final OrderService service=new OrderService(repository,services,types,associated,times,users,
        mock(com.ondemandmonitoring.user.repository.CustomerProfileRepository.class),Mappers.getMapper(OrderMapper.class),checklists,
        mock(com.ondemandmonitoring.review.service.OrderReviewRecordInitializer.class),
        mock(com.ondemandmonitoring.finance.repository.QuoteRepository.class),mock(com.ondemandmonitoring.finance.repository.InvoiceRepository.class));
    OrderCreateRequest valid() {
        var user=com.ondemandmonitoring.user.domain.User.builder().fullName("Customer").build();user.setId("customer");
        when(users.getCurrentUser()).thenReturn(user);
        var catalog=com.ondemandmonitoring.service.domain.Service.builder().name("Catalog service").isActive(true).build();catalog.setId("service");
        when(services.findByIdForUpdate("service")).thenReturn(Optional.of(catalog));
        var time=new com.ondemandmonitoring.warehouse.domain.PreferredTime();time.setId("time");when(times.findById("time")).thenReturn(Optional.of(time));
        var type=new com.ondemandmonitoring.service.domain.DeliverableType();type.setId("photo");when(types.findById("photo")).thenReturn(Optional.of(type));
        when(associated.existsByServiceIdAndDeliverableTypeId("service","photo")).thenReturn(true);
        var checklist=new OrderChecklistItemRequest();checklist.setSourceChecklistId("catalog-checklist");
        checklist.setExpectedChecklistVersion(2L);checklist.setRequestedImageCount(4);checklist.setRequestedVideoCount(0);
        return OrderCreateRequest.builder().serviceId("service").preferredTimeId("time")
            .flightPurpose(OrderFlightPurpose.CONSTRUCTION_MONITORING).siteContactName("Customer").siteContactPhone("0901234567").accessNotes("Gate")
            .checklistItems(List.of(checklist))
            .preferredDateFrom(LocalDate.now().plusDays(10)).preferredDateTo(LocalDate.now().plusDays(10))
            .latitude(10.7765).longitude(106.7005)
            .coverageArea(Map.of("type","Polygon","coordinates",List.of(List.of(List.of(106.700,10.776),List.of(106.701,10.776),List.of(106.701,10.777),List.of(106.700,10.777),List.of(106.700,10.776)))))
            .monitoringRelationship(MonitoringRelationship.OWNER).monitoringAuthorizationConfirmed(true).termsAccepted(true).dataRetentionDays(30)
            .deliverables(List.of(OrderDeliverableRequest.builder().deliverableTypeId("photo").requirement(Map.of("quantity",4)).build())).build();
    }
    @Test void validDraftIsReadyWithoutCreatingAnything() {
        var request=valid();var result=service.validateDraftForReview(request);
        assertTrue(result.readyForReview(),result.invalidFields().toString());assertSame(request,result.canonicalDraft());
        verify(checklists).validateSelection(any(),eq(request.getChecklistItems()));
        verify(repository,never()).save(any());verify(repository,never()).flush();verify(checklists,never()).capture(any(),any());
    }
    @Test void incompleteDraftReturnsFieldsWithoutRepositoryWrites() {
        var result=service.validateDraftForReview(new OrderCreateRequest());assertFalse(result.readyForReview());assertNull(result.canonicalDraft());
        assertTrue(result.invalidFields().containsKey("serviceId"));verifyNoInteractions(checklists,repository);
    }
    @Test void staleChecklistCannotBeReady() {
        var request=valid();doThrow(new com.ondemandmonitoring.common.exception.ApiException(com.ondemandmonitoring.common.exception.ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED))
            .when(checklists).validateSelection(any(),any());
        assertFalse(service.validateDraftForReview(request).readyForReview());verify(repository,never()).save(any());
    }
    @Test void missingAuthorizationPointsToSiteStepRatherThanReview() {
        var request=valid();request.setMonitoringAuthorizationConfirmed(false);
        var result=service.validateDraftForReview(request);
        assertFalse(result.readyForReview());assertEquals(4,result.fieldSteps().get("monitoringAuthorizationConfirmed"));
        verify(repository,never()).save(any());
    }
    @Test void unacceptedTermsPointToTermsField() {
        var request=valid();request.setTermsAccepted(false);
        var result=service.validateDraftForReview(request);
        assertFalse(result.readyForReview());assertEquals(6,result.fieldSteps().get("termsAccepted"));
        verify(repository,never()).save(any());
    }
    @Test void genericChecklistFailureStillPointsToChecklistStep() {
        var request=valid();doThrow(new com.ondemandmonitoring.common.exception.ApiException(com.ondemandmonitoring.common.exception.ErrorCode.INVALID_REQUEST,"Duplicate source checklist"))
            .when(checklists).validateSelection(any(),any());
        var result=service.validateDraftForReview(request);
        assertFalse(result.readyForReview());assertEquals(3,result.fieldSteps().get("checklistItems"));
        verify(repository,never()).save(any());
    }
}
