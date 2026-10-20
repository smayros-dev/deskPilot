package pl.piomin.services.deskpilot.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Long conversations (12h) with a scripted model: summaries answer summary prompts, answers everything else. */
@SpringBootTest(properties = {"agent.memory.summarize-after=4", "agent.memory.keep-recent=2"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ConversationSummarizerTest {

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private ConversationSummarizer summarizer;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AgentSettingsService settingsService;

    @Autowired
    private ChatMemory chatMemory;

    private final AtomicInteger answers = new AtomicInteger();
    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = sessionService.create("alice").getId();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (isSummaryRequest(prompt)) {
                return response("- The user works on a MacBook Pro\n- VPN drops were fixed by switching to IPSec");
            }
            return response("Answer " + answers.incrementAndGet());
        });
    }

    @Test
    void olderMessagesAreSummarizedAndTheSummaryReachesTheModel() {
        orchestrator.chat(sessionId, "I work on a MacBook Pro");
        orchestrator.chat(sessionId, "My VPN drops every few minutes");
        orchestrator.chat(sessionId, "Switching to IPSec fixed it, thanks");

        String summary = awaitSummary();
        assertThat(summary).contains("MacBook Pro", "IPSec");
        assertThat(chatMemory.get(sessionId)).hasSize(2)
                .extracting(Message::getText).containsExactly("Switching to IPSec fixed it, thanks", "Answer 3");

        orchestrator.chat(sessionId, "Which laptop do I use?");

        Prompt last = lastAgentPrompt();
        assertThat(last.getInstructions().getFirst().getText())
                .contains("Summary of the earlier part of this conversation", "MacBook Pro");
    }

    @Test
    void aMessageAddedWhileSummarizingIsNeverLost() {
        for (int i = 1; i <= 3; i++) {
            chatMemory.add(sessionId, List.of(new UserMessage("Question " + i), new AssistantMessage("Answer " + i)));
        }
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            // The user sends another message while the model is summarizing.
            chatMemory.add(sessionId, List.of(new UserMessage("Question 4"), new AssistantMessage("Answer 4")));
            return response("- summary");
        });

        boolean summarized = summarizer.summarizeIfLong(sessionId, settingsService.current());

        assertThat(summarized).isFalse();
        assertThat(sessionService.get(sessionId).getSummary()).isNull();
        assertThat(chatMemory.get(sessionId)).hasSize(8);
    }

    @Test
    void shortConversationsAreLeftAlone() {
        orchestrator.chat(sessionId, "Hi");
        orchestrator.chat(sessionId, "Thanks");

        assertThat(summarizer.summarizeIfLong(sessionId, settingsService.current())).isFalse();
        assertThat(chatMemory.get(sessionId)).hasSize(4);
    }

    private String awaitSummary() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            String summary = sessionService.get(sessionId).getSummary();
            if (summary != null && chatMemory.get(sessionId).size() == 2) {
                return summary;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("The conversation was not summarized");
    }

    private Prompt lastAgentPrompt() {
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, atLeastOnce()).call(prompts.capture());
        return prompts.getAllValues().stream().filter(p -> !isSummaryRequest(p)).reduce((a, b) -> b).orElseThrow();
    }

    private static boolean isSummaryRequest(Prompt prompt) {
        return prompt.getInstructions().getFirst().getText().startsWith(ConversationSummarizer.PROMPT.substring(0, 40));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
    }
}
