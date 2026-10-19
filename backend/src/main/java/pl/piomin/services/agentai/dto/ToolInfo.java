package pl.piomin.services.agentai.dto;

/** A tool the agent can be given, as shown on the settings page. */
public record ToolInfo(String name, String description, boolean approvalByDefault) {
}
