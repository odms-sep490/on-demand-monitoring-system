package com.ondemandmonitoring.checklist.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StringListJsonConverterTest {
    private final StringListJsonConverter converter = new StringListJsonConverter();

    @Test void roundTripKeepsOrderAndUnicode() {
        var steps = List.of("Bay một vòng", "Chụp \"cận\" vị trí", "Ghi chú");
        assertEquals(steps, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(steps)));
    }

    @Test void nullAndEmptyAreStoredAsNull() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToDatabaseColumn(List.of()));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToEntityAttribute("  "));
    }
}
