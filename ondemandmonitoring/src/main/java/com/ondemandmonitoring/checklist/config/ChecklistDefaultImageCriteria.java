package com.ondemandmonitoring.checklist.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Initial catalog criteria describing what one photograph can establish. */
public final class ChecklistDefaultImageCriteria {
    private static final List<String> CONSTRUCTION_OVERVIEW = List.of(
            "A building or construction site is clearly visible as a main subject.",
            "At least one exterior face of the building is visible and mostly unobstructed.",
            "The frame shows enough of the building and immediately surrounding area for an overview, rather than only a close-up.");
    private static final List<String> ROOF = List.of(
            "A recognizable roof surface is clearly visible.",
            "An area of the roof is visible with enough detail and without major obstruction for visual review. Do not infer damage or safety.");
    private static final List<String> AREA_OVERVIEW = List.of(
            "The frame shows the surrounding context of an outdoor area.",
            "The frame covers a broad portion of the area rather than a narrow close-up. Do not infer the site boundaries.");
    private static final List<String> CURRENT_CONDITION = List.of(
            "The inspection subject and its surroundings are visible together so the photographed area can be understood.",
            "Visible surfaces, ground or vegetation have enough detail to document their appearance. Do not infer capture time, completed work, or changes from an earlier visit.");
    private static final List<String> REQUESTED_LOCATION = List.of(
            "The photograph shows a distinct inspection location or feature with enough surrounding context to distinguish it.",
            "The target area is sufficiently visible and unobstructed for visual inspection.",
            "The visible location matches identifying details explicitly supplied in the checklist context. Return UNCERTAIN if no identifying reference is supplied; do not infer coordinates or customer intent.");
    private static final Map<String, Rubric> SUPPORTED = Map.ofEntries(
            seed(1, CONSTRUCTION_OVERVIEW),
            seed(2, List.of(
                    "A construction work area is visible with recognizable building elements or ongoing work.",
                    "The image contains enough detail to observe the visible construction stage, such as foundations, structural frame or completed surfaces. Do not infer completion percentages, schedule compliance or progress over time.")),
            seed(3, List.of(
                    "An exterior building wall or facade is the main inspection subject.",
                    "The facade surface or joints are visible in enough detail to inspect their appearance. Do not require damage to be present or infer structural safety.")),
            seed(4, List.of(
                    "An inspection surface such as a roof, upper facade, ledge, recess or another target specified in the checklist context is visible.",
                    "The target surface is sufficiently unobstructed and detailed for visual inspection. Do not infer physical accessibility, flight altitude or safe access from the image.")),
            seed(5, CONSTRUCTION_OVERVIEW),
            seed(6, CURRENT_CONDITION),
            seed(7, List.of(
                    "A warehouse or industrial building is visible as the inspection subject.",
                    "The frame includes the building roof or walls together with surrounding site context for an overall view. Do not infer operational condition or safety.")),
            seed(8, ROOF),
            seed(9, List.of(
                    "An upper structural element, roof-mounted installation, beam, truss or connection is recognizable as the inspection subject.",
                    "The target component, surface or connection is visible with enough detail for visual inspection. Do not infer load capacity, structural integrity or hidden damage.")),
            seed(10, List.of(
                    "A localized visible irregularity, or a suspect feature explicitly identified in the checklist context, can be distinguished from adjacent surfaces. Return UNCERTAIN if the suspected issue cannot be established from the image.",
                    "The suspected feature is shown with sufficient detail and surrounding context to locate it on the photographed subject. Do not infer its cause, severity or safety implications.")),
            seed(11, AREA_OVERVIEW),
            seed(12, List.of(
                    "A distinct outdoor site section or principal feature is visible with its immediate surroundings.",
                    "The framing and detail allow the visible site section to be documented. Do not infer that every principal area has been covered or that the location is inside an unseen boundary.")),
            seed(13, REQUESTED_LOCATION),
            seed(14, CURRENT_CONDITION),
            seed(15, List.of(
                    "A forest or wooded area with multiple trees or visible canopy is a substantial subject of the image.",
                    "The frame provides an overview of the wooded area, showing canopy distribution and surrounding terrain where visible. Do not infer forest boundaries, coverage totals or changes over time.")),
            seed(16, List.of(
                    "Trees, crowns or foliage are visible as the main inspection subject.",
                    "The crowns, foliage or visible trunks have enough detail to describe their appearance. Do not infer disease, tree death or the cause of discoloration without visible supporting evidence.")),
            seed(17, List.of(
                    "A localized unusual feature in a wooded area is visible, such as smoke, a canopy gap, felled vegetation, exposed ground or a disturbed slope. Return UNCERTAIN if the suspected anomaly cannot be established visually.",
                    "The suspected feature and surrounding vegetation or terrain are visible together to document its location in the scene. Do not infer illegal activity, an incident cause, severity or change since an earlier visit.")),
            seed(18, REQUESTED_LOCATION),
            seed(19, CURRENT_CONDITION),
            seed(20, List.of(
                    "At least one exterior face of a building or construction structure is clearly visible as the main subject.",
                    "The exterior face is sufficiently unobstructed and detailed to document its surfaces. Do not require a whole-building overview or infer that all sides were photographed or a full flight circuit was completed.")));

    private ChecklistDefaultImageCriteria() {}

    public static List<String> forSeed(String code, String content) {
        if (code == null || content == null) return List.of();
        var rubric = SUPPORTED.get(code);
        return rubric != null && rubric.checklistContent().equals(normalize(content))
                ? rubric.criteria() : List.of();
    }

    private static Map.Entry<String, Rubric> seed(int number, List<String> criteria) {
        return Map.entry(ChecklistDefaults.code(number),
                new Rubric(normalize(ChecklistDefaults.CONTENTS.get(number - 1)), criteria));
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record Rubric(String checklistContent, List<String> criteria) {}
}
