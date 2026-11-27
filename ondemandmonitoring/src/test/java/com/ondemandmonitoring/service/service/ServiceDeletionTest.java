package com.ondemandmonitoring.service.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.mapper.ServiceMapper;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.service.service.impl.ServiceServiceImpl;
import com.ondemandmonitoring.service.controller.ServiceController;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ServiceDeletionTest {
    ServiceRepository repository = mock(ServiceRepository.class);
    IServiceImageService images = mock(IServiceImageService.class);
    JdbcTemplate db = mock(JdbcTemplate.class);
    ServiceServiceImpl service = new ServiceServiceImpl(repository, mock(ServiceMapper.class), images, db);
    Service row = Service.builder().isActive(false).build();
    @BeforeEach void setup() {
        row.setId("s1"); when(repository.findByIdForUpdate("s1")).thenReturn(Optional.of(row));
    }
    @Test void locksAndDeletesInactiveUnusedServiceInsteadOfSavingInactive() {
        service.delete("s1");
        var ordered = inOrder(repository, db, images);
        ordered.verify(repository).findByIdForUpdate("s1");
        ordered.verify(db).queryForObject(anyString(), eq(Boolean.class), eq("s1"));
        ordered.verify(db).update(startsWith("DELETE FROM mission_template_items"), eq("s1"));
        ordered.verify(db).update(startsWith("DELETE FROM mission_templates"), eq("s1"));
        ordered.verify(db).update(startsWith("DELETE FROM service_checklists"), eq("s1"));
        ordered.verify(db).update(startsWith("DELETE FROM service_deliverables"), eq("s1"));
        ordered.verify(repository).delete(row); ordered.verify(repository).flush();
        ordered.verify(images).scheduleDeletion(row);
        verify(repository, never()).save(any());
    }
    @Test void anyOrderRejectsWithoutDeletingOrSchedulingStorageCleanup() {
        when(db.queryForObject(anyString(), eq(Boolean.class), eq("s1"))).thenReturn(true);
        assertEquals(ErrorCode.SERVICE_IN_USE, assertThrows(ApiException.class, () -> service.delete("s1")).getErrorCode());
        verify(repository, never()).delete(any()); verifyNoInteractions(images);
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void fkConflictBecomesServiceInUseAndDoesNotScheduleCleanup() {
        doThrow(new DataIntegrityViolationException("FK")).when(repository).flush();
        assertEquals(ErrorCode.SERVICE_IN_USE, assertThrows(ApiException.class, () -> service.delete("s1")).getErrorCode());
        verifyNoInteractions(images);
    }
    @Test void missingServiceIs404() {
        assertEquals(ErrorCode.SERVICE_NOT_FOUND, assertThrows(ApiException.class, () -> service.delete("missing")).getErrorCode());
        verifyNoInteractions(db, images);
    }
    @Test void httpConflictUsesExistingCodeEnvelopeAndLocalizedMessage() throws Exception {
        when(db.queryForObject(anyString(), eq(Boolean.class), eq("s1"))).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new ServiceController(service, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(delete("/api/services/s1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_IN_USE"))
                .andExpect(jsonPath("$.message").value(ErrorCode.SERVICE_IN_USE.getMessage()));
    }
}
