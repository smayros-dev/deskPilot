import { Pipe, PipeTransform } from '@angular/core';

import { DICTIONARIES, Lang } from '../core/i18n';

/**
 * Formats milliseconds as a short human duration: 850 → "850 ms", 4200 → "4.2 s", 95000 → "1 min 35 s".
 * Pass the UI language (`| duration: i18n.lang()`) for localized units and decimal separator.
 */
@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(millis: number | null | undefined, lang: Lang = 'en'): string {
    if (millis === null || millis === undefined || Number.isNaN(millis)) {
      return '–';
    }
    const units = DICTIONARIES[lang];
    if (millis < 1000) {
      return `${Math.round(millis)} ${units['unit.ms']}`;
    }
    const seconds = millis / 1000;
    if (seconds < 60) {
      const value = seconds.toFixed(seconds < 10 ? 1 : 0);
      return `${lang === 'fr' ? value.replace('.', ',') : value} ${units['unit.s']}`;
    }
    const minutes = Math.floor(seconds / 60);
    const rest = Math.round(seconds % 60);
    return rest === 0 ? `${minutes} ${units['unit.min']}` : `${minutes} ${units['unit.min']} ${rest} ${units['unit.s']}`;
  }
}
