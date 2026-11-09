package com.ondemandmonitoring.checklist.config;

import com.ondemandmonitoring.checklist.domain.*;
import com.ondemandmonitoring.checklist.repository.*;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChecklistDefaultSeedInitializerTest {
    ServiceRepository services = mock(ServiceRepository.class);
    ChecklistDefinitionRepository definitions = mock(ChecklistDefinitionRepository.class);
    ServiceChecklistRepository assignments = mock(ServiceChecklistRepository.class);
    ChecklistDefaultSeedInitializer seed = new ChecklistDefaultSeedInitializer(services, definitions, assignments);
    Map<String, ChecklistDefinition> catalog = new LinkedHashMap<>();
    Map<String, List<ServiceChecklist>> templates = new HashMap<>();
    Service service;

    @BeforeEach void setup() {
        service = Service.builder().name("Giám sát công trình").build(); service.setId("s");
        when(services.findAll()).thenReturn(List.of(service));
        when(services.findByIdForUpdate("s")).thenReturn(Optional.of(service));
        when(definitions.findBySeedCode(anyString())).thenAnswer(call -> Optional.ofNullable(catalog.get(call.getArgument(0))));
        when(definitions.findByNormalizedContent(anyString())).thenAnswer(call -> catalog.values().stream().filter(row -> row.getNormalizedContent().equals(call.getArgument(0))).findFirst());
        when(definitions.saveAndFlush(any())).thenAnswer(call -> {
            ChecklistDefinition row = call.getArgument(0); if (row.getId() == null) row.setId(UUID.randomUUID().toString());
            catalog.put(row.getSeedCode(), row); return row;
        });
        when(assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc(anyString())).thenAnswer(call -> templates.getOrDefault(call.getArgument(0), List.of()));
        when(assignments.save(any())).thenAnswer(call -> {
            ServiceChecklist row = call.getArgument(0); templates.computeIfAbsent(row.getService().getId(), id -> new ArrayList<>()).add(row); return row;
        });
    }

    @Test void bootstrapIsIdempotentAndDoesNotCreateServices() {
        seed.run(null); seed.run(null);
        assertEquals(21, catalog.size()); assertEquals(2, templates.get("s").size());
        assertEquals(java.util.stream.IntStream.range(0, 2).boxed().toList(), templates.get("s").stream().map(ServiceChecklist::getDisplayOrder).toList());
        assertTrue(catalog.values().stream().allMatch(row -> row.getDescription() != null
                && !row.getExecutionSteps().isEmpty() && !row.getInputRequirements().isEmpty() && !row.getExpectedOutputs().isEmpty()));
        assertTrue(service.getChecklistDefaultsInitialized());
        verify(definitions, times(21)).saveAndFlush(any());
        verify(services).save(service);
    }

    @Test void restartKeepsEditedInactiveDefinitionsAndRestoresEmptySupportedTemplate() {
        seed.run(null);
        var edited = catalog.get(ChecklistDefaults.code(1)); edited.setContent("Admin edited"); edited.setIsActive(false);
        templates.get("s").clear(); seed.run(null);
        assertEquals("Admin edited", catalog.get(ChecklistDefaults.code(1)).getContent());
        assertFalse(catalog.get(ChecklistDefaults.code(1)).getIsActive());
        assertEquals(2, templates.get("s").size()); assertEquals(21, catalog.size());
    }

    @Test void restartKeepsGuidanceWrittenByAdministrator() {
        seed.run(null);
        var edited = catalog.get(ChecklistDefaults.code(2)); edited.setDescription("Admin text"); edited.setExecutionSteps(List.of("Own step"));
        seed.run(null);
        assertEquals("Admin text", catalog.get(ChecklistDefaults.code(2)).getDescription());
        assertEquals(List.of("Own step"), catalog.get(ChecklistDefaults.code(2)).getExecutionSteps());
    }

    @Test void preexistingTemplateIsNotChangedAndGetsMarker() {
        var custom = new ServiceChecklist(); custom.setDisplayOrder(7);
        templates.put("s", new ArrayList<>(List.of(custom))); seed.run(null);
        assertEquals(List.of(custom), templates.get("s")); assertEquals(7, custom.getDisplayOrder());
        assertTrue(service.getChecklistDefaultsInitialized()); verify(assignments, never()).save(any());
    }

    @Test void missingAndAmbiguousServicesAreSkipped() {
        when(services.findAll()).thenReturn(List.of()); seed.run(null);
        assertEquals(21, catalog.size()); verify(assignments, never()).save(any());
        var duplicate = Service.builder().name(service.getName().toUpperCase(Locale.ROOT)).build(); duplicate.setId("other");
        when(services.findAll()).thenReturn(List.of(service, duplicate)); seed.run(null);
        verify(services, never()).findByIdForUpdate(anyString());
    }

    @Test void reusesNormalizedDefinitionWithoutReactivatingIt() {
        var existing = new ChecklistDefinition(); existing.setId("existing"); existing.setContent(ChecklistDefaults.CONTENTS.getFirst());
        existing.setNormalizedContent(existing.getContent().toLowerCase(Locale.ROOT)); existing.setIsActive(false);
        when(definitions.findByNormalizedContent(existing.getNormalizedContent())).thenReturn(Optional.of(existing));
        seed.run(null); assertSame(existing, catalog.get(ChecklistDefaults.code(1))); assertFalse(existing.getIsActive());
        assertEquals(2, templates.get("s").size());
    }

    @Test void inactiveAndRenamedServicesDoNotReceiveWrongMappings() {
        service.setIsActive(false); seed.run(null); assertFalse(templates.containsKey("s"));
        service.setName("Admin-specific custom service"); service.setChecklistDefaultsInitialized(false); service.setIsActive(true);
        seed.run(null); assertFalse(templates.containsKey("s"));
    }
}
