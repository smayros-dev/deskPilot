package pl.piomin.services.deskpilot.model;

/** Who started a run: a user in the chat, or the evaluation runner (kept out of the dashboard and the reviewers' queue). */
public enum RunSource {
    CHAT,
    EVALUATION
}
