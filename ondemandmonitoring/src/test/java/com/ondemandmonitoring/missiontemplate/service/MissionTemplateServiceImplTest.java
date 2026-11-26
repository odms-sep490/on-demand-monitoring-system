package com.ondemandmonitoring.missiontemplate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.missiontemplate.domain.MissionTemplate;
import com.ondemandmonitoring.missiontemplate.domain.MissionTemplateItem;
import com.ondemandmonitoring.missiontemplate.dto.MissionTemplateRequest;
import com.ondemandmonitoring.missiontemplate.repository.MissionTemplateItemRepository;
import com.ondemandmonitoring.missiontemplate.repository.MissionTemplateRepository;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MissionTemplateServiceImplTest {
    ServiceRepository services;
    ChecklistDefinitionRepository checklists;
    MissionTemplateRepository templates;
    MissionTemplateItemRepository items;
    MissionTemplateServiceImpl service;
    Service parent;
    ChecklistDefinition checklist;

    @BeforeEach
    void setup() {
        services = mock(ServiceRepository.class);
        checklists = mock(ChecklistDefinitionRepository.class);
        templates = mock(MissionTemplateRepository.class);
        items = mock(MissionTemplateItemRepository.class);
        service = new MissionTemplateServiceImpl(services, checklists, templates, items);
        parent = Service.builder().name("Service").build(); parent.setId("s1");
        checklist = new ChecklistDefinition(); checklist.setId("c1");
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(parent));
        when(services.existsById("s1")).thenReturn(true);
        when(checklists.findAllById(any())).thenReturn(List.of(checklist));
        when(templates.saveAndFlush(any())).thenAnswer(call -> {
            MissionTemplate template = call.getArgument(0);
            if (template.getId() == null) template.setId("mt1");
            return template;
        });
    }

    @Test
    void createsTemplateWithChecklistItems() {
        var response = service.create("s1", new MissionTemplateRequest("North area", List.of("c1")));
        assertEquals("mt1", response.id());
        assertEquals("North area", response.scopeLabel());
        assertEquals(List.of("c1"), response.checklistIds());
        verify(items).saveAllAndFlush(any());
    }

    @Test
    void rejectsChecklistAssignedToAnotherTemplateInTheSameService() {
        when(items.countOverlappingChecklistsExcludingTemplate(eq("s1"), any(), any()))
                .thenReturn(1L);

        var error = assertThrows(ApiException.class,
                () -> service.create("s1", new MissionTemplateRequest("North area", List.of("c1"))));

        assertEquals(ErrorCode.MISSION_TEMPLATE_CHECKLIST_OVERLAP, error.getErrorCode());
    }

    @Test
    void updateExcludesTheTemplateBeingEditedFromOverlapCheck() {
        var template = new MissionTemplate();
        template.setId("mt1");
        template.setService(parent);
        template.setScopeLabel("Old scope");
        when(templates.findByIdAndServiceId("mt1", "s1")).thenReturn(Optional.of(template));

        var response = service.update(
                "s1", "mt1", new MissionTemplateRequest("Updated scope", List.of("c1")));

        assertEquals("Updated scope", response.scopeLabel());
        verify(items).countOverlappingChecklistsExcludingTemplate(eq("s1"), eq("mt1"), any());
    }

    @Test
    void readsTemplatesAndTheirChecklistIds() {
        var template = new MissionTemplate(); template.setId("mt1"); template.setService(parent);
        template.setScopeLabel("North area");
        var item = new MissionTemplateItem(); item.setMissionTemplate(template); item.setChecklistDefinition(checklist);
        when(templates.findAllByServiceIdOrderByCreatedAtAscIdAsc("s1")).thenReturn(List.of(template));
        when(items.findAllByMissionTemplateIdIn(List.of("mt1"))).thenReturn(List.of(item));
        assertEquals(List.of("c1"), service.getAll("s1").getFirst().checklistIds());
    }

    @Test
    void deletesItemsBeforeTemplate() {
        var template = new MissionTemplate(); template.setId("mt1"); template.setService(parent);
        var item = new MissionTemplateItem();
        when(templates.findByIdAndServiceId("mt1", "s1")).thenReturn(Optional.of(template));
        when(items.findAllByMissionTemplateId("mt1")).thenReturn(List.of(item));
        service.delete("s1", "mt1");
        verify(items).deleteAll(List.of(item));
        verify(templates).delete(template);
    }
}
