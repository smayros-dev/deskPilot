package pl.piomin.services.deskpilot.notifications;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Remembers each user's e-mail address from their access token, for ticket confirmations. */
@Service
@Transactional(readOnly = true)
public class ContactService {

    private final UserContactRepository repository;

    public ContactService(UserContactRepository repository) {
        this.repository = repository;
    }

    /** Stores or updates the caller's address when the token carries an {@code email} claim. */
    @Transactional
    public void remember(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return;
        }
        String email = jwt.getToken().getClaimAsString("email");
        if (email == null || email.isBlank() || email.length() > 254) {
            return;
        }
        repository.findById(authentication.getName()).ifPresentOrElse(
                contact -> contact.update(email.strip()),
                () -> repository.save(new UserContact(authentication.getName(), email.strip())));
    }

    public Optional<String> emailOf(String userId) {
        return repository.findById(userId).map(UserContact::getEmail);
    }
}
