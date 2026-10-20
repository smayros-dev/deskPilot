package pl.piomin.services.deskpilot.service;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.AuditEvent;
import pl.piomin.services.deskpilot.repository.AuditEventRepository;

/**
 * Audit trail (12o): who approved what, changed which settings, uploaded or deleted which documents, ran evaluations,
 * and deleted personal data. Entries are written in their own transaction, so an entry is kept even if the caller's
 * transaction later fails, and they are never updated.
 */
@Service
@Transactional(readOnly = true)
public class AuditService {

    public static final String SYSTEM = "system";
    static final int MAX_EXPORT = 10_000;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id"));

    /** @param action, actor, from (inclusive), to (exclusive) — each optional */
    public record Filter(AuditAction action, String actor, Instant from, Instant to) {
    }

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent record(String actor, AuditAction action, String targetType, String targetId, String details) {
        return repository.save(new AuditEvent(actor, action, targetType, targetId, details));
    }

    public Page<AuditEvent> search(Filter filter, int page, int size) {
        return repository.findAll(specification(filter), PageRequest.of(page, size, NEWEST_FIRST));
    }

    /** The matching entries as CSV (newest first, at most {@value #MAX_EXPORT} rows). */
    public String exportCsv(Filter filter) {
        List<AuditEvent> events = repository.findAll(specification(filter), PageRequest.of(0, MAX_EXPORT, NEWEST_FIRST))
                .getContent();
        StringBuilder csv = new StringBuilder("at,actor,action,target_type,target_id,details\n");
        for (AuditEvent e : events) {
            csv.append(List.of(e.getAt().toString(), e.getActor(), e.getAction().name(), nullToEmpty(e.getTargetType()),
                            nullToEmpty(e.getTargetId()), nullToEmpty(e.getDetails())).stream()
                    .map(AuditService::csvField).collect(Collectors.joining(","))).append('\n');
        }
        return csv.toString();
    }

    /**
     * The fields that differ between two records of the same type, as {@code name: old → new; …} (long values are
     * shortened). Used for settings changes; {@code ignored} components (e.g. timestamps) are skipped.
     */
    public static String diff(Record before, Record after, String... ignored) {
        if (!before.getClass().equals(after.getClass())) {
            throw new IllegalArgumentException("Cannot compare " + before.getClass() + " with " + after.getClass());
        }
        List<String> skip = List.of(ignored);
        List<String> changes = new ArrayList<>();
        for (RecordComponent component : before.getClass().getRecordComponents()) {
            if (skip.contains(component.getName())) {
                continue;
            }
            Object old = value(component, before);
            Object current = value(component, after);
            if (!Objects.equals(normalize(old), normalize(current))) {
                changes.add(component.getName() + ": " + shorten(old) + " → " + shorten(current));
            }
        }
        return String.join("; ", changes);
    }

    private static Object value(RecordComponent component, Record record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sets compare without order; blank text equals no text. */
    private static Object normalize(Object value) {
        if (value instanceof Collection<?> collection) {
            return new TreeSet<>(collection.stream().map(String::valueOf).toList());
        }
        if (value instanceof String text && text.isBlank()) {
            return null;
        }
        return value;
    }

    private static String shorten(Object value) {
        Object normalized = normalize(value);
        String text = normalized == null ? "—" : String.valueOf(normalized);
        return text.length() > 120 ? text.substring(0, 117) + "…" : text;
    }

    private static Specification<AuditEvent> specification(Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.action() != null) {
                predicates.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.actor() != null && !filter.actor().isBlank()) {
                predicates.add(cb.equal(root.get("actor"), filter.actor().strip()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("at"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("at"), filter.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Quotes a field when needed; a leading =, +, - or @ is prefixed so spreadsheets do not run it as a formula. */
    static String csvField(String value) {
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
