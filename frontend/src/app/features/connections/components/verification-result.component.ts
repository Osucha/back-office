import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TimeAgoPipe } from '../../../core/ui/pipes/time-ago.pipe';
import {
  excessBoundByScopes,
  jiraPermissionName,
  messageTone,
  permissionsFor,
  sortPermissions,
  verificationChip,
  verificationMessageText,
} from '../data/connection-model';
import type { ConnectionVerification } from '../data/connection-types';
import { CONNECTION_CHIP_STYLES } from './chip-styles';

/**
 * The result of checking a connection against Jira: status, account, localized messages and the
 * Jira permissions per project (granted, missing = red, excess = amber).
 */
@Component({
  selector: 'app-verification-result',
  imports: [TimeAgoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (verification(); as v) {
      <div class="vr" aria-live="polite">
        <div class="head">
          <span class="cchip" [class]="chip().tone">{{ chip().label }}</span>
          @if (v.account?.displayName) {
            <span class="muted" i18n="@@connections.verification.account"
              >as {{ v.account?.displayName }}</span
            >
          }
          <span class="muted" [title]="v.checkedAt">{{ v.checkedAt | timeAgo }}</span>
        </div>
        @if (v.messages.length) {
          <ul class="messages">
            @for (m of v.messages; track $index) {
              <li [class]="tone(m)" [title]="m.text">{{ text(m) }}</li>
            }
          </ul>
        }
        @for (p of v.projects; track p.projectKey) {
          <div class="project">
            <span class="mono key">{{ p.projectKey }}</span>
            <span class="cchips">
              @for (g of sorted(p.granted); track g) {
                @if (!isExcess(p.excess, g)) {
                  <span class="cchip ok">{{ name(g) }}</span>
                }
              }
              @for (g of sorted(p.missing); track g) {
                <span class="cchip bad" [title]="text_.missingTitle"
                  >{{ name(g) }}
                  <span i18n="@@connections.verification.missingChip">missing</span></span
                >
              }
              @for (g of sorted(p.excess); track g) {
                @if (boundByScopes()) {
                  <span class="cchip neutral" [title]="text_.excessScopedTitle"
                    >{{ name(g) }}
                    <span i18n="@@connections.verification.excessScopedChip"
                      >not in the token</span
                    ></span
                  >
                } @else {
                  <span class="cchip warn" [title]="text_.excessTitle"
                    >{{ name(g) }}
                    <span i18n="@@connections.verification.excessChip">not needed</span></span
                  >
                }
              }
            </span>
          </div>
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
      .head {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
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
      .project {
        display: flex;
        gap: 8px;
        align-items: baseline;
      }
      .key {
        font-size: 12px;
        min-width: 56px;
        color: var(--bo-ink);
        font-weight: 600;
      }
    `,
  ],
})
export class VerificationResultComponent {
  readonly verification = input<ConnectionVerification | null | undefined>(null);
  /** Capabilities of the connection (for "use an account with only …"). */
  readonly capabilities = input<readonly string[]>([]);

  readonly chip = computed(() => verificationChip(this.verification()));
  readonly boundByScopes = computed(() => excessBoundByScopes(this.verification()));
  readonly needed = computed(() => permissionsFor(this.capabilities()));
  readonly text_ = {
    missingTitle: $localize`:@@connections.verification.missingTitle:Missing for the declared capabilities: steps that need it fail.`,
    excessScopedTitle: $localize`:@@connections.verification.excessScopedTitle:The account has this permission in Jira, but the scopes of this token do not allow using it.`,
    excessTitle: $localize`:@@connections.verification.excessTitle:Over-privileged: the declared capabilities do not need this permission. Use a dedicated account without it.`,
  };

  readonly name = jiraPermissionName;
  readonly sorted = sortPermissions;

  isExcess(excess: readonly string[], permission: string): boolean {
    return excess.includes(permission);
  }

  tone(m: { severity: string }): string {
    return messageTone(m as { severity: 'ERROR' | 'WARNING' | 'INFO' });
  }

  text(m: Parameters<typeof verificationMessageText>[0]): string {
    return verificationMessageText(m, this.needed());
  }
}
