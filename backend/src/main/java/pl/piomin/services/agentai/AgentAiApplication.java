package pl.piomin.services.agentai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentAiApplication.class, args);
	}

}
