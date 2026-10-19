package pl.piomin.services.deskpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the real local model. Runs only when OLLAMA_IT=true, because it needs a running Ollama
 * with the configured model pulled and takes several seconds on CPU.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "OLLAMA_IT", matches = "true")
class OllamaSmokeTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void localModelAnswers() {
        String answer = chatClientBuilder.build()
                .prompt("Reply with exactly the word PONG and nothing else.")
                .call()
                .content();

        assertThat(answer).containsIgnoringCase("pong");
    }
}
