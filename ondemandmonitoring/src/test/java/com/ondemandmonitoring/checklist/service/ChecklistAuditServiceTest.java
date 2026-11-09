package com.ondemandmonitoring.checklist.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.checklist.domain.ChecklistConfigAudit;
import com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction;
import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.repository.ChecklistConfigAuditRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChecklistAuditServiceTest {
    ChecklistConfigAuditRepository repository;
    AuthenticatedUserResolver currentUser;
    ChecklistAuditService audit;
    ChecklistDefinition checklist;
    Service service;

    @BeforeEach void setup() {
        repository = mock(ChecklistConfigAuditRepository.class);
        currentUser = mock(AuthenticatedUserResolver.class);
        audit = new ChecklistAuditService(repository, currentUser);
        var admin = User.builder().fullName("Admin An").build(); admin.setId("u1");
        when(currentUser.getCurrentUser()).thenReturn(admin);
        checklist = new ChecklistDefinition(); checklist.setId("c1"); checklist.setContent("Kiểm tra mái");
        checklist.setBasePrice(new BigDecimal("800000")); checklist.setExecutionSteps(List.of("Bay lưới", "Chụp cận"));
        service = Service.builder().name("Kiểm tra nhà xưởng").build(); service.setId("s1");
    }

    ChecklistConfigAudit saved() {
        var captor = ArgumentCaptor.forClass(ChecklistConfigAudit.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test void updateRecordsOnlyTheFieldsThatChangedWithActorAndNames() {
        var before = audit.snapshot(checklist);
        checklist.setBasePrice(new BigDecimal("900000"));
        checklist.setDescription("Phạm vi mới");
        audit.record(ChecklistConfigAuditAction.CHECKLIST_UPDATED, checklist, null, before, audit.snapshot(checklist));
        var entry = saved();
        assertEquals(ChecklistConfigAuditAction.CHECKLIST_UPDATED, entry.getAction());
        assertEquals("u1", entry.getActorId());
        assertEquals("Admin An", entry.getActorName());
        assertEquals("Kiểm tra mái", entry.getChecklistContent());
        assertTrue(entry.getChanges().contains("basePrice"));
        assertTrue(entry.getChanges().contains("900000"));
        assertTrue(entry.getChanges().contains("description"));
        assertFalse(entry.getChanges().contains("executionSteps"), "unchanged fields are not listed");
    }

    @Test void anUpdateThatChangesNothingWritesNothing() {
        var before = audit.snapshot(checklist);
        audit.record(ChecklistConfigAuditAction.CHECKLIST_UPDATED, checklist, null, before, audit.snapshot(checklist));
        verify(repository, never()).save(any());
    }

    @Test void trailingZerosAndBlankTextAreNotTreatedAsChanges() {
        var before = audit.snapshot(checklist);
        checklist.setBasePrice(new BigDecimal("800000.00"));
        checklist.setDescription("   ");
        audit.record(ChecklistConfigAuditAction.CHECKLIST_UPDATED, checklist, null, before, audit.snapshot(checklist));
        verify(repository, never()).save(any());
    }

    @Test void serviceLinkChangesKeepTheServiceName() {
        var link = new ServiceChecklist(); link.setService(service); link.setChecklist(checklist);
        var before = audit.snapshot(link);
        link.setRequired(true); link.setBasePriceOverride(new BigDecimal("700000"));
        audit.record(ChecklistConfigAuditAction.SERVICE_CHECKLIST_UPDATED, checklist, service, before, audit.snapshot(link));
        var entry = saved();
        assertEquals("s1", entry.getServiceId());
        assertEquals("Kiểm tra nhà xưởng", entry.getServiceName());
        assertTrue(entry.getChanges().contains("required"));
        assertTrue(entry.getChanges().contains("basePriceOverride"));
    }

    @Test void eventsWithoutFieldsAreStillRecorded() {
        audit.record(ChecklistConfigAuditAction.SERVICE_CHECKLIST_REMOVED, checklist, service);
        var entry = saved();
        assertEquals(ChecklistConfigAuditAction.SERVICE_CHECKLIST_REMOVED, entry.getAction());
        assertNull(entry.getChanges());
    }

    @Test void createdListsTheInitialValues() {
        audit.record(ChecklistConfigAuditAction.CHECKLIST_CREATED, checklist, null, Map.of(), audit.snapshot(checklist));
        var entry = saved();
        assertTrue(entry.getChanges().contains("content"));
        assertTrue(entry.getChanges().contains("Kiểm tra mái"));
    }

    @Test void aMissingSignedInUserDoesNotBlockTheChange() {
        when(currentUser.getCurrentUser()).thenThrow(new ApiException(com.ondemandmonitoring.common.exception.ErrorCode.UNAUTHORIZED));
        audit.record(ChecklistConfigAuditAction.CHECKLIST_DEACTIVATED, checklist, null);
        var entry = saved();
        assertNull(entry.getActorId());
        assertEquals("system", entry.getActorName());
    }

    @Test void listRejectsAbusivePaging() {
        assertThrows(ApiException.class, () -> audit.list(null, null, 0, 101));
        assertThrows(ApiException.class, () -> audit.list(null, null, -1, 20));
    }
}
