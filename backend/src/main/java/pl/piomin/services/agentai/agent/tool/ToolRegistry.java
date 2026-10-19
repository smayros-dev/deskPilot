package pl.piomin.services.agentai.agent.tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * All tools the agent may call, plus the approval policy derived from {@link RequiresApproval}.
 * Unknown tool names (a model hallucinating a tool) are rejected here, not executed.
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolCallback> callbacks;
    private final Set<String> approvalRequired;

    public ToolRegistry(List<AgentTools> toolBeans) {
        this.callbacks = Arrays.stream(ToolCallbacks.from(toolBeans.toArray()))
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity(),
                        (a, b) -> { throw new IllegalStateException("Duplicate tool " + a.getToolDefinition().name()); },
                        LinkedHashMap::new));
        this.approvalRequired = toolBeans.stream()
                .flatMap(bean -> Arrays.stream(AopUtils.getTargetClass(bean).getMethods()))
                .filter(method -> method.isAnnotationPresent(Tool.class) && method.isAnnotationPresent(RequiresApproval.class))
                .map(ToolRegistry::toolName)
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<ToolCallback> all() {
        return List.copyOf(callbacks.values());
    }

    public Optional<ToolCallback> find(String name) {
        return Optional.ofNullable(callbacks.get(name));
    }

    /** Tools annotated {@code @RequiresApproval}: the default approval policy, which settings can change. */
    public Set<String> defaultApprovalTools() {
        return approvalRequired;
    }

    public Set<String> names() {
        return callbacks.keySet();
    }

    // Same naming rule as Spring AI: @Tool(name) if set, otherwise the method name.
    private static String toolName(Method method) {
        String name = method.getAnnotation(Tool.class).name();
        return StringUtils.hasText(name) ? name : method.getName();
    }
}
