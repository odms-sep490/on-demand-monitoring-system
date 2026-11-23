package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.CriterionInput;
import java.util.List;

/** Snapshot of the authorized preview and criteria. Safe to use after the read transaction ends. */
public record ChecklistImageAssessmentTarget(
        long executionVersion, String evidenceId, String bucket, String previewKey,
        List<CriterionInput> criteria) {}
