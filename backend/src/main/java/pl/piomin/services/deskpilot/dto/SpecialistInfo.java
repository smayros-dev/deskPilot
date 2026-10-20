package pl.piomin.services.deskpilot.dto;

import java.util.List;

/**
 * A specialist agent as shown in Settings (12f).
 *
 * @param tools    the installed tools it may use (before the tools disabled in the settings)
 * @param examples number of example requests it is given
 */
public record SpecialistInfo(String id, String name, String description, List<String> keywords, List<String> tools,
                             int examples) {
}
