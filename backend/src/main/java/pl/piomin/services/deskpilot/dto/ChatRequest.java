package pl.piomin.services.deskpilot.dto;

import jakarta.validation.constraints.NotBlank;

/** Length and content limits are enforced by the guardrails, which are configurable. */
public record ChatRequest(@NotBlank String message) {
}
