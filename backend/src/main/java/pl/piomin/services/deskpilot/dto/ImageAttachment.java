package pl.piomin.services.deskpilot.dto;

import java.util.Base64;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A screenshot sent with a chat message, base64-encoded. It is read by the vision model and not stored.
 *
 * @param mimeType {@code image/png}, {@code image/jpeg} or {@code image/webp}
 * @param data     base64 of the image bytes (at most {@value #MAX_BYTES} bytes once decoded)
 */
public record ImageAttachment(
        @NotBlank @Pattern(regexp = "image/(png|jpeg|webp)", message = "must be a PNG, JPEG or WebP image") String mimeType,
        @NotBlank @Size(max = 7_000_000) String data) {

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    /** @throws IllegalArgumentException if the data is not base64 or the image is too large */
    public byte[] bytes() {
        String base64 = data.startsWith("data:") ? data.substring(data.indexOf(',') + 1) : data;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The screenshot is not valid base64 data");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("The screenshot is larger than 5 MB");
        }
        return bytes;
    }
}
