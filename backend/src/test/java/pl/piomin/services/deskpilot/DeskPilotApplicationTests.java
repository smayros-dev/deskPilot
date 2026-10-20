package pl.piomin.services.deskpilot;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import pl.piomin.services.deskpilot.agent.ResilientChatModel;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DeskPilotApplicationTests {

	@Autowired
	private ChatModel chatModel;

	@Test
	void contextLoads() {
	}

	@Test
	void everyModelCallGoesThroughTheCircuitBreaker() {
		assertThat(chatModel).isInstanceOf(ResilientChatModel.class);
	}

}
