package pl.piomin.services.agentai.model;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a set of names (e.g. tool names) as a sorted, comma-separated string. */
@Converter
public class StringSetConverter implements AttributeConverter<Set<String>, String> {

    @Override
    public String convertToDatabaseColumn(Set<String> values) {
        return values == null ? "" : String.join(",", new TreeSet<>(values));
    }

    @Override
    public Set<String> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(column.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
