import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  afterNextRender,
  inject,
  viewChild,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import {
  SESSION_EXPIRED_TEXT,
  SESSION_EXPIRED_TITLE,
  SessionExpiryService,
  type NoticeTemplate,
} from './session-expiry.service';

/**
 * Hosts the templates of the session notices (NG-ZORRO notifications need a TemplateRef for
 * buttons). Rendered once in the root component; it renders nothing itself.
 */
@Component({
  selector: 'app-session-notices',
  imports: [NzButtonModule, NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-template #warning>
      <div class="session-notice" role="alert" aria-live="assertive">
        <div class="title">
          <nz-icon nzType="clock-circle" nzTheme="outline" class="icon warn" />
          <span i18n="@@core.session.warning.title">Your session is about to expire</span>
        </div>
        @if (session.warning() === 'absolute') {
          <p i18n="@@core.session.warning.absoluteCountdown">
            The maximum session length is reached in {{ session.secondsLeft() }} s. Save your work;
            you will need to sign in again.
          </p>
        } @else {
          <p i18n="@@core.session.warning.idleCountdown">
            You will be signed out in {{ session.secondsLeft() }} s because of inactivity.
          </p>
          <div class="actions">
            <button nz-button nzType="primary" nzSize="small" (click)="session.staySignedIn()">
              <span i18n="@@core.session.staySignedIn">Stay signed in</span>
            </button>
          </div>
        }
      </div>
    </ng-template>

    <ng-template #expired>
      <div class="session-notice" role="alert" aria-live="assertive">
        <div class="title">
          <nz-icon nzType="warning" nzTheme="outline" class="icon warn" />
          {{ expiredTitle }}
        </div>
        <p>{{ expiredText }}</p>
        <div class="actions">
          <button nz-button nzType="primary" nzSize="small" (click)="session.signInAgain()">
            <span i18n="@@core.session.signInAgain">Sign in again</span>
          </button>
          <button
            nz-button
            nzSize="small"
            (click)="session.stayOnPage()"
            i18n="@@core.session.stayOnPage"
          >
            Stay on this page
          </button>
        </div>
      </div>
    </ng-template>
  `,
  styles: [
    `
      .session-notice .title {
        font-weight: 600;
        font-size: 15px;
        margin-bottom: 6px;
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .session-notice .icon.warn {
        color: var(--bo-you);
        font-size: 18px;
      }
      .session-notice p {
        margin: 0 0 10px;
      }
      .session-notice .actions {
        display: flex;
        gap: 8px;
        justify-content: flex-end;
      }
    `,
  ],
})
export class SessionNoticesComponent implements OnDestroy {
  readonly session = inject(SessionExpiryService);
  readonly expiredTitle = SESSION_EXPIRED_TITLE;
  readonly expiredText = SESSION_EXPIRED_TEXT;

  private readonly warningTpl = viewChild.required<NoticeTemplate>('warning');
  private readonly expiredTpl = viewChild.required<NoticeTemplate>('expired');

  constructor() {
    afterNextRender(() =>
      this.session.registerTemplates({ warning: this.warningTpl(), expired: this.expiredTpl() }),
    );
  }

  ngOnDestroy(): void {
    this.session.registerTemplates(null);
  }
}
