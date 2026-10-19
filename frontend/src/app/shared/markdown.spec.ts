import { parseInline, parseMarkdown } from './markdown';

describe('parseMarkdown', () => {
  it('reads paragraphs, numbered and bulleted lists', () => {
    const blocks = parseMarkdown('To add the printer:\n\n1. Open **Settings**.\n2. Click *Add device*.\n\n- tip one\n- tip two');

    expect(blocks.map((b) => b.kind)).toEqual(['paragraph', 'list', 'list']);
    expect(blocks[1]).toMatchObject({ kind: 'list', ordered: true, start: 1 });
    expect(blocks[1].kind === 'list' && blocks[1].items[0]).toEqual([
      { kind: 'text', text: 'Open ' },
      { kind: 'strong', text: 'Settings' },
      { kind: 'text', text: '.' },
    ]);
    expect(blocks[2]).toMatchObject({ kind: 'list', ordered: false });
  });

  it('keeps consecutive lines in one paragraph and appends indented lines to the list item', () => {
    const blocks = parseMarkdown('Line one\nLine two\n1. Item\n   continued');

    expect(blocks[0]).toMatchObject({ kind: 'paragraph', lines: [[{ text: 'Line one' }], [{ text: 'Line two' }]] });
    expect(blocks[1].kind === 'list' && blocks[1].items[0].map((i) => ('text' in i ? i.text : ''))).toEqual(['Item', ' ', 'continued']);
  });
});

describe('parseInline', () => {
  it('recognises code, italic, citation markers and https links', () => {
    expect(parseInline('Run `outlook.exe /safe` _now_ [4] see [portal](https://vpn.example.com)')).toEqual([
      { kind: 'text', text: 'Run ' },
      { kind: 'code', text: 'outlook.exe /safe' },
      { kind: 'text', text: ' ' },
      { kind: 'em', text: 'now' },
      { kind: 'text', text: ' ' },
      { kind: 'cite', id: 4 },
      { kind: 'text', text: ' see ' },
      { kind: 'link', text: 'portal', href: 'https://vpn.example.com' },
    ]);
  });

  it('keeps links with other schemes as plain text', () => {
    expect(parseInline('[click](javascript:alert(1))')).toEqual([{ kind: 'text', text: '[click](javascript:alert(1)' }, { kind: 'text', text: ')' }]);
    expect(parseInline('[file](file:///etc/passwd)')).toEqual([{ kind: 'text', text: '[file](file:///etc/passwd)' }]);
  });

  it('does not treat a path with asterisks or a lone star as italic', () => {
    expect(parseInline('Use 2*3 and C:\\*.ost')).toEqual([{ kind: 'text', text: 'Use 2*3 and C:\\*.ost' }]);
  });
});
