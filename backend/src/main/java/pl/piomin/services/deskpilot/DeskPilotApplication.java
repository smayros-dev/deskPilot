package pl.piomin.services.deskpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DeskPilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(DeskPilotApplication.class, args);
	}

}
