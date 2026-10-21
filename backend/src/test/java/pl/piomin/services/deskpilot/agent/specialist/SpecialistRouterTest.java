package pl.piomin.services.deskpilot.agent.specialist;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import pl.piomin.services.deskpilot.agent.AgentConfig;
import pl.piomin.services.deskpilot.agent.ModelGateway;
import pl.piomin.services.deskpilot.agent.TestAgentConfigs;
import pl.piomin.services.deskpilot.config.SpecialistProperties;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The supervisor with the real specialist definitions, a scripted embedding model and a mocked chat model. */
class SpecialistRouterTest {

    private static final AgentConfig CONFIG = TestAgentConfigs.defaults();

    private SpecialistCatalog catalog;
    private ModelGateway modelGateway;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new SpecialistCatalog(() -> SpecialistDefaults.load(JsonMapper.builder().build()));
        modelGateway = mock(ModelGateway.class);
    }

    @Test
    void routesByKeywordsIgnoringCaseAndAccents() {
        SpecialistRouter router = router(text -> new float[] {0, 0}, false);

        SpecialistRouter.Routing vpn = router.route("My VPN disconnects every few minutes", null, CONFIG);
        SpecialistRouter.Routing french = router.route("Mon MOT DE PASSE a expiré", null, CONFIG);
        SpecialistRouter.Routing printer = router.route("The network printer is printing blank pages", null, CONFIG);

        assertThat(vpn.specialist().id()).isEqualTo("network");
        assertThat(vpn.method()).isEqualTo(SpecialistRouter.Method.KEYWORDS);
        assertThat(vpn.confidence()).isEqualTo(1.0);
        assertThat(vpn.detail()).contains("vpn", "disconnects");
        assertThat(french.specialist().id()).isEqualTo("accounts");
        // "network" for the network agent, "printer" and "printing" for hardware: most hits win.
        assertThat(printer.specialist().id()).isEqualTo("hardware");
        assertThat(printer.detail()).contains("also network: network");
    }

    @Test
    void keywordTieIsBrokenByMeaning() {
        // Texts about VPN gateways point one way, everything else the other.
        SpecialistRouter router = router(text -> text.toLowerCase().contains("vpn") ? new float[] {1, 0} : new float[] {0, 1}, false);

        SpecialistRouter.Routing routing = router.route("The VPN on my laptop", null, CONFIG);

        assertThat(routing.specialist().id()).isEqualTo("network");
        assertThat(routing.method()).isEqualTo(SpecialistRouter.Method.MEANING);
        assertThat(routing.detail()).contains("keyword tie", "network", "hardware");
    }

    @Test
    void followUpStaysWithThePreviousSpecialistAndOtherwiseGoesToTheGeneralAgent() {
        SpecialistRouter router = router(text -> new float[] {0, 0}, false);

        SpecialistRouter.Routing followUp = router.route("It still does not work", "network", CONFIG);
        SpecialistRouter.Routing other = router.route("What is a good recipe for lasagna?", null, CONFIG);

        assertThat(followUp.specialist().id()).isEqualTo("network");
        assertThat(followUp.method()).isEqualTo(SpecialistRouter.Method.PREVIOUS);
        assertThat(other.specialist()).isEqualTo(Specialist.GENERAL);
        assertThat(other.method()).isEqualTo(SpecialistRouter.Method.DEFAULT);
        verify(modelGateway, never()).call(any(), anyList(), anyList());
    }

    @Test
    void modelChoosesWhenEnabledAndNothingElseDecides() {
        when(modelGateway.call(any(), anyList(), anyList())).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("<think>about logins</think>\nAccounts"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(50, 3)).build()));
        SpecialistRouter router = router(text -> new float[] {0, 0}, true);

        SpecialistRouter.Routing routing = router.route("I cannot get into the HR portal", null, CONFIG);

        assertThat(routing.specialist().id()).isEqualTo("accounts");
        assertThat(routing.method()).isEqualTo(SpecialistRouter.Method.MODEL);
        assertThat(routing.modelCalls()).isEqualTo(1);
        assertThat(routing.promptTokens()).isEqualTo(50);
    }

    @Test
    void failingEmbeddingOrModelNeverFailsTheRun() {
        when(modelGateway.call(any(), anyList(), anyList())).thenThrow(new IllegalStateException("model down"));
        SpecialistRouter router = router(text -> {
            throw new IllegalStateException("Ollama down");
        }, true);

        SpecialistRouter.Routing routing = router.route("I cannot get into the HR portal", null, CONFIG);

        assertThat(routing.specialist()).isEqualTo(Specialist.GENERAL);
    }

    @Test
    void specialistToolsAndPrompt() {
        Specialist software = catalog.find("software").orElseThrow();
        Specialist accounts = catalog.find("accounts").orElseThrow();

        assertThat(software.allowsTool("mcp_github_create_issue")).isTrue();
        assertThat(accounts.allowsTool("mcp_github_create_issue")).isFalse();
        assertThat(accounts.allowsTool("save_memory")).isFalse();
        assertThat(Specialist.GENERAL.allowsTool("save_memory")).isTrue();
        assertThat(accounts.promptAddition()).contains("Accounts & MFA specialist", "How similar requests are handled",
                "I forgot my password");
        assertThat(Specialist.GENERAL.promptAddition()).isEmpty();
        assertThat(catalog.all()).extracting(Specialist::id)
                .containsExactly("network", "accounts", "hardware", "software", "general");
    }

    @Test
    void anEditedSpecialistIsUsedAtOnceAndOnlyItIsEmbeddedAgain() {
        List<Specialist> definitions = new ArrayList<>(SpecialistDefaults.load(JsonMapper.builder().build()));
        SpecialistCatalog editable = new SpecialistCatalog(() -> List.copyOf(definitions));
        List<String> embedded = new ArrayList<>();
        SpecialistRouter router = new SpecialistRouter(editable, new ScriptedEmbeddings(text -> {
            embedded.add(text);
            return new float[] {1, 0};
        }), modelGateway, new SpecialistProperties(true, 0.6, 0.03, false));
        router.preview("What is a good recipe for lasagna?", CONFIG);
        String networkDescription = definitions.getFirst().description();
        assertThat(embedded).contains(networkDescription);

        int hardware = definitions.indexOf(editable.find("hardware").orElseThrow());
        Specialist old = definitions.get(hardware);
        definitions.set(hardware, new Specialist(old.id(), old.name(), "Printers, toner and docking stations.",
                List.of("toner"), old.tools(), old.prompt(), old.examples()));
        editable.invalidate();
        embedded.clear();
        SpecialistRouter.Preview preview = router.preview("I need new toner", CONFIG);

        assertThat(preview.routing().specialist().id()).isEqualTo("hardware");
        assertThat(preview.routing().method()).isEqualTo(SpecialistRouter.Method.KEYWORDS);
        assertThat(preview.keywords()).containsEntry("hardware", List.of("toner"));
        assertThat(preview.similarities()).containsKeys("network", "accounts", "hardware", "software");
        assertThat(embedded).contains("Printers, toner and docking stations.", "I need new toner")
                .doesNotContain(networkDescription);
    }

    private SpecialistRouter router(Function<String, float[]> embed, boolean modelFallback) {
        return new SpecialistRouter(catalog, new ScriptedEmbeddings(embed), modelGateway,
                new SpecialistProperties(true, 0.6, 0.03, modelFallback));
    }

    /** Embeddings decided by the test; the router's task prefix is stripped first. */
    private record ScriptedEmbeddings(Function<String, float[]> embed) implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> texts = request.getInstructions();
            return new EmbeddingResponse(java.util.stream.IntStream.range(0, texts.size())
                    .mapToObj(i -> new Embedding(vector(texts.get(i)), i))
                    .toList());
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        private float[] vector(String text) {
            return embed.apply(text.replace(SpecialistRouter.EMBEDDING_PREFIX, ""));
        }
    }
}
