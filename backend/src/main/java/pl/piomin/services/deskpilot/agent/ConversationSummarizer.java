package pl.piomin.services.deskpilot.agent;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps long conversations within the context window (12h): once the short-term memory holds more than
 * {@code agent.memory.summarize-after} messages, the older ones are condensed into a summary stored on the session,
 * and only the last {@code agent.memory.keep-recent} messages stay verbatim. Runs in the background after an answer;
 * if the conversation moves on meanwhile, the result is dropped and the next answer tries again.
 */
@Component
class ConversationSummarizer {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationSummarizer.class);

    static final String PROMPT = """
            Summarize this IT helpdesk conversation for the assistant that will continue it.
            Write at most 6 short bullet points: the user's setup (devices, software), the problems raised, what was \
            tried and the outcome, tickets requested, and anything still open. Keep facts like error codes, product \
            names and ticket numbers. Do not include passwords or other secrets. Write in the language of the conversation.""";

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final ChatOptionsFactory chatOptionsFactory;
    private final SessionService sessionService;
    private final AgentMetrics metrics;
    private final int summarizeAfter;
    private final int keepRecent;

    ConversationSummarizer(ChatModel chatModel, ChatMemory chatMemory, ChatOptionsFactory chatOptionsFactory,
                           SessionService sessionService, AgentMetrics metrics,
                           @Value("${agent.memory.summarize-after:12}") int summarizeAfter,
                           @Value("${agent.memory.keep-recent:4}") int keepRecent) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.chatOptionsFactory = chatOptionsFactory;
        this.sessionService = sessionService;
        this.metrics = metrics;
        this.summarizeAfter = summarizeAfter;
        this.keepRecent = Math.max(2, keepRecent);
    }

    /** Starts {@link #summarizeIfLong} on a virtual thread; never fails the caller. */
    void summarizeIfLongAsync(String sessionId, AgentConfig config) {
        if (summarizeAfter <= 0) {
            return;
        }
        Thread.ofVirtual().name("summarize-" + sessionId).start(() -> {
            try {
                summarizeIfLong(sessionId, config);
            } catch (RuntimeException e) {
                LOG.warn("Could not summarize conversation {}", sessionId, e);
            }
        });
    }

    /** @return true if the conversation was summarized */
    boolean summarizeIfLong(String sessionId, AgentConfig config) {
        List<Message> messages = chatMemory.get(sessionId);
        if (summarizeAfter <= 0 || messages.size() <= summarizeAfter) {
            return false;
        }
        List<Message> older = messages.subList(0, messages.size() - keepRecent);
        List<Message> recent = List.copyOf(messages.subList(messages.size() - keepRecent, messages.size()));
        String previous = sessionService.get(sessionId).getSummary();
        String material = (previous == null ? "" : "Summary of the conversation so far:\n" + previous + "\n\n")
                + "Messages to add to the summary:\n" + transcript(older);

        long start = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(List.of(new SystemMessage(PROMPT), new UserMessage(material)),
                chatOptionsFactory.create(config, List.of())));
        Usage usage = response.getMetadata().getUsage();
        metrics.modelCall((System.nanoTime() - start) / 1_000_000,
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
        String summary = response.getResult() == null ? null : response.getResult().getOutput().getText();
        if (summary == null || summary.isBlank()) {
            return false;
        }
        // Compare-and-set: a message added while the model was summarizing must not be lost.
        if (chatMemory.get(sessionId).size() != messages.size()) {
            LOG.info("Conversation {} moved on while summarizing; will summarize after the next answer", sessionId);
            return false;
        }
        sessionService.updateSummary(sessionId, summary.strip());
        chatMemory.clear(sessionId);
        chatMemory.add(sessionId, recent);
        LOG.info("Summarized {} older messages of conversation {}", older.size(), sessionId);
        return true;
    }

    private static String transcript(List<Message> messages) {
        return messages.stream()
                .map(m -> (m.getMessageType() == MessageType.USER ? "User: " : "Assistant: ") + m.getText())
                .collect(Collectors.joining("\n"));
    }
}
