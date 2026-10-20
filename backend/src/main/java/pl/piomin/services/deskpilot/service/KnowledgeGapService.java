package pl.piomin.services.deskpilot.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pl.piomin.services.deskpilot.agent.AgentConfig;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.agent.ModelGateway;
import pl.piomin.services.deskpilot.config.KnowledgeGapProperties;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.GapReason;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;
import pl.piomin.services.deskpilot.model.KnowledgeGap;
import pl.piomin.services.deskpilot.model.KnowledgeGapQuestion;
import pl.piomin.services.deskpilot.model.KnowledgeGapStatus;
import pl.piomin.services.deskpilot.repository.KnowledgeArticleRepository;
import pl.piomin.services.deskpilot.repository.KnowledgeGapQuestionRepository;
import pl.piomin.services.deskpilot.repository.KnowledgeGapRepository;

/**
 * Knowledge-gap detection (12d). Questions the knowledge base did not answer — escalated, turned into a ticket, rated
 * 👎, or whose searches all came back empty — are embedded and grouped by meaning; questions an article now answers
 * are skipped. Each group becomes a gap that reviewers turn into a new article (drafted by the model, then edited) or
 * dismiss. Runs weekly ({@link KnowledgeGapScheduler}) and on demand.
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeGapService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeGapService.class);
    static final String NO_RESULTS_MARKER = "No relevant article found";
    private static final int SAMPLE_QUESTIONS = 8;
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    static final String DRAFT_PROMPT = """
            You write articles for an internal IT helpdesk knowledge base. Users asked the questions below and the \
            knowledge base had no answer. Write one article that answers them, with standard, safe IT procedures.
            Where a company-specific detail is needed (a URL, a phone number, a team or tool name), write a placeholder \
            in square brackets such as [helpdesk phone] instead of inventing it. Never ask for passwords.
            Reply exactly in this format, in English:
            TITLE: <short title>
            TAGS: <3 to 6 comma-separated lowercase keywords>

            <the article: a one-sentence summary, then short numbered steps, then when to open a ticket>""";

    // Finished chat runs not yet in a gap, with why the knowledge base did not answer them.
    private static final String CANDIDATES = """
            select r.id, r.user_input, r.created_at,
                   case when r.status = 'ESCALATED' then 'ESCALATED'
                        when exists (select 1 from agent_step s where s.run_id = r.id and s.type = 'TOOL_CALL'
                                     and s.name = 'create_ticket') then 'TICKET'
                        when exists (select 1 from run_feedback f where f.run_id = r.id and f.rating = 'DOWN') then 'NEGATIVE_FEEDBACK'
                        else 'NOT_FOUND' end as reason
            from agent_run r
            where r.source = 'CHAT' and r.status in ('COMPLETED', 'ESCALATED') and r.created_at >= :since
              and not exists (select 1 from knowledge_gap_question q where q.run_id = r.id)
              and (r.status = 'ESCALATED'
                   or exists (select 1 from agent_step s where s.run_id = r.id and s.type = 'TOOL_CALL' and s.name = 'create_ticket')
                   or exists (select 1 from run_feedback f where f.run_id = r.id and f.rating = 'DOWN')
                   or (exists (select 1 from agent_step s where s.run_id = r.id and s.type = 'TOOL_RESULT'
                               and s.name = 'search_knowledge_base' and s.content like :noResults)
                       and not exists (select 1 from agent_step s where s.run_id = r.id and s.type = 'TOOL_RESULT'
                                       and s.name = 'search_knowledge_base' and s.content not like :noResults)))
            order by r.created_at desc
            limit :limit""";

    public record Candidate(String runId, String question, Instant askedAt, GapReason reason) {
    }

    /** @param candidates unanswered questions found; covered: answered by an article meanwhile */
    public record Detection(int candidates, int covered, int newGaps, int grownGaps, int unassigned) {
    }

    public record GapView(KnowledgeGap gap, List<KnowledgeGapQuestion> questions) {
    }

    private final KnowledgeGapRepository gapRepository;
    private final KnowledgeGapQuestionRepository questionRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingModel embeddingModel;
    private final ModelGateway modelGateway;
    private final AgentSettingsService settingsService;
    private final KnowledgeGapProperties properties;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean detecting = new AtomicBoolean();

    public KnowledgeGapService(KnowledgeGapRepository gapRepository, KnowledgeGapQuestionRepository questionRepository,
                               KnowledgeArticleRepository articleRepository, KnowledgeBaseService knowledgeBaseService,
                               EmbeddingModel embeddingModel, ModelGateway modelGateway,
                               AgentSettingsService settingsService, KnowledgeGapProperties properties,
                               JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.gapRepository = gapRepository;
        this.questionRepository = questionRepository;
        this.articleRepository = articleRepository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingModel = embeddingModel;
        this.modelGateway = modelGateway;
        this.settingsService = settingsService;
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    /** Pending gaps first (most asked first), then the decided ones (newest first). */
    public List<GapView> list() {
        List<KnowledgeGap> gaps = gapRepository.findAll();
        Map<Long, List<KnowledgeGapQuestion>> questions = questionRepository
                .findByGapIdInOrderByAskedAtDesc(gaps.stream().map(KnowledgeGap::getId).toList()).stream()
                .collect(Collectors.groupingBy(KnowledgeGapQuestion::getGapId));
        return gaps.stream()
                .map(gap -> new GapView(gap, questions.getOrDefault(gap.getId(), List.of())))
                .sorted(Comparator.<GapView, Boolean>comparing(v -> !v.gap().getStatus().isPending())
                        .thenComparing(v -> v.gap().getStatus().isPending() ? -v.questions().size() : 0)
                        .thenComparing(v -> v.gap().getUpdatedAt(), Comparator.reverseOrder()))
                .toList();
    }

    public GapView get(long id) {
        KnowledgeGap gap = gapRepository.findById(id).orElseThrow(() -> new NotFoundException("Knowledge gap " + id + " not found"));
        return new GapView(gap, questionRepository.findByGapIdInOrderByAskedAtDesc(List.of(id)));
    }

    /**
     * Groups the new unanswered questions: each joins the closest pending gap or new group (by cosine similarity to
     * the group's centroid); new groups with enough questions become gaps. Questions left alone stay candidates for
     * the next detection, while they are within the look-back window.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Detection detect() {
        if (!detecting.compareAndSet(false, true)) {
            throw new ConflictException("Knowledge-gap detection is already running");
        }
        try {
            return doDetect();
        } finally {
            detecting.set(false);
        }
    }

    private Detection doDetect() {
        List<Candidate> candidates = candidates();
        List<Candidate> open = candidates.stream().filter(c -> !isCovered(c.question())).toList();
        List<Cluster> clusters = new ArrayList<>();
        // Pending gaps are represented by their title (their most representative question), so new questions join them.
        List<KnowledgeGap> pending = gapRepository.findByStatusIn(List.of(KnowledgeGapStatus.OPEN, KnowledgeGapStatus.DRAFTED));
        if (!open.isEmpty() && !pending.isEmpty()) {
            List<float[]> vectors = embeddingModel.embed(pending.stream().map(KnowledgeGap::getTitle).toList());
            for (int i = 0; i < pending.size(); i++) {
                clusters.add(new Cluster(pending.get(i).getId(), vectors.get(i)));
            }
        }
        if (!open.isEmpty()) {
            List<float[]> vectors = embeddingModel.embed(open.stream().map(Candidate::question).toList());
            // Oldest first, so a topic's first question seeds its group.
            for (int i = open.size() - 1; i >= 0; i--) {
                float[] vector = normalize(vectors.get(i));
                Cluster best = null;
                double bestSimilarity = properties.similarity();
                for (Cluster cluster : clusters) {
                    double similarity = cosine(cluster.centroid(), vector);
                    if (similarity >= bestSimilarity) {
                        best = cluster;
                        bestSimilarity = similarity;
                    }
                }
                if (best == null) {
                    best = new Cluster(null, vector);
                    clusters.add(best);
                }
                best.add(open.get(i), vector);
            }
        }
        Detection detection = transactionTemplate.execute(status -> save(clusters, candidates.size(), candidates.size() - open.size()));
        LOG.info("Knowledge-gap detection: {}", detection);
        return detection;
    }

    private Detection save(List<Cluster> clusters, int candidates, int covered) {
        int newGaps = 0;
        int grownGaps = 0;
        int unassigned = 0;
        for (Cluster cluster : clusters) {
            if (cluster.members().isEmpty()) {
                continue;
            }
            Long gapId = cluster.gapId();
            if (gapId != null) {
                gapRepository.findById(gapId).ifPresent(KnowledgeGap::touch);
                grownGaps++;
            } else if (cluster.members().size() >= properties.minQuestions()) {
                gapId = gapRepository.save(new KnowledgeGap(cluster.representative().question())).getId();
                newGaps++;
            } else {
                unassigned += cluster.members().size();
                continue;
            }
            for (Candidate member : cluster.members()) {
                questionRepository.save(new KnowledgeGapQuestion(gapId, member.runId(), member.question(), member.reason(), member.askedAt()));
            }
        }
        return new Detection(candidates, covered, newGaps, grownGaps, unassigned);
    }

    List<Candidate> candidates() {
        Instant since = Instant.now().minus(Duration.ofDays(properties.lookbackDays()));
        return jdbcClient.sql(CANDIDATES)
                .param("since", Timestamp.from(since))
                .param("noResults", "%" + NO_RESULTS_MARKER + "%")
                .param("limit", properties.maxQuestions())
                .query((rs, row) -> new Candidate(rs.getString("id"), rs.getString("user_input"),
                        rs.getTimestamp("created_at").toInstant(), GapReason.valueOf(rs.getString("reason"))))
                .list();
    }

    private boolean isCovered(String question) {
        return !knowledgeBaseService.search(question, 1, properties.coveredSimilarity(), false).isEmpty();
    }

    /**
     * Asks the model of the current settings (local or cloud) for an article answering the gap's questions. The draft
     * is only stored; a reviewer reads and edits it before publishing.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GapView draft(long id) {
        GapView view = get(id);
        requirePending(view.gap());
        if (view.questions().isEmpty()) {
            throw new ConflictException("Knowledge gap " + id + " has no questions left to draft an article from");
        }
        AgentConfig config = settingsService.current();
        String questions = view.questions().stream().limit(SAMPLE_QUESTIONS)
                .map(q -> "- " + q.getQuestion().strip().replaceAll("\\s+", " "))
                .collect(Collectors.joining("\n"));
        ChatResponse response = modelGateway.call(config,
                List.of(new SystemMessage(DRAFT_PROMPT), new UserMessage("Questions:\n" + questions)), List.of());
        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new ConflictException("The model returned an empty draft; try again");
        }
        Draft draft = Draft.parse(text, view.gap().getTitle());
        transactionTemplate.executeWithoutResult(status -> gapRepository.findById(id).ifPresent(gap -> {
            requirePending(gap);
            gap.draft(draft.title(), draft.content(), draft.tags(), config.modelLabel());
        }));
        return get(id);
    }

    /** A reviewer's edit of the draft (or an article written without the model). */
    @Transactional
    public GapView updateDraft(long id, String title, String content, String tags, String actor) {
        KnowledgeGap gap = gapRepository.findById(id).orElseThrow(() -> new NotFoundException("Knowledge gap " + id + " not found"));
        requirePending(gap);
        String draftedBy = gap.getDraftedBy() == null ? actor : gap.getDraftedBy();
        gap.draft(title.strip(), content.strip(), tags == null ? "" : tags.strip(), draftedBy);
        return new GapView(gap, questionRepository.findByGapIdInOrderByAskedAtDesc(List.of(id)));
    }

    /** Adds the draft to the knowledge base as an article, embedded and searchable at once. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GapView publish(long id, String actor) {
        KnowledgeArticle article = transactionTemplate.execute(status -> {
            KnowledgeGap gap = gapRepository.findById(id).orElseThrow(() -> new NotFoundException("Knowledge gap " + id + " not found"));
            requirePending(gap);
            if (gap.getDraftTitle() == null || gap.getDraftContent() == null || gap.getDraftContent().isBlank()) {
                throw new ConflictException("Knowledge gap " + id + " has no draft to publish");
            }
            KnowledgeArticle saved = articleRepository.save(new KnowledgeArticle(gap.getDraftTitle(), gap.getDraftContent(), gap.getDraftTags()));
            gap.publish(saved.getId(), actor);
            return saved;
        });
        knowledgeBaseService.index(List.of(article));
        return get(id);
    }

    @Transactional
    public GapView dismiss(long id, String actor) {
        KnowledgeGap gap = gapRepository.findById(id).orElseThrow(() -> new NotFoundException("Knowledge gap " + id + " not found"));
        requirePending(gap);
        gap.dismiss(actor);
        return new GapView(gap, questionRepository.findByGapIdInOrderByAskedAtDesc(List.of(id)));
    }

    private static void requirePending(KnowledgeGap gap) {
        if (!gap.getStatus().isPending()) {
            throw new ConflictException("Knowledge gap " + gap.getId() + " is already " + gap.getStatus().name().toLowerCase());
        }
    }

    /** The model's reply split into title, tags and body; tolerant of missing headers and thinking blocks. */
    record Draft(String title, String tags, String content) {

        static Draft parse(String text, String fallbackTitle) {
            String title = null;
            String tags = "";
            List<String> body = new ArrayList<>();
            for (String line : THINK.matcher(text).replaceAll("").strip().split("\\R", -1)) {
                String trimmed = line.strip().replace("*", "");
                if (title == null && body.isEmpty() && trimmed.regionMatches(true, 0, "TITLE:", 0, 6)) {
                    title = trimmed.substring(6).strip();
                } else if (tags.isEmpty() && body.isEmpty() && trimmed.regionMatches(true, 0, "TAGS:", 0, 5)) {
                    tags = trimmed.substring(5).strip().toLowerCase();
                } else if (!body.isEmpty() || !trimmed.isEmpty()) {
                    body.add(line);
                }
            }
            String safeTitle = title == null || title.isBlank() ? fallbackTitle : title;
            if (safeTitle.length() > 200) {
                safeTitle = safeTitle.substring(0, 199) + "…";
            }
            if (tags.length() > 300) {
                tags = tags.substring(0, 300);
            }
            return new Draft(safeTitle, tags, String.join("\n", body).strip());
        }
    }

    /** A group of similar questions; {@code gapId} is set when it extends a pending gap. */
    private static final class Cluster {

        private final Long gapId;
        private final float[] sum;
        private float[] centroid;
        private final List<Candidate> members = new ArrayList<>();
        private final List<float[]> vectors = new ArrayList<>();

        Cluster(Long gapId, float[] seed) {
            this.gapId = gapId;
            this.sum = normalize(seed).clone();
            this.centroid = normalize(sum);
        }

        void add(Candidate candidate, float[] vector) {
            members.add(candidate);
            vectors.add(vector);
            // A new group's seed is its first member, so it is not counted twice.
            if (gapId != null || members.size() > 1) {
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += vector[i];
                }
                centroid = normalize(sum);
            }
        }

        Long gapId() {
            return gapId;
        }

        float[] centroid() {
            return centroid;
        }

        List<Candidate> members() {
            return members;
        }

        /** The member closest to the centroid. */
        Candidate representative() {
            int best = 0;
            for (int i = 1; i < members.size(); i++) {
                if (cosine(centroid, vectors.get(i)) > cosine(centroid, vectors.get(best))) {
                    best = i;
                }
            }
            return members.get(best);
        }
    }

    static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[vector.length];
        if (norm == 0) {
            return result;
        }
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    /** Cosine similarity of two normalized vectors. */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
