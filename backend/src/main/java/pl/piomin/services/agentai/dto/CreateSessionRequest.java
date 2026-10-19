package pl.piomin.services.agentai.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param userId who is chatting; optional until authentication is added (Step 11), defaults to a demo user
 */
public record CreateSessionRequest(
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._@-]*", message = "may contain letters, digits and . _ @ - only")
        String userId) {
}
