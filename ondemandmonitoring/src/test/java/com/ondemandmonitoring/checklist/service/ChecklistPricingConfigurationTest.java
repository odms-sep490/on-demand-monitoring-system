package com.ondemandmonitoring.checklist.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.dto.request.ChecklistAssignmentRequest;
import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.dto.request.ServiceChecklistPricingRequest;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Admin create/edit of a checklist's pricing configuration and of the per-service association overrides. */
class ChecklistPricingConfigurationTest {
    ChecklistDefinitionRepository repository = mock(ChecklistDefinitionRepository.class);
    ChecklistServiceImpl catalog = new ChecklistServiceImpl(repository, Mappers.getMapper(ChecklistMapper.class));
    ServiceRepository services = mock(ServiceRepository.class);
    ServiceChecklistRepository links = mock(ServiceChecklistRepository.class);
    ServiceChecklistServiceImpl assignments = new ServiceChecklistServiceImpl(services, repository, links, Mappers.getMapper(ServiceChecklistMapper.class));
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach void setup() {
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(links.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    ChecklistRequest request(String base, Integer images, Integer videos, Integer duration, String profile) {
        var r = new ChecklistRequest();
        r.setContent("Check roof"); r.setBasePrice(base == null ? null : new BigDecimal(base));
        r.setIncludedImageCount(images); r.setIncludedVideoCount(videos); r.setStandardVideoDurationSeconds(duration); r.setPricingProfile(profile);
        return r;
    }

    @Test void createStoresPricingConfigurationWithNormalisedProfile() {
        var response = catalog.create(request("200000", 10, 1, 30, " standard "));
        assertEquals(0, new BigDecimal("200000").compareTo(response.getBasePrice()));
        assertEquals(10, response.getIncludedImageCount());
        assertEquals(1, response.getIncludedVideoCount());
        assertEquals(30, response.getStandardVideoDurationSeconds());
        assertEquals("STANDARD", response.getPricingProfile());
    }

    @Test void createWithoutPricingFieldsUsesNeutralDefaultsSoLegacyClientsStillWork() {
        var response = catalog.create(request(null, null, null, null, null));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getBasePrice()));
        assertEquals(0, response.getIncludedImageCount());
        assertEquals(0, response.getIncludedVideoCount());
        assertNull(response.getPricingProfile());
    }

    @Test void updateChangesOnlyTheProvidedFields() {
        var existing = new ChecklistDefinition();
        existing.setId("c1"); existing.setContent("Check roof"); existing.setNormalizedContent("check roof");
        existing.setBasePrice(new BigDecimal("200000")); existing.setIncludedImageCount(10); existing.setIncludedVideoCount(1);
        existing.setPricingProfile("STANDARD");
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(existing));

        var response = catalog.update("c1", request(null, 20, null, null, null));

