package pl.piomin.services.deskpilot.guardrail;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import pl.piomin.services.deskpilot.config.AgentProperties;

/**
 * Deterministic safety checks around the model:
 * <ul>
 *   <li>input: length limit and prompt-injection markers, checked before a run starts;</li>
 *   <li>output: secrets and card numbers redacted from the final answer;</li>
 *   <li>tools: oversized results truncated, secrets kept out of long-term memory.</li>
 * </ul>
 * Pattern checks are a first line of defence, not a guarantee; the system prompt also tells the model
 * to treat tool results as data.
 */
@Service
public class GuardrailService {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern CARD_CANDIDATE = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            // "password: hunter2", "pwd = ...", "mot de passe : ..."
            Pattern.compile("(?i)\\b(password|passwd|pwd|mot de passe|passcode|pin)\\s*[:=]\\s*\\S+"),
            // API keys / tokens with well-known prefixes
            Pattern.compile("\\b(sk|pk|rk)-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"));

    private final AgentProperties.Guardrails properties;
    private final List<Pattern> blockedPatterns;

    public GuardrailService(AgentProperties properties) {
        this.properties = properties.guardrails();
        this.blockedPatterns = this.properties.blockedPatterns().stream()
                .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
    }

    public record Sanitized(String text, int redactions) {
    }

    /** @throws GuardrailViolationException if the message must not reach the model */
    public void checkUserInput(String message) {
        if (message == null || message.isBlank()) {
            throw new GuardrailViolationException("empty", "Message must not be empty");
        }
        if (message.length() > properties.maxInputChars()) {
            throw new GuardrailViolationException("too_long",
                    "Message is longer than " + properties.maxInputChars() + " characters");
        }
        for (Pattern pattern : blockedPatterns) {
            if (pattern.matcher(message).find()) {
                throw new GuardrailViolationException("prompt_injection",
                        "Message was blocked by the safety filter. Please rephrase your IT question.");
            }
        }
    }

    /** Replaces secrets and valid card numbers with {@value #REDACTED}. */
    public Sanitized sanitizeOutput(String text) {
        if (text == null || text.isEmpty()) {
            return new Sanitized(text, 0);
        }
        int redactions = 0;
        String result = text;
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                redactions++;
                matcher.appendReplacement(sb, REDACTED);
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }
        Matcher cards = CARD_CANDIDATE.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (cards.find()) {
            if (isLuhnValid(cards.group())) {
                redactions++;
                cards.appendReplacement(sb, REDACTED);
            } else {
                cards.appendReplacement(sb, Matcher.quoteReplacement(cards.group()));
            }
        }
        cards.appendTail(sb);
        return new Sanitized(sb.toString(), redactions);
    }

    public boolean containsSensitiveData(String text) {
        return sanitizeOutput(text).redactions() > 0;
    }

    /** Keeps tool results within the configured size so one large result cannot flood the context window. */
    public String limitToolOutput(String output) {
        if (output == null || output.length() <= properties.maxToolOutputChars()) {
            return output;
        }
        return output.substring(0, properties.maxToolOutputChars())
                + "\n...[truncated " + (output.length() - properties.maxToolOutputChars()) + " characters]";
    }

    // Luhn checksum keeps ticket ids, phone numbers etc. from being redacted as card numbers.
    private static boolean isLuhnValid(String candidate) {
        String digits = candidate.replaceAll("[ -]", "");
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
