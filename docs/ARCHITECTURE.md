# DeskPilot — Architecture

This document describes how DeskPilot is built, with diagrams traced from the source code
(`backend/src/main/java/pl/piomin/services/deskpilot`, `frontend/src/app`). The Mermaid diagrams render directly on
GitHub; the interactive versions are listed at the end.

- [Use cases](#use-cases)
- [Components](#components)
- [Domain model (class diagram)](#domain-model)
- [Agent core (class diagram)](#agent-core)
- [Sequence: a chat message with an approval](#sequence-chat-message-with-approval)
- [States](#states)
- [Interactive diagrams](#interactive-diagrams)

## Use cases

Three roles come from Keycloak (realm `deskpilot`): **user**, **reviewer** (also a user) and **admin** (also a user and
a reviewer). Each role adds use cases to the one before it.

```mermaid
flowchart LR
    U["👤 User"]
    R["👤 Reviewer"]
    A["👤 Admin"]

    subgraph DeskPilot
        direction TB
        UC1(["Chat with the agent"])
        UC2(["Attach a screenshot"])
        UC3(["Browse, rename, delete conversations"])
        UC4(["Rate an answer 👍 / 👎"])
        UC5(["See own tickets and memories"])
        UC6(["Export or delete my data"])
        UC7(["Approve or reject a paused action"])
        UC8(["See all tickets"])
        UC9(["Review negative feedback"])
        UC10(["Detect knowledge gaps, draft and publish articles"])
        UC11(["Change runtime settings and model"])
        UC12(["Configure cloud providers and keys"])
        UC13(["Upload knowledge-base documents"])
        UC14(["Run and compare evaluations"])
        UC15(["Prompt versions and A/B experiments"])
        UC16(["Read and export the audit trail"])
        UC17(["Watch dashboards and alerts"])
    end

    KC[["Keycloak (OIDC)"]]
    LLM[["Ollama / Mistral / Gemini"]]
    MAIL[["Mailpit / Slack-Teams webhook"]]
    MCP[["MCP clients and servers"]]
    GRAF[["Grafana"]]

    U --> UC1 & UC2 & UC3 & UC4 & UC5 & UC6
    R --> UC7 & UC8 & UC9 & UC10
    A --> UC11 & UC12 & UC13 & UC14 & UC15 & UC16 & UC17
    R -. "is a" .-> U
    A -. "is a" .-> R

    UC1 --- LLM
    UC2 --- LLM
    UC10 --- LLM
    UC14 --- LLM
    UC7 --- MAIL
    UC1 --- MCP
    UC17 --- GRAF
    DeskPilot --- KC
```

Notes:
- **Sign-in**: every `/api` call carries a Keycloak access token (Authorization Code + PKCE in the browser).
- **Approvals**: tools marked `@RequiresApproval` (e.g. `create_ticket`) and every external MCP tool pause the run
  until a reviewer decides. Reviewers get an e-mail; approvals that wait longer than the SLA are escalated to admins.
- **MCP**: other agents and IDEs read the knowledge base over MCP (`/mcp`). The agent can also call tools of external
  MCP servers, always behind the approval gate.

## Components

```mermaid
flowchart LR
    subgraph Browser["Angular 22 SPA (zoneless, signals)"]
        Pages["Pages: chat, approvals, runs, dashboard, tickets,<br/>knowledge, memories, settings, evaluation,<br/>experiments, audit"]
        Core["core: AuthService (OIDC PKCE), AgentApi,<br/>SettingsStore, ModelOptions, LiveEvents (SSE), I18n"]
    end

    subgraph Backend["Spring Boot 4 backend"]
        Controllers["REST controllers<br/>Session, Run, Settings, Provider, Evaluation,<br/>Experiment, Prompt, KnowledgeGap, Audit, Privacy…"]
        Security["SecurityConfig<br/>JWT resource server, roles"]
        Orchestrator["AgentOrchestrator<br/>ReAct loop"]
        Gateway["ModelGateway"]
        Resilient["ResilientChatModel<br/>circuit breaker"]
        Cloud["CloudProviderService<br/>+ SecretCipher"]
        Tools["ToolRegistry<br/>KB, tickets, memory, escalation, MCP"]
        Guard["GuardrailService<br/>+ ModerationService"]
        Services["Services: RunService, KnowledgeBaseService,<br/>DocumentIngestion, KnowledgeGapService,<br/>ExperimentService, Audit, Privacy, Metrics"]
        Notify["NotificationService"]
        Live["LiveEventHub (SSE)"]
        McpServer["MCP server tools"]
    end

    subgraph Infra["Infrastructure (backend/compose.yaml)"]
        PG[("PostgreSQL + pgvector")]
        KC["Keycloak"]
        Ollama["Ollama<br/>qwen3:8b, nomic-embed-text, gemma4:26b"]
        Jaeger["Jaeger"]
        Mailpit["Mailpit"]
        LGTM["Grafana otel-lgtm (profile grafana)"]
    end

    CloudAPI["Mistral / Gemini<br/>OpenAI-compatible API"]

    Pages --> Core
    Core -- "REST + SSE, Bearer token" --> Controllers
    Core -- "login" --> KC
    Controllers --> Security
    Security -- "JWKS" --> KC
    Controllers --> Orchestrator
    Controllers --> Services
    Orchestrator --> Guard
    Orchestrator --> Tools
    Orchestrator --> Gateway
    Gateway --> Resilient --> Ollama
    Gateway --> Cloud --> CloudAPI
    Tools --> Services
    Services --> PG
    Orchestrator --> Live
    Services --> Notify --> Mailpit
    McpServer --> Services
    Backend -- "OTLP traces" --> Jaeger
    Backend -. "OTLP metrics (optional)" .-> LGTM
```

The model behind every call is chosen at runtime: `ModelGateway` sends a call to the local Ollama `ChatModel`
(wrapped by `ResilientChatModel`) or to a cloud client built by `CloudProviderService`, depending on the provider in
`AgentConfig`. Settings are stored in the database (`agent_settings`) and apply from the next request.

## Domain model

JPA entities in `model/` (tables created by Flyway migrations V1–V20). Entities reference each other **by id** (for
example `AgentRun.sessionId`, `AgentStep.runId`); there are no JPA associations, so the links below are logical
references, not mapped relations.

```mermaid
classDiagram
    direction LR

    class AgentSession {
        String id
        String userId
        String title
        String summary
        Instant createdAt
        Instant lastActivityAt
        Instant busySince
    }
    class AgentRun {
        String id
        String sessionId
        String userId
        String userInput
        String model
        RunStatus status
        RunSource source
        String finalAnswer
        int iterations
        int toolCalls
        long promptTokens
        long completionTokens
        String experimentId
        String variant
        String specialist
        Instant createdAt
        Instant completedAt
        pauseForApproval()
        resume()
    }
    class AgentStep {
        Long id
        String runId
        StepType type
        String name
        String content
        boolean error
        long durationMillis
    }
    class PendingTurn {
        String runId
        String sessionId
        String state
    }
    class RunFeedback {
        Long id
        String runId
        String userId
        FeedbackRating rating
        String comment
    }
    class Ticket {
        Long id
        String title
        String description
        TicketPriority priority
        TicketStatus status
        String requestedBy
    }
    class MemoryFact {
        Long id
        String userId
        String content
    }
    class KnowledgeDocument {
        Long id
        String title
        String fileName
        DocumentFormat format
        DocumentStatus status
        int chunks
        String uploadedBy
    }
    class KnowledgeDocumentFile {
        Long documentId
    }
    class KnowledgeArticle {
        Long id
        String title
        String content
        String tags
        Long documentId
    }
    class KnowledgeGap {
        Long id
        String title
        KnowledgeGapStatus status
        String draftTitle
        String draftContent
        Long articleId
        String decidedBy
        draft()
        publish()
        dismiss()
    }
    class KnowledgeGapQuestion {
        Long id
        Long gapId
        String runId
        String question
        GapReason reason
    }
    class AgentSettings {
        Integer id
        String chatProvider
        String chatModel
        String visionProvider
        String visionModel
        double temperature
        int contextWindow
        int maxIterations
        boolean groundingCheck
        boolean selfCheck
        boolean specialists
        Set disabledTools
        Set approvalTools
        String systemPrompt
    }
    class CloudProvider {
        String id
        boolean enabled
        String apiKeyEncrypted
        String keyHint
        String updatedBy
    }
    class PromptVersion {
        Long id
        String name
        String content
        String createdBy
    }
    class Experiment {
        String id
        String name
        ExperimentStatus status
        String aProvider
        String aModel
        Long aPromptId
        String bProvider
        String bModel
        Long bPromptId
        int bPercent
    }
    class Evaluation {
        String id
        String model
        EvaluationStatus status
        int scenarioCount
        int completed
        int passed
        String startedBy
    }
    class EvaluationResult {
        Long id
        String evaluationId
        String scenarioId
        String category
        boolean passed
        String runId
        long latencyMillis
    }
    class AuditEvent {
        Long id
        Instant at
        String actor
        AuditAction action
        String targetType
        String targetId
        String details
    }

    AgentSession "1" --> "*" AgentRun : sessionId
    AgentRun "1" --> "*" AgentStep : runId
    AgentRun "1" --> "0..1" PendingTurn : paused turn
    AgentRun "1" --> "0..1" RunFeedback : rating
    AgentRun "*" --> "0..1" Experiment : experimentId, variant
    Experiment "*" --> "0..2" PromptVersion : aPromptId, bPromptId
    KnowledgeDocument "1" --> "1" KnowledgeDocumentFile : original file
    KnowledgeDocument "1" --> "*" KnowledgeArticle : chunks
    KnowledgeGap "1" --> "*" KnowledgeGapQuestion : gapId
    KnowledgeGapQuestion "*" --> "1" AgentRun : runId
    KnowledgeGap "0..1" --> "0..1" KnowledgeArticle : articleId when published
    Evaluation "1" --> "*" EvaluationResult : evaluationId
    EvaluationResult "1" --> "0..1" AgentRun : runId
```

```mermaid
classDiagram
    direction LR
    class RunStatus {
        <<enumeration>>
        RUNNING
        AWAITING_APPROVAL
        COMPLETED
        ESCALATED
        FAILED
    }
    class RunSource {
        <<enumeration>>
        CHAT
        EVALUATION
    }
    class StepType {
        <<enumeration>>
        USER_INPUT
        THOUGHT
        TOOL_CALL
        TOOL_RESULT
        APPROVAL_REQUESTED
        APPROVAL_DECISION
        ANSWER
        GUARDRAIL
        ERROR
        IMAGE_ANALYSIS
        HANDOFF
    }
    class KnowledgeGapStatus {
        <<enumeration>>
        OPEN
        DRAFTED
        PUBLISHED
        DISMISSED
    }
    class GapReason {
        <<enumeration>>
        ESCALATED
        TICKET
        NEGATIVE_FEEDBACK
        NOT_FOUND
    }
    class TicketStatus {
        <<enumeration>>
        OPEN
        IN_PROGRESS
        RESOLVED
        CLOSED
    }
    class TicketPriority {
        <<enumeration>>
        LOW
        MEDIUM
        HIGH
        CRITICAL
    }
    class DocumentStatus {
        <<enumeration>>
        PROCESSING
        READY
        FAILED
    }
    class DocumentFormat {
        <<enumeration>>
        PDF
        MARKDOWN
        TEXT
        HTML
    }
    class FeedbackRating {
        <<enumeration>>
        UP
        DOWN
    }
    class ExperimentStatus {
        <<enumeration>>
        RUNNING
        STOPPED
    }
    class EvaluationStatus {
        <<enumeration>>
        RUNNING
        COMPLETED
        CANCELLED
        FAILED
    }
```

`AuditAction` (26 values) groups: approvals (`APPROVAL_APPROVED`, `APPROVAL_REJECTED`), settings (`SETTINGS_CHANGED`,
`SETTINGS_RESET`), documents (`DOCUMENT_UPLOADED`, `DOCUMENT_REINDEXED`, `DOCUMENT_DELETED`), evaluations
(`EVALUATION_STARTED`, `EVALUATION_CANCELLED`), privacy (`CONVERSATION_DELETED`, `MEMORY_DELETED`, `MEMORIES_DELETED`,
`USER_DATA_EXPORTED`, `USER_DATA_DELETED`, `RETENTION_CLEANUP`), cloud providers (`PROVIDER_UPDATED`,
`PROVIDER_KEY_SET`, `PROVIDER_KEY_REMOVED`), experiments (`PROMPT_VERSION_SAVED`, `PROMPT_VERSION_DELETED`,
`EXPERIMENT_STARTED`, `EXPERIMENT_STOPPED`) and knowledge gaps (`KNOWLEDGE_GAPS_DETECTED`, `KNOWLEDGE_GAP_DRAFTED`,
`KNOWLEDGE_GAP_PUBLISHED`, `KNOWLEDGE_GAP_DISMISSED`).

## Agent core

The services that run one agent turn, with their constructor dependencies (`agent/`, `agent/tool/`,
`agent/specialist/`, `cloud/`, `guardrail/`, `service/`).

```mermaid
classDiagram
    direction TB
    class AgentOrchestrator {
        chat(sessionId, message, image, listener) AgentRun
        evaluate(sessionId, message, config) AgentRun
        decide(runId, decision) AgentRun
        abandon(runId, reason) AgentRun
    }
    class ModelGateway {
        call(config, messages, tools)
    }
    class ResilientChatModel {
        CircuitBreaker breaker
    }
    class CloudProviderService {
        chatModel(id)
        models(id)
        test(id)
    }
    class SecretCipher {
        AES-256-GCM
    }
    class ChatOptionsFactory
    class AgentConfig {
        <<record>>
        chatProvider, chatModel
        visionProvider, visionModel
        systemPrompt, specialists
        withChat()
        forVision()
    }
    class AgentSettingsService {
        current() AgentConfig
    }
    class ExperimentService {
        assign(sessionId, settings)
    }
    class SpecialistRouter {
        route()
    }
    class SpecialistCatalog
    class SelfCheck
    class VisionReader
    class ConversationSummarizer
    class GuardrailService {
        checkUserInput()
    }
    class ModerationService
    class ToolRegistry
    class AgentTools {
        <<interface>>
    }
    class ExternalTools {
        <<interface>>
    }
    class KnowledgeBaseTools {
        search_knowledge_base
    }
    class TicketTools {
        get_ticket
        create_ticket
    }
    class MemoryTools {
        save_memory
        recall_memories
    }
    class EscalationTools {
        escalate_to_human
    }
    class McpClientTools
    class RunService
    class SessionService
    class TurnStore
    class AgentMetrics

    AgentOrchestrator --> ToolRegistry
    AgentOrchestrator --> RunService
    AgentOrchestrator --> SessionService
    AgentOrchestrator --> TurnStore
    AgentOrchestrator --> AgentSettingsService
    AgentOrchestrator --> ChatOptionsFactory
    AgentOrchestrator --> GuardrailService
    AgentOrchestrator --> AgentMetrics
    AgentOrchestrator --> SelfCheck
    AgentOrchestrator --> VisionReader
    AgentOrchestrator --> ConversationSummarizer
    AgentOrchestrator --> ModelGateway
    AgentOrchestrator --> ExperimentService
    AgentOrchestrator --> SpecialistRouter
    AgentOrchestrator ..> AgentConfig : uses
    ModelGateway --> ResilientChatModel : Ollama
    ModelGateway --> CloudProviderService : Mistral, Gemini
    ModelGateway --> ChatOptionsFactory
    CloudProviderService --> SecretCipher
    SelfCheck --> ModelGateway
    VisionReader --> ModelGateway
    ConversationSummarizer --> ModelGateway
    SpecialistRouter --> SpecialistCatalog
    SpecialistRouter --> ModelGateway
    GuardrailService --> ModerationService
    AgentSettingsService --> ToolRegistry
    AgentSettingsService --> CloudProviderService
    ExperimentService --> AgentSettingsService
    ToolRegistry --> AgentTools
    ToolRegistry --> ExternalTools
    AgentTools <|.. KnowledgeBaseTools
    AgentTools <|.. TicketTools
    AgentTools <|.. MemoryTools
    AgentTools <|.. EscalationTools
    ExternalTools <|.. McpClientTools
```

## Sequence: chat message with approval

A user asks for something that needs a ticket. The run pauses at `create_ticket` (a `@RequiresApproval` tool) until a
reviewer approves it, then continues to the final answer. Progress is streamed to the browser as Server-Sent Events.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as Angular chat
    participant API as SessionController
    participant Orch as AgentOrchestrator
    participant Guard as GuardrailService
    participant Router as SpecialistRouter
    participant GW as ModelGateway
    participant LLM as Ollama or cloud model
    participant Tools as ToolRegistry
    participant DB as PostgreSQL
    actor Reviewer

    User->>UI: types a message
    UI->>API: POST /api/sessions/{id}/messages/stream
    API->>Orch: chat(sessionId, message, image, listener)
    Orch->>Guard: checkUserInput(message)
    Note over Guard: blocked input → 422 Problem Detail, no run
    Orch->>DB: start run (RUNNING), USER_INPUT step
    Orch->>Router: route(message) if specialists on
    Router-->>Orch: specialist, HANDOFF step
    loop ReAct loop, up to maxIterations
        Orch->>GW: call(config, messages, tools)
        GW->>LLM: chat completion
        LLM-->>GW: reply with tool calls
        GW-->>Orch: AssistantMessage
        Orch->>Tools: search_knowledge_base(query)
        Tools->>DB: hybrid search, pgvector + full text
        Tools-->>Orch: articles, TOOL_RESULT step
        Orch->>GW: next call
        GW-->>Orch: tool call create_ticket
    end
    Orch->>DB: save PendingTurn, APPROVAL_REQUESTED, status AWAITING_APPROVAL
    Orch-->>UI: SSE done, run waiting for approval
    Note over Reviewer: e-mail via NotificationService, badge via /api/events
    Reviewer->>API: POST /api/runs/{id}/approval/stream approved
    API->>Orch: decide(runId, decision)
    Orch->>DB: resume, status RUNNING, APPROVAL_DECISION step
    Orch->>Tools: create_ticket(...)
    Tools->>DB: insert Ticket
    Orch->>GW: call with the tool result
    GW-->>Orch: final answer
    Orch->>DB: ANSWER step, status COMPLETED
    Orch-->>UI: SSE run update to the owner
    UI-->>User: answer with citations and ticket number
```

## States

### Agent run (`RunStatus`)

From `AgentOrchestrator`, `AgentRun` and `InterruptedRunRecovery`.

```mermaid
stateDiagram-v2
    [*] --> RUNNING : message accepted
    RUNNING --> AWAITING_APPROVAL : tool call needs approval
    AWAITING_APPROVAL --> RUNNING : reviewer approves or rejects
    RUNNING --> COMPLETED : answer, no escalation
    RUNNING --> ESCALATED : escalate_to_human, or step limit reached
    RUNNING --> FAILED : empty answer or error
    AWAITING_APPROVAL --> ESCALATED : abandoned by an evaluation
    AWAITING_APPROVAL --> FAILED : restart without a saved turn
    RUNNING --> FAILED : interrupted by a restart
    COMPLETED --> [*]
    ESCALATED --> [*]
    FAILED --> [*]
```

- A message blocked by the input guardrail never creates a run (HTTP 422).
- Runs waiting for approval survive a restart: the paused turn is stored in `pending_turn`. Runs still `RUNNING`
  when the backend stopped are marked `FAILED` at startup.

### Knowledge gap (`KnowledgeGapStatus`)

From `KnowledgeGap` and `KnowledgeGapService`.

```mermaid
stateDiagram-v2
    [*] --> OPEN : detection groups 2+ similar questions
    OPEN --> OPEN : new similar questions join
    OPEN --> DRAFTED : model draft or reviewer writes one
    DRAFTED --> DRAFTED : reviewer edits the draft
    DRAFTED --> PUBLISHED : publish, becomes a KnowledgeArticle
    OPEN --> DISMISSED : dismiss
    DRAFTED --> DISMISSED : dismiss
    PUBLISHED --> [*]
    DISMISSED --> [*]
```

Publishing needs a draft (otherwise 409); acting on a published or dismissed gap returns 409.

## Interactive diagrams

Interactive versions (zoom, focus, dark/light theme, export to PNG/SVG) are standalone HTML pages. Open them locally
in a browser; GitHub shows HTML files as source.

| Diagram | File |
|---|---|
| Components / architecture | [diagrams/architecture-deskpilot-20261020-233020/deskpilot-architecture.html](diagrams/architecture-deskpilot-20261020-233020/deskpilot-architecture.html) |
| Sequence: chat message with approval | [diagrams/sequence-chat-approval-20261020-233020/chat-approval.html](diagrams/sequence-chat-approval-20261020-233020/chat-approval.html) |
| States of an agent run | [diagrams/lifecycle-agent-run-20261020-233020/agent-run.html](diagrams/lifecycle-agent-run-20261020-233020/agent-run.html) |
| Workflow of the agent loop | [diagrams/workflow-agent-loop-20261020-233020/agent-loop.html](diagrams/workflow-agent-loop-20261020-233020/agent-loop.html) |
