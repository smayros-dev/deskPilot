package pl.piomin.services.deskpilot.agent.specialist;

import java.util.List;

/**
 * A specialist agent (12f): the part of the system prompt, the tools and the examples used for one kind of request.
 *
 * @param keywords words or phrases that route a message here (matched at the start of a word, accents ignored)
 * @param tools    tools it may use; a name ending in {@code *} is a prefix (e.g. {@code mcp_*}); null = all tools
 * @param prompt   added to the system prompt; null for the general agent
 * @param examples typical requests with how to handle them: shown to the model and used to route by meaning
 */
public record Specialist(String id, String name, String description, List<String> keywords, List<String> tools,
                         String prompt, List<Example> examples) {

    public static final String GENERAL_ID = "general";

    /** Handles everything the specialists do not: the agent as it works without routing. */
    public static final Specialist GENERAL = new Specialist(GENERAL_ID, "General IT",
            "Any other IT request.", List.of(), null, null, List.of());

    public record Example(String question, String approach) {
    }

    public Specialist {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        examples = examples == null ? List.of() : List.copyOf(examples);
        tools = tools == null ? null : List.copyOf(tools);
    }

    public boolean isGeneral() {
        return GENERAL_ID.equals(id);
    }

    /** Whether this specialist may use the tool; tools disabled in the settings stay disabled anyway. */
    public boolean allowsTool(String tool) {
        return tools == null || tools.stream().anyMatch(pattern -> pattern.endsWith("*")
                ? tool.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(tool));
    }

    /** What is added to the system prompt: the specialist's instructions and its examples. */
    public String promptAddition() {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\nThis request was handed to you as the ").append(name)
                .append(" specialist. ").append(prompt.strip());
        if (!examples.isEmpty()) {
            text.append("\n\nHow similar requests are handled:");
            examples.forEach(example -> text.append("\n- \"").append(example.question()).append("\" → ")
                    .append(example.approach()));
        }
        return text.toString();
    }
}
