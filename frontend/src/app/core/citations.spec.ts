import { citationsOf, searchedArticles } from './citations';
import { Run, RunStep } from './models';

function searchStep(articles: { id: number; title: string }[], overrides: Partial<RunStep> = {}): RunStep {
  return {
    id: Math.floor(Math.random() * 1e6),
    type: 'TOOL_RESULT',
    name: 'search_knowledge_base',
    content: JSON.stringify({ articles: articles.map((a) => ({ ...a, cite: `[${a.id}]`, content: '…', relevance: 0.8 })), guidance: null }),
    error: false,
    durationMillis: 10,
    createdAt: '',
    ...overrides,
  };
}

function runWith(steps: RunStep[]): Run {
  return {
    id: 'r1', sessionId: 's1', userId: 'alice', model: 'qwen3:8b', status: 'COMPLETED', userInput: 'q', answer: null, steps,
    pendingApprovals: [], stats: { iterations: 1, toolCalls: 1, toolErrors: 0, promptTokens: 1, completionTokens: 1, activeMillis: 1 },
    createdAt: '', completedAt: null,
  };
}

describe('citations', () => {
  const run = runWith([
    searchStep([{ id: 4, title: 'Adding a network printer' }, { id: 1, title: 'Connecting to the corporate VPN' }]),
    searchStep([{ id: 7, title: 'Installing software' }]),
    searchStep([], { error: true, content: 'Search failed' }),
  ]);

  it('collects the articles of every knowledge-base search in the run', () => {
    expect([...searchedArticles(run).keys()]).toEqual([4, 1, 7]);
  });

  it('numbers cited articles in order of first use and ignores repeated markers', () => {
    expect(citationsOf('Open Settings [4]. Then reinstall [7] or retry [4].', run)).toEqual([
      { number: 1, articleId: 4, title: 'Adding a network printer' },
      { number: 2, articleId: 7, title: 'Installing software' },
    ]);
  });

  it('ignores markers for articles the run never retrieved', () => {
    expect(citationsOf('Trust me [99].', run)).toEqual([]);
    expect(citationsOf('No markers.', run)).toEqual([]);
    expect(citationsOf(null, run)).toEqual([]);
  });
});
