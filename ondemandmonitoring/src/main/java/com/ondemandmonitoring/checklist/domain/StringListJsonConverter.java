package com.ondemandmonitoring.checklist.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Stores an ordered list of strings as a JSON array in a text column; null/empty is stored as null. */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        return MAPPER.writeValueAsString(attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) return null;
        return MAPPER.readValue(column, TYPE);
    }
}
