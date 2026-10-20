package pl.piomin.services.deskpilot.mcp;

import java.util.Locale;

import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.piomin.services.deskpilot.agent.tool.ExternalTools;

/**
 * Model Context Protocol in both directions. As a server ({@code /mcp}, Streamable HTTP, JWT with role {@code user}),
 * DeskPilot exposes {@link McpServerTools}. As a client, it adds the tools of the MCP servers configured under
 * {@code spring.ai.mcp.client.*} to the agent (see {@link McpClientTools}).
 */
@Configuration
class McpConfig {

    /** The only tools the MCP server exposes (Spring AI exposes every non-MCP {@link ToolCallbackProvider} bean). */
    @Bean
    ToolCallbackProvider deskpilotMcpServerTools(McpServerTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    /**
     * External tool names: {@code mcp_<server>_<tool>}. They cannot clash with built-in tools, and the name shows
     * reviewers where an action will run.
     */
    @Bean
    McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return (connection, tool) -> {
            String server = connection.initializeResult() == null || connection.initializeResult().serverInfo() == null
                    ? "server" : connection.initializeResult().serverInfo().name();
            String name = (ExternalTools.NAME_PREFIX + server + "_" + tool.name())
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            return name.length() <= 64 ? name : name.substring(0, 64);
        };
    }

    /** Nothing about the run or the user (ids, settings) is sent to external servers. */
    @Bean
    ToolContextToMcpMetaConverter toolContextToMcpMetaConverter() {
        return ToolContextToMcpMetaConverter.noOp();
    }
}
