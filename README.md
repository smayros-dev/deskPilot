# DeskPilot

**An AI copilot for the IT helpdesk that runs fully on your machine.** DeskPilot answers questions from a knowledge base, opens tickets, and remembers each user's setup. A human approves every risky action before it runs.

Built with **Angular** + **Spring Boot** + **Spring AI**, on a **free local model served by [Ollama](https://ollama.com)**. No paid API and no data leaves your machine.

> The step-by-step implementation plan and its progress live in [`steps.md`](steps.md).

## Features

- **ReAct agent loop**: the model reasons, calls tools, observes results and repeats, within a configurable step budget.
- **Agentic RAG**: semantic search over helpdesk articles in PostgreSQL + pgvector. The agent decides when to search and rephrases when results aren't relevant.
- **Human in the loop**: tools marked `@RequiresApproval` (for example `create_ticket`) pause the run until a reviewer approves or rejects it.
- **Memory**: short-term conversation memory and long-term per-user facts (which refuse secrets).
- **Guardrails**: a prompt-injection filter, redaction of passwords, API keys and card numbers, and tool-output limits.
- **Full traceability**: every thought, tool call, result and approval is stored and shown as a reasoning trace.
- **Metrics dashboard**: success rate, average and p95 latency, tool accuracy, escalations and tokens, overall and per model.
- **Runtime settings**: switch the model and tune temperature, top-p, context window, thinking mode, RAG and tools from the UI, with no restart.

## Architecture

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
backend/                       Spring Boot app (pl.piomin.services.agentai)
  agent/                       orchestrator, settings, model catalog, chat options
  agent/tool/                  @Tool classes: knowledge base, tickets, memory, escalation
  guardrail/                   input filter and output redaction
  controller/ service/ model/  REST API, services, JPA entities
  src/main/resources/          application.yml, Flyway migrations, system prompt, KB articles
  compose.yaml                 Postgres + pgvector (started automatically)
frontend/                      Angular app
  src/app/chat/                chat, approval cards, model switcher
  src/app/runs/                run trace and details
  src/app/dashboard/           success metrics
  src/app/helpdesk/            tickets, knowledge base, memories
  src/app/settings/            runtime agent settings
steps.md                       implementation plan and progress
```

## Prerequisites

- Java 21+, Node 22+
- Docker (Docker Desktop / Rancher Desktop): Postgres + pgvector runs from `backend/compose.yaml`, started automatically by Spring Boot
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

Open **http://localhost:4200**. The dev server proxies `/api` and `/actuator` to the backend.
Check `http://localhost:8080/actuator/health`. The `ollama` component reports DOWN if Ollama isn't running or a model is missing.

## Configuration

Most agent behaviour (model, sampling, context window, tools, approvals, prompt) is changed at runtime on the **Settings** page and stored in the database. To try another model, `ollama pull <name>` and pick it there; only models with tool calling are offered. The environment variables below set the **defaults** used on first start or after "Reset to defaults".

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server |
| `AGENT_CHAT_MODEL` | `qwen3:8b` | Chat/tool-calling model |
| `AGENT_EMBEDDING_MODEL` | `nomic-embed-text` | Embedding model for RAG |
| `AGENT_THINK` | `false` | qwen3 reasoning mode. About 15x slower on CPU; enable on a GPU |

## Frontend pages

| Route | What it shows |
|---|---|
| `/chat` | Conversation with the agent, approval cards for gated actions, per-answer reasoning trace |
| `/runs/:id` | Full trace of one run (thoughts, tool calls, results, approvals, tokens, timing) |
| `/dashboard` | Success metrics: task success, latency (avg/p95), tool accuracy, escalations, approvals, tokens |
| `/helpdesk/tickets` · `/knowledge` · `/memories` | Tickets created, KB articles, long-term memories of the current user |
| `/settings` | Agent configuration: model, temperature, top-p, context window, max tokens, thinking, max steps, RAG, tools & approvals, system prompt |

## Tests

```bash
cd frontend && npx ng test --watch=false   # Vitest component/service tests
cd backend && ./mvnw test                  # unit + integration tests on Postgres via Testcontainers (Docker needed, no Ollama)
OLLAMA_IT=true ./mvnw test -Dtest="*SmokeTest"   # real local model: chat, RAG, full agent runs (slow on CPU)
```

## REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/sessions` | Start a conversation (`{"userId": "alice"}`, optional) |
| `POST` | `/api/sessions/{id}/messages` | Ask the agent (`{"message": "..."}`) → run with trace |
| `GET` | `/api/sessions/{id}/runs` | Conversation history |
| `GET` | `/api/runs/{id}` | One run with its steps |
| `POST` | `/api/runs/{id}/approval` | Approve/reject a paused tool call (`{"approved": true, "comment": "..."}`) |
| `GET` | `/api/tickets`, `/api/users/{id}/memories`, `/api/knowledge-base/articles` | Agent data |
| `GET` | `/api/metrics` | Success metrics (success rate, latency, tool accuracy, escalations…), also per model |
| `GET` `PUT` `DELETE` | `/api/settings` | Read / change / reset the runtime agent settings (applied from the next request) |
| `GET` | `/api/models`, `/api/tools` | Installed Ollama models with capabilities; available tools |

Errors are RFC 9457 Problem Details (400 validation, 404, 409 conflicting state, 422 guardrail, 502 model down).

## Changelog

- **1.0.6** — Runtime agent settings and model switching from the UI (Settings page + chat switcher), per-model metrics.
- **1.0.5** — PostgreSQL + pgvector (Docker Compose, Flyway, Testcontainers) replaces H2 and the in-memory vector store.
- **1.0.4** — Angular frontend: chat with approvals and reasoning traces, run details, metrics dashboard, tickets/knowledge/memories pages.
- **1.0.3** — Guardrails (prompt-injection filter, secret/card redaction, tool-output limits), REST API, Micrometer metrics and `/api/metrics` success metrics.
- **1.0.2** — Tools (`@Tool` + `@RequiresApproval`), ReAct agent loop with human approval gate, run tracing; tests with a scripted model and opt-in smoke tests on qwen3.
- **1.0.1** — Domain model (runs, steps, tickets, memories, KB articles), vector-search knowledge base with seeded helpdesk articles.
- **1.0.0** — Project scaffold; Spring AI + Ollama wiring, Ollama health indicator, Problem Details error handling.
