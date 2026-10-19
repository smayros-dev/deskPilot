import { parseEvents } from './run-stream';

describe('parseEvents', () => {
  it('reads named events with JSON data, ignoring comments and unknown events', () => {
    const text = [
      ':keep-alive',
      '',
      'event:thinking',
      'data:{"iteration":2}',
      '',
      'event:other',
      'data:{}',
      '',
      'event: error',
      'data: {"status":502,',
      'data: "title":"Model unavailable"}',
    ].join('\n');

    expect(parseEvents(text)).toEqual([
      { type: 'thinking', iteration: 2 },
      { type: 'error', problem: { status: 502, title: 'Model unavailable' } },
    ]);
  });
});
