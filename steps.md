# Agent AI — Implementation Steps

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
- [ ] 20–30 scripted helpdesk scenarios with expected outcomes (answer from KB, ticket created, escalated)
- [ ] Runner that reports success rate and latency per scenario
- [ ] Compare models (`qwen3:8b` vs `gemma4:26b`, or a paid model) on the same scenarios, so the choice rests on data
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
- [ ] **11c — Durable sessions:** sessions in the database, chat memory in the JDBC `ChatMemoryRepository`, and runs paused for approval can still be approved after a backend restart
- [ ] **11d — Security:** Spring Security resource server (OAuth2/JWT, Keycloak in Docker Compose); the user id comes from the token, not the request; reviewer role required to approve
- [ ] **11e — Streaming:** Server-Sent Events, so the UI shows each step (thought, tool call, result) while the agent works
- [ ] **11f — Delivery:** Dockerfiles (backend + frontend/nginx), full-stack Compose (incl. Ollama), CI pipeline (build + tests), OpenTelemetry tracing

## Step 12 — Extensions (only if measurements justify them)
- [ ] MCP tools (e.g. a real ticketing system), A2A
- [ ] Reflection/critic pass or a supervisor with specialist sub-agents
- [ ] A larger or hosted model if the evaluation shows quality gaps
