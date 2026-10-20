package pl.piomin.services.deskpilot.mcp;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.agent.AgentConfig;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

/**
 * What DeskPilot offers to other agents and IDEs over MCP: read-only access to the knowledge base. Tools that change
 * data or act for a user (tickets, memories, escalation) are not exposed: they need DeskPilot's approval gate and run
 * context. These tools need neither, so they behave the same for every authenticated caller.
 */
@Component
class McpServerTools {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentSettingsService settingsService;

    McpServerTools(KnowledgeBaseService knowledgeBaseService, AgentSettingsService settingsService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.settingsService = settingsService;
    }

    public record Article(long id, String title, String content, double relevance) {
    }

    @Tool(name = "search_knowledge_base", description = """
            Search Example Corp's IT helpdesk knowledge base (VPN, passwords, MFA, printers, e-mail, hardware, \
            software, Wi-Fi) and return the most relevant articles. Answer only from the returned articles.""")
    public List<Article> searchKnowledgeBase(
            @ToolParam(description = "Search query describing the problem, e.g. 'outlook not receiving email'") String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        // Same retrieval settings as the live agent (top-K, threshold, hybrid search).
        AgentConfig config = settingsService.current();
        return knowledgeBaseService.search(query, config.ragTopK(), config.ragSimilarityThreshold(), config.hybridSearch())
                .stream()
                .map(hit -> new Article(hit.articleId(), hit.title(), hit.content(), Math.round(hit.score() * 100) / 100.0))
                .toList();
    }

    @Tool(name = "get_knowledge_article", description = "Read one knowledge-base article by its numeric id.")
    public Article getKnowledgeArticle(@ToolParam(description = "Article id, as returned by search_knowledge_base") long id) {
        return knowledgeBaseService.find(id)
                .map(article -> new Article(id, article.getTitle(), article.getContent(), 1.0))
                .orElseThrow(() -> new NotFoundException("Article " + id + " not found"));
    }
}
