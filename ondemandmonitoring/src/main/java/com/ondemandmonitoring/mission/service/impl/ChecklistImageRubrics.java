package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.CriterionInput;
import com.ondemandmonitoring.checklist.config.ChecklistDefaults;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pilot rubrics describe what a single photo can show, independently of mission acceptance. */
final class ChecklistImageRubrics {
    private static final List<Criterion> CONSTRUCTION_OVERVIEW = List.of(
            new Criterion("BUILDING_VISIBLE", "Thấy công trình", "Building visible",
                    "A building or construction site is clearly visible as a main subject."),
            new Criterion("EXTERIOR_VISIBLE", "Thấy mặt ngoài", "Exterior visible",
                    "At least one exterior face of the building is visible and mostly unobstructed."),
            new Criterion("OVERVIEW_FRAMING", "Góc chụp tổng quan", "Overview framing",
                    "The frame shows enough of the building and immediately surrounding area for an overview, rather than only a close-up."));
    private static final List<Criterion> ROOF = List.of(
            new Criterion("ROOF_VISIBLE", "Thấy bề mặt mái", "Roof surface visible",
                    "A recognizable roof surface is clearly visible."),
            new Criterion("ROOF_INSPECTABLE", "Quan sát được chi tiết mái", "Roof detail visible",
                    "An area of the roof is visible with enough detail and without major obstruction for visual review. Do not infer damage or safety."));
    private static final List<Criterion> AREA_OVERVIEW = List.of(
            new Criterion("AREA_CONTEXT", "Thấy bối cảnh khu vực", "Site context visible",
                    "The frame shows the surrounding context of an outdoor area."),
            new Criterion("AREA_FRAMING", "Góc chụp bao quát", "Wide framing",
                    "The frame covers a broad portion of the area rather than a narrow close-up. Do not infer the site boundaries."));
    private static final Map<String, Rubric> SUPPORTED = Map.of(
            ChecklistDefaults.code(5), rubric(5, CONSTRUCTION_OVERVIEW),
            ChecklistDefaults.code(20), rubric(20, CONSTRUCTION_OVERVIEW),
            ChecklistDefaults.code(8), rubric(8, ROOF),
            ChecklistDefaults.code(11), rubric(11, AREA_OVERVIEW));

    private ChecklistImageRubrics() {}

    static List<Criterion> forChecklist(OrderChecklistItem item) {
        if (item.getSourceChecklist() == null || item.getContent() == null) return List.of();
        String code = item.getSourceChecklist().getSeedCode();
        if (code == null) return List.of();
        // A catalog edit cannot silently apply the original rubric to a changed order snapshot.
        var rubric = SUPPORTED.get(code);
        return rubric != null && rubric.checklistContent().equals(normalize(item.getContent()))
                ? rubric.criteria() : List.of();
    }

    private static Rubric rubric(int number, List<Criterion> criteria) {
        return new Rubric(normalize(ChecklistDefaults.CONTENTS.get(number - 1)), criteria);
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    record Criterion(String code, String vietnamese, String english, String instruction) {
        CriterionInput input(String language) {
            return new CriterionInput(code, "vi".equals(language) ? vietnamese : english, instruction);
        }
    }

    private record Rubric(String checklistContent, List<Criterion> criteria) {}
}
