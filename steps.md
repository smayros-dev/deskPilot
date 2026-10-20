# DeskPilot — Implementation Steps

Stack: **Angular 22** (frontend) + **Spring Boot 4.1 / Java 21** (backend) + **Spring AI 2.0** + **Ollama** (free, local LLM).

- Chat model: `qwen3:8b` (supports tool calling, ~5 GB). Runs on CPU with 32 GB RAM, but slowly; expect a few seconds per answer and longer for multi-tool runs.
- Embedding model: `nomic-embed-text`, for vector-based RAG.
- Provider-agnostic: Spring AI lets us move to another model (another Ollama model, or a paid API such as Claude) by changing the starter dependency and config, with no change to the agent code.

Backend conventions follow the [piomin/claude-ai-spring-boot](https://github.com/piomin/claude-ai-spring-boot) plugin: no Lombok, constructor injection, records for DTOs, `@ConfigurationProperties`, `@RestControllerAdvice` + Problem Details, `@Transactional(readOnly = true)` on service classes, RestTestClient + Testcontainers for tests, Maven with group ID `pl.piomin.services`, and a README update plus a PATCH version bump on each change.

Guiding principle (from the brief): **one focused use case → a working agent → measure → add complexity only when needed.**

## Use case (MVP)

**IT Helpdesk Agent.** It answers employee questions from a knowledge base, looks up tickets, and creates tickets only after a human approves. It remembers user preferences and escalates to a human when it can't help.

| Brief topic | How the MVP covers it |
|---|---|
| 1. Architecture | Single agent with a ReAct-style loop (native tool calling) |
| 2. Framework | Spring AI: `ChatClient`, `@Tool`, `ChatMemory`, `VectorStore`, `ToolCallingManager` |
| 3. Memory | Short-term: `ChatMemory` (conversation window). Long-term/semantic: `MemoryFact` table. Episodic: persisted runs and steps. Procedural: the system prompt |
| 4. Tools | `@Tool` function calling. MCP (Spring AI MCP client) and A2A come later (step 12) |
| 5. Agentic RAG | `search_knowledge_base` tool using embeddings and similarity search, with a relevance threshold so the agent can reformulate the query (corrective RAG) |
| 6. Human-in-the-loop | Spring AI user-controlled tool execution (`internalToolExecutionEnabled=false`): pause before risky tools, resume after approval |
| 7. Build stack | Ollama (LLM + embeddings), vector store (Simple → pgvector), H2 → Postgres, Spring Boot runtime |
| 8. LLMOps & safety | Spring AI and Micrometer observations (tokens, latency), run traces, input/output guardrails, max-iteration cap |
| 9. Roadmap | The steps below |
| 10. Metrics | Task success rate, latency, tool accuracy, escalation rate. Cost is $0 locally; tokens are still tracked |

---

## Step 0 — Scaffolding ✅
- [x] `backend/`: Spring Boot 4.1.1 (web, validation, data-jpa, h2, actuator)
- [x] `frontend/`: Angular 22 (zoneless, standalone, vitest)
- [x] `steps.md` (this file)
- [x] Ollama installed; `qwen3:8b` and `nomic-embed-text` pulled
- [x] Root `README.md` and `.gitignore`, then `git init`

## Step 1 — Backend foundation ✅
- [x] Spring AI 2.0.1 BOM + `spring-ai-starter-model-ollama` (works with Boot 4.1.1)
- [x] `application.yml`: Ollama base URL, chat and embedding models, virtual threads, Problem Details, graceful shutdown, H2 file DB, actuator (`health`, `info`, `metrics`), 5-minute HTTP read timeout
- [x] `AgentProperties` (`@ConfigurationProperties("agent")`, validated): maxIterations, RAG threshold/topK, guardrail limits
- [x] `GlobalExceptionHandler` (404 / 409 / 400 / 502 as `ProblemDetail`; 422 for guardrails comes in Step 6)
- [x] `OllamaHealthIndicator`: `/actuator/health` reports DOWN when Ollama is down or a model hasn't been pulled
- [x] Tests: context loads, health indicator (3 cases), `OllamaSmokeTest` against the real model (opt-in with `OLLAMA_IT=true`)
- **Result:** health is UP (db, ollama). qwen3 thinking mode is **off** by default: on this CPU it took 44 s vs 3 s for a one-word answer (3–5 tokens/s)

## Step 2 — Domain & persistence ✅
- [x] Entities: `AgentRun`, `AgentStep`, `KnowledgeArticle`, `Ticket`, `MemoryFact` (Jakarta Persistence, no Lombok)
- [x] Repositories and services (`RunService`, `TicketService`, `MemoryService`, `KnowledgeBaseService`). Run tracking uses one short transaction per call, so no transaction stays open while the model is thinking
- [x] 8 helpdesk KB articles in `kb/articles.json`, seeded on first start and embedded into an in-memory `SimpleVectorStore` (`nomic-embed-text`) at startup
- [x] Tests: 17 service tests (fake bag-of-words embeddings, no Ollama needed) + `KnowledgeBaseSmokeTest` with real embeddings (opt-in)
- **Result:** with real embeddings, paraphrased questions find the right article ("can't reach the intranet from home" → VPN) and off-topic questions return nothing at threshold 0.55

## Step 3 — Tools (function calling) ✅
- [x] Tools as `@Tool` methods: `search_knowledge_base`, `get_ticket`, `create_ticket` (**approval required**), `save_memory`, `recall_memories`, `escalate_to_human`
- [x] Approval policy: the `@RequiresApproval` annotation, read by `ToolRegistry`; unknown tool names are rejected
- [x] `RunContext` passed through Spring AI `ToolContext`: tools use the real user id, which the model can't see or forge
- [x] Tests: 13 tests through the real `ToolCallback` JSON path (positive + negative)

## Step 4 — Agent loop (ReAct) ✅
- [x] `AgentOrchestrator`: call the model → run the requested tools → feed results back, until it answers. Spring AI 2.0's `ChatModel` never runs tools itself, so our loop executes every call and records it
- [x] Short-term memory: Spring AI `ChatMemory` (message window) keeps question/answer pairs per session; tool exchanges stay in the in-flight `ActiveTurn` and the run trace
- [x] Procedural memory: `prompts/system-prompt.txt`
- [x] Max-iteration cap (→ `ESCALATED`), empty answer (→ `FAILED`), unknown tool / bad arguments (→ error result the model can react to), model failure (→ `FAILED`, session released)
- [x] Each user input, thought, tool call, tool result, approval and answer is recorded as an `AgentStep`
- [x] Tests: 11 orchestrator tests with a scripted `ChatModel` + `AgentSmokeTest` against qwen3 (opt-in)
- **Result (qwen3:8b on CPU):** VPN question → `search_knowledge_base` → correct answer ("switch to IPSec") in 45 s; ticket request → `create_ticket` → paused for approval in 88 s (first call includes the cold start). It works, but slowly: a GPU or a smaller/faster model would help (Step 10 compares models)

## Step 5 — Human-in-the-loop ✅
- [x] Pause the run (`AWAITING_APPROVAL`) when a gated tool is requested; all tool calls of that model message are held so their results go back together
- [x] `POST /api/runs/{id}/approval` approves or rejects (with a comment) and resumes the loop
- [x] A rejection goes back to the model as an error tool result ("Rejected by the human reviewer (…). Do not retry"), so the agent can explain it to the user
- [x] Tests: approve, reject, new message while paused → 409, decision on a run that isn't paused → 409

## Step 6 — Guardrails & safety ✅
- [x] Input: blank / too long / prompt-injection patterns (configurable regexes in `agent.guardrails.blocked-patterns`) → 422 Problem Detail with a `rule` field; blocked input never reaches the model and creates no run
- [x] Output: passwords, API keys/tokens and Luhn-valid card numbers are redacted from answers (recorded as a `GUARDRAIL` step)
- [x] Tools: unknown tool → error result, argument validation in each tool, oversized results truncated (`max-tool-output-chars`), `save_memory` refuses secrets, system prompt says tool output is data
- [x] Tests: 12 guardrail unit tests + 2 orchestrator tests

## Step 7 — REST API ✅
- [x] `POST /api/sessions`, `GET /api/sessions/{id}`, `POST /api/sessions/{id}/messages`, `GET /api/sessions/{id}/runs`
- [x] `GET /api/runs/{id}`, `POST /api/runs/{id}/approval`
- [x] `GET /api/tickets`, `GET /api/tickets/{id}`, `GET /api/users/{userId}/memories`, `GET /api/knowledge-base/articles`, `GET /api/metrics`
- [x] DTO records, validation (`@Valid`), Problem Details for 400 / 404 / 409 / 422 / 502
- [x] Tests: 10 RestTestClient integration tests (happy paths + every error status)

## Step 8 — Observability & success metrics ✅
- [x] Micrometer (`/actuator/metrics`): `agent.runs{status}`, `agent.run.duration`, `agent.llm.latency`, `agent.tokens{type}`, `agent.tool.calls{tool,outcome}`, `agent.approvals{decision}`, `agent.guardrail.blocked{rule}`, plus Spring AI's built-in `gen_ai.client.*` observations
- [x] `GET /api/metrics` (computed from the database, so it survives restarts): task success rate, escalation rate, avg and p95 latency, avg iterations, tool error rate, per-tool accuracy and latency, approvals requested/rejected, tokens, blocked inputs
- [x] Tests: `MetricsServiceTest` (rates, percentiles, empty database)

## Step 9 — Angular frontend (modern Angular, all core features) ✅
- [x] **Architecture:** standalone components only (no NgModules), zoneless change detection, `OnPush` everywhere, folders `core/`, `shared/`, `chat/`, `runs/`, `dashboard/`, `helpdesk/`
- [x] **Routing:** `provideRouter` with lazy `loadComponent` for every page and lazy `loadChildren` for `/helpdesk/*`; `withComponentInputBinding` (resolved run → `input()`); `runResolver` (unknown id → `RedirectCommand` to 404); `leaveWhileBusyGuard` (`canDeactivate`); `withViewTransitions`; custom `TitleStrategy`; wildcard 404 page
- [x] **Signals:** `signal`, `computed`, `effect` (persistence, elapsed timer), `linkedSignal` (selected ticket follows the filter), `input()` / `input.required()` / `output()` / `model()` (`[(userId)]`), `viewChild.required()`, `httpResource` (dashboard, tickets, knowledge base, memories with a reactive URL, health), `afterRenderEffect` (auto-scroll); signal store `ChatStore`
- [x] **Templates:** `@if` / `@else if`, `@for` with `track` and `@empty`, `@switch`, `@let`, `@defer` (`on immediate`, `on viewport`, with `@placeholder` / `@loading`), pipes (`date`, `number`, `percent`, `json` + custom `duration`), attribute directive `appAutoScroll`
- [x] **Forms:** typed reactive forms (`NonNullableFormBuilder`) with validators (required, max length, custom `notBlank`) for the chat input and approval comment
- [x] **HTTP:** `provideHttpClient(withFetch(), withInterceptors([loadingInterceptor, errorInterceptor]))`, Problem Details → toast, `HttpContextToken` (`SILENT_ERRORS`) for background polling; dev proxy `/api` + `/actuator` → `localhost:8080`
- [x] **DI:** `inject()` everywhere, `InjectionToken` (`API_CONFIG`) with factory, `providedIn: 'root'` services
- [x] **UI:** chat (example prompts, typing indicator with elapsed time, approval card, per-run reasoning trace), run details page, dashboard (success-metric tiles, outcome bar, per-tool table), tickets (filter + master/detail), knowledge base (search), memories; health pill (Ollama up/down); light/dark theme; skip link, ARIA labels, keyboard support, responsive layout
- [x] **Tests:** 17 Vitest tests (`ChatStore` with `HttpTestingController`, `ApprovalCard`, `DurationPipe`, `App` shell)
- **Result:** full flow through the dev proxy on the real model: ticket request → paused (87 s) → approved → ticket #1 created (22 s); printer question answered from the KB (56 s); injection attempt → 422. Dashboard, tickets, run trace and chat pages checked with headless-Chrome screenshots. Initial bundle 93 kB gzipped, each page a lazy chunk

## Step 10 — Evaluation (before the pilot)
- [x] 25 scripted helpdesk scenarios (`eval/scenarios.json`): knowledge-base answers, no-answer, tickets, escalation, memory, safety, French. Each states the expected outcome (answered / approval / escalated / blocked), tools that must and must not be called, facts the answer must mention (`"a|b"` alternatives), text it must not contain, and details of the action waiting for approval
- [x] Runner (`EvaluationService`): runs the scenarios through the real agent with a chosen model and the current settings, one at a time in the background, each in its own session; actions waiting for approval are checked and then abandoned (no ticket is created); deterministic scoring with readable failure reasons (`ScenarioScorer`); evaluation runs are tagged `source = EVALUATION` and kept out of the dashboard and the reviewers' queue (Flyway V4)
- [x] Admin page `/evaluation` + `GET/POST /api/evaluations`: start (model, categories), progress, cancel, comparison table (pass rate, avg / p95 latency, score per category) and per-scenario details with links to the traces
- [x] Baseline and improvements measured on `qwen3:8b` (results table in the README): 64% → **84%** of the scenarios. Found: the model often answered without searching (invented procedures, fake `[4]` citations copied from the prompt's example) and opened tickets without looking up the procedure (wrong priority). Fixed with prompt v2 and the **grounding check** (Settings toggle, Flyway V6): a turn that ends without any tool call gets one reminder to search, and a `create_ticket` requested before any search is sent back once. Cost: average latency 25 s → 44 s on CPU. Left: two retrieval gaps (→ 12c hybrid search) and one incomplete answer
- [ ] Comparison with `gemma4:26b` (several hours on this CPU — run only on request, or on a GPU)
- **Done when:** baseline numbers are recorded in the README

## Step 11 — Production hardening (Pilot → Scale)
Done one sub-step at a time:
- [x] **11a — Postgres + pgvector ✅:** `compose.yaml` (`pgvector/pgvector:pg17`, random host port) auto-started by Spring Boot's Docker Compose support; Flyway `V1__init.sql` (Hibernate only validates); `PgVectorStore` (768 dims, cosine, HNSW) replaces the in-memory store; tests run on the same image through Testcontainers `@ServiceConnection`; H2 removed
  - Found and fixed on the real database: KB indexing ran inside a read-only transaction (pgvector `INSERT` failed); indexing and search now run outside transactions, so no connection is held while Ollama embeds
- [x] **11b — Agent settings & model switching ✅:** `agent_settings` table (Flyway V2), `GET/PUT/DELETE /api/settings`, `GET /api/models` (installed Ollama models with capabilities from `/api/show`), `GET /api/tools`; applied from the next request with no restart, as a per-run snapshot (`AgentConfig`), so a run waiting for approval keeps its settings
  - Settings: chat model, temperature, top-p, context window (`num_ctx`), max output tokens (`num_predict`), thinking on/off, max agent steps, RAG top-K / similarity threshold, enabled tools, tools that need approval, system-prompt override
  - Validation: model must be installed **and** support tool calling (e.g. `qwen2:0.5b` is rejected); value ranges; unknown tool names
  - Each run records its model; `/api/metrics` and the dashboard compare models (success rate, latency, steps, tokens)
  - UI: Settings page (typed reactive form with `FormRecord` for tools, Fast / Balanced / Thorough presets, capability badges, unsaved-changes guard) + model switcher in the chat header
  - Tests: 9 backend (settings applied to the model call, approval gate switchable, disabled tool rejected, non-tool model rejected, REST round-trip + validation) and 4 frontend tests
  - Checked live: `ollama ps` showed `CONTEXT 4096` after switching to the Fast values. Changing the context window or the model makes Ollama reload the model once (the first request after the change is slower)
- [x] **11c — Durable sessions ✅:** `agent_session` table (Flyway V3) replaces the in-memory store; one request per session through a database claim (`busy_since`, taken over after 30 min if a request crashed); chat memory in Spring AI's `JdbcChatMemoryRepository`; a run paused for approval is saved as JSON in `pending_turn` (messages, held-back tool calls, settings snapshot, escalation) and can be approved after a restart (verified live: paused, restarted, approved, ticket created); on startup, runs cut off mid-flight are marked FAILED. Assumes a single backend instance
- [x] **11d — Security ✅:** Keycloak 26 in `compose.yaml` with an imported realm (roles user/reviewer/admin, public SPA client with PKCE, demo users); Spring Security OAuth2 resource server (JWT, `realm_access.roles` → `ROLE_*`, principal = `preferred_username`); the user id comes from the token; approvals need `reviewer`, settings changes and actuator details need `admin`; other users' sessions/runs/tickets answer 404; approval steps record the reviewer. Frontend: `angular-oauth2-oidc` code flow in an app initializer, functional token interceptor, `canMatch` role guard, Approvals queue page, role-aware chat/settings, polling of runs waiting for a reviewer. Tests use real signed JWTs (test RSA key); verified live with Keycloak tokens and the real model
- [x] **11e — Streaming ✅:** `POST …/messages/stream` and `…/approval/stream` return Server-Sent Events (`run`, `thinking`, `step`, `done` / `error` with a Problem Detail) from a `RunListener` the orchestrator calls for every saved step; the run goes on if the browser disconnects. Frontend reads the stream with `HttpClient` (fetch backend, progress events with `partialText`), so the auth and loading interceptors still apply; the chat shows the user's message at once, then live steps and the current activity. Verified live through the dev proxy: events at +0 s, +12 s, +13 s, +44 s instead of one response at +44 s
- [x] **11f — Delivery ✅:** `backend/Dockerfile` (Maven build with a dependency cache, layered jar, JRE 21, non-root), `frontend/Dockerfile` + `nginx.conf` (static build, `/api` proxy with buffering off for SSE, SPA fallback); root `compose.yaml` with Postgres, Keycloak (`KC_HOSTNAME` so tokens keep the browser's issuer; the backend fetches keys from `keycloak:8080`), Jaeger, backend, frontend, and an optional `ollama` profile that pulls the models; GitHub Actions CI (backend verify with Testcontainers, frontend tests + build, Docker images); OpenTelemetry via `spring-boot-starter-opentelemetry`: a `deskpilot.run` span per request on a run (with run/session ids and model), Spring AI model-call spans and `deskpilot.tool` spans as children, trace context carried to the SSE worker thread; exported over OTLP to Jaeger. Verified: both images build, the whole stack runs in containers (Keycloak login with the browser issuer, nginx serving the app and streaming SSE, 401/200 on the API), and Jaeger shows one trace per request: `http post …/stream` → `agent chat` → `chat qwen3:8b` → HTTP calls to Ollama. Found and fixed: Spring AI's default retry (10 attempts, backoff ×5, no maximum) kept a request hanging for minutes when Ollama was unreachable; now 2 attempts, fails in ~4 s with "Model unavailable"

## Step 12 — Advanced features
Each sub-step is a self-contained release (own Flyway migration, tests, README and changelog entry), done in the
order of the recommended path below. Value / effort are rough estimates for this codebase.

### A. Better answers (knowledge & retrieval)
- [x] **12a — Answers with citations ✅:** the knowledge-base tool returns article ids; the agent cites them as `[1]`, the chat renders Markdown safely and shows the cited articles as links/cards. *Value: high · Effort: S*
- [x] **12b — Document ingestion ✅ (verified live: a Markdown guide became 3 searchable articles in 0.5 s; no OCR for scanned PDFs):** admins upload PDF / Markdown / HTML (Spring AI document readers + token-based chunking with overlap), re-index on change, delete; a Knowledge admin page with ingestion status. *Value: high · Effort: M*
- [x] **12c — Hybrid search ✅ (re-ranking by the model not done: too slow on CPU):** Postgres full-text (BM25-like `ts_rank`) combined with pgvector by reciprocal rank fusion, optional re-ranking of the top hits by the model; measured against Step 10's scenarios. *Value: medium–high · Effort: M*
- [x] **12d — Knowledge-gap detection ✅ (embedding clusters, weekly + on demand; thresholds tuned with nomic-embed-text: grouping 0.55, covered 0.65):** questions answered "not found" or escalated are clustered weekly; reviewers get suggested new articles drafted by the model. *Value: medium · Effort: M*

### B. Smarter agent
- [x] **12e — Multimodal tickets ✅ (verified live: gemma4:26b transcribed a GlobalProtect "Gateway not found / GP-1017" dialog in 83 s, the agent found the VPN article and answered "reinstall from the Company Portal [1]"; 10 min in total on CPU because two large models swap in memory):** the user attaches a screenshot of an error; a vision model (`gemma4:26b` is installed and supports images) reads it, and the text goes into the search and the ticket. *Value: high · Effort: M*
- [x] **12f — Supervisor + specialist agents ✅ (keywords → embeddings → previous → optional model; on by default since the evaluation: 25/25 vs 24/25 without; no per-specialist KB filter):** a router sends each request to a specialist (network/VPN, accounts/MFA, hardware, software) with its own prompt, tools and examples; the trace shows the hand-off. *Value: medium · Effort: L*
- [x] **12g — Self-check before answering ✅ (answers and tickets; knowledge 12/13, tickets 4/4):** a reflection pass compares the draft answer with the retrieved articles and rewrites or escalates unsupported claims (configurable, off on CPU). *Value: medium · Effort: S*
- [x] **12h — Conversation summarization ✅ (verified live with qwen3:8b):** long sessions are summarized into memory instead of truncated, so the context window holds more history. *Value: medium · Effort: S*

### C. Integrations
- [x] **12i — MCP ✅ (server: read-only KB tools at /mcp with JWT; client: external tools always need approval; auth headers for external servers not yet):** expose DeskPilot's tools as an MCP server (Spring AI MCP server starter) and let the agent call external MCP servers (e.g. GitHub Issues or Jira as the real ticket system) behind the same approval gate. *Value: high · Effort: M*
- [x] **12j — Notifications ✅ (reviewer/admin addresses from configuration):** e-mail (Mailpit in development) and/or Slack/Teams webhook when an approval is waiting or a ticket is created; approvals not decided within an SLA are escalated automatically. *Value: medium · Effort: S*
- [x] **12k — Real-time updates ✅ (GET /api/events, single instance):** replace the 5-second polling of paused runs with a per-user SSE channel (approval decided, ticket updated, new item in the reviewers' queue with a counter in the nav). *Value: medium · Effort: S*

### D. Quality, safety & operations
- [x] **12l — User feedback loop ✅:** 👍/👎 and a comment on every answer, stored with the run; shown on the dashboard and turned into evaluation scenarios. *Value: high · Effort: S*
- [x] **12m — Prompt & model experiments ✅ (per-session assignment, raw rates without significance test):** versioned system prompts; A/B split of traffic between two prompt/model variants; dashboard compares success rate, latency and feedback per variant. *Value: medium · Effort: M*
- [x] **12n — Guardrails v2 ✅ (Bucket4j rate limits, Resilience4j circuit breaker, optional moderation; single instance):** model-based moderation and PII detection on input and output, in addition to the regex rules; per-user rate limits and token budgets (Bucket4j); circuit breaker and retry around Ollama (Resilience4j). *Value: medium · Effort: M*
- [x] **12o — Audit & privacy ✅:** audit log of approvals and settings changes with export; users can view and delete their memories and conversations (GDPR "right to erasure"); data retention job. *Value: medium · Effort: S*
- [x] **12p — Grafana dashboards ✅ (grafana/otel-lgtm under a compose profile, OTLP metrics, alerts shown in Grafana only):** metrics and traces in Grafana (OTLP collector + Prometheus/Tempo, or `grafana/otel-lgtm`), alerts on success rate and p95 latency. *Value: low–medium · Effort: S*

### E. User experience
- [x] **12q — Multilingual ✅ (own signal-based i18n with type-checked keys; backend messages stay English):** Angular i18n (English / French / Arabic, including right-to-left layout); the agent answers in the user's language. *Value: medium · Effort: M*
- [x] **12r — Conversation history ✅ (titles from the first question, not generated by the model):** a list of past conversations per user, renamed by the model from the first question, searchable, reopenable. *Value: medium · Effort: S*

### F. Models
- [x] **12s — Cloud providers ✅ (Mistral and Gemini via their OpenAI-compatible endpoints; live test needs the user's own keys):** admins enable a provider and store its API key from the Settings page (AES-256-GCM, write-only; environment variables take precedence), test the connection, and pick its models for chat, vision or an evaluation; errors from the provider become a 502 with an explanation. *Value: medium · Effort: M*

### Recommended path
1. **Step 10 first** (evaluation): every change below should move a measured number, not a feeling.
2. Quick wins that users see at once: **12a** citations + Markdown, **12l** feedback, **12k** real-time updates.
3. Better knowledge: **12b** ingestion, then **12c** hybrid search (measured with Step 10).
4. Differentiators: **12e** screenshots, **12i** MCP / real ticket system.
5. Then by need: 12j, 12r, 12n, 12o, 12m, 12f, 12g, 12h, 12d, 12q, 12p.

## Step 13 — Follow-ups and quality
- [x] **13a — Measured specialists and thresholds ✅:** evaluation 25/25 with specialists vs 24/25 without → on by default; routing and knowledge-gap thresholds tuned on real embeddings (1.0.23).
- [x] **13b — Architecture documentation ✅:** `docs/ARCHITECTURE.md` (use cases, classes, components, sequence, states in Mermaid) and interactive Archify diagrams (1.0.24).
- [x] **13c — Alert notifications ✅:** Grafana contact points (e-mail via Mailpit, optional Slack/Teams webhook) and a notification policy (1.0.25).
- [x] **13d — Experiment follow-ups ✅:** variant badge on runs, automatic stop with a reason when a provider breaks, significance test per metric (1.0.25).
- [x] **13e — End-to-end tests ✅ (scripted fake Ollama; self-check and specialists off in that profile):** Playwright suite over the whole stack, run in CI (1.0.25).
- [ ] **13f — Cloud model measured:** evaluation of a Mistral/Gemini model against qwen3:8b (needs a key).
- [ ] **13g — Specialists editable from the UI** instead of a classpath file.
- [ ] **13h — Several backend instances:** shared rate limits, SSE fan-out and circuit-breaker state (e.g. Redis or Postgres LISTEN/NOTIFY).
- [ ] **13i — Backups and real identities:** database backups, production Keycloak realm without demo users.
