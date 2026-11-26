package com.ondemandmonitoring.checklist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.config.ChecklistVisionProperties;
import com.ondemandmonitoring.ai.config.OpenRouterProperties;
import com.ondemandmonitoring.checklist.config.ChecklistDefaultSeedInitializer;
import com.ondemandmonitoring.checklist.config.ChecklistDefaults;
import com.ondemandmonitoring.checklist.config.ChecklistDefaultPricing;
import com.ondemandmonitoring.checklist.config.ChecklistPricingSeedInitializer;
import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository;
import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import com.ondemandmonitoring.mission.controller.ChecklistEvidenceController;
import com.ondemandmonitoring.mission.dto.request.ChecklistExecutionUpdateRequest;
import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionMutationGuard;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import com.ondemandmonitoring.mission.mapper.ChecklistEvidenceMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IChecklistEvidenceService;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL and Ollama. Authentication and object storage are controlled test fixtures. */
@SpringJUnitConfig(ChecklistVisionPostgresTest.VisionConfig.class)
@EnabledIfEnvironmentVariable(named = "ODMS_TEST_LOCAL_VISION", matches = "true")
class ChecklistVisionPostgresTest extends ChecklistEvidenceIntegrationPostgresTest {
    @Test
    void seedsUniqueCatalogAndAssessesPersistedOrderSnapshotsThroughInspectorEndpoint() throws Exception {
        service("Giám sát công trình");
        transaction.executeWithoutResult(tx -> {
            var seed = new ChecklistDefaultSeedInitializer(services, definitions, links);
            seed.run(null);
            seed.run(null);
        });
        var jdbc = new JdbcTemplate(dataSource);
        assertEquals(21L, definitions.count());
        assertEquals(21L, jdbc.queryForObject(
                "select count(distinct normalized_content) from checklist_definitions", Long.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from (
                    select normalized_content from checklist_definitions
                    group by normalized_content having count(*) > 1
                ) duplicates
                """, Integer.class));
        var seeded = definitions.findBySeedCode(ChecklistDefaults.code(20)).orElseThrow();
        assertEquals("Chụp ảnh các mặt ngoài công trình", seeded.getContent());
        assertFalse(seeded.getImageAssessmentCriteria().isEmpty());
        transaction.executeWithoutResult(tx ->
                new ChecklistPricingSeedInitializer(definitions, defaultPrices).run(null));
        String seedService = service("Seed photo criteria QA");
        for (int number = 1; number <= ChecklistDefaults.CONTENTS.size(); number++) {
            var definition = definitions.findBySeedCode(ChecklistDefaults.code(number)).orElseThrow();
            if (ChecklistDefaultPricing.forNumber(number).includedImages() > 0) {
                assertFalse(definition.getImageAssessmentCriteria().isEmpty(), definition.getSeedCode());
                assignments.assign(seedService, assignment(definition.getId(), number));
            } else {
                assertNull(definition.getImageAssessmentCriteria());
            }
        }

        String serviceId = service("AI snapshot QA");
        String originalCriterion = "A black rectangle is visible on a white background.";
        String updatedCriterion = "A white background is visible around a black rectangle.";
        var configuration = new ChecklistRequest();
        configuration.setContent("Photograph the test target");
        configuration.setBasePrice(BigDecimal.valueOf(100000));
        configuration.setStandardAreaM2(BigDecimal.valueOf(500000));
        configuration.setIncludedImageCount(1);
        configuration.setImageAssessmentCriteria(List.of(originalCriterion));
        String checklistId = catalog.create(configuration).getId();
        assignments.assign(serviceId, assignment(checklistId, 0));

        var request = orderRequest(serviceId);
        String originalOrder = orderService.createOrder(request).getId();
        String originalItem = snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(originalOrder).getFirst().getId();
        configuration.setImageAssessmentCriteria(List.of(updatedCriterion));
        catalog.update(checklistId, configuration);
        String updatedOrder = orderService.createOrder(request).getId();
        var updatedItem = snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(updatedOrder).getFirst();
        assertEquals(List.of(originalCriterion), snapshots.findById(originalItem).orElseThrow().getImageAssessmentCriteriaSnapshot());
        assertEquals(List.of(updatedCriterion), updatedItem.getImageAssessmentCriteriaSnapshot());
        assertEquals(originalCriterion, jdbc.queryForObject(
                "select image_assessment_criteria_snapshot ->> 0 from order_checklist_items where id=?",
                String.class, originalItem));
        assertEquals(updatedCriterion, jdbc.queryForObject(
                "select image_assessment_criteria ->> 0 from checklist_definitions where id=?",
                String.class, checklistId));
        request.setServiceId(seedService);
        when(serviceDeliverables.existsByServiceIdAndDeliverableTypeId(eq(seedService), anyString()))
                .thenReturn(true);
        String seedOrder = orderService.createOrder(request).getId();
        var seedSnapshots = snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(seedOrder);
        assertEquals(20, seedSnapshots.size());
        transaction.executeWithoutResult(tx -> {
            for (var snapshot : snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(seedOrder)) {
                assertFalse(snapshot.getImageAssessmentCriteriaSnapshot().isEmpty());
                assertEquals(snapshot.getSourceChecklist().getImageAssessmentCriteria(),
                        snapshot.getImageAssessmentCriteriaSnapshot());
            }
        });

        byte[] jpeg = targetJpeg();
        when(storage.open("test", "qa-preview.jpg")).thenAnswer(call ->
                new IMediaObjectStorage.StoredObjectStream(new ByteArrayInputStream(jpeg),
                        (long) jpeg.length, "image/jpeg"));
        var properties = new ChecklistVisionProperties();
        properties.setEnabled(true);
        properties.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
        properties.setModel("qwen3-vl:2b-instruct");
        var vision = new ChecklistImageVisionClient(properties, new OpenRouterProperties(), new ObjectMapper());
        try {
            var assessment = new ChecklistImageContentAssessmentService(
                    evidenceService, storage, vision, resolver, Clock.systemUTC());
            var mvc = MockMvcBuilders.standaloneSetup(new ChecklistEvidenceController(evidenceService, assessment)).build();
            for (String orderId : List.of(originalOrder, updatedOrder)) {
                transaction.executeWithoutResult(tx -> {
                    var order = orders.findById(orderId).orElseThrow();
                    order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
                    order.getExecutionPlan().setRevision(1);
                    order.getExecutionPlan().setFinalizedAt(java.time.Instant.now());
                    order.getExecutionPlan().setFinalizedBy(resolver.getCurrentUser().getId());
                });
                String missionId = legacyMission(orderId, request(orderId)).getId();
                setStatus(missionId, MissionStatus.PENDING_REVIEW);
                when(auth.canSelectCustomerEvidence(missionId)).thenReturn(true);
                when(auth.canAssessMonitoringChecklist(missionId)).thenReturn(true);
                String inspectorId = resolver.getCurrentUser().getId();
                when(resolver.getCurrentUserId()).thenReturn(inspectorId);
                String mediaId = asset(missionId, MediaStatus.AVAILABLE);
                transaction.executeWithoutResult(tx -> {
                    var media = mediaRepository.findById(mediaId).orElseThrow();
                    media.setDerivativeStatus(MediaDerivativeStatus.READY);
                    media.setPreviewKey("qa-preview.jpg");
                    media.setPreviewSizeBytes((long) jpeg.length);
                });
                String evidenceId = evidenceService.attach(missionId, attachRequest(missionId, mediaId)).getFirst().getEvidenceId();
                var execution = row(missionId);
                var review = new ChecklistExecutionUpdateRequest();
                review.setExpectedVersion(execution.getVersion());
                review.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
                review.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
                review.setAssessmentNote("QA Inspector reviewed the target.");
                executionService.update(missionId, execution.getId(), review);
                execution = row(missionId);
                String expectedCriterion = orderId.equals(originalOrder) ? originalCriterion : updatedCriterion;
                var response = mvc.perform(post("/api/missions/{mission}/checklist-executions/{execution}/evidence/{evidence}/content-assessment",
                                missionId, execution.getId(), evidenceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(
                                new com.ondemandmonitoring.mission.dto.request.ChecklistImageContentAssessmentRequest(
                                        execution.getVersion(), "en"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.evidenceId").value(evidenceId))
                        .andExpect(jsonPath("$.data.criteria[0].code").value("PHOTO_1"))
                        .andExpect(jsonPath("$.data.criteria[0].label").value(expectedCriterion))
                        .andExpect(jsonPath("$.data.criteria[0].observation").isNotEmpty())
                        .andExpect(jsonPath("$.data.verdict").value("MATCH"))
                        .andReturn().getResponse().getContentAsString();
                System.out.println("REAL_OLLAMA_INSPECTOR_RESPONSE " + response);
            }
        } finally {
            ReflectionTestUtils.invokeMethod(vision, "close");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    IMediaObjectStorage storage;

    @org.springframework.beans.factory.annotation.Autowired
    com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices;

    private static byte[] targetJpeg() throws java.io.IOException {
        var image = new java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, 320, 200);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.fillRect(60, 60, 200, 80);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    @Configuration
    @Import(ChecklistEvidenceIntegrationPostgresTest.EvidenceConfig.class)
    static class VisionConfig {
        @Bean com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository weatherChecks() {
            return mock(com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository.class);
        }

        @Bean com.ondemandmonitoring.mission.service.IMissionReworkContextSnapshotService reworkContext() {
            return mock(com.ondemandmonitoring.mission.service.IMissionReworkContextSnapshotService.class);
        }

        @Bean com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository revisionCases() {
            return mock(com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository.class);
        }

        @Bean com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository revisionItems() {
            return mock(com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository.class);
        }

        @Bean com.ondemandmonitoring.refund.service.RefundService refunds() {
            return mock(com.ondemandmonitoring.refund.service.RefundService.class);
        }

        @Bean com.ondemandmonitoring.finance.repository.PaymentRepository payments() {
            return mock(com.ondemandmonitoring.finance.repository.PaymentRepository.class);
        }

        @Bean InspectionCorrectionPolicy inspectionCorrections() {
            return mock(InspectionCorrectionPolicy.class);
        }

        @Bean EvidenceHandoffPolicy handoffPolicy() {
            return mock(EvidenceHandoffPolicy.class);
        }

        @Bean com.ondemandmonitoring.mission.inspection.service.IInspectionCorrectionService correctionWorkflow() {
            return mock(com.ondemandmonitoring.mission.inspection.service.IInspectionCorrectionService.class);
        }

        @Bean InspectionCorrectionMutationGuard inspectionMutation() {
            return mock(InspectionCorrectionMutationGuard.class);
        }

        @Bean @Primary
        IChecklistEvidenceService visionEvidence(EntityManagerFactory factory, OrderRepository orders,
                MissionRepository missions, MissionChecklistExecutionRepository executions,
                MissionChecklistEvidenceRepository evidence, MediaAssetRepository media,
                MissionResultRepository results, IMissionAuthorizationService auth,
                AuthenticatedUserResolver resolver, IMediaObjectStorage storage, javax.sql.DataSource dataSource) {
            var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
            return new ChecklistEvidenceServiceImpl(missions, executions, evidence, media, results, auth, resolver,
                    new ChecklistEvidencePolicy(), Mappers.getMapper(ChecklistEvidenceMapper.class),
                    storage, mock(MediaDerivativeService.class), orders,
                    repositories.getRepository(ResultReviewRoundEvidenceRepository.class),
                    new ChecklistDeliverySuggestionPolicy(new DeliveryImageQualityReader(
                            new NamedParameterJdbcTemplate(dataSource))));
        }
    }
}
