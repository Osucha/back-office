import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { HelpLinkComponent } from '../../help/help-link.component';
import type { HelpKey } from '../../help/help-map';

/** A secondary action in the header's overflow menu. */
export interface PageAction {
  label: string;
  icon?: string;
  /** Router link, or an external `href`, or a handler. */
  link?: unknown[] | string;
  href?: string;
  run?: () => void;
  danger?: boolean;
  disabled?: boolean;
}

/**
 * Page header: eyebrow (context), title, status, provenance line, primary action on the right and
 * secondary actions in an overflow menu. The breadcrumb lives in the top bar.
 *
 *   <bo-page-header title="Runs" subtitle="Every workflow execution, live.">
 *     <bo-baton boHeaderStatus [baton]="b" />
 *     <bo-provenance boHeaderMeta [actor]="run.startedBy" verb="started by" [at]="run.createdAt" />
 *     <button boHeaderActions nz-button nzType="primary">Start run</button>
 *     <bo-relay-track boHeaderBelow ... />
 *   </bo-page-header>
 *
 *   help="runs"   adds the contextual help button (topics in core/help/help-map.ts)
 *   [menu]="[{ label: 'Duplicate', icon: 'copy', run: dup }, { label: 'Delete', danger: true, run: del }]"
 */
@Component({
  selector: 'bo-page-header',
  imports: [RouterLink, NzButtonModule, NzDropDownModule, NzIconModule, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="head">
      <div class="lead"><ng-content select="[boHeaderLead]" /></div>
      <div class="main">
        @if (eyebrow()) {
          <div class="eyebrow">{{ eyebrow() }}</div>
        }
        <div class="title-row">
          <h1 [class.mono-title]="monoTitle()">{{ title() }}</h1>
          <ng-content select="[boHeaderStatus]" />
          @if (help(); as h) {
            <bo-help-link class="help" [topic]="h" />
          }
        </div>
        @if (subtitle()) {
          <p class="subtitle">{{ subtitle() }}</p>
        }
        <div class="meta"><ng-content select="[boHeaderMeta]" /></div>
      </div>
      <div class="actions">
        <ng-content select="[boHeaderActions]" />
        @if (menu().length) {
          <button
            nz-button
            type="button"
            nz-dropdown
            [nzDropdownMenu]="more"
            nzTrigger="click"
            nzPlacement="bottomRight"
            aria-label="More actions"
            i18n-aria-label="@@core.header.moreActions"
          >
            <nz-icon nzType="more" />
          </button>
          <nz-dropdown-menu #more="nzDropdownMenu">
            <ul nz-menu>
              @for (a of menu(); track a.label) {
                @if (a.href) {
                  <li nz-menu-item [nzDisabled]="!!a.disabled">
                    <a [href]="a.href" target="_blank" rel="noopener noreferrer">
                      @if (a.icon) {
                        <nz-icon [nzType]="a.icon" />
                      }
                      {{ a.label }}
                    </a>
                  </li>
                } @else if (a.link) {
                  <li nz-menu-item [nzDisabled]="!!a.disabled" [routerLink]="a.link">
                    @if (a.icon) {
                      <nz-icon [nzType]="a.icon" />
                    }
                    {{ a.label }}
                  </li>
                } @else {
                  <li
                    nz-menu-item
                    [nzDanger]="!!a.danger"
                    [nzDisabled]="!!a.disabled"
                    (click)="a.run?.()"
                  >
                    @if (a.icon) {
                      <nz-icon [nzType]="a.icon" />
                    }
                    {{ a.label }}
                  </li>
                }
              }
            </ul>
          </nz-dropdown-menu>
        }
      </div>
    </header>
    <div class="below"><ng-content select="[boHeaderBelow]" /></div>
  `,
  styles: [
    `
      :host {
        display: block;
        margin-bottom: var(--bo-space-5);
      }
      .head {
        display: flex;
        align-items: flex-start;
        gap: var(--bo-space-4);
        flex-wrap: wrap;
      }
      .lead:empty {
        display: none;
      }
      .main {
        flex: 1 1 360px;
        min-width: 0;
      }
      .eyebrow {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-3);
        margin-bottom: 2px;
        font-family: var(--bo-font-mono);
        letter-spacing: 0.02em;
      }
      .title-row {
        display: flex;
        align-items: center;
        gap: 10px;
        flex-wrap: wrap;
        min-width: 0;
      }
      h1 {
        margin: 0;
        font-size: var(--bo-fs-title);
        line-height: var(--bo-lh-tight);
        font-weight: 600;
        letter-spacing: var(--bo-tracking-title);
        overflow-wrap: anywhere;
      }
      h1.mono-title {
        font-family: var(--bo-font-mono);
        font-size: 18px;
        letter-spacing: 0;
      }
      .subtitle {
        margin: 4px 0 0;
        color: var(--bo-ink-3);
        font-size: 13px;
        max-width: 76ch;
      }
      .meta {
        margin-top: 4px;
      }
      .meta:empty {
        display: none;
      }
      .actions {
        display: flex;
        gap: var(--bo-space-2);
        align-items: center;
        flex-wrap: wrap;
        margin-left: auto;
        padding-top: 2px;
      }
      .actions:empty {
        display: none;
      }
      .below:empty {
        display: none;
      }
      .below {
        margin-top: var(--bo-space-4);
      }
    `,
  ],
})
export class PageHeaderComponent {
  readonly title = input.required<string>();
  readonly eyebrow = input<string | null>(null);
  readonly subtitle = input<string | null>(null);
  readonly monoTitle = input(false);
  readonly menu = input<PageAction[]>([]);
  /** Contextual help topic (core/help/help-map.ts): a ? button after the title. */
  readonly help = input<HelpKey | null>(null);
}
