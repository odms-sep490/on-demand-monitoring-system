package com.ondemandmonitoring.mission.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.CriterionInput;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Finding;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Verdict;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IMediaObjectStorage.StoredObjectStream;
import com.ondemandmonitoring.mission.dto.request.ChecklistImageContentAssessmentRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistImageContentAssessmentResponse;
import com.ondemandmonitoring.mission.service.ChecklistImageAssessmentTarget;
import com.ondemandmonitoring.mission.service.IChecklistEvidenceService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChecklistImageContentAssessmentServiceTest {
    private static final byte[] JPEG_HEADER = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private final IChecklistEvidenceService evidence = mock(IChecklistEvidenceService.class);
    private final IMediaObjectStorage storage = mock(IMediaObjectStorage.class);
    private final ChecklistImageVisionClient vision = mock(ChecklistImageVisionClient.class);
    private final AuthenticatedUserResolver user = mock(AuthenticatedUserResolver.class);
    private final com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService settings =
            mock(com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService.class);
    private final ChecklistImageContentAssessmentService service = new ChecklistImageContentAssessmentService(
            evidence, storage, vision, user, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), settings);
    private final ChecklistImageContentAssessmentRequest request = new ChecklistImageContentAssessmentRequest(7L, "en");
    private final List<CriterionInput> criteria = List.of(
            new CriterionInput("VISIBLE", "Visible", "Subject is visible"),
            new CriterionInput("FRAMING", "Framing", "Frame covers the surroundings"));
    private final ChecklistImageAssessmentTarget target =
            new ChecklistImageAssessmentTarget(7, "evidence", "private", "preview.jpg", criteria, null);

    @BeforeEach void setup() {
        when(evidence.resolveContentAssessment("mission", "execution", "evidence", request)).thenReturn(target);
        when(vision.available()).thenReturn(true);
        when(vision.runtimeEnabled()).thenReturn(true);
        when(settings.isEnabled()).thenReturn(true);
        when(user.getCurrentUserId()).thenReturn("inspector");
        when(storage.open("private", "preview.jpg")).thenAnswer(call ->
                new StoredObjectStream(new ByteArrayInputStream(JPEG_HEADER), 3L, "image/jpeg"));
        when(vision.assess(aryEq(JPEG_HEADER), eq("en"), eq(criteria), eq(target.context()))).thenReturn(Map.of(
                "VISIBLE", new Finding(Verdict.MATCH, "The roof is visible"),
                "FRAMING", new Finding(Verdict.UNCERTAIN, "Only one side is visible")));
    }

    @Test void sendsOnlyPreviewAndReturnsCriteriaInRubricOrder() {
        var result = assess();
        assertEquals(Verdict.UNCERTAIN, result.verdict());
        assertEquals(List.of("Visible", "Framing"), result.criteria().stream().map(criterion -> criterion.label()).toList());
        verify(evidence, times(2)).resolveContentAssessment("mission", "execution", "evidence", request);
        verify(storage).open("private", "preview.jpg");
        verify(evidence, never()).selectForCustomer(any(), any(), any());
    }

    @Test void dropsResultIfPreviewChangedDuringInference() {
        var replacement = new ChecklistImageAssessmentTarget(7, "evidence", "private", "new.jpg", criteria, null);
        when(evidence.resolveContentAssessment("mission", "execution", "evidence", request)).thenReturn(target, replacement);
        assertEquals(ErrorCode.CONCURRENT_UPDATE, assertThrows(ApiException.class, this::assess).getErrorCode());
    }

    @Test void disabledOrInvalidPreviewNeverReachesProvider() {
        when(vision.available()).thenReturn(false);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, this::assess).getStatus());
        verifyNoInteractions(storage);
        when(vision.available()).thenReturn(true);
        when(storage.open("private", "preview.jpg")).thenReturn(new StoredObjectStream(
                new ByteArrayInputStream(new byte[]{0, 1, 2}), 3L, "image/jpeg"));
        assertEquals(ErrorCode.MEDIA_DERIVATIVE_UNAVAILABLE,
                assertThrows(ApiException.class, this::assess).getErrorCode());
        verify(vision, never()).assess(any(), any(), any(), any());
    }

    @Test void limitsRepeatedRequestsFromTheSameInspector() {
        for (int index = 0; index < 8; index++) assess();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ApiException.class, this::assess).getStatus());
        verify(vision, times(8)).assess(any(), any(), any(), any());
    }

    @Test void registryDisableDuringInferenceDropsResult() {
        when(vision.runtimeEnabled()).thenReturn(true, false);
        assertEquals(ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED,
                assertThrows(ApiException.class, this::assess).getErrorCode());
        verify(vision).assess(any(), any(), any(), any());
    }

    @Test void systemSwitchBlocksNewRequestsAndDropsInFlightResults() {
        when(settings.isEnabled()).thenReturn(false);
        assertEquals(false, service.available());
        assertEquals(ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED,
                assertThrows(ApiException.class, this::assess).getErrorCode());
        verify(vision, never()).assess(any(), any(), any(), any());
        verifyNoInteractions(storage);
        when(settings.isEnabled()).thenReturn(true, false);
        assertEquals(ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED,
                assertThrows(ApiException.class, this::assess).getErrorCode());
        verify(vision).assess(any(), any(), any(), any());
    }

    @Test void releasesInspectorPermitWhenProviderFails() {
        when(vision.assess(any(), any(), any(), any())).thenThrow(new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        assertEquals(ErrorCode.AI_PROVIDER_UNAVAILABLE, assertThrows(ApiException.class, this::assess).getErrorCode());
        doReturn(Map.of(
                "VISIBLE", new Finding(Verdict.MATCH, "visible"),
                "FRAMING", new Finding(Verdict.MATCH, "visible")))
                .when(vision).assess(any(), any(), any(), any());
        assertEquals(Verdict.MATCH, assess().verdict());
    }

    @Test void accessDenialNeverReadsPreviewOrCallsProvider() {
        when(evidence.resolveContentAssessment(any(), any(), any(), any())).thenThrow(new ApiException(ErrorCode.ACCESS_DENIED));
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class, this::assess).getErrorCode());
        verifyNoInteractions(storage);
        verify(vision, never()).assess(any(), any(), any(), any());
    }

    private ChecklistImageContentAssessmentResponse assess() {
        return service.assess("mission", "execution", "evidence", request);
    }
}
