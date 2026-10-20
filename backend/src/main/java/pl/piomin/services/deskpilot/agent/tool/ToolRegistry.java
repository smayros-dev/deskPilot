package pl.piomin.services.deskpilot.agent.tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * All tools the agent may call, plus the approval policy derived from {@link RequiresApproval}.
 * Unknown tool names (a model hallucinating a tool) are rejected here, not executed. Besides the built-in tools, it
 * includes {@link ExternalTools} (MCP servers), which are looked up on every call because servers can come and go.
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolCallback> builtIn;
    private final Set<String> approvalRequired;
    private final List<ExternalTools> externalSources;

    @Autowired
    public ToolRegistry(List<AgentTools> toolBeans, List<ExternalTools> externalSources) {
        this.builtIn = Arrays.stream(ToolCallbacks.from(toolBeans.toArray()))
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity(),
                        (a, b) -> { throw new IllegalStateException("Duplicate tool " + a.getToolDefinition().name()); },
                        LinkedHashMap::new));
        this.approvalRequired = toolBeans.stream()
                .flatMap(bean -> Arrays.stream(AopUtils.getTargetClass(bean).getMethods()))
                .filter(method -> method.isAnnotationPresent(Tool.class) && method.isAnnotationPresent(RequiresApproval.class))
                .map(ToolRegistry::toolName)
                .collect(Collectors.toUnmodifiableSet());
        this.externalSources = List.copyOf(externalSources);
    }

    public ToolRegistry(List<AgentTools> toolBeans) {
        this(toolBeans, List.of());
    }

    public List<ToolCallback> all() {
        return Stream.concat(builtIn.values().stream(), external().stream()).toList();
    }

    public Optional<ToolCallback> find(String name) {
        ToolCallback tool = builtIn.get(name);
        if (tool != null) {
            return Optional.of(tool);
        }
        return isExternal(name)
                ? external().stream().filter(cb -> cb.getToolDefinition().name().equals(name)).findFirst()
                : Optional.empty();
    }

    /** Tools annotated {@code @RequiresApproval}: the default approval policy, which settings can change. */
    public Set<String> defaultApprovalTools() {
        return approvalRequired;
    }

    /** External tools always need a human approval, whatever the settings say. */
    public boolean isExternal(String name) {
        return name != null && name.startsWith(ExternalTools.NAME_PREFIX) && !builtIn.containsKey(name);
    }

    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>(builtIn.keySet());
        external().forEach(cb -> names.add(cb.getToolDefinition().name()));
        return names;
    }

    private List<ToolCallback> external() {
        return externalSources.stream()
                .flatMap(source -> source.callbacks().stream())
                // Only prefixed names are accepted, so an external server cannot shadow a built-in tool.
                .filter(cb -> cb.getToolDefinition().name().startsWith(ExternalTools.NAME_PREFIX))
                .filter(cb -> !builtIn.containsKey(cb.getToolDefinition().name()))
                .toList();
    }

    // Same naming rule as Spring AI: @Tool(name) if set, otherwise the method name.
    private static String toolName(Method method) {
        String name = method.getAnnotation(Tool.class).name();
        return StringUtils.hasText(name) ? name : method.getName();
    }
}
