package pl.piomin.services.deskpilot.controller;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.dto.ArticleResponse;
import pl.piomin.services.deskpilot.dto.KnowledgeDocumentResponse;
import pl.piomin.services.deskpilot.model.DocumentStatus;
import pl.piomin.services.deskpilot.service.FakeEmbeddingConfiguration;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;
import pl.piomin.services.deskpilot.service.TestDocuments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uploading documents over HTTP into the real database and vector store (pgvector via Testcontainers); embeddings
 * come from a deterministic fake model instead of Ollama.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class, FakeEmbeddingConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class KnowledgeDocumentApiTest {

    private static final String DOCUMENTS = "/api/knowledge-base/documents";
    private static final String DOCKING = """
            # Docking stations

            Plug the Thunderbolt dock into the USB-C port on the left side of the laptop.

            # Spare chargers

            Spare chargers are lent by the reception for one day.
            """;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TestSecurityConfiguration.TestTokens tokens;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private JdbcClient jdbcClient;

    private RestTestClient admin;
    private RestTestClient alice;

    @BeforeEach
    void setUp() {
        admin = as("admin", "user", "reviewer", "admin");
        alice = as("alice", "user");
    }

    @Test
    void markdownIsSplitIntoSearchableArticlesThatShowTheirDocument() {
        KnowledgeDocumentResponse uploaded = upload("docking.md", DOCKING, "Office hardware")
                .expectStatus().isAccepted()
                .expectBody(KnowledgeDocumentResponse.class).returnResult().getResponseBody();
        assertThat(uploaded).isNotNull();
        assertThat(uploaded.uploadedBy()).isEqualTo("admin");

        KnowledgeDocumentResponse ready = awaitIndexed(uploaded.id());

        assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.chunks()).isEqualTo(2);
        assertThat(knowledgeBaseService.search("thunderbolt dock", 3, 0.5, true))
                .extracting(KnowledgeBaseService.Hit::title).contains("Office hardware: Docking stations");
        List<ArticleResponse> articles = alice.get().uri("/api/knowledge-base/articles").exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<ArticleResponse>>() { })
                .returnResult().getResponseBody();
        assertThat(articles).filteredOn(a -> uploaded.id() == (a.documentId() == null ? -1 : a.documentId()))
                .hasSize(2)
                .allSatisfy(a -> assertThat(a.documentTitle()).isEqualTo("Office hardware"));
    }

    @Test
    void pdfPagesAreIndexedAndDeletingTheDocumentRemovesChunksAndVectors() {
        byte[] pdf = TestDocuments.pdf(List.of("Visitor badges are printed at the reception.",
                "Lost badges are blocked within one hour."));
        KnowledgeDocumentResponse uploaded = upload("badges.pdf", pdf, null).expectStatus().isAccepted()
                .expectBody(KnowledgeDocumentResponse.class).returnResult().getResponseBody();
        assertThat(uploaded).isNotNull();
        KnowledgeDocumentResponse ready = awaitIndexed(uploaded.id());
        assertThat(ready.title()).isEqualTo("badges");
        assertThat(ready.chunks()).isEqualTo(2);
        List<Long> chunkIds = chunkIds(uploaded.id());
        assertThat(vectorCount(chunkIds)).isEqualTo(2);

        admin.delete().uri(DOCUMENTS + "/{id}", uploaded.id()).exchange().expectStatus().isNoContent();

        assertThat(chunkIds(uploaded.id())).isEmpty();
        assertThat(vectorCount(chunkIds)).isZero();
        assertThat(knowledgeBaseService.search("visitor badges reception", 3, 0.5, true))
                .extracting(KnowledgeBaseService.Hit::title).noneMatch(title -> title.startsWith("badges"));
        admin.get().uri(DOCUMENTS + "/{id}", uploaded.id()).exchange().expectStatus().isNotFound();
    }

    @Test
    void reindexingReplacesTheChunksInsteadOfAddingMore() {
        KnowledgeDocumentResponse uploaded = upload("charging.md", DOCKING, "Charging").expectStatus().isAccepted()
                .expectBody(KnowledgeDocumentResponse.class).returnResult().getResponseBody();
        assertThat(uploaded).isNotNull();
        awaitIndexed(uploaded.id());

        admin.post().uri(DOCUMENTS + "/{id}/reindex", uploaded.id()).exchange().expectStatus().isAccepted();
        KnowledgeDocumentResponse again = awaitIndexed(uploaded.id());

        assertThat(again.chunks()).isEqualTo(2);
        assertThat(chunkIds(uploaded.id())).hasSize(2);
        assertThat(vectorCount(chunkIds(uploaded.id()))).isEqualTo(2);
    }

    @Test
    void onlyAdminsManageDocuments() {
        upload(alice, "notes.md", "# Notes\n\nSome text.", null).expectStatus().isForbidden();
        alice.get().uri(DOCUMENTS).exchange().expectStatus().isForbidden();
        admin.get().uri(DOCUMENTS).exchange().expectStatus().isOk();
    }

    @Test
    void unsupportedAndEmptyFilesAreRejected() {
        upload("setup.exe", "MZ binary", null)
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectBody().jsonPath("$.title").isEqualTo("Unsupported file type");
        upload("empty.md", "", null).expectStatus().isBadRequest();
    }

    private RestTestClient.ResponseSpec upload(String fileName, String content, String title) {
        return upload(admin, fileName, content.getBytes(StandardCharsets.UTF_8), title);
    }

    private RestTestClient.ResponseSpec upload(String fileName, byte[] content, String title) {
        return upload(admin, fileName, content, title);
    }

    private RestTestClient.ResponseSpec upload(RestTestClient user, String fileName, String content, String title) {
        return upload(user, fileName, content.getBytes(StandardCharsets.UTF_8), title);
    }

    private RestTestClient.ResponseSpec upload(RestTestClient user, String fileName, byte[] content, String title) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (title != null) {
            body.part("title", title);
        }
        return user.post().uri(DOCUMENTS).contentType(MediaType.MULTIPART_FORM_DATA).body(body.build()).exchange();
    }

    private KnowledgeDocumentResponse awaitIndexed(long id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            KnowledgeDocumentResponse document = admin.get().uri(DOCUMENTS + "/{id}", id).exchange()
                    .expectStatus().isOk()
                    .expectBody(KnowledgeDocumentResponse.class).returnResult().getResponseBody();
            if (document != null && document.status() != DocumentStatus.PROCESSING) {
                assertThat(document.error()).isNull();
                return document;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("Document " + id + " was not indexed in time");
    }

    private List<Long> chunkIds(long documentId) {
        return jdbcClient.sql("select id from knowledge_article where document_id = ? order by id")
                .param(documentId).query(Long.class).list();
    }

    /** Vectors stored for the given articles (vector ids are derived from article ids; the article id is metadata). */
    private long vectorCount(List<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return 0;
        }
        return articleIds.stream()
                .mapToLong(id -> jdbcClient.sql("select count(*) from vector_store where metadata->>'articleId' = ?")
                        .param(String.valueOf(id)).query(Long.class).single())
                .sum();
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .build();
    }
}
