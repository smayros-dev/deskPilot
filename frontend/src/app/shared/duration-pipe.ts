import { Pipe, PipeTransform } from '@angular/core';

/** Formats milliseconds as a short human duration: 850 → "850 ms", 4200 → "4.2 s", 95000 → "1 min 35 s". */
@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(millis: number | null | undefined): string {
    if (millis === null || millis === undefined || Number.isNaN(millis)) {
      return '–';
    }
    if (millis < 1000) {
      return `${Math.round(millis)} ms`;
    }
    const seconds = millis / 1000;
    if (seconds < 60) {
      return `${seconds.toFixed(seconds < 10 ? 1 : 0)} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const rest = Math.round(seconds % 60);
    return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
  }
}
