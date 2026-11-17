package com.ondemandmonitoring.checklist.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.dto.request.ChecklistAssignmentRequest;
import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.dto.request.ServiceChecklistRequirementsRequest;
import com.ondemandmonitoring.checklist.mapper.ChecklistMapper;
import com.ondemandmonitoring.checklist.mapper.ServiceChecklistMapper;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.checklist.service.impl.ChecklistServiceImpl;
import com.ondemandmonitoring.checklist.service.impl.ServiceChecklistServiceImpl;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Base Checklist pricing and service-specific evidence requirements. */
class ChecklistPricingConfigurationTest {
    ChecklistDefinitionRepository repository = mock(ChecklistDefinitionRepository.class);
    ChecklistServiceImpl catalog = new ChecklistServiceImpl(repository, Mappers.getMapper(ChecklistMapper.class));
    ServiceRepository services = mock(ServiceRepository.class);
    ServiceChecklistRepository links = mock(ServiceChecklistRepository.class);
    ServiceChecklistServiceImpl assignments = new ServiceChecklistServiceImpl(services, repository, links,
            Mappers.getMapper(ServiceChecklistMapper.class));
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setup() {
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(links.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private ChecklistRequest request(String base, BigDecimal standardArea, Integer images, Integer videos) {
        var request = new ChecklistRequest();
        request.setContent("Check roof");
        request.setBasePrice(base == null ? null : new BigDecimal(base));
        request.setStandardAreaM2(standardArea);
        request.setIncludedImageCount(images);
        request.setIncludedVideoCount(videos);
        return request;
    }

    @Test
    void createStoresBasePriceAndItsStandardArea() {
        var response = catalog.create(request("200000", new BigDecimal("100"), 10, 1));
        assertEquals(0, new BigDecimal("200000").compareTo(response.getBasePrice()));
        assertEquals(0, new BigDecimal("100").compareTo(response.getStandardAreaM2()));
        assertEquals(10, response.getIncludedImageCount());
        assertEquals(1, response.getIncludedVideoCount());
    }

    @Test
    void standardAreaIsOptionalUntilPriceIsUsedAndPriceDefaultsToZero() {
        var response = catalog.create(request(null, null, null, null));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getBasePrice()));
        assertNull(response.getStandardAreaM2());
        assertEquals(0, response.getIncludedImageCount());
        assertEquals(0, response.getIncludedVideoCount());
    }

    @Test
    void updateChangesOnlyProvidedPriceAndEvidenceFields() {
        var existing = new ChecklistDefinition();
        existing.setId("c1"); existing.setContent("Check roof"); existing.setNormalizedContent("check roof");
        existing.setBasePrice(new BigDecimal("200000")); existing.setStandardAreaM2(new BigDecimal("100"));
        existing.setIncludedImageCount(10); existing.setIncludedVideoCount(1);
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(existing));

        var response = catalog.update("c1", request("250000", new BigDecimal("125"), 20, null));

        assertEquals(0, new BigDecimal("250000").compareTo(response.getBasePrice()));
        assertEquals(0, new BigDecimal("125").compareTo(response.getStandardAreaM2()));
        assertEquals(20, response.getIncludedImageCount());
        assertEquals(1, response.getIncludedVideoCount());
    }

    @Test
    void invalidMoneyAreaAndEvidenceCountsAreRejected() {
        assertFalse(validator.validate(request("-1", new BigDecimal("100"), 0, 0)).isEmpty());
        assertFalse(validator.validate(request("10.5", new BigDecimal("100"), 0, 0)).isEmpty());
        assertFalse(validator.validate(request("10", BigDecimal.ZERO, 0, 0)).isEmpty());
        assertFalse(validator.validate(request("10", new BigDecimal("100"), -1, 0)).isEmpty());
        assertFalse(validator.validate(request("10", new BigDecimal("100"), 0, 501)).isEmpty());
        assertThrows(ApiException.class, () -> catalog.create(request("100.5", new BigDecimal("100"), 0, 0)));
    }

    private Service service(String id) {
        var service = Service.builder().name(id).isActive(true).build();
        service.setId(id);
        return service;
    }

    @Test
    void serviceAssociationCanChangeEvidenceRequirementsButExposesNoPriceOverride() {
        var definition = new ChecklistDefinition();
        definition.setId("c1"); definition.setContent("Check roof"); definition.setIsActive(true);
        definition.setBasePrice(new BigDecimal("200000")); definition.setStandardAreaM2(new BigDecimal("100"));
        definition.setIncludedImageCount(10);
        var association = new ServiceChecklist();
        association.setId("a"); association.setService(service("s1")); association.setChecklist(definition);
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(association.getService()));
        when(links.findByServiceIdAndChecklistId("s1", "c1")).thenReturn(Optional.of(association));

        var requirements = new ServiceChecklistRequirementsRequest();
        requirements.setIncludedImageCountOverride(20);
        requirements.setRequired(true);
        var response = assignments.updateRequirements("s1", "c1", requirements);

        assertEquals(20, response.getIncludedImageCount());
        assertTrue(response.isRequired());
        assertEquals(0, new BigDecimal("200000").compareTo(definition.getBasePrice()));
        assertFalse(java.util.Arrays.stream(response.getClass().getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .anyMatch(name -> name.toLowerCase().contains("price")));
    }

    @Test
    void staleAssociationVersionIsRejected() {
        var definition = new ChecklistDefinition(); definition.setId("c1"); definition.setIsActive(true);
        var association = new ServiceChecklist(); association.setId("a"); association.setService(service("s1"));
        association.setChecklist(definition); association.setVersion(4L);
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(association.getService()));
        when(links.findByServiceIdAndChecklistId("s1", "c1")).thenReturn(Optional.of(association));
        var requirements = new ServiceChecklistRequirementsRequest(); requirements.setExpectedVersion(3L);
        assertEquals(ErrorCode.CONCURRENT_UPDATE, assertThrows(ApiException.class,
                () -> assignments.updateRequirements("s1", "c1", requirements)).getErrorCode());
    }

    @Test
    void assigningCarriesEvidenceRequirementsAndRequiredFlag() {
        var definition = new ChecklistDefinition(); definition.setId("c1"); definition.setContent("x"); definition.setIsActive(true);
        var service = service("s1");
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(service));
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(definition));
        when(links.existsByServiceIdAndChecklistId("s1", "c1")).thenReturn(false);
        when(links.findAllByServiceIdOrderByDisplayOrderAscIdAsc("s1")).thenReturn(new ArrayList<>());
        var request = new ChecklistAssignmentRequest();
        request.setChecklistId("c1"); request.setRequired(true); request.setIncludedVideoCountOverride(2);

        var response = assignments.assign("s1", request);

        assertTrue(response.isRequired());
        assertEquals(2, response.getIncludedVideoCountOverride());
        request.setIncludedVideoCountOverride(-1);
        assertFalse(validator.validate(request).isEmpty());
    }
}
