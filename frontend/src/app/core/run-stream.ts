import { HttpEvent, HttpEventType } from '@angular/common/http';
import { Observable, OperatorFunction, concatMap, from } from 'rxjs';

import { ProblemDetail, Run, RunStep } from './models';

/** Events of a streamed run, mirroring the backend's RunStream. */
export type RunEvent =
  | { type: 'run'; run: Run }
  | { type: 'thinking'; iteration: number }
  | { type: 'step'; step: RunStep }
  | { type: 'done'; run: Run }
  | { type: 'error'; problem: ProblemDetail };

/**
 * Turns the HTTP events of a `text/event-stream` response (requested with `observe: 'events'`,
 * `reportProgress: true`, `responseType: 'text'`) into run events as they arrive. The fetch backend reports the
 * text received so far with each progress event; only complete Server-Sent Events (ending in a blank line) are parsed.
 */
export function toRunEvents(): OperatorFunction<HttpEvent<string>, RunEvent> {
  return (source: Observable<HttpEvent<string>>) => {
    let consumed = 0;
    return source.pipe(
      concatMap((event) => {
        let text: string | undefined;
        if (event.type === HttpEventType.DownloadProgress) {
          text = event.partialText;
        } else if (event.type === HttpEventType.Response) {
          text = `${event.body ?? ''}\n\n`;
        }
        if (text === undefined) {
          return from([]);
        }
        const end = text.lastIndexOf('\n\n');
        if (end < consumed) {
          return from([]);
        }
        const complete = text.slice(consumed, end);
        consumed = end + 2;
        return from(parseEvents(complete));
      }),
    );
  };
}

export function parseEvents(text: string): RunEvent[] {
  const events: RunEvent[] = [];
  for (const block of text.split(/\r?\n\r?\n/)) {
    let name = 'message';
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        name = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (data.length === 0) {
      continue;
    }
    const payload = JSON.parse(data.join('\n'));
    switch (name) {
      case 'run':
      case 'done':
        events.push({ type: name, run: payload as Run });
        break;
      case 'thinking':
        events.push({ type: 'thinking', iteration: (payload as { iteration: number }).iteration });
        break;
      case 'step':
        events.push({ type: 'step', step: payload as RunStep });
        break;
      case 'error':
        events.push({ type: 'error', problem: payload as ProblemDetail });
        break;
    }
  }
  return events;
}
