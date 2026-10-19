/**
 * A small Markdown parser for the subset the model writes: paragraphs, headings, numbered and bulleted lists,
 * **bold**, *italic*, `code`, [links](https://…) and [n] citation markers. It returns plain data that the
 * template renders with interpolation, so no model text ever becomes HTML (no innerHTML, nothing to sanitize).
 */
export type Inline =
  | { kind: 'text'; text: string }
  | { kind: 'strong'; text: string }
  | { kind: 'em'; text: string }
  | { kind: 'code'; text: string }
  | { kind: 'link'; text: string; href: string }
  | { kind: 'cite'; id: number };

export type Block =
  | { kind: 'heading'; inlines: Inline[] }
  | { kind: 'paragraph'; lines: Inline[][] }
  | { kind: 'list'; ordered: boolean; start: number; items: Inline[][] };

const ORDERED_ITEM = /^\s*(\d{1,4})[.)]\s+(.*)$/;
const BULLET_ITEM = /^\s*[-*+•]\s+(.*)$/;
const HEADING = /^\s*#{1,6}\s+(.*)$/;
const SAFE_URL = /^https?:\/\/[^\s<>"]+$/i;

// Alternatives, in order: **bold**, `code`, [text](url), [n], *italic*, _italic_.
const INLINE = /\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)|\[(\d{1,6})\]|(?<![\w*])\*(?!\s)([^*\n]+?)\*(?![\w*])|(?<![\w_])_(?!\s)([^_\n]+?)_(?![\w_])/g;

export function parseMarkdown(text: string | null | undefined): Block[] {
  const blocks: Block[] = [];
  let paragraph: Inline[][] | null = null;
  let list: Extract<Block, { kind: 'list' }> | null = null;

  const close = () => {
    paragraph = null;
    list = null;
  };

  for (const raw of (text ?? '').replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trimEnd();
    if (!line.trim()) {
      close();
      continue;
    }
    const heading = HEADING.exec(line);
    const ordered = ORDERED_ITEM.exec(line);
    const bullet = ordered ? null : BULLET_ITEM.exec(line);
    if (heading) {
      close();
      blocks.push({ kind: 'heading', inlines: parseInline(heading[1]) });
    } else if (ordered || bullet) {
      const isOrdered = ordered !== null;
      const content = ordered ? ordered[2] : bullet![1];
      const current = list as Extract<Block, { kind: 'list' }> | null;
      if (!current || current.ordered !== isOrdered) {
        paragraph = null;
        list = { kind: 'list', ordered: isOrdered, start: ordered ? Number(ordered[1]) : 1, items: [] };
        blocks.push(list);
      }
      list!.items.push(parseInline(content));
    } else if (list && /^\s+/.test(line)) {
      // Indented continuation of the previous list item.
      const items = (list as Extract<Block, { kind: 'list' }>).items;
      items[items.length - 1] = [...items[items.length - 1], { kind: 'text', text: ' ' }, ...parseInline(line.trim())];
    } else {
      list = null;
      if (!paragraph) {
        paragraph = [];
        blocks.push({ kind: 'paragraph', lines: paragraph });
      }
      (paragraph as Inline[][]).push(parseInline(line.trim()));
    }
  }
  return blocks;
}

export function parseInline(text: string): Inline[] {
  const inlines: Inline[] = [];
  let last = 0;
  for (const match of text.matchAll(INLINE)) {
    const index = match.index ?? 0;
    if (index > last) {
      inlines.push({ kind: 'text', text: text.slice(last, index) });
    }
    const [whole, strong, code, linkText, href, cite, em, em2] = match;
    if (strong !== undefined) {
      inlines.push({ kind: 'strong', text: strong });
    } else if (code !== undefined) {
      inlines.push({ kind: 'code', text: code });
    } else if (linkText !== undefined) {
      // Only plain web links; anything else (javascript:, data:, relative) stays visible as text.
      inlines.push(SAFE_URL.test(href) ? { kind: 'link', text: linkText, href } : { kind: 'text', text: whole });
    } else if (cite !== undefined) {
      inlines.push({ kind: 'cite', id: Number(cite) });
    } else {
      inlines.push({ kind: 'em', text: em ?? em2 });
    }
    last = index + whole.length;
  }
  if (last < text.length) {
    inlines.push({ kind: 'text', text: text.slice(last) });
  }
  return inlines;
}
