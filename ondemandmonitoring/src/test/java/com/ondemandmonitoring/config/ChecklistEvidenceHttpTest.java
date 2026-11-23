package com.ondemandmonitoring.config;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Verdict;
import com.ondemandmonitoring.mission.controller.ChecklistEvidenceController;
import com.ondemandmonitoring.mission.dto.response.ChecklistDeliverySuggestionResponse;
import com.ondemandmonitoring.mission.dto.request.ChecklistImageContentAssessmentRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistImageContentAssessmentResponse;
import com.ondemandmonitoring.mission.service.IChecklistEvidenceService;
import com.ondemandmonitoring.mission.service.impl.ChecklistImageContentAssessmentService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(classes={SecurityConfig.class,ActiveAccountFilter.class,GlobalExceptionHandler.class,
        ChecklistEvidenceController.class,ChecklistEvidenceHttpTest.Beans.class})
@WebAppConfiguration
class ChecklistEvidenceHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired IChecklistEvidenceService evidence;
    @Autowired ChecklistImageContentAssessmentService assessments;
    @Autowired AuthenticatedUserResolver resolver;
    MockMvc mvc;
    @BeforeEach void prepare() {
        reset(evidence,resolver,assessments);
        var user=User.builder().isActive(true).role(Role.builder().code(RoleCode.STAFF).active(true).build()).build();user.setId("actor");
        when(resolver.getCurrentUser()).thenReturn(user);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void unauthenticatedAndCustomerCannotReadWorkingEvidence() throws Exception {
        mvc.perform(get("/api/missions/m/checklist-evidence/candidates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/missions/m/checklist-evidence/candidates").with(jwt().authorities(()->"ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(evidence);
    }
    @Test void validSingleAttachUsesBackendIdsAndEnvelope() throws Exception {
        when(evidence.attach(eq("m"),any())).thenReturn(List.of());
        mvc.perform(put("/api/missions/m/checklist-executions/e/evidence/backend-media").with(jwt().authorities(()->"ROLE_STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(evidence).attach(eq("m"),argThat(request->request.mediaId().equals("backend-media")&&request.targets().getFirst().executionId().equals("e")&&request.targets().getFirst().expectedVersion()==4L));
    }
    @Test void missingVersionAndOversizedBatchRejected() throws Exception {
        mvc.perform(put("/api/missions/m/checklist-executions/e/evidence/a").with(jwt().authorities(()->"ROLE_STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        String targets=String.join(",",java.util.Collections.nCopies(101,"{\"executionId\":\"e\",\"expectedVersion\":0}"));
        mvc.perform(post("/api/missions/m/checklist-evidence/batch").with(jwt().authorities(()->"ROLE_STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":\"a\",\"targets\":["+targets+"]}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(evidence);
    }
    @Test void candidatePaginationAndDetachVersionAreValidated() throws Exception {
        mvc.perform(get("/api/missions/m/checklist-evidence/candidates?size=101").with(jwt().authorities(()->"ROLE_STAFF"))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/missions/m/checklist-executions/e/evidence/link?expectedVersion=4").with(jwt().authorities(()->"ROLE_STAFF")))
                .andExpect(status().isOk()); verify(evidence).detach("m","e","link",4,null);
    }
    @Test void selectionUsesExecutionVersionAndEvidenceIdsWithEnvelope() throws Exception {
        var selected = new com.ondemandmonitoring.mission.dto.response.ChecklistEvidenceResponse();
        selected.setEvidenceId("evidence-1"); selected.setSelectedForCustomer(true); selected.setSelectedBy("inspector");
        when(evidence.selectForCustomer(eq("m"), eq("e"), any())).thenReturn(List.of(selected));
        mvc.perform(put("/api/missions/m/checklist-executions/e/customer-selection")
                .with(jwt().authorities(() -> "ROLE_STAFF")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":4,\"evidenceIds\":[\"evidence-1\"],\"confirmSurplus\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].selectedForCustomer").value(true))
                .andExpect(jsonPath("$.data[0].selectedBy").value("inspector"));
        verify(evidence).selectForCustomer(eq("m"), eq("e"), argThat(request -> request.expectedVersion() == 4L
                && request.evidenceIds().equals(List.of("evidence-1")) && request.confirmSurplus()));
    }

    @Test void unauthenticatedAndCustomerCannotReadDeliverySuggestions() throws Exception {
        String url = "/api/missions/m/checklist-executions/e/delivery-suggestion";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(evidence);
    }

    @Test void staffGetsVersionedDeliverySuggestionInApiEnvelope() throws Exception {
        var candidate = new ChecklistDeliverySuggestionResponse.Candidate(
                "image-link", ChecklistDeliverySuggestionResponse.Recommendation.SUGGESTED,
                List.of("TECHNICAL_CHECKS_PASS"), "quality-check");
        when(evidence.getDeliverySuggestion("m", "e")).thenReturn(new ChecklistDeliverySuggestionResponse(
                4L, "1", 2, 1, List.of("image-link"), List.of("INSUFFICIENT_RECOMMENDABLE_IMAGES"), List.of(candidate), true));
        when(assessments.available()).thenReturn(true);

        mvc.perform(get("/api/missions/m/checklist-executions/e/delivery-suggestion")
                .with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionVersion").value(4))
                .andExpect(jsonPath("$.data.requiredImageCount").value(2))
                .andExpect(jsonPath("$.data.contentAssessmentAvailable").value(true))
                .andExpect(jsonPath("$.data.suggestedImageEvidenceIds[0]").value("image-link"))
                .andExpect(jsonPath("$.data.candidates[0].recommendation").value("SUGGESTED"))
                .andExpect(jsonPath("$.data.warnings[0]").value("INSUFFICIENT_RECOMMENDABLE_IMAGES"));
        verify(evidence).getDeliverySuggestion("m", "e");
    }

    @Test void contentAssessmentValidatesAccessAndVersionedRequest() throws Exception {
        String url = "/api/missions/m/checklist-executions/e/evidence/image-link/content-assessment";
        String body = "{\"expectedVersion\":4,\"language\":\"vi\"}";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(jwt().authorities(() -> "ROLE_CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(jwt().authorities(() -> "ROLE_STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":-1,\"language\":\"de\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(assessments);

        var request = new ChecklistImageContentAssessmentRequest(4L, "vi");
        var criterion = new ChecklistImageContentAssessmentResponse.Criterion("ROOF_VISIBLE", "Thấy mái",
                Verdict.UNCERTAIN, "Ảnh chỉ thấy một phần mái");
        when(assessments.assess("m", "e", "image-link", request))
                .thenReturn(new ChecklistImageContentAssessmentResponse(4L, "image-link", Verdict.UNCERTAIN, List.of(criterion)));
        mvc.perform(post(url).with(jwt().authorities(() -> "ROLE_STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionVersion").value(4))
                .andExpect(jsonPath("$.data.evidenceId").value("image-link"))
                .andExpect(jsonPath("$.data.verdict").value("UNCERTAIN"))
                .andExpect(jsonPath("$.data.criteria[0].code").value("ROOF_VISIBLE"));
        verify(assessments).assess("m", "e", "image-link", request);
    }

    @Test void selectionPayloadRequiresNonnegativeVersionAndAtMostThousandNonblankIds() throws Exception {
        String url = "/api/missions/m/checklist-executions/e/customer-selection";
        for (String body : List.of("{}", "{\"expectedVersion\":-1,\"evidenceIds\":[],\"confirmSurplus\":false}",
                "{\"expectedVersion\":0,\"evidenceIds\":[\"\"],\"confirmSurplus\":false}")) {
            mvc.perform(put(url).with(jwt().authorities(() -> "ROLE_STAFF"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        String ids = String.join(",", java.util.Collections.nCopies(1001, "\"evidence\""));
        mvc.perform(put(url).with(jwt().authorities(() -> "ROLE_STAFF")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"evidenceIds\":[" + ids + "],\"confirmSurplus\":false}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(evidence);
    }

    @Configuration
    @EnableWebMvc
    static class Beans {
        @Bean static org.springframework.validation.beanvalidation.MethodValidationPostProcessor methodValidation() {
            var processor = new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
            processor.setProxyTargetClass(true); return processor;
        }
        @Bean IChecklistEvidenceService evidence() {return mock(IChecklistEvidenceService.class);}
        @Bean ChecklistImageContentAssessmentService assessments() {
            return mock(ChecklistImageContentAssessmentService.class);
        }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean JwtDecoder cognitoAccessTokenDecoder() { return mock(JwtDecoder.class); }
    }
}
