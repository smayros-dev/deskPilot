package pl.piomin.services.agentai.agent.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Tool} method that has side effects a human must approve before it runs
 * (human-in-the-loop gate). The agent pauses the run until a reviewer approves or rejects the call.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresApproval {
}
