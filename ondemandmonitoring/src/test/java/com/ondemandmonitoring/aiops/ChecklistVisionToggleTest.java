package com.ondemandmonitoring.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime;
import com.ondemandmonitoring.aiops.service.impl.AiOperationsServiceImpl;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChecklistVisionToggleTest {
    @Test void modelSwitchAlsoUpdatesTheInspectorGate() {
        var db = mock(JdbcTemplate.class);
        var users = mock(AuthenticatedUserResolver.class);
        when(users.getCurrentUserId()).thenReturn("admin");
        var service = spy(new AiOperationsServiceImpl(db, new ObjectMapper(), users));
        when(db.update(startsWith("UPDATE ai_models SET enabled"), anyBoolean(), anyString(), anyString())).thenReturn(1);
        var model = mock(com.ondemandmonitoring.aiops.dto.AiOperationsDtos.AiModelView.class);
        when(model.code()).thenReturn(ChecklistVisionRuntime.MODEL_CODE);
        doReturn(List.of(model)).when(service).listModels();
        org.junit.jupiter.api.Assertions.assertSame(model,
                service.toggleModel(ChecklistVisionRuntime.MODEL_CODE, false));
        verify(db).update(contains("UPDATE checklist_image_ai_settings"), eq(false), eq("admin"));
    }
}
