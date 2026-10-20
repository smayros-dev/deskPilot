package pl.piomin.services.deskpilot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** Length and content limits are enforced by the guardrails, which are configurable. */
public record ChatRequest(@NotBlank String message, @Valid ImageAttachment image) {

    public ChatRequest(String message) {
        this(message, null);
    }
}
