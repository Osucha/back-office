import { Pipe, PipeTransform } from '@angular/core';
import { fixed } from '../../i18n/numbers';

/** "850ms", "2.3s", "2m 5s", "2h 1m", "1d 1h" (Polish: "850 ms", "2,3 s", "2 min 5 s" ...). */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms) || ms < 0) return '-';
  if (ms < 1000) return $localize`:@@core.duration.ms:${Math.round(ms)}:n:ms`;
  const totalSeconds = ms / 1000;
  if (totalSeconds < 60)
    return $localize`:@@core.duration.s:${fixed(totalSeconds, totalSeconds < 10 ? 1 : 0)}:n:s`;
  const totalMinutes = Math.floor(totalSeconds / 60);
  const seconds = Math.round(totalSeconds - totalMinutes * 60);
  if (totalMinutes < 60)
    return seconds
      ? $localize`:@@core.duration.ms2:${totalMinutes}:m:m ${seconds}:s:s`
      : $localize`:@@core.duration.m:${totalMinutes}:m:m`;
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes - hours * 60;
  if (hours < 24)
    return minutes
      ? $localize`:@@core.duration.hm:${hours}:h:h ${minutes}:m:m`
      : $localize`:@@core.duration.h:${hours}:h:h`;
  const days = Math.floor(hours / 24);
  const remHours = hours - days * 24;
  return remHours
    ? $localize`:@@core.duration.dh:${days}:d:d ${remHours}:h:h`
    : $localize`:@@core.duration.d:${days}:d:d`;
}

/** Duration between two ISO timestamps; the end defaults to now (for running items). */
export function durationBetween(
  start: string | null | undefined,
  end?: string | null,
): number | null {
  if (!start) return null;
  const s = Date.parse(start);
  if (Number.isNaN(s)) return null;
  const e = end ? Date.parse(end) : Date.now();
  if (Number.isNaN(e)) return null;
  return Math.max(0, e - s);
}

@Pipe({ name: 'duration', pure: true })
export class DurationPipe implements PipeTransform {
  /**
   * `{{ 1234 | duration }}` -> "1.2s" (milliseconds)
   * `{{ startedAt | duration:finishedAt }}` -> elapsed between two timestamps
   * `{{ seconds | duration:'s' }}` -> value given in seconds
   */
  transform(value: number | string | null | undefined, endOrUnit?: string | null): string {
    if (typeof value === 'string') return formatDuration(durationBetween(value, endOrUnit));
    if (endOrUnit === 's' && typeof value === 'number') return formatDuration(value * 1000);
    return formatDuration(value);
  }
}
