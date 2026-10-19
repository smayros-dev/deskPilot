package pl.piomin.services.deskpilot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalRequest(@NotNull Boolean approved, @Size(max = 1000) String comment) {
}
