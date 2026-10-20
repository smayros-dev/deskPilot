package pl.piomin.services.deskpilot.mcp;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.agent.tool.ExternalTools;

/**
 * Tools of the external MCP servers configured under {@code spring.ai.mcp.client.*} (none by default). Spring AI's
 * provider caches the tool list and refreshes it when a server announces a change.
 */
@Component
class McpClientTools implements ExternalTools {

    private static final Logger LOG = LoggerFactory.getLogger(McpClientTools.class);

    private final ObjectProvider<SyncMcpToolCallbackProvider> providers;

    McpClientTools(ObjectProvider<SyncMcpToolCallbackProvider> providers) {
        this.providers = providers;
    }

    @Override
    public List<ToolCallback> callbacks() {
        try {
            return providers.stream().flatMap(provider -> Arrays.stream(provider.getToolCallbacks())).toList();
        } catch (RuntimeException e) {
            LOG.warn("MCP tools unavailable: {}", e.getMessage());
            return List.of();
        }
    }
}
