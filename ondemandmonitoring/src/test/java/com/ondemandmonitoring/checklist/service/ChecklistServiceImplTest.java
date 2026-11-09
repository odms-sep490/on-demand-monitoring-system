package com.ondemandmonitoring.checklist.service;

import com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction;
import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.checklist.service.ChecklistAuditService;
import java.util.List;
import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.mapper.ChecklistMapper;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.service.impl.ChecklistServiceImpl;
import com.ondemandmonitoring.common.exception.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChecklistServiceImplTest {
    ChecklistDefinitionRepository repository;
    ChecklistServiceImpl service;
    @BeforeEach void setup() {
        repository = mock(ChecklistDefinitionRepository.class);
        service = new ChecklistServiceImpl(repository, Mappers.getMapper(ChecklistMapper.class));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }
    ChecklistRequest request(String content) {
        var request = new ChecklistRequest(); request.setContent(content); return request;
    }
    ChecklistDefinition entity() {
        var entity = new ChecklistDefinition(); entity.setId("c1"); entity.setContent("Old"); return entity;
    }
    @Test void createNormalizesWhitespaceAndPreservesAccents() {
        var response = service.create(request("  Kiểm  tra\nLối thoát  "));
        assertEquals("Kiểm tra Lối thoát", response.getContent());
        assertTrue(response.getIsActive());
        verify(repository).existsByNormalizedContent("kiểm tra lối thoát");
    }
    @Test void invalidContentRejectedWithoutSave() {
        for (String content : new String[]{null, "  ", "x".repeat(501)}) {
            assertThrows(ApiException.class, () -> service.create(request(content)));
        }
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void unicodeNormalizationIsCanonicalAndLowercaseCanExpand() {
        assertEquals("Kiểm tra", service.create(request("Kie\u0302\u0309m tra")).getContent());
        assertEquals(500, service.create(request("\u0130".repeat(500))).getContent().length());
    }
    @Test void duplicateIncludesInactiveCatalog() {
        when(repository.existsByNormalizedContent("check exits")).thenReturn(true);
        assertEquals(ErrorCode.CHECKLIST_ALREADY_EXISTS,
                assertThrows(ApiException.class, () -> service.create(request("Check exits"))).getErrorCode());
    }
    @Test void updateChecksDuplicateExcludingSelf() {
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(entity()));
        assertEquals("New", service.update("c1", request(" New ")).getContent());
        verify(repository).existsByNormalizedContentAndIdNot("new", "c1");
    }
    @Test void guidanceIsStoredTrimmedInOrderAndBlankStepsAreDropped() {
        var request = request("Roof");
        request.setDescription("  What it covers  ");
        request.setExecutionSteps(java.util.Arrays.asList(" Step one ", "", "Step two", null));
        request.setInputRequirements(java.util.List.of("Clear weather"));
        request.setExpectedOutputs(java.util.List.of("Photos"));
        var response = service.create(request);
        assertEquals("What it covers", response.getDescription());
        assertEquals(java.util.List.of("Step one", "Step two"), response.getExecutionSteps());
        assertEquals(java.util.List.of("Clear weather"), response.getInputRequirements());
        assertEquals(java.util.List.of("Photos"), response.getExpectedOutputs());
    }
    @Test void updateKeepsAbsentGuidanceAndClearsEmptyGuidance() {
        var entity = entity();
        entity.setDescription("Keep"); entity.setExecutionSteps(java.util.List.of("Old step"));
        entity.setExpectedOutputs(java.util.List.of("Old output"));
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(entity));
        var request = request("Roof");
        request.setExpectedOutputs(java.util.List.of());
        var response = service.update("c1", request);
        assertEquals("Keep", response.getDescription());
        assertEquals(java.util.List.of("Old step"), response.getExecutionSteps());
        assertNull(response.getExpectedOutputs());
    }
    @Test void responsesCarryHowManyServicesAndOrdersUseTheChecklist() {
        var serviceUsage = mock(ServiceChecklistRepository.class);
        var orderUsage = mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.class);
        service.setUsage(serviceUsage, orderUsage);
        var row = mock(ServiceChecklistRepository.UsageCount.class);
        when(row.getChecklistId()).thenReturn("c1"); when(row.getTotal()).thenReturn(3L);
        var orderRow = mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.UsageCount.class);
        when(orderRow.getChecklistId()).thenReturn("c1"); when(orderRow.getTotal()).thenReturn(12L);
        when(serviceUsage.countByChecklistIds(any())).thenReturn(List.of(row));
        when(orderUsage.countBySourceChecklistIds(any())).thenReturn(List.of(orderRow));
        when(repository.findById("c1")).thenReturn(Optional.of(entity()));
        var response = service.getById("c1");
        assertEquals(3L, response.getUsedByServiceCount());
        assertEquals(12L, response.getUsedByOrderCount());
    }
    @Test void updateAndStatusChangesAreWrittenToTheHistory() {
        var audit = mock(ChecklistAuditService.class);
        service.setAudit(audit);
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(entity()));
        service.update("c1", request("New"));
        verify(audit).record(eq(ChecklistConfigAuditAction.CHECKLIST_UPDATED), any(), isNull(), any(), any());
        service.updateStatus("c1", false);
        verify(audit).record(eq(ChecklistConfigAuditAction.CHECKLIST_DEACTIVATED), any(), isNull());
    }
    @Test void duplicateUpdateRejected() {
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(entity()));
        when(repository.existsByNormalizedContentAndIdNot("new", "c1")).thenReturn(true);
        assertThrows(ApiException.class, () -> service.update("c1", request("New")));
    }
    @Test void deactivateAndReactivateKeepCatalogIdentity() {
        var entity = entity();
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(entity));
        assertFalse(service.updateStatus("c1", false).getIsActive());
        assertTrue(service.updateStatus("c1", true).getIsActive());
        assertEquals("c1", entity.getId());
        verify(repository, never()).delete(any(ChecklistDefinition.class));
    }
    @Test void missingChecklistReturns404() {
        assertEquals(ErrorCode.CHECKLIST_NOT_FOUND,
                assertThrows(ApiException.class, () -> service.getById("missing")).getErrorCode());
    }
    @Test void listWithoutSearchWorksAndAddsStableSort() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        assertEquals(0, service.getAll(null, null, PageRequest.of(0, 20)).getTotalItems());
        verify(repository).findAll(any(Specification.class),
                eq(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))));
    }
    @Test void invalidPageSizeAndSortRejected() {
        assertThrows(ApiException.class, () -> service.getAll(null, null, PageRequest.of(0, 101)));
        assertThrows(ApiException.class, () -> service.getAll(null, null, PageRequest.of(0, 20, Sort.by("[]"))));
    }
}
