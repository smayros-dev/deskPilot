package pl.piomin.services.deskpilot.events;

/** DeskPilot stopped an experiment itself because a variant could no longer run; the admins are told after commit. */
public record ExperimentAutoStopped(String experimentId, String name, String reason) {
}
