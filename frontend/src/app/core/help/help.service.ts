import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { activeLocale } from '../i18n/locale';
import type { HelpTopic } from './help-map';
import { chapterFileUrl, manifestUrl, manualLink, type ManualManifest } from './manual';

/**
 * In-app help: which section the help panel shows (the panel itself is `@defer`red in the shell)
 * and the manual files, fetched on first use and cached for the session. Missing chapters resolve
 * to null ("This chapter is being written."), never to an error.
 *
 * Languages: the manual of the UI language (`/manual/pl/...`) is used where it exists; the
 * manifest or a chapter that is not translated yet falls back to the English file.
 */
@Injectable({ providedIn: 'root' })
export class HelpService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly locale = activeLocale();

  /** The section shown in the help panel, or null when it is closed. */
  readonly panel = signal<HelpTopic | null>(null);

  private manifest$: Promise<ManualManifest | null> | null = null;
  private readonly chapters = new Map<string, Promise<string | null>>();

  /** Opens the panel on a topic (`HELP_TOPICS[key]`; `bo-help-link` does the lookup). */
  open(topic: HelpTopic): void {
    this.panel.set(topic);
  }

  close(): void {
    this.panel.set(null);
  }

  /** Help for the page at `url` in the panel; the manual itself when the page has no topic. */
  openForUrl(url: string): void {
    // The route map loads with the first help request (it stays out of the initial bundle).
    void import('./help-map').then(({ HELP_TOPICS, helpTopicForUrl }) => {
      const key = helpTopicForUrl(url);
      if (key) this.open(HELP_TOPICS[key]);
      else if (!url.startsWith('/help')) void this.router.navigateByUrl('/help');
    });
  }

  /** Opens the full manual at a topic and closes the panel. */
  openInManual(topic: HelpTopic): void {
    this.close();
    void this.router.navigateByUrl(manualLink(topic));
  }

  /** The manifest of the UI language, else the English one. */
  manifest(): Promise<ManualManifest | null> {
    const load = (locale: string) =>
      firstValueFrom(this.http.get<ManualManifest>(manifestUrl(locale))).then(
        (m) => (m && Array.isArray(m.groups) ? m : null),
        () => null,
      );
    this.manifest$ ??= (
      this.locale === 'en' ? load('en') : load(this.locale).then((m) => m ?? load('en'))
    ).then((m) => {
      if (!m) this.manifest$ = null; // try again next time
      return m;
    });
    return this.manifest$;
  }

  /**
   * The Markdown of a chapter in the UI language (or in `locale`), falling back to English; null
   * when it is not there (yet).
   */
  chapter(slug: string, locale: string = this.locale): Promise<string | null> {
    if (locale === 'en') return this.file(slug, 'en');
    return this.file(slug, locale).then((text) => text ?? this.file(slug, 'en'));
  }

  private file(slug: string, locale: string): Promise<string | null> {
    const key = `${locale}/${slug}`;
    let p = this.chapters.get(key);
    if (!p) {
      p = firstValueFrom(
        this.http.get(chapterFileUrl(slug, locale), { responseType: 'text' }),
      ).then(
        (text) => (text && !/^\s*(<!doctype html|<html[\s>])/i.test(text) ? text : null),
        () => null,
      );
      // A missing chapter is asked for again next time (it may have been published meanwhile).
      void p.then((text) => text ?? this.chapters.delete(key));
      this.chapters.set(key, p);
    }
    return p;
  }
}
