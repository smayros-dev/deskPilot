package pl.piomin.services.deskpilot.dto;

import java.time.Instant;

/**
 * @param title          the first question, or the name the user gave the conversation; null before the first message
 * @param lastActivityAt time of the last message
 * @param runs           number of messages (runs) in the conversation
 */
public record SessionResponse(String id, String userId, Instant createdAt, String title, Instant lastActivityAt, long runs) {
}
