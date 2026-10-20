package pl.piomin.services.deskpilot.model;

import java.util.Locale;
import java.util.Optional;

/** File types the knowledge base accepts, recognised by their extension. */
public enum DocumentFormat {
    PDF("pdf"),
    MARKDOWN("md", "markdown"),
    TEXT("txt"),
    HTML("html", "htm");

    private final String[] extensions;

    DocumentFormat(String... extensions) {
        this.extensions = extensions;
    }

    public static Optional<DocumentFormat> fromFileName(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return Optional.empty();
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            for (String candidate : format.extensions) {
                if (candidate.equals(extension)) {
                    return Optional.of(format);
                }
            }
        }
        return Optional.empty();
    }
}
