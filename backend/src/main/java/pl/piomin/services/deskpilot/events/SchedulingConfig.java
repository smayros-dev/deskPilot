package pl.piomin.services.deskpilot.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the keep-alive of the live event connections ({@link LiveEventHub#heartbeat()}). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
