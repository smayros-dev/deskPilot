package pl.piomin.services.deskpilot.dto;

import java.time.Instant;

public record SessionResponse(String id, String userId, Instant createdAt) {
}
