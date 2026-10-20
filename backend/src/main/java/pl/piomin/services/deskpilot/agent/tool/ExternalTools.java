package pl.piomin.services.deskpilot.agent.tool;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;

/**
 * Tools that live outside DeskPilot (e.g. on MCP servers). They can act on real systems, so the agent never runs one
 * without a human approval, whatever the settings say.
 */
public interface ExternalTools {

    /** Prefix of every external tool name, so they can never clash with (or impersonate) a built-in tool. */
    String NAME_PREFIX = "mcp_";

    /** The tools currently available; empty (never an exception) when a source is unreachable. */
    List<ToolCallback> callbacks();
}
