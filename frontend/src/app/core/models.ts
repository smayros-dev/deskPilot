// Mirrors the backend DTOs (pl.piomin.services.deskpilot.dto).

export type RunStatus = 'RUNNING' | 'AWAITING_APPROVAL' | 'COMPLETED' | 'ESCALATED' | 'FAILED';

export type StepType =
  | 'USER_INPUT'
  | 'THOUGHT'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECISION'
  | 'ANSWER'
  | 'GUARDRAIL'
  | 'ERROR'
  | 'IMAGE_ANALYSIS';

export interface Session {
  id: string;
  userId: string;
  createdAt: string;
}

export interface RunStep {
  id: number;
  type: StepType;
  name: string | null;
  content: string | null;
  error: boolean;
  durationMillis: number;
  createdAt: string;
}

export interface PendingApproval {
  toolCallId: string;
  toolName: string;
  arguments: string;
}

export interface RunStats {
  iterations: number;
  toolCalls: number;
  toolErrors: number;
  promptTokens: number;
  completionTokens: number;
  activeMillis: number;
}

export type FeedbackRating = 'UP' | 'DOWN';

/** The user's rating of an answer (PUT /api/runs/{id}/feedback). */
export interface RunFeedback {
  rating: FeedbackRating;
  comment: string | null;
  updatedAt: string;
}

/** A rated answer as listed for reviewers (GET /api/feedback). */
export interface RatedAnswer {
  runId: string;
  userId: string;
  model: string | null;
  question: string;
  answer: string | null;
  rating: FeedbackRating;
  comment: string | null;
  updatedAt: string;
}

export interface FeedbackStats {
  up: number;
  down: number;
  satisfactionRate: number;
  models: { model: string; up: number; down: number; satisfactionRate: number }[];
}

export interface Run {
  id: string;
  sessionId: string;
  userId: string;
  model: string | null;
  status: RunStatus;
  userInput: string;
  answer: string | null;
  steps: RunStep[];
  pendingApprovals: PendingApproval[];
  stats: RunStats;
  createdAt: string;
  completedAt: string | null;
  /** Null until the user rates the answer. */
  feedback?: RunFeedback | null;
}

export interface ApprovalDecision {
  approved: boolean;
  comment: string | null;
}

export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface Ticket {
  id: number;
  title: string;
  description: string;
  priority: TicketPriority;
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
  requestedBy: string;
  createdAt: string;
}

export interface Memory {
  id: number;
  content: string;
  createdAt: string;
}

export interface Article {
  id: number;
  title: string;
  tags: string;
  content: string;
  /** Set when the article is a chunk of an uploaded document. */
  documentId: number | null;
  documentTitle: string | null;
}

export type DocumentFormat = 'PDF' | 'MARKDOWN' | 'TEXT' | 'HTML';
export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED';

/** A document uploaded to the knowledge base and split into articles. */
export interface KnowledgeDocument {
  id: number;
  title: string;
  fileName: string;
  format: DocumentFormat;
  sizeBytes: number;
  status: DocumentStatus;
  chunks: number;
  error: string | null;
  uploadedBy: string;
  uploadedAt: string;
  indexedAt: string | null;
}

export interface ToolStats {
  tool: string;
  calls: number;
  errors: number;
  accuracy: number;
  avgMillis: number;
}

export interface ModelStats {
  model: string;
  runs: number;
  successRate: number;
  avgLatencyMillis: number;
  avgIterations: number;
  avgTokens: number;
}

export interface AgentMetrics {
  totalRuns: number;
  finishedRuns: number;
  awaitingApproval: number;
  completed: number;
  escalated: number;
  failed: number;
  taskSuccessRate: number;
  escalationRate: number;
  avgLatencyMillis: number;
  p95LatencyMillis: number;
  avgIterations: number;
  toolCalls: number;
  toolErrorRate: number;
  approvalsRequested: number;
  approvalsRejected: number;
  promptTokens: number;
  completionTokens: number;
  inputsBlockedSinceStart: number;
  tools: ToolStats[];
  models: ModelStats[];
  feedback: FeedbackStats;
}

/** Runtime agent configuration (GET/PUT /api/settings). */
export interface AgentSettings {
  chatModel: string;
  temperature: number;
  topP: number;
  contextWindow: number;
  maxOutputTokens: number;
  thinking: boolean;
  maxIterations: number;
  ragTopK: number;
  ragSimilarityThreshold: number;
  /** Remind the model to search the knowledge base when it answers without any tool. */
  groundingCheck: boolean;
  /** Combine keyword and semantic ranking in knowledge-base searches. */
  hybridSearch: boolean;
  /** Check answers and ticket requests against the retrieved articles. */
  selfCheck: boolean;
  /** Model that reads screenshots (vision capability); null when screenshots are off. */
  visionModel: string | null;
  disabledTools: string[];
  approvalTools: string[];
  systemPrompt: string | null;
  updatedAt?: string;
}

/** A model installed in Ollama (GET /api/models). */
export interface ModelInfo {
  name: string;
  sizeBytes: number;
  parameterSize: string | null;
  family: string | null;
  capabilities: string[];
  supportsTools: boolean;
  supportsThinking: boolean;
  supportsVision: boolean;
  chatModel: boolean;
}

export interface ToolInfo {
  name: string;
  description: string;
  approvalByDefault: boolean;
  /** Provided by an MCP server: always needs approval. */
  external: boolean;
}

export interface Health {
  status: 'UP' | 'DOWN' | 'UNKNOWN' | 'OUT_OF_SERVICE';
  components?: Record<string, { status: string; details?: Record<string, unknown> }>;
}

/** RFC 9457 Problem Details, as returned by the backend for every error. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  rule?: string;
}

/** A screenshot sent with a message: read by the vision model, not stored. */
export interface ImageAttachment {
  mimeType: string;
  /** Base64 of the image bytes. */
  data: string;
}
