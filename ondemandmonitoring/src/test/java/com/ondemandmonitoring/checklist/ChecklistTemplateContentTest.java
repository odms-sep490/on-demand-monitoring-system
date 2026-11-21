package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.checklist.util.ChecklistContentNormalizer;
import com.ondemandmonitoring.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChecklistTemplateContentTest {
    @Test void keepsDefaultWhenNoOverrideIsProvided() {
        assertEquals("Quay video", ChecklistContentNormalizer.serviceTemplateContent("Quay video", null));
    }
    @Test void acceptsEquivalentNormalizedTextForExistingClients() {
        assertEquals("Quay video", ChecklistContentNormalizer.serviceTemplateContent("Quay video", " Quay\nvideo "));
    }
    @Test void rejectsDifferentOrBlankContent() {
        assertThrows(ApiException.class, () -> ChecklistContentNormalizer.serviceTemplateContent("Quay video", "Chụp ảnh"));
        assertThrows(ApiException.class, () -> ChecklistContentNormalizer.serviceTemplateContent("Quay video", " "));
    }
}
