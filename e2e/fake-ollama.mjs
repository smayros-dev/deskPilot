// A scripted stand-in for Ollama, so the end-to-end tests run the real backend without models.
// It answers the endpoints DeskPilot uses (/api/tags, /api/show, /api/chat, /api/embed) and plays a fixed script:
//   - a technical question: search the knowledge base, then answer citing the first article found;
//   - a request mentioning a ticket: search, then call create_ticket (which pauses for approval), then confirm;
//   - anything else: a short generic reply.
// Usage: node fake-ollama.mjs [port]   (default 11500, or FAKE_OLLAMA_PORT)
import { createServer } from 'node:http';

const PORT = Number(process.argv[2] ?? process.env.FAKE_OLLAMA_PORT ?? 11500);
const DIMENSIONS = 768; // the pgvector column of the backend
const CHAT_MODEL = 'qwen3:8b';
const EMBEDDING_MODEL = 'nomic-embed-text:latest';

const MODELS = [
  { name: CHAT_MODEL, family: 'qwen3', parameterSize: '8.2B', capabilities: ['completion', 'tools'] },
  { name: EMBEDDING_MODEL, family: 'nomic-bert', parameterSize: '137M', capabilities: ['embedding'] },
];

let toolCallCounter = 0;

function tags() {
  return {
    models: MODELS.map((m) => ({
      name: m.name,
      model: m.name,
      modified_at: '2026-01-01T00:00:00Z',
      size: 1_000_000,
      digest: `fake-${m.name}`,
      details: { format: 'gguf', family: m.family, families: [m.family], parameter_size: m.parameterSize, quantization_level: 'Q4_K_M' },
    })),
  };
}

function show(name) {
  const model = MODELS.find((m) => m.name === name || m.name === `${name}:latest`) ?? MODELS[0];
  return {
    modelfile: '',
    parameters: '',
    template: '',
    details: { format: 'gguf', family: model.family, families: [model.family], parameter_size: model.parameterSize, quantization_level: 'Q4_K_M' },
    capabilities: model.capabilities,
    modified_at: '2026-01-01T00:00:00Z',
  };
}

/** Same idea as the backend's test embeddings: words map to buckets, so texts sharing words are similar. */
function embed(text) {
  const vector = new Array(DIMENSIONS).fill(0);
  vector[0] = 0.01; // never a zero vector (cosine distance is undefined for it)
  for (const word of String(text).toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (word.length >= 3) {
      let hash = 0;
      for (const ch of word) hash = (hash * 31 + ch.codePointAt(0)) | 0;
      vector[1 + (Math.abs(hash) % (DIMENSIONS - 1))] += 1;
    }
  }
  return vector;
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const isTicketRequest = (text) => /\bticket\b/i.test(text);
const isTechnical = (text) =>
  /vpn|password|printer|outlook|e-?mail|wi-?fi|laptop|screen|mfa|software|install|mouse|keyboard/i.test(text);

function toolCall(name, args) {
  toolCallCounter += 1;
  return { id: `call_${toolCallCounter}`, function: { index: 0, name, arguments: args } };
}

/** Decides the next assistant message from the conversation, like a (very predictable) model. */
function reply(messages) {
  // Reminders of the grounding check are user messages too; the question is the last one the user wrote.
  const lastUserIndex = messages.findLastIndex((m) => m.role === 'user' && !String(m.content).startsWith('(Automatic check'));
  const question = lastUserIndex >= 0 ? String(messages[lastUserIndex].content) : '';
  const after = messages.slice(lastUserIndex + 1);
  const lastTool = after.findLast((m) => m.role === 'tool');

  if (!lastTool) {
    if (isTechnical(question) || isTicketRequest(question)) {
      return { content: '', tool_calls: [toolCall('search_knowledge_base', { query: question })] };
    }
    return { content: 'I am DeskPilot, the IT helpdesk assistant. I can help with VPN, passwords, printers, e-mail and hardware.' };
  }

  const result = parseJson(lastTool.content);
  // Spring AI does not send tool_name with tool results: the name comes from the call that preceded them.
  const toolName = lastTool.tool_name ?? after.findLast((m) => m.role === 'assistant' && m.tool_calls?.length)?.tool_calls[0].function.name;
  if (toolName === 'search_knowledge_base') {
    if (isTicketRequest(question)) {
      return {
        content: '',
        tool_calls: [
          toolCall('create_ticket', {
            title: 'Cracked laptop screen',
            description: `${question}\nThe knowledge base says broken hardware needs a ticket.`,
            priority: 'HIGH',
          }),
        ],
      };
    }
    const article = result?.articles?.[0];
    if (!article) {
      return { content: 'I could not find this in the knowledge base. Shall I open a ticket?' };
    }
    return { content: `Follow the steps of the article "${article.title}" ${article.cite}.` };
  }
  if (toolName === 'create_ticket') {
    if (result?.id) {
      return { content: `Your ticket #${result.id} has been created. The IT team will contact you.` };
    }
    return { content: 'The ticket was not created.' };
  }
  return { content: 'Done.' };
}

function chatResponse(model, message) {
  return {
    model,
    created_at: new Date().toISOString(),
    message: { role: 'assistant', ...message },
    done_reason: 'stop',
    done: true,
    total_duration: 1_000_000,
    load_duration: 1_000,
    prompt_eval_count: 50,
    prompt_eval_duration: 1_000,
    eval_count: 20,
    eval_duration: 1_000,
  };
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = createServer((req, res) => {
  let raw = '';
  req.on('data', (chunk) => (raw += chunk));
  req.on('end', () => {
    const body = parseJson(raw) ?? {};
    const path = req.url.split('?')[0];
    if (req.method === 'GET' && (path === '/' || path === '/api/version')) {
      return path === '/' ? (res.writeHead(200), res.end('Ollama is running')) : send(res, 200, { version: '0.0.0-fake' });
    }
    if (path === '/api/tags') return send(res, 200, tags());
    if (path === '/api/show') return send(res, 200, show(body.model ?? body.name));
    if (path === '/api/embed') {
      const inputs = Array.isArray(body.input) ? body.input : [body.input];
      return send(res, 200, { model: body.model, embeddings: inputs.map(embed), total_duration: 1, load_duration: 1, prompt_eval_count: 1 });
    }
    if (path === '/api/chat') {
      const message = reply(body.messages ?? []);
      if (process.env.FAKE_OLLAMA_LOG) console.log(JSON.stringify({ last: body.messages?.at(-1), reply: message }));
      return send(res, 200, chatResponse(body.model ?? CHAT_MODEL, message));
    }
    send(res, 404, { error: `fake ollama: no route for ${req.method} ${path}` });
  });
});

server.listen(PORT, () => console.log(`fake ollama listening on http://localhost:${PORT}`));
