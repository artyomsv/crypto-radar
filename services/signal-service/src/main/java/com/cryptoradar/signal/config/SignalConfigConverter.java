package com.cryptoradar.signal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that serializes {@link SignalConfig} to/from the JSONB column.
 *
 * <p>The column is defined as {@code columnDefinition = "jsonb"} on the entity,
 * and Hibernate passes the value as a String through this converter. PostgreSQL
 * accepts a plain JSON string for JSONB columns — no special type handler required.
 */
@Converter
public class SignalConfigConverter implements AttributeConverter<SignalConfig, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(SignalConfig config) {
        if (config == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SignalConfig to JSON", e);
        }
    }

    @Override
    public SignalConfig convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, SignalConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize SignalConfig from JSON: " + e.getMessage(), e);
        }
    }
}
