package com.vit.hackathon.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

@Converter
public class ImportedTeamFieldsConverter implements AttributeConverter<List<ImportedTeamField>, String> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<ImportedTeamField>> FIELD_LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<ImportedTeamField> fields) {
        try {
            return OBJECT_MAPPER.writeValueAsString(fields == null ? List.of() : fields);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize team details", exception);
        }
    }

    @Override
    public List<ImportedTeamField> convertToEntityAttribute(String storedFields) {
        if (storedFields == null || storedFields.isBlank()) return new ArrayList<>();
        try {
            List<ImportedTeamField> fields = OBJECT_MAPPER.readValue(storedFields, FIELD_LIST_TYPE);
            return fields == null ? new ArrayList<>() : fields;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read stored team details", exception);
        }
    }
}
