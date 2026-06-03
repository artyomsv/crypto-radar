package com.cryptoradar.signal.backtest;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that passes a plain JSON string through to a PostgreSQL JSONB column.
 *
 * <p>Without this converter Hibernate maps {@code String} fields to {@code varchar},
 * which PostgreSQL rejects on insert into a {@code jsonb} column. Applying this
 * converter keeps the field as a Java {@code String} while telling Hibernate
 * the wire-type is {@code jsonb}.
 */
@Converter
class JsonbStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}
