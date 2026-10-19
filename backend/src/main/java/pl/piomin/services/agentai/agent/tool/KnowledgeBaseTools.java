package pl.piomin.services.agentai.agent.tool;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import pl.piomin.services.agentai.agent.AgentConfig;
import pl.piomin.services.agentai.service.KnowledgeBaseService;

@Component
public class KnowledgeBaseTools implements AgentTools {

    static final String NO_RESULTS_GUIDANCE = "No relevant article found. Try once more with different keywords "
            + "(e.g. the product or symptom name). If that also fails, do not guess: tell the user the knowledge base "
            + "has no answer and offer to create a ticket.";

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseTools(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    public record Article(long id, String title, String content, double relevance) {
    }

    public record SearchResult(List<Article> articles, String guidance) {
    }

    @Tool(name = "search_knowledge_base", description = """
            Search the IT helpdesk knowledge base (VPN, passwords, MFA, printers, e-mail, hardware, software, Wi-Fi). \
            Always search before answering a technical question, and base the answer only on the returned articles.""")
    public SearchResult searchKnowledgeBase(
            @ToolParam(description = "Search query describing the problem, e.g. 'outlook not receiving email'") String query,
            ToolContext toolContext) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        AgentConfig config = RunContext.from(toolContext).config();
        List<Article> articles = knowledgeBaseService.search(query, config.ragTopK(), config.ragSimilarityThreshold()).stream()
                .map(hit -> new Article(hit.articleId(), hit.title(), hit.content(), Math.round(hit.score() * 100) / 100.0))
                .toList();
        return new SearchResult(articles, articles.isEmpty() ? NO_RESULTS_GUIDANCE : null);
    }
}
