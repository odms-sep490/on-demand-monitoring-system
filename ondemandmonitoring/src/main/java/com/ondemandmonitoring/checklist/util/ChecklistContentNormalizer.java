package com.ondemandmonitoring.checklist.util;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.text.Normalizer;

public final class ChecklistContentNormalizer {
    private ChecklistContentNormalizer() {}
    public static String canonicalize(String raw) {
        return Normalizer.normalize(raw, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
    }
    public static String content(String raw) {
        if (raw == null) throw new ApiException(ErrorCode.VALIDATION_ERROR, "Checklist content is required");
        String value = canonicalize(raw);
        if (value.isBlank() || value.length() > 500)
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Checklist content must contain 1 to 500 characters");
        return value;
    }
}
