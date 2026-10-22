import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TimeAgoPipe } from '../../../core/ui/pipes/time-ago.pipe';
import { messageTone } from '../data/connection-model';
import type { ScmMessage, ScmVerification } from '../data/connection-types';
import {
  scmCapabilityLabel,
  scmMessageText,
  scmVerificationChip,
  sortScmCapabilities,
} from '../data/scm-connection-model';
import { CONNECTION_CHIP_STYLES } from './chip-styles';

/**
 * The result of testing a repository token (ADR 0051): status, the token as GitLab reports it (name,
 * role, scopes, expiry), granted and missing capabilities, write powers beyond the declared ones and
 * localized messages.
 */
@Component({
  selector: 'app-scm-verification-result',
  imports: [DatePipe, TimeAgoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (verification(); as v) {
      <div class="vr" aria-live="polite">
        <div class="head">
          <span class="cchip" [class]="chip().tone">{{ chip().label }}</span>
          @if (v.tokenName) {
            <span class="muted" i18n="@@connections.scm.verification.token"
              >token <span class="mono">{{ v.tokenName }}</span></span
            >
          }
          @if (v.role) {
            <span class="muted" i18n="@@connections.scm.verification.role">role {{ v.role }}</span>
          }
          @if (v.tokenExpiresAt) {
            <span class="muted" i18n="@@connections.scm.verification.expires"
              >expires {{ v.tokenExpiresAt | date: 'mediumDate' }}</span
            >
          }
          <span class="muted" [title]="v.checkedAt">{{ v.checkedAt | timeAgo }}</span>
        </div>
        <div class="row">
          <span class="label" i18n="@@connections.scm.verification.scopes">Scopes</span>
          @if (v.scopes) {
            <span class="mono">{{ v.scopes.join(', ') || '-' }}</span>
          } @else {
            <span class="muted" i18n="@@connections.scm.verification.noScopes"
              >unknown: the token cannot call the API</span
            >
          }
        </div>
        @if (v.granted.length || v.missing.length) {
          <div class="row">
            <span class="label" i18n="@@connections.scm.verification.capabilities"
              >Capabilities</span
            >
            <span class="cchips">
              @for (c of granted(); track c) {
                <span class="cchip ok">{{ label(c) }}</span>
              }
              @for (c of missing(); track c) {
                <span class="cchip bad" [title]="text.missingTitle"
                  >{{ label(c) }}
                  <span i18n="@@connections.scm.verification.missingChip">missing</span></span
                >
              }
            </span>
          </div>
        }
        @if (v.writePowers.length) {
          <div class="row">
            <span class="label" i18n="@@connections.scm.verification.writePowers">Can also</span>
            <span class="cchips">
              @for (p of v.writePowers; track p) {
                <span class="cchip warn mono" [title]="text.powerTitle">{{ p }}</span>
              }
            </span>
          </div>
        }
        @if (v.messages.length) {
          <ul class="messages">
            @for (m of v.messages; track $index) {
              <li [class]="tone(m)" [title]="m.text">{{ message(m, v) }}</li>
            }
          </ul>
        }
      </div>
    }
  `,
  styles: [
    CONNECTION_CHIP_STYLES,
    `
      .vr {
        display: flex;
        flex-direction: column;
        gap: 6px;
        font-size: 12.5px;
      }
      .head,
      .row {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
      }
      .label {
        min-width: 90px;
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .muted {
        color: var(--bo-ink-3);
        font-size: 12px;
      }
      .messages {
        margin: 0;
        padding: 0 0 0 16px;
        display: flex;
        flex-direction: column;
        gap: 2px;
        color: var(--bo-ink-2);
      }
      .messages li.bad {
        color: var(--bo-blocked-ink);
      }
      .messages li.warn {
        color: var(--bo-you-ink);
      }
    `,
  ],
})
export class ScmVerificationResultComponent {
  readonly verification = input<ScmVerification | null | undefined>(null);

  readonly chip = computed(() => scmVerificationChip(this.verification()));
  readonly granted = computed(() => sortScmCapabilities(this.verification()?.granted ?? []));
  readonly missing = computed(() => sortScmCapabilities(this.verification()?.missing ?? []));
  readonly label = scmCapabilityLabel;
  readonly text = {
    missingTitle: $localize`:@@connections.scm.verification.missingTitle:Declared, but the token does not grant it: steps that need it cannot use this token.`,
    powerTitle: $localize`:@@connections.scm.verification.powerTitle:The token can do this although no declared capability needs it. Use a token with fewer rights.`,
  };

  tone(m: ScmMessage): string {
    return messageTone(m);
  }

  message(m: ScmMessage, v: ScmVerification): string {
    return scmMessageText(m, v);
  }
}
