import { HttpEvent } from '@angular/common/http';
import { Observable, OperatorFunction, concatMap, from } from 'rxjs';

import { ProblemDetail, Run, RunStep } from './models';
import { SseMessage, parseSse, toSseMessages } from './sse';

/** Events of a streamed run, mirroring the backend's RunStream. */
export type RunEvent =
  | { type: 'run'; run: Run }
  | { type: 'thinking'; iteration: number }
  | { type: 'step'; step: RunStep }
  | { type: 'done'; run: Run }
  | { type: 'error'; problem: ProblemDetail };

/** Turns a streamed run response (see {@link toSseMessages}) into run events as they arrive. */
export function toRunEvents(): OperatorFunction<HttpEvent<string>, RunEvent> {
  return (source: Observable<HttpEvent<string>>) =>
    source.pipe(
      toSseMessages(),
      concatMap((message) => from(toRunEvent(message))),
    );
}

export function parseEvents(text: string): RunEvent[] {
  return parseSse(text).flatMap(toRunEvent);
}

function toRunEvent(message: SseMessage): RunEvent[] {
  const payload = JSON.parse(message.data);
  switch (message.event) {
    case 'run':
    case 'done':
      return [{ type: message.event, run: payload as Run }];
    case 'thinking':
      return [{ type: 'thinking', iteration: (payload as { iteration: number }).iteration }];
    case 'step':
      return [{ type: 'step', step: payload as RunStep }];
    case 'error':
      return [{ type: 'error', problem: payload as ProblemDetail }];
    default:
      return [];
  }
}
