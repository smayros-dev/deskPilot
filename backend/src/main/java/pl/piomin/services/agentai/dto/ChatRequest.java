package pl.piomin.services.agentai.dto;

import jakarta.validation.constraints.NotBlank;

/** Length and content limits are enforced by the guardrails, which are configurable. */
public record ChatRequest(@NotBlank String message) {
}
