package pl.piomin.services.deskpilot.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The authenticated caller: user name from the token and its realm roles.
 */
public record CurrentUser(String username, Set<String> roles) {

    public static final String USER = "user";
    public static final String REVIEWER = "reviewer";
    public static final String ADMIN = "admin";

    public static CurrentUser of(Authentication authentication) {
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(SecurityConfig.ROLE_PREFIX))
                .map(a -> a.substring(SecurityConfig.ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
        return new CurrentUser(authentication.getName(), roles);
    }

    public boolean isReviewer() {
        return roles.contains(REVIEWER);
    }

    public boolean isAdmin() {
        return roles.contains(ADMIN);
    }

    /** Reviewers and admins see every user's runs and tickets. */
    public boolean canSeeAll() {
        return isReviewer() || isAdmin();
    }

    public boolean canSee(String ownerId) {
        return username.equals(ownerId) || canSeeAll();
    }
}
