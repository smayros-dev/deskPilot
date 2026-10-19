package pl.piomin.services.deskpilot.dto;

import java.time.Instant;

import pl.piomin.services.deskpilot.model.MemoryFact;

public record MemoryResponse(long id, String content, Instant createdAt) {

    public static MemoryResponse of(MemoryFact fact) {
        return new MemoryResponse(fact.getId(), fact.getContent(), fact.getCreatedAt());
    }
}
