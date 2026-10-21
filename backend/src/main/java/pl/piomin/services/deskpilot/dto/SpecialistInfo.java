package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.List;

import pl.piomin.services.deskpilot.agent.specialist.Specialist;

/**
 * A specialist agent (12f), as listed for everyone and edited by admins on the Specialists page (13g). The fields
 * after {@code examples} are only filled for admins.
 *
 * @param tools        the installed tools it may use (before the tools disabled in the settings)
 * @param examples     number of example requests it is given
 * @param builtIn      the general agent, which cannot be changed
 * @param allowedTools the tool patterns as stored ({@code mcp_*} style prefixes); null = every tool
 */
public record SpecialistInfo(String id, String name, String description, List<String> keywords, List<String> tools,
                             int examples, boolean enabled, boolean builtIn, String prompt, List<String> allowedTools,
                             List<Specialist.Example> exampleList, Integer sortOrder, String updatedBy, Instant updatedAt) {

    /** For any user: what the specialist does, not its instructions. */
    public static SpecialistInfo summary(Specialist specialist, List<String> tools, boolean enabled) {
        return new SpecialistInfo(specialist.id(), specialist.name(), specialist.description(), specialist.keywords(),
                tools, specialist.examples().size(), enabled, specialist.isGeneral(), null, null, null, null, null, null);
    }

    /** For admins: everything the Specialists page edits. */
    public static SpecialistInfo full(Specialist specialist, List<String> tools, boolean enabled, Integer sortOrder,
                                      String updatedBy, Instant updatedAt) {
        return new SpecialistInfo(specialist.id(), specialist.name(), specialist.description(), specialist.keywords(),
                tools, specialist.examples().size(), enabled, specialist.isGeneral(), specialist.prompt(),
                specialist.tools(), specialist.examples(), sortOrder, updatedBy, updatedAt);
    }
}
