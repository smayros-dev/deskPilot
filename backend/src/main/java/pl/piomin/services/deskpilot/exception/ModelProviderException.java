package pl.piomin.services.deskpilot.exception;

/** A cloud model provider refused or failed the request (bad key, quota, unknown model, outage). */
public class ModelProviderException extends RuntimeException {

    private final String provider;
    private final int status;

    public ModelProviderException(String provider, int status, String message) {
        super(message);
        this.provider = provider;
        this.status = status;
    }

    public String getProvider() {
        return provider;
    }

    /** HTTP status returned by the provider. */
    public int getStatus() {
        return status;
    }

    /** A short explanation for the user, without the provider's raw error text. */
    public String explanation() {
        String name = Character.toUpperCase(provider.charAt(0)) + provider.substring(1);
        return switch (status) {
            case 401, 403 -> name + " rejected the API key. An admin can update it in Settings > Providers.";
            case 404 -> name + " does not know the selected model. An admin can pick another one in Settings.";
            case 429 -> name + " quota or rate limit reached. Try again later or switch to the local model.";
            default -> name + " could not answer (HTTP " + status + "). Try again later or switch to the local model.";
        };
    }
}
