package pl.piomin.services.deskpilot.exception;

/** An uploaded file the knowledge base cannot read (answered with 415 Unsupported Media Type). */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
