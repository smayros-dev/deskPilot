package pl.piomin.services.agentai.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.agentai.model.MemoryFact;
import pl.piomin.services.agentai.repository.MemoryFactRepository;

/**
 * Long-term memory per user. Recall is keyword based: a user has few facts, so embeddings
 * would add latency without improving results.
 */
@Service
@Transactional(readOnly = true)
public class MemoryService {

    static final int MAX_RECALLED = 10;

    private final MemoryFactRepository repository;

    public MemoryService(MemoryFactRepository repository) {
        this.repository = repository;
    }

    /** Saves the fact unless the user already has an identical one. */
    @Transactional
    public MemoryFact save(String userId, String content) {
        String fact = content.strip();
        if (fact.isEmpty()) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }
        return repository.findFirstByUserIdAndContentIgnoreCase(userId, fact)
                .orElseGet(() -> repository.save(new MemoryFact(userId, fact)));
    }

    /**
     * Returns the user's facts that share a keyword with the query, newest first.
     * A blank query returns the most recent facts.
     */
    public List<MemoryFact> recall(String userId, String query) {
        List<MemoryFact> all = repository.findByUserIdOrderByCreatedAtDesc(userId);
        Set<String> keywords = keywords(query);
        if (keywords.isEmpty()) {
            return all.stream().limit(MAX_RECALLED).toList();
        }
        return all.stream()
                .filter(fact -> keywords.stream().anyMatch(keywords(fact.getContent())::contains))
                .limit(MAX_RECALLED)
                .toList();
    }

    public List<MemoryFact> findAll(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    // Words of 3+ letters, lower-cased; short words ("my", "is") match too much to be useful.
    private static Set<String> keywords(String text) {
        if (text == null) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= 3)
                .collect(Collectors.toSet());
    }
}