        assertEquals(20, response.getIncludedImageCount());
        assertEquals(0, new BigDecimal("200000").compareTo(response.getBasePrice()));
        assertEquals(1, response.getIncludedVideoCount());
        assertEquals("STANDARD", response.getPricingProfile());
    }

    @Test void fractionalVndIsRejectedByTheService() {
        var ex = assertThrows(ApiException.class, () -> catalog.create(request("100.5", 0, 0, null, null)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test void requestValidationRejectsNegativeAndOutOfRangeValues() {
        assertFalse(validator.validate(request("-1", 0, 0, null, null)).isEmpty());
        assertFalse(validator.validate(request("10", -1, 0, null, null)).isEmpty());
        assertFalse(validator.validate(request("10", 0, 501, null, null)).isEmpty());
        assertFalse(validator.validate(request("10", 0, 0, 0, null)).isEmpty());
        assertFalse(validator.validate(request("10", 0, 0, 7201, null)).isEmpty());
        assertFalse(validator.validate(request("10", 0, 0, null, "not valid!")).isEmpty());
        assertTrue(validator.validate(request("0", 0, 0, 1, "STANDARD_2")).isEmpty());
    }

    // ---- Service-checklist association overrides -----------------------------------------------------------------
    Service service(String id) { var s = Service.builder().name(id).isActive(true).build(); s.setId(id); return s; }

    @Test void overridesLiveOnTheAssociationAndNeverChangeTheDefinitionOrAnotherService() {
        var definition = new ChecklistDefinition();
        definition.setId("c1"); definition.setContent("Check roof"); definition.setIsActive(true);
        definition.setBasePrice(new BigDecimal("200000")); definition.setIncludedImageCount(10);
        var a = new ServiceChecklist(); a.setId("a"); a.setService(service("s1")); a.setChecklist(definition);
        var b = new ServiceChecklist(); b.setId("b"); b.setService(service("s2")); b.setChecklist(definition);
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(a.getService()));
        when(links.findByServiceIdAndChecklistId("s1", "c1")).thenReturn(Optional.of(a));

        var pricing = new ServiceChecklistPricingRequest();
        pricing.setBasePriceOverride(new BigDecimal("260000")); pricing.setIncludedImageCountOverride(20);
        pricing.setPricingProfileOverride("premium"); pricing.setRequired(true);
        var response = assignments.updatePricing("s1", "c1", pricing);

        assertEquals(0, new BigDecimal("260000").compareTo(response.getBasePrice()));
        assertEquals(20, response.getIncludedImageCount());
        assertEquals("PREMIUM", response.getPricingProfile());
        assertTrue(response.isRequired());
        assertEquals(0, new BigDecimal("200000").compareTo(response.getChecklistBasePrice()));
        // the shared definition and the other service's association are untouched
        assertEquals(0, new BigDecimal("200000").compareTo(definition.getBasePrice()));
        assertEquals(10, definition.getIncludedImageCount());
        assertNull(b.getBasePriceOverride());
        assertNull(b.getIncludedImageCountOverride());
        assertFalse(b.isRequired());
    }

    @Test void clearingAnOverrideRestoresInheritance() {
        var definition = new ChecklistDefinition();
        definition.setId("c1"); definition.setContent("x"); definition.setIsActive(true); definition.setBasePrice(new BigDecimal("200000"));
        var a = new ServiceChecklist(); a.setId("a"); a.setService(service("s1")); a.setChecklist(definition);
        a.setBasePriceOverride(new BigDecimal("1")); a.setIncludedImageCountOverride(3);
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(a.getService()));
        when(links.findByServiceIdAndChecklistId("s1", "c1")).thenReturn(Optional.of(a));

        var response = assignments.updatePricing("s1", "c1", new ServiceChecklistPricingRequest());

        assertEquals(0, new BigDecimal("200000").compareTo(response.getBasePrice()));
        assertEquals(0, response.getIncludedImageCount());
    }

    @Test void staleAssociationVersionIsRejected() {
        var definition = new ChecklistDefinition(); definition.setId("c1"); definition.setIsActive(true);
        var a = new ServiceChecklist(); a.setId("a"); a.setService(service("s1")); a.setChecklist(definition); a.setVersion(4L);
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(a.getService()));
        when(links.findByServiceIdAndChecklistId("s1", "c1")).thenReturn(Optional.of(a));
        var pricing = new ServiceChecklistPricingRequest(); pricing.setExpectedVersion(3L);
        assertEquals(ErrorCode.CONCURRENT_UPDATE, assertThrows(ApiException.class, () -> assignments.updatePricing("s1", "c1", pricing)).getErrorCode());
    }

    @Test void assigningCarriesRequiredFlagAndOverridesAndRejectsNegativeOverride() {
        var definition = new ChecklistDefinition(); definition.setId("c1"); definition.setContent("x"); definition.setIsActive(true);
        var s1 = service("s1");
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(s1));
        when(repository.findByIdForUpdate("c1")).thenReturn(Optional.of(definition));
        when(links.existsByServiceIdAndChecklistId("s1", "c1")).thenReturn(false);
        when(links.findAllByServiceIdOrderByDisplayOrderAscIdAsc("s1")).thenReturn(new ArrayList<>());
        var request = new ChecklistAssignmentRequest();
        request.setChecklistId("c1"); request.setRequired(true); request.setBasePriceOverride(new BigDecimal("1000")); request.setIncludedVideoCountOverride(2);

        var response = assignments.assign("s1", request);

        assertTrue(response.isRequired());
        assertEquals(0, new BigDecimal("1000").compareTo(response.getBasePriceOverride()));
        assertEquals(2, response.getIncludedVideoCountOverride());

        request.setBasePriceOverride(new BigDecimal("-1"));
        when(links.existsByServiceIdAndChecklistId(anyString(), anyString())).thenReturn(false);
        assertThrows(ApiException.class, () -> assignments.assign("s1", request));
    }
}
