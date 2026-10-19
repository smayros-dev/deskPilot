package pl.piomin.services.agentai.dto;

import java.time.Instant;

public record SessionResponse(String id, String userId, Instant createdAt) {
}
