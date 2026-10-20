package pl.piomin.services.deskpilot.cloud;

/** A cloud provider was disabled or lost its API key, so nothing can run on it any more. Published inside the change. */
public record ProviderUnavailable(String providerId, String label) {
}
