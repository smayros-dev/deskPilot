# DeskPilot

[![CI](https://github.com/smayros-dev/deskPilot/actions/workflows/ci.yml/badge.svg)](https://github.com/smayros-dev/deskPilot/actions/workflows/ci.yml)

**An AI copilot for the IT helpdesk that runs fully on your machine.** DeskPilot answers questions from a knowledge base, opens tickets, and remembers each user's setup. A human approves every risky action before it runs.

Built with **Angular** + **Spring Boot** + **Spring AI**, on a **free local model served by [Ollama](https://ollama.com)**. No paid API and no data leaves your machine.

> The step-by-step implementation plan and its progress live in [`steps.md`](steps.md).

## Features

- **ReAct agent loop**: the model reasons, calls tools, observes results and repeats, within a configurable step budget.
- **Agentic RAG with hybrid search**: articles in PostgreSQL are ranked both semantically (pgvector) and by keywords (full-text search), merged with Reciprocal Rank Fusion. The agent decides when to search and rephrases when results aren't relevant.
- **Human in the loop**: tools marked `@RequiresApproval` (for example `create_ticket`) pause the run until a reviewer approves or rejects it.
- **Memory**: short-term conversation memory and long-term per-user facts (which refuse secrets).
- **Guardrails**: a prompt-injection filter, redaction of passwords, API keys and card numbers, and tool-output limits.
- **Full traceability**: every thought, tool call, result and approval is stored and shown as a reasoning trace.
- **Cited answers**: answers are rendered as Markdown (safely, without raw HTML) and cite the knowledge-base articles they use as `[n]` chips, with a Sources list linking to each article.
- **User feedback**: 👍/👎 and a comment on every answer; the dashboard shows the satisfaction rate (overall and per model) and the latest negative feedback for reviewers.
- **Live progress**: answers are streamed as Server-Sent Events, so the chat shows each step (model thinking, tool calls, results) while the agent works instead of a silent wait of a minute or more.
- **Metrics dashboard**: success rate, average and p95 latency, tool accuracy, escalations and tokens, overall and per model.
- **Durable conversations**: sessions, chat memory and runs waiting for approval are stored in PostgreSQL, so a backend restart loses nothing; a paused run can still be approved afterwards.
- **Sign-in and roles**: OpenID Connect login through Keycloak (Authorization Code + PKCE). The user id comes from the token; only **reviewers** can approve actions (from their own Approvals queue) and only **admins** can change settings.
- **Tracing**: one OpenTelemetry trace per agent run, with every model and tool call as a span, viewable in Jaeger.
- **Grounding check**: when the model answers or opens a ticket without searching the knowledge base, it is sent back once to search first (measured: 64% → 84% of the evaluation scenarios).
- **Self-check**: before answering, the draft is compared with the retrieved articles (missing key facts are added); before a ticket goes to a reviewer, the model checks that the articles really require one.
- **Real-time updates**: a per-user Server-Sent Events channel (`GET /api/events`) pushes run changes to their owner, new tickets, and the number of runs waiting for approval to reviewers (live badge on the Approvals link). The chat only polls while the stream is down.
- **MCP**: DeskPilot's read-only knowledge-base tools are available to other agents and IDEs over the Model Context Protocol, and tools of external MCP servers can be given to the agent (always behind the approval gate).
- **Document upload**: admins add PDF, Markdown, text or HTML files (up to 10 MB) on the Knowledge base page; they are split into articles (by page or heading, with overlapping chunks), embedded, and searched like the built-in articles.
- **Screenshots**: users attach or paste a screenshot of an error; the vision model chosen in Settings (e.g. `gemma4:26b`) transcribes it, and the agent works with that text (marked as data). The image is not stored; the trace keeps what was read.
- **Conversation history**: past conversations in the chat sidebar, titled after their first question; search, reopen, rename, delete.
- **Notifications**: reviewers get an e-mail when an action waits for approval, requesters when their ticket is created, and admins when an approval waits longer than the SLA (default 30 min). Optional Slack/Teams webhook. In development the e-mails land in Mailpit: http://localhost:8026.
- **Audit & privacy**: an audit trail of approvals, settings changes, documents, evaluations and data deletions (admin Audit page with CSV export); users can download everything stored about them and delete their conversations and memories (tickets stay with the helpdesk); optional retention (`agent.retention.conversations-days`).
- **Long conversations**: once a conversation is long, older messages are condensed by the model into a summary that the agent keeps in its instructions; only the recent messages stay verbatim.
- **Guardrails v2**: per-user rate limits on chat messages and approvals (429 with `Retry-After`; admins exempt); a circuit breaker around the model (after 5 failures in a row, calls fail fast with 503 for 30 s; state in `/actuator/health`); optional model-based moderation of messages (`MODERATION_ENABLED`); IBANs and phone numbers redacted from answers like card numbers.
- **Multilingual UI**: English, French and Arabic (right-to-left), switchable from the user menu; the choice is remembered per browser and the default follows the browser language. Dates and numbers follow the selected locale. Error details returned by the backend stay in English.
- **Cloud models (optional)**: admins can enable Mistral and Gemini (free tiers) on the Settings page and pick their models for chat, vision or an evaluation, next to the local Ollama models. Keys come from `MISTRAL_API_KEY` / `GEMINI_API_KEY` or are entered once in the UI, stored encrypted (AES-256-GCM) and never shown again. A warning reminds that questions, articles and tickets are then sent to that provider; the local model stays the default.
- **Prompt & model experiments**: admins save named versions of the system prompt and run an A/B experiment between two variants (local or cloud model + optional prompt version, configurable split). Each conversation stays on its variant; the Experiments page compares success rate, escalations, latency (avg/p95), tokens and 👍/👎 per variant, with a significance verdict (two-proportion z-test, Fisher's exact test for small samples, 5 % level; "not enough data" below `EXPERIMENT_MIN_SAMPLE` runs). Reviewers and admins see the variant as a 🧪 badge on answers and runs. An experiment stops by itself, with the reason shown and an e-mail to admins, when a cloud provider it uses is disabled or loses its key, or after `EXPERIMENT_MAX_PROVIDER_FAILURES` provider errors in a row.
- **Knowledge gaps**: every week (and on demand) questions that were escalated, rated 👎 or found nothing in the knowledge base are grouped by meaning; questions already covered by an article are skipped. On the Knowledge page reviewers let the model draft an article for a gap, edit it, and publish it to the knowledge base or dismiss it.
- **Specialist agents**: with "Specialist agents" on in Settings (the default), each message is routed to a network/VPN, accounts/MFA, hardware or software specialist (keywords first, then embedding similarity, then the conversation's previous specialist; optionally the model) with its own instructions, examples and tool subset. The hand-off is a step in the trace, the answer shows a 🧭 badge, and the dashboard breaks results down per specialist. Measured: 25/25 scenarios with specialists vs 24/25 without, same latency.
- **Grafana (optional)**: `docker compose --profile grafana up -d lgtm` in `backend/` starts Grafana at http://localhost:3000 (admin / admin) with a provisioned "DeskPilot — Agent" dashboard (success rate, p95 latency, outcomes, per model/provider, tokens, tools, feedback, approvals, guardrails) and alerts on success rate (< 70 % over 1 h) and p95 latency (> 2 min). Set `OTEL_METRICS_EXPORT_ENABLED=true` to send metrics. Alerts are e-mailed (also when resolved) to `GRAFANA_ALERT_EMAILS` through Mailpit, and to a Slack/Teams/generic webhook when `GRAFANA_ALERT_WEBHOOK_URL` is set.
- **Runtime settings**: switch the model and tune temperature, top-p, context window, thinking mode, RAG and tools from the UI, with no restart.

## Architecture

Detailed documentation with diagrams is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): use cases per role, domain and agent-core class diagrams, components, the chat → approval sequence, and the states of a run and of a knowledge gap (Mermaid, rendered by GitHub). Interactive versions (architecture, sequence, run lifecycle, agent-loop workflow) built with Archify are in [`docs/diagrams/`](docs/diagrams/); open the HTML files locally in a browser.

```mermaid
flowchart LR
    user([User / Reviewer]) -->|chat · approve| spa[Angular 22 SPA]
    spa -->|REST /api| api[REST controllers<br/>Spring Boot 4.1]
    api -->|start / resume run| orch[AgentOrchestrator<br/>ReAct loop]
    orch -->|chat + tool schemas| llm[Ollama chat model<br/>qwen3:8b]
    orch -->|check input · redact| guard{{GuardrailService}}
    orch -->|run tool · gated → approval| tools[ToolRegistry<br/>KB · tickets · memory · escalate]
    tools -->|embed query| emb[Ollama embeddings<br/>nomic-embed-text]
    tools --> db[(PostgreSQL + pgvector)]
    orch -->|persist run & steps| db
    api -->|runs · settings · metrics| db
```

| Part | Tech | Folder |
|---|---|---|
| Backend | Spring Boot 4.1, Java 21, Spring AI 2.0, PostgreSQL + pgvector, Flyway | [`backend/`](backend) |
| Frontend | Angular 22 (standalone, signals, zoneless, `httpResource`, `@defer`) | [`frontend/`](frontend) |
| LLM | Ollama: `qwen3:8b` (chat + tools), `nomic-embed-text` (embeddings) | local |

## Project layout

```
backend/                       Spring Boot app (pl.piomin.services.deskpilot)
  agent/                       orchestrator, settings, model catalog, chat options
  agent/tool/                  @Tool classes: knowledge base, tickets, memory, escalation
  guardrail/                   input filter and output redaction
  security/                    JWT resource server, roles (user / reviewer / admin)
  controller/ service/ model/  REST API, services, JPA entities
  src/main/resources/          application.yml, Flyway migrations, system prompt, KB articles
  compose.yaml                 development containers: Postgres + pgvector, Keycloak, Jaeger (started automatically)
  Dockerfile                   layered Spring Boot image (JRE 21, non-root)
  keycloak/                    realm with roles, SPA client and demo users
frontend/                      Angular app
  src/app/chat/                chat, approval cards, model switcher
  src/app/approvals/           reviewers' approval queue
  src/app/runs/                run trace and details
  src/app/dashboard/           success metrics
  src/app/helpdesk/            tickets, knowledge base, memories
  src/app/settings/            runtime agent settings
  Dockerfile, nginx.conf       production build served by nginx, proxying the API
compose.yaml                   the whole stack in containers
.github/workflows/ci.yml       build, tests and Docker images on every push
steps.md                       implementation plan and progress
```

## Prerequisites

- Java 21+, Node 22+
- Docker (Docker Desktop / Rancher Desktop): Postgres + pgvector and Keycloak run from `backend/compose.yaml`, started automatically by Spring Boot
- [Ollama](https://ollama.com) running locally, with the models pulled:
  ```bash
  ollama pull qwen3:8b
  ollama pull nomic-embed-text
  ```

## Run

```bash
# backend → http://localhost:8080
cd backend && ./mvnw spring-boot:run

# frontend → http://localhost:4200
cd frontend && npm start
```

Open **http://localhost:4200** and sign in. The dev server proxies `/api` and `/actuator` to the backend.

### Demo users

The Keycloak realm (`backend/keycloak/deskpilot-realm.json`) is imported on first start, with these users (local demo only):

| User / password | Roles | Can |
|---|---|---|
| `alice` / `alice` | user | Chat, see own tickets and memories, read settings |
| `bob` / `bob` | user, reviewer | Also approve or reject agent actions on the **Approvals** page, see all tickets |
| `admin` / `admin` | user, reviewer, admin | Also change the agent settings and switch the model |

Keycloak's own console is at http://localhost:8180 (admin / admin). E-mails sent in development are in Mailpit at http://localhost:8026 (if the development containers were already running, start Mailpit once with `docker compose up -d` in `backend/`). Traces are in Jaeger at http://localhost:16686 (service `deskpilot`, one trace per agent run, with the model and tool calls as spans).

> Spring Boot only runs `docker compose up` when none of the project's containers are running. If Postgres was
> already up from an earlier version, start Keycloak once with `docker compose up -d` in `backend/`.
Check `http://localhost:8080/actuator/health`. The `ollama` component reports DOWN if Ollama isn't running or a model is missing.

## Configuration

Most agent behaviour (model, sampling, context window, tools, approvals, prompt) is changed at runtime on the **Settings** page and stored in the database. To try another model, `ollama pull <name>` and pick it there; only models with tool calling are offered. The environment variables below set the **defaults** used on first start or after "Reset to defaults".

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `DESKPILOT_PG_PORT` | `5433` | Host port of the development Postgres (`backend/compose.yaml`); fixed so a Docker restart does not strand a running backend |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server |
| `AGENT_CHAT_MODEL` | `qwen3:8b` | Chat/tool-calling model |
| `AGENT_EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model for RAG |
| `AGENT_THINK` | `false` | qwen3 reasoning mode. About 15x slower on CPU; enable on a GPU |
| `AGENT_VISION_MODEL` | (empty) | Default vision model for screenshots, e.g. `gemma4:26b`; empty = off (can be set on the Settings page) |
| `MISTRAL_API_KEY` / `GEMINI_API_KEY` | (empty) | Cloud provider keys; take precedence over keys entered on the Settings page |
| `DESKPILOT_SECRET_KEY` | (empty) | Base64 AES-256 key that encrypts keys stored from the UI; empty = generated once into `~/.deskpilot/secret.key` |
| `RATE_LIMIT_PER_MINUTE` / `RATE_LIMIT_PER_DAY` | `10` / `100` | Chat messages and approval decisions per user (0 = off) |
| `MODERATION_ENABLED` | `false` | Classify each message SAFE/UNSAFE with the model before the agent runs |
| `MODERATION_MODEL` | (empty) | Model used for moderation; empty = the chat model |
| `NOTIFICATIONS_ENABLED` | `true` | E-mail/webhook notifications |
| `NOTIFICATIONS_REVIEWERS` / `NOTIFICATIONS_ADMINS` | `bob@example.com,admin@example.com` / `admin@example.com` | Who gets approval requests / SLA escalations |
| `NOTIFICATIONS_APPROVAL_SLA` | `PT30M` | Escalate approvals waiting longer than this |
| `NOTIFICATIONS_WEBHOOK_URL` | (empty) | Slack/Teams incoming webhook |
| `NOTIFICATIONS_BASE_URL` | `http://localhost:4200` | Base of the links in e-mails |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1026` | SMTP server (Mailpit in development) |
| `DESKPILOT_ISSUER_URI` | `http://localhost:8180/realms/deskpilot` | OIDC issuer whose tokens the API accepts |
| `OTEL_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP endpoint for traces (Jaeger) |
| `TRACING_EXPORT_ENABLED` | `true` | Set to `false` to stop exporting traces |
| `OTEL_METRICS_EXPORT_ENABLED` | `false` | Export Micrometer metrics over OTLP to Grafana (lgtm) |
| `GRAFANA_ALERT_EMAILS` | `admin@example.com` | Recipients of Grafana alerts (compose, `grafana` profile) |
| `GRAFANA_ALERT_WEBHOOK_URL` / `GRAFANA_ALERT_WEBHOOK_TYPE` | (empty) / `slack` | Also send alerts to a `slack`, `teams` or generic `webhook` URL |
| `EXPERIMENT_MIN_SAMPLE` / `EXPERIMENT_MAX_PROVIDER_FAILURES` | `10` / `3` | Runs per variant before a significance verdict; provider errors in a row that stop an experiment |
| `OTEL_METRICS_ENDPOINT` / `OTEL_METRICS_STEP` | `http://localhost:4328/v1/metrics` / `15s` | OTLP metrics endpoint (host port 4328, Jaeger keeps 4318) and export interval |
| `KNOWLEDGE_GAPS_CRON` | `0 0 6 * * MON` | Weekly knowledge-gap detection (`-` = only on demand); thresholds under `agent.knowledge-gaps.*` |
| `AGENT_SPECIALISTS` | `true` | Default of the "Specialist agents" setting; thresholds under `agent.specialists.*` |
| `AGENT_SPECIALISTS_MODEL_FALLBACK` | `false` | Let the chat model pick a specialist when keywords and embeddings don't decide |

## Frontend pages

| Route | What it shows |
|---|---|
| `/chat` | Conversation with the agent, pending actions (decided here by reviewers, otherwise shown until a reviewer decides), per-answer reasoning trace |
| `/approvals` | Reviewers only: every run waiting for a decision, from all users |
| `/runs/:id` | Full trace of one run (thoughts, tool calls, results, approvals, tokens, timing) |
| `/dashboard` | Success metrics: task success, latency (avg/p95), tool accuracy, escalations, approvals, tokens |
| `/helpdesk/tickets` · `/knowledge` · `/memories` | Tickets (own, or all for reviewers), KB articles, long-term memories of the signed-in user |
| `/experiments` | Admins: prompt versions, start/stop A/B experiments, results per variant |
| `/settings` | Agent configuration (read-only unless admin): cloud providers (admins), model (local or cloud), temperature, top-p, context window, max tokens, thinking, max steps, RAG, tools & approvals, system prompt |

## Run everything in Docker

The root `compose.yaml` builds and starts the whole stack: Postgres + pgvector, Keycloak, Jaeger, the backend and the
frontend (nginx, which also proxies `/api` and streams Server-Sent Events).

```bash
docker compose up --build        # then open http://localhost:4200
```

By default the backend uses the Ollama already running on your machine (`host.docker.internal:11434`), which is the
fastest option and can use your GPU. Ollama must then accept connections from containers: by default it listens on
`127.0.0.1` only, which containers cannot reach (on Windows with Rancher Desktop or WSL, for example). Either start Ollama
with `OLLAMA_HOST=0.0.0.0` (it is then reachable from your network too — keep the firewall on), or run Ollama in a
container as well (pulls `qwen3:8b` and `nomic-embed-text` on first start, CPU only):

```bash
OLLAMA_BASE_URL=http://ollama:11434 docker compose --profile ollama up --build
```

The stack uses the same ports as the development setup (4200, 8080, 8180, 16686), so stop one before starting the other
(`docker compose down` in `backend/` for the development containers).

## Evaluation

The **Evaluation** page (admins) runs 25 scripted helpdesk scenarios ([`eval/scenarios.json`](backend/src/main/resources/eval/scenarios.json))
through the real agent with a chosen model and scores each answer: expected outcome (answered, approval requested,
escalated, blocked), tools that must and must not be called, facts the answer must mention and text it must not contain.
Use it to compare models, prompts and settings on the same requests before changing the live agent. Ticket requests are
checked at the approval step and never created; evaluation runs do not count in the dashboard.

### Results (qwen3:8b on CPU, 2026-10-20)

| Version | Score | Knowledge | Tickets | Escalation | Avg / p95 latency |
|---|---|---|---|---|---|
| Baseline (prompt v1) | 16/25 (64%) | 6/13 | 3/4 | 1/2 | 25 s / 52 s |
| Prompt v2 (19 scenarios) | 13/19 (baseline: 10/19) | 8/13 | 3/4 | 2/2 | — |
| + grounding check for answers (19 scenarios) | 16/19 | 11/13 | 3/4 | 2/2 | — |
| + grounding check for tickets | **21/25 (84%)** | 10/13 | 3/4 | 2/2 | 44 s / 71 s |
| + refined ticket reminder (ticket scenarios) | — | — | **4/4** | — | — |
| + hybrid search (knowledge scenarios) | — | **11/13** | — | — | 62 s / — |
| + self-check (knowledge + ticket scenarios) | — | **12/13** | **4/4** | — | ~110 s* / — |
| 1.0.22, all features, specialists off | 24/25 (96%) | 12/13 | 4/4 | 2/2 | 84 s / 126 s |
| 1.0.22, specialists on | **25/25 (100%)** | **13/13** | 4/4 | 2/2 | 81 s / 147 s |

What the evaluation found and what fixed it:
- **Specialist agents (2026-10-20)**: with routing on, all 25 scenarios pass; the one difference is "account locked", which the
  Accounts & MFA specialist answers with the 30-minute wait (the general agent suggested a password reset). Routing was
  right for all 24 routed questions: 21 by keywords, 3 by embedding similarity (correct specialist 0.73–0.95, runner-up
  0.63–0.67, so the similarity threshold was raised from 0.6 to 0.7); the two off-topic or "talk to a person" messages
  stayed with the general agent. One run each, so one scenario is within noise: specialists are on by default because
  they did not lose anything, not because they are proven better.
- **Knowledge-gap thresholds** checked with `nomic-embed-text`: paraphrases of the same question score 0.52–0.86 and
  different questions at most 0.49, so questions are grouped from 0.55 (was 0.8, which grouped 2 of 13 paraphrase pairs).
  Questions the knowledge base answers score 0.54–0.83 against their article and uncovered ones 0.45–0.60, so a question
  counts as covered from 0.65 (was 0.75, which treated 8 of 13 answered questions as gaps).
- **The model answered from general knowledge** in 7 of 13 knowledge questions: no search, invented procedures
  ("12 characters" instead of 14, a guest Wi-Fi without password) and copied the prompt's example citation `[4]`.
  Prompt v2 (no literal example, "search even if you think you know") helped a little; the **grounding check** — when a
  turn ends without any tool call, the model is reminded once to search — fixed most of it.
- **Tickets were opened without looking up the procedure**: wrong priority for a lost phone (HIGH instead of CRITICAL),
  a ticket for a mouse that the IT desk hands out. The grounding check also sends back a `create_ticket` requested
  before any search, once per turn.
- **Cost**: one extra model call when a check fires, so the average latency rose from 25 s to 44 s on CPU. The check
  can be switched off on the Settings page.
- **Retrieval gaps** ("install visual studio code", "broken mouse" found no article above the similarity threshold)
  were fixed by **hybrid search** (12c): both questions now retrieve the right article.
- **Self-check** (12g) fixed the unnecessary ticket for a mouse: the articles say accessories are handed out at the IT
  desk, so the ticket is sent back before it reaches a reviewer.
- **Left**: one answer (account locked) suggests a password reset instead of waiting 30 minutes; the self-check judged
  it acceptable. *Latency of the last run inflated by other builds running on the same machine.
  `gemma4:26b` was not compared: far too slow on this CPU.

## MCP (Model Context Protocol)

- **DeskPilot as an MCP server**: `http://localhost:8080/mcp` (Streamable HTTP) exposes `search_knowledge_base` and
  `get_knowledge_article`, read-only, to other agents and IDEs. Send `Authorization: Bearer <access token>` from Keycloak,
  for a user with the `user` role. Tools that change data (tickets, memories, escalation) are not exposed.
- **External MCP servers**: their tools join the agent as `mcp_<server>_<tool>`, always need a reviewer's approval, and
  never receive user or run data. They appear on the Settings page with an "MCP" badge. Example:

  ```yaml
  spring:
    ai:
      mcp:
        client:
          streamable-http:
            connections:
              github:
                url: https://api.githubcopilot.com
                endpoint: /mcp/
  ```

  Stdio servers work too (`spring.ai.mcp.client.stdio.connections.<name>.command/args/env`). Servers that need an auth
  header (e.g. a GitHub token) are not supported yet.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and pull request: backend build and tests
(Testcontainers starts Postgres on the runner), frontend tests and production build, then builds both Docker images. An `e2e` job then starts Postgres, Keycloak, a scripted fake Ollama and the backend (`e2e` profile) with the frontend, and runs the Playwright tests; the report and logs are uploaded when it fails.

## Tests

```bash
cd frontend && npx ng test --watch=false   # Vitest component/service tests
cd backend && ./mvnw test                  # unit + integration tests on Postgres via Testcontainers (Docker needed, no Ollama)
OLLAMA_IT=true ./mvnw test -Dtest="*SmokeTest"   # real local model: chat, RAG, full agent runs (slow on CPU)
```

End-to-end tests ([`e2e/`](e2e/README.md)): Playwright drives the real UI through Keycloak sign-in, a cited answer, a ticket
approved by a reviewer, role checks and the French/Arabic UI, against the backend's `e2e` profile and a scripted
stand-in for Ollama (no model needed, about 30 s). See [`e2e/README.md`](e2e/README.md) to run them locally.

## REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/sessions` | Start a conversation for the signed-in user |
| `POST` | `/api/sessions/{id}/messages` | Ask the agent (`{"message": "...", "image": {"mimeType": "image/png", "data": "<base64>"}}`, image optional) → run with trace |
| `POST` | `/api/sessions/{id}/messages/stream` | Same, as `text/event-stream`: `run`, `thinking`, `step`… then `done` (final run) or `error` (Problem Detail) |
| `GET` | `/api/sessions/{id}/runs` | Messages of a conversation |
| `GET` | `/api/sessions?q=` | Your conversations, newest first (search in titles and questions) |
| `PATCH` `DELETE` | `/api/sessions/{id}` | Rename (`{"title": "..."}`) or delete a conversation (409 while it is working or waiting for approval) |
| `GET` `DELETE` | `/api/me/export`, `/api/me/data`, `/api/me/memories[/{id}]` | Download your data; delete your conversations and memories |
| `GET` | `/api/audit`, `/api/audit/export` | Admins: audit trail (filters action, actor, from, to; paging) and CSV export |
| `GET` | `/api/runs/{id}` | One run with its steps |
| `GET` `POST` `PUT` | `/api/knowledge-gaps`, `POST /detect`, `POST` `PUT /{id}/draft`, `POST /{id}/publish`, `POST /{id}/dismiss` | Reviewers: knowledge gaps, model drafts, publish to the knowledge base |
| `GET` `POST` `DELETE` | `/api/prompts`, `/api/prompts/{id}` | Admins: immutable prompt versions (409 when used by an experiment) |
| `GET` `POST` | `/api/experiments`, `/{id}`, `POST /{id}/stop` | Admins: A/B experiments (`{name, a:{provider, model, promptId}, b:{…}, bPercent}`) with per-variant results; one running at a time |
| `GET` | `/api/specialists` | Specialist agents with their keywords and allowed tools |
| `GET` `POST` `DELETE` | `/api/knowledge-base/documents`, `/{id}`, `POST /{id}/reindex` | Admins: upload (multipart `file`, optional `title`), list, re-index and delete documents |
| `GET` | `/api/events` | Live updates as Server-Sent Events: `run` (your runs), `ticket` (your tickets; all for reviewers), `approvals` (`{"pending": n}`, reviewers) |
| `GET` | `/api/runs` | Reviewers: runs waiting for approval, oldest first |
| `PUT` `DELETE` | `/api/runs/{id}/feedback` | Owner: rate a finished answer (`{"rating": "UP"|"DOWN", "comment": "..."}`) or remove the rating |
| `GET` | `/api/feedback?rating=DOWN` | Reviewers: latest negative feedback |
| `GET` `POST` | `/api/evaluations`, `/api/evaluations/{id}`, `/api/evaluations/scenarios`, `POST /api/evaluations/{id}/cancel` | Admins: run and compare evaluations |
| `POST` | `/api/runs/{id}/approval` | Reviewers: approve/reject a paused tool call (`{"approved": true, "comment": "..."}`) |
| `POST` | `/api/runs/{id}/approval/stream` | Same, streaming the resumed run as Server-Sent Events |
| `GET` | `/api/tickets`, `/api/me/memories`, `/api/knowledge-base/articles` | Agent data (tickets: own, or all for reviewers) |
| `GET` | `/api/metrics` | Success metrics (success rate, latency, tool accuracy, escalations…), also per model |
| `GET` `PUT` `DELETE` | `/api/settings` | Read / change / reset the runtime agent settings (applied from the next request) |
| `GET` | `/api/models`, `/api/tools` | Installed Ollama models with capabilities; available tools |
| `GET` `PATCH` `POST` | `/api/providers`, `/{id}` (`{"enabled": true, "apiKey": "...", "removeKey": false}`), `/{id}/test`, `GET /{id}/models` | Admins: cloud providers (keys are write-only), connection test, chat models of a provider |

Every `/api` call needs `Authorization: Bearer <access token>` from Keycloak (401 without, 403 without the role). Other users' sessions, runs and tickets answer 404. `PUT`/`DELETE /api/settings` need the admin role.

Errors are RFC 9457 Problem Details (400 validation, 404, 409 conflicting state, 422 guardrail, 502 model down).

## Changelog

- **1.0.26** — Fix: the status pill no longer says "Model offline" for every problem; it tells apart the model (Ollama), the database and an unreachable backend, with a hint for each, and gives up after 10 s instead of waiting for a stuck backend. The development Postgres now has a fixed host port (5433) instead of a random one that changed on every Docker restart.
- **1.0.25** — Grafana alerts e-mailed through Mailpit and optionally to a Slack/Teams webhook; experiments: variant badge on answers and runs, automatic stop with a reason when a provider breaks (V21), significance verdict per metric; Playwright end-to-end tests with a fake Ollama, run in CI.
- **1.0.24** — Documentation: `docs/ARCHITECTURE.md` with use-case, class, component, sequence and state diagrams (Mermaid) and interactive Archify diagrams of the architecture, the chat → approval sequence, the run lifecycle and the agent loop.
- **1.0.23** — Measured with the evaluation: specialist agents on by default (25/25 vs 24/25), routing similarity threshold 0.7; knowledge-gap thresholds tuned on real embeddings (grouping 0.55, covered 0.65).
- **1.0.22** — Prompt versions and A/B experiments (12m, V18); knowledge-gap detection with model-drafted articles (12d, V19); Grafana dashboard and alerts with OTLP metrics (12p); optional supervisor + specialist agents with hand-off in the trace (12f, V20).
- **1.0.21** — Optional cloud models: Mistral and Gemini through their OpenAI-compatible APIs, enabled and keyed from the Settings page (keys encrypted, V17), usable for chat, vision and evaluations (12s).
- **1.0.20** — Conversation summaries (12h, V16); guardrails v2: rate limits, circuit breaker around the model, optional moderation, IBAN/phone redaction (12n); English/French/Arabic UI with right-to-left layout (12q); fix: the page no longer gets wider than the screen when the header is long.
- **1.0.19** — Conversation history (12r, V13), audit trail and privacy controls (12o, V14), e-mail/webhook notifications with Mailpit and an approval SLA (12j, V15).
- **1.0.18** — Document ingestion (12b): PDF/Markdown/text/HTML uploads split into searchable articles (Flyway V12).
- **1.0.17** — Screenshots (12e): attach or paste an error screenshot; a vision model from the Settings (Flyway V11) reads it before the agent starts.
- **1.0.16** — Self-check of answers and tickets (12g, Flyway V10); real-time updates over a per-user SSE channel with a live approval badge (12k); MCP server for the knowledge base and MCP client for external tools behind the approval gate (12i).
- **1.0.15** — Hybrid search (12c): Postgres full-text ranking merged with pgvector by Reciprocal Rank Fusion (Flyway V7), Settings toggle; knowledge scenarios 10/13 → 11/13.
- **1.0.14** — Step 10 results: prompt v2 and the grounding check (answers and tickets, Settings toggle, Flyway V6); qwen3:8b from 64% to 84% of the scenarios.
- **1.0.13** — Cited answers with safe Markdown rendering (12a); 👍/👎 feedback on answers with satisfaction metrics (12l).
- **1.0.12** — Evaluation: 25 scenarios, background runner with deterministic scoring, admin Evaluation page comparing models; evaluation runs kept out of the live metrics; bounded Spring AI retry so an unreachable model fails in seconds.
- **1.0.11** — Delivery: Dockerfiles (layered backend image, nginx frontend with SSE proxying), full-stack `compose.yaml` (optional Ollama container), GitHub Actions CI, OpenTelemetry tracing to Jaeger (one trace per run, model and tool spans).
- **1.0.10** — Streaming: Server-Sent Events endpoints for chat and approvals; the chat shows live steps and what the agent is doing.
- **1.0.9** — Security: Keycloak (OIDC, PKCE) in Docker Compose, Spring Security resource server, user id from the token, reviewer role for approvals (new Approvals page), admin role for settings, ownership checks on sessions/runs/tickets.
- **1.0.8** — Durable sessions: sessions, chat memory (Spring AI JDBC repository) and runs paused for approval stored in PostgreSQL (Flyway V3); runs interrupted by a restart are marked failed on startup.
- **1.0.7** — Project renamed to DeskPilot: Maven artifact `deskpilot`, package `pl.piomin.services.deskpilot`, UI title, Compose database `deskpilot`.
- **1.0.6** — Runtime agent settings and model switching from the UI (Settings page + chat switcher), per-model metrics.
- **1.0.5** — PostgreSQL + pgvector (Docker Compose, Flyway, Testcontainers) replaces H2 and the in-memory vector store.
- **1.0.4** — Angular frontend: chat with approvals and reasoning traces, run details, metrics dashboard, tickets/knowledge/memories pages.
- **1.0.3** — Guardrails (prompt-injection filter, secret/card redaction, tool-output limits), REST API, Micrometer metrics and `/api/metrics` success metrics.
- **1.0.2** — Tools (`@Tool` + `@RequiresApproval`), ReAct agent loop with human approval gate, run tracing; tests with a scripted model and opt-in smoke tests on qwen3.
- **1.0.1** — Domain model (runs, steps, tickets, memories, KB articles), vector-search knowledge base with seeded helpdesk articles.
- **1.0.0** — Project scaffold; Spring AI + Ollama wiring, Ollama health indicator, Problem Details error handling.
