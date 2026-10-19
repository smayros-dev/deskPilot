import { Run } from './models';

/** A knowledge-base article the answer cites, numbered in order of first citation. */
export interface Citation {
  number: number;
  articleId: number;
  title: string;
}

const CITE_MARKER = /\[(\d{1,6})\]/g;

interface SearchResult {
  articles?: { id?: number; title?: string }[];
}

/** Articles returned by the run's knowledge-base searches, by id (the cite marker [4] refers to article 4). */
export function searchedArticles(run: Run | null | undefined): Map<number, string> {
  const articles = new Map<number, string>();
  for (const step of run?.steps ?? []) {
    if (step.type !== 'TOOL_RESULT' || step.name !== 'search_knowledge_base' || step.error || !step.content) {
      continue;
    }
    try {
      const result = JSON.parse(step.content) as SearchResult;
      for (const article of result.articles ?? []) {
        if (typeof article.id === 'number' && article.title) {
          articles.set(article.id, article.title);
        }
      }
    } catch {
      // Truncated or non-JSON tool output: nothing to cite from it.
    }
  }
  return articles;
}

/**
 * The articles an answer cites with [n] markers, in order of first use. Markers that do not match an article the
 * run actually retrieved are ignored, so the model cannot cite something it did not read.
 */
export function citationsOf(answer: string | null | undefined, run: Run | null | undefined): Citation[] {
  if (!answer) {
    return [];
  }
  const articles = searchedArticles(run);
  const citations: Citation[] = [];
  for (const match of answer.matchAll(CITE_MARKER)) {
    const articleId = Number(match[1]);
    const title = articles.get(articleId);
    if (title && !citations.some((c) => c.articleId === articleId)) {
      citations.push({ number: citations.length + 1, articleId, title });
    }
  }
  return citations;
}
