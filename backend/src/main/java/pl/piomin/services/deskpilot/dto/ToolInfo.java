package pl.piomin.services.deskpilot.dto;

/**
 * A tool the agent can be given, as shown on the settings page.
 *
 * @param external provided by an MCP server; always needs approval
 */
public record ToolInfo(String name, String description, boolean approvalByDefault, boolean external) {
}
