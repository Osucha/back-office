import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import {
  LOCALE_NAMES,
  SUPPORTED_LOCALES,
  activeLocale,
  switchLocale,
  type AppLocale,
} from '../../../core/i18n/locale';
import { RelayIllustrationComponent } from './relay-illustration.component';
import { LogoComponent } from '../../../core/ui/relay/logo.component';

/**
 * Split layout of the public auth screens (sign-in, set password, forced password change, MFA
 * set-up): left the product mark, one line of copy and the relay illustration; right the form card.
 * Below 960 px the left side shrinks to a header strip. The card area reserves its height, so
 * switching steps does not shift the layout.
 */
@Component({
  selector: 'app-auth-shell',
  imports: [LogoComponent, RelayIllustrationComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="split">
      <aside class="brand-side">
        <div class="brand">
          <bo-logo class="mark" [size]="28" />
          <span class="brand-name">Back Office</span>
        </div>
        <div class="pitch">
          <p class="copy" i18n="@@auth.shell.pitch">
            Agents and CI carry the work. The baton comes back to you when a decision needs a
            person.
          </p>
          <app-relay-illustration class="art" />
        </div>
        <p class="foot">
          <span class="dot" data-holder="agent"></span>
          <span i18n="@@auth.shell.legend.agent">agent</span>
          <span class="dot" data-holder="you"></span>
          <span i18n="@@auth.shell.legend.you">you</span>
          <span class="dot" data-holder="ci"></span>
          <span i18n="@@auth.shell.legend.ci">ci</span>
          <span class="dot" data-holder="done"></span>
          <span i18n="@@auth.shell.legend.done">done</span>
        </p>
      </aside>
      <main class="form-side" [attr.aria-label]="label()">
        <nav class="lang" aria-label="Language / Język" i18n-aria-label="@@auth.shell.language">
          @for (l of locales; track l) {
            <button
              type="button"
              [attr.lang]="l"
              [attr.aria-pressed]="l === locale"
              [class.on]="l === locale"
              (click)="setLocale(l)"
            >
              {{ localeNames[l] }}
            </button>
          }
        </nav>
        <section class="card" [class.wide]="wide()">
          <ng-content />
        </section>
        <div class="below"><ng-content select="[authBelow]" /></div>
      </main>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
        min-height: 100vh;
        background: var(--bo-bg);
        color: var(--bo-ink);
      }
      .split {
        display: grid;
        grid-template-columns: minmax(380px, 46fr) 54fr;
        min-height: 100vh;
      }
      .brand-side {
        display: flex;
        flex-direction: column;
        justify-content: space-between;
        gap: var(--bo-space-6);
        padding: var(--bo-space-6) clamp(32px, 5vw, 72px);
        background: var(--bo-surface);
        border-right: 1px solid var(--bo-line);
        background-image: radial-gradient(
          circle at 1px 1px,
          color-mix(in srgb, var(--bo-line-strong) 55%, transparent) 1px,
          transparent 0
        );
        background-size: 20px 20px;
      }
      .brand {
        display: flex;
        align-items: center;
        gap: 10px;
      }
      .mark {
        width: 30px;
        height: 30px;
      }
      .brand-name {
        font-weight: 600;
        font-size: 16px;
        letter-spacing: -0.01em;
      }
      .pitch {
        display: flex;
        flex-direction: column;
        gap: var(--bo-space-6);
        max-width: 460px;
      }
      .copy {
        margin: 0;
        font-size: 24px;
        line-height: 1.3;
        letter-spacing: -0.015em;
        font-weight: 500;
        color: var(--bo-ink);
        max-width: 26ch;
        text-wrap: balance;
      }
      .art {
        padding: var(--bo-space-5);
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        box-sizing: border-box;
      }
      .foot {
        margin: 0;
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        margin-left: 8px;
      }
      .dot:first-child {
        margin-left: 0;
      }
      .dot[data-holder='agent'] {
        background: var(--bo-agent);
      }
      .dot[data-holder='you'] {
        background: var(--bo-you);
      }
      .dot[data-holder='ci'] {
        background: var(--bo-ci);
      }
      .dot[data-holder='done'] {
        background: var(--bo-done);
      }
      /* The card hangs from a fixed offset (not centred), so a taller step never moves it. */
      .form-side {
        position: relative;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: flex-start;
        padding: clamp(32px, 16vh, 168px) var(--bo-space-4) var(--bo-space-6);
      }
      .lang {
        position: absolute;
        top: var(--bo-space-4);
        right: var(--bo-space-5);
        display: flex;
        gap: 2px;
        font-size: var(--bo-fs-meta);
      }
      .lang button {
        all: unset;
        cursor: pointer;
        padding: 4px 8px;
        border-radius: 6px;
        color: var(--bo-ink-3);
      }
      .lang button:hover {
        color: var(--bo-ink);
      }
      .lang button.on {
        color: var(--bo-ink);
        font-weight: 600;
        background: var(--bo-surface-3);
      }
      .lang button:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .card {
        width: min(440px, 100%);
        box-sizing: border-box;
        display: flex;
        flex-direction: column;
        padding: var(--bo-space-6) var(--bo-space-6) var(--bo-space-5);
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .card.wide {
        width: min(540px, 100%);
      }
      .below {
        width: min(440px, 100%);
        margin-top: var(--bo-space-5);
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      @media (max-width: 960px) {
        .split {
          grid-template-columns: 1fr;
          grid-template-rows: auto 1fr;
        }
        .brand-side {
          padding: var(--bo-space-4);
          border-right: 0;
          border-bottom: 1px solid var(--bo-line);
          background-image: none;
        }
        .pitch,
        .foot {
          display: none;
        }
        .form-side {
          padding-top: var(--bo-space-5);
        }
        .card {
          padding: var(--bo-space-5) var(--bo-space-4);
        }
      }
    `,
  ],
})
export class AuthShellComponent {
  readonly wide = input(false);
  readonly label = input($localize`:@@auth.shell.label:Sign in`);

  /** Pre-sign-in language switch (each name in its own language; switching reloads the page). */
  readonly locales = SUPPORTED_LOCALES;
  readonly localeNames = LOCALE_NAMES;
  readonly locale = activeLocale();

  setLocale(locale: AppLocale): void {
    switchLocale(locale);
  }
}
