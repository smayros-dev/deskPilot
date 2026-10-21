package pl.piomin.services.deskpilot.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.dto.AuditEventResponse;
import pl.piomin.services.deskpilot.dto.SpecialistInfo;
import pl.piomin.services.deskpilot.service.FakeEmbeddingConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 13g over HTTP: specialists stored in the database (seeded by V22), edited by admins, and routing that follows an
 * edit at once. The defaults are restored after each test so the other tests see the built-in specialists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class, FakeEmbeddingConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class SpecialistIntegrationTest {

    private static final String SPECIALISTS = "/api/specialists";
    private static final ParameterizedTypeReference<List<SpecialistInfo>> LIST = new ParameterizedTypeReference<>() {
    };

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TestSecurityConfiguration.TestTokens tokens;

    private RestTestClient alice;
    private RestTestClient admin;

    @BeforeEach
    void setUp() {
        alice = as("alice", "user");
        admin = as("admin", "user", "admin");
    }

    @AfterEach
    void restoreDefaults() {
        admin.post().uri(SPECIALISTS + "/restore-defaults").exchange().expectStatus().isOk();
    }

    @Test
    void everyoneListsTheSpecialistsButOnlyAdminsSeeAndChangeTheirInstructions() {
        List<SpecialistInfo> forUser = list(alice);
        List<SpecialistInfo> forAdmin = list(admin);

        assertThat(forUser).extracting(SpecialistInfo::id).containsExactly("network", "accounts", "hardware", "software", "general");
        assertThat(forUser).allSatisfy(s -> assertThat(s.prompt()).isNull());
        assertThat(forUser.getFirst().examples()).isPositive();
        assertThat(forAdmin.getFirst().prompt()).contains("network and VPN specialist");
        assertThat(forAdmin.getFirst().exampleList()).isNotEmpty();
        assertThat(forAdmin.getFirst().updatedBy()).isNotBlank(); // "system" when seeded by V22
        assertThat(forAdmin.getLast().builtIn()).isTrue();

        alice.post().uri(SPECIALISTS).body(printing(List.of("toner"))).exchange().expectStatus().isForbidden();
        alice.post().uri(SPECIALISTS + "/route-preview").body(Map.of("message", "toner")).exchange().expectStatus().isForbidden();
        alice.get().uri(SPECIALISTS + "/network").exchange().expectStatus().isForbidden();
    }

    @Test
    void anAdminCreatesEditsDisablesAndDeletesASpecialistAndRoutingFollowsAtOnce() {
        assertThat(preview("I need a new toner cartridge").specialist()).isNotEqualTo("printing");

        SpecialistInfo created = admin.post().uri(SPECIALISTS).body(printing(List.of("toner", "cartridge", "Toner ")))
                .exchange().expectStatus().isCreated().expectBody(SpecialistInfo.class).returnResult().getResponseBody();
        assertThat(created.keywords()).containsExactly("toner", "cartridge"); // trimmed, duplicates dropped
        assertThat(created.tools()).containsExactly("create_ticket", "search_knowledge_base");
        assertThat(created.updatedBy()).isEqualTo("admin");

        SpecialistController.RoutePreview toner = preview("I need a new toner cartridge");
        assertThat(toner.specialist()).isEqualTo("printing");
        assertThat(toner.method()).isEqualTo("keywords");
        assertThat(toner.keywords()).containsEntry("printing", List.of("toner", "cartridge"));
        assertThat(toner.similarities()).containsKey("printing");

        // New keywords apply to the next message, the old ones no longer do.
        Map<String, Object> edited = printing(List.of("ink"));
        admin.put().uri(SPECIALISTS + "/printing").body(edited).exchange().expectStatus().isOk();
        assertThat(preview("I need a new toner cartridge").specialist()).isNotEqualTo("printing");
        assertThat(preview("The ink is smudged").specialist()).isEqualTo("printing");

        edited.put("enabled", false);
        admin.put().uri(SPECIALISTS + "/printing").body(edited).exchange().expectStatus().isOk();
        assertThat(preview("The ink is smudged").specialist()).isNotEqualTo("printing");
        assertThat(list(alice)).extracting(SpecialistInfo::id).doesNotContain("printing");
        assertThat(list(admin)).filteredOn(s -> s.id().equals("printing")).singleElement()
                .satisfies(s -> assertThat(s.enabled()).isFalse());

        admin.delete().uri(SPECIALISTS + "/printing").exchange().expectStatus().isNoContent();
        admin.get().uri(SPECIALISTS + "/printing").exchange().expectStatus().isNotFound();

        assertThat(audit("SPECIALIST_CREATED").items()).anySatisfy(e -> assertThat(e.targetId()).isEqualTo("printing"));
        assertThat(audit("SPECIALIST_UPDATED").items()).anySatisfy(e -> assertThat(e.details()).contains("keywords"));
        assertThat(audit("SPECIALIST_DELETED").items()).anySatisfy(e -> assertThat(e.targetId()).isEqualTo("printing"));
    }

    @Test
    void invalidSpecialistsAreRejected() {
        admin.post().uri(SPECIALISTS).body(printing(List.of("toner"))).exchange().expectStatus().isCreated();
        admin.post().uri(SPECIALISTS).body(printing(List.of("toner"))).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> general = printing(List.of("toner"));
        general.put("id", "general");
        admin.post().uri(SPECIALISTS).body(general).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        admin.delete().uri(SPECIALISTS + "/general").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> badId = printing(List.of("toner"));
        badId.put("id", "Bad Id");
        admin.post().uri(SPECIALISTS).body(badId).exchange().expectStatus().isBadRequest();

        Map<String, Object> neverChosen = printing(List.of());
        neverChosen.put("id", "nothing");
        admin.post().uri(SPECIALISTS).body(neverChosen).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(detail -> assertThat(detail.toString()).contains("keyword or one example"));

        Map<String, Object> unknownTool = printing(List.of("toner"));
        unknownTool.put("id", "unknown-tool");
        unknownTool.put("tools", List.of("format_disk"));
        admin.post().uri(SPECIALISTS).body(unknownTool).exchange().expectStatus().isBadRequest();

        Map<String, Object> external = printing(List.of("toner"));
        external.put("id", "external");
        external.put("tools", List.of("search_knowledge_base", "mcp_*"));
        admin.post().uri(SPECIALISTS).body(external).exchange().expectStatus().isCreated();

        Map<String, Object> noName = printing(List.of("toner"));
        noName.put("id", "no-name");
        noName.put("name", " ");
        admin.post().uri(SPECIALISTS).body(noName).exchange().expectStatus().isBadRequest();
    }

    @Test
    void restoringTheDefaultsBringsBackTheBuiltInSpecialists() {
        admin.delete().uri(SPECIALISTS + "/network").exchange().expectStatus().isNoContent();
        assertThat(list(alice)).extracting(SpecialistInfo::id).doesNotContain("network");

        admin.post().uri(SPECIALISTS + "/restore-defaults").exchange().expectStatus().isOk();

        assertThat(list(alice)).extracting(SpecialistInfo::id).containsExactly("network", "accounts", "hardware", "software", "general");
        assertThat(preview("My VPN disconnects").specialist()).isEqualTo("network");
        assertThat(audit("SPECIALISTS_RESTORED").total()).isPositive();
    }

    private static Map<String, Object> printing(List<String> keywords) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", "printing");
        body.put("name", "Printing");
        body.put("description", "Printers, toner and paper.");
        body.put("keywords", keywords);
        body.put("tools", List.of("search_knowledge_base", "create_ticket"));
        body.put("prompt", "You are the printing specialist.");
        body.put("examples", List.of());
        body.put("enabled", true);
        body.put("sortOrder", 5);
        return body;
    }

    private SpecialistController.RoutePreview preview(String message) {
        return admin.post().uri(SPECIALISTS + "/route-preview").body(Map.of("message", message)).exchange()
                .expectStatus().isOk().expectBody(SpecialistController.RoutePreview.class).returnResult().getResponseBody();
    }

    private List<SpecialistInfo> list(RestTestClient as) {
        return as.get().uri(SPECIALISTS).exchange().expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
    }

    private AuditEventResponse.PageResponse audit(String action) {
        return admin.get().uri("/api/audit?action={action}", action).exchange().expectStatus().isOk()
                .expectBody(AuditEventResponse.PageResponse.class).returnResult().getResponseBody();
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .build();
    }
}
