import { HttpEvent, HttpEventType } from '@angular/common/http';
import { Observable, OperatorFunction, concatMap, from } from 'rxjs';

/** One Server-Sent Event: its name ({@code message} when unnamed) and its raw data. Comments are dropped. */
export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Turns the HTTP events of a `text/event-stream` response (requested with `observe: 'events'`,
 * `reportProgress: true`, `responseType: 'text'`) into Server-Sent Events as they arrive. The fetch backend reports the
 * text received so far with each progress event; only complete events (ending in a blank line) are parsed.
 */
export function toSseMessages(): OperatorFunction<HttpEvent<string>, SseMessage> {
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
        return from(parseSse(complete));
      }),
    );
  };
}

export function parseSse(text: string): SseMessage[] {
  const messages: SseMessage[] = [];
  for (const block of text.split(/\r?\n\r?\n/)) {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (data.length > 0) {
      messages.push({ event, data: data.join('\n') });
    }
  }
  return messages;
}
