import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { timeAgo } from '../pipes/time-ago.pipe';
import { BatonComponent } from './baton.component';
import { absoluteTime } from './format';
import { KbdComponent } from './kbd.component';
import type { TurnItem } from './turn';

/**
 * Turn card: one thing that needs you. What, where (project · workflow · node), why you, how long
 * it has waited, and the primary action with its key hint. The whole card opens the item; the
 * button runs the primary action (for gates: open the gate panel focused on the checklist; a gate
 * is never approved from the card).
 *
 *   <bo-turn-card [item]="t" [active]="i === cursor()" (primary)="act(t)" (open)="open(t)" />
 */
@Component({
  selector: 'bo-turn-card',
  imports: [NzButtonModule, BatonComponent, KbdComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.active]': 'active()',
    '[attr.data-kind]': 'item().kind',
  },
  template: `
    @let t = item();
    <article class="card" [attr.aria-labelledby]="headingId()">
      <div class="lead">
        <bo-baton [baton]="t.baton" size="sm" />
        <span class="age" [attr.title]="absolute()">{{ age() }}</span>
      </div>
      <h3 class="title" [id]="headingId()">
        <button type="button" class="open" (click)="open.emit(t)">{{ t.title }}</button>
      </h3>
      <div class="where">
        @for (w of t.where; track $index; let first = $first) {
          <span [class.key]="first">{{ w }}</span>
        }
      </div>
      <div class="why">{{ t.why }}</div>
      <div class="actions">
        <button
          nz-button
          nzSize="small"
          [nzType]="t.kind === 'gate' ? 'primary' : 'default'"
          type="button"
          (click)="primary.emit(t)"
        >
          {{ t.primary.label }}
          @if (t.primary.keys && active()) {
            <bo-kbd [keys]="t.primary.keys" [quiet]="true" />
          }
        </button>
      </div>
    </article>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .card {
        position: relative;
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          'lead lead'
          'title actions'
          'where actions'
          'why actions';
        column-gap: 16px;
        row-gap: 2px;
        padding: 12px 16px 12px 18px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        transition: border-color var(--bo-dur-fast) var(--bo-ease);
      }
      .card::before {
        content: '';
        position: absolute;
        left: -1px;
        top: 10px;
        bottom: 10px;
        width: 3px;
        border-radius: 0 3px 3px 0;
        background: var(--bo-idle);
      }
      :host([data-kind='gate']) .card::before,
      :host([data-kind='connection']) .card::before {
        background: var(--bo-you);
      }
      :host([data-kind='failed-run']) .card::before,
      :host([data-kind='failed-ci']) .card::before {
        background: var(--bo-blocked);
      }
      .card:hover {
        border-color: var(--bo-line-strong);
      }
      :host(.active) .card {
        border-color: var(--bo-ink-3);
        box-shadow: 0 0 0 1px var(--bo-ink-3);
      }
      .lead {
        grid-area: lead;
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 4px;
      }
      .age {
        font-size: 12px;
        color: var(--bo-ink-3);
        font-variant-numeric: tabular-nums;
      }
      .title {
        grid-area: title;
        margin: 0;
        font-size: 14px;
        font-weight: 600;
        line-height: 1.35;
        min-width: 0;
      }
      .open {
        all: unset;
        cursor: pointer;
        color: var(--bo-ink);
        overflow-wrap: anywhere;
      }
      .open:hover {
        text-decoration: underline;
        text-underline-offset: 2px;
      }
      .open:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
        border-radius: 2px;
      }
      .where {
        grid-area: where;
        font-size: 12px;
        color: var(--bo-ink-2);
        display: flex;
        flex-wrap: wrap;
        min-width: 0;
      }
      .where span + span::before {
        content: '·';
        margin: 0 6px;
        color: var(--bo-ink-3);
      }
      .where .key {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
      }
      .why {
        grid-area: why;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .actions {
        grid-area: actions;
        align-self: center;
        display: flex;
        gap: 8px;
      }
      .actions button {
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
      @media (max-width: 640px) {
        .card {
          grid-template-columns: 1fr;
          grid-template-areas: 'lead' 'title' 'where' 'why' 'actions';
        }
        .actions {
          margin-top: 8px;
        }
      }
    `,
  ],
})
export class TurnCardComponent {
  readonly item = input.required<TurnItem>();
  /** Keyboard cursor is on this card (shows the key hint). */
  readonly active = input(false);
  readonly primary = output<TurnItem>();
  readonly open = output<TurnItem>();

  readonly headingId = computed(() => `turn-${this.item().id.replace(/[^a-z0-9-]/gi, '-')}`);
  readonly age = computed(() => {
    const { since, kind } = this.item();
    if (!since) return '';
    const rel = timeAgo(since);
    if (kind !== 'gate' || !rel.endsWith(' ago')) return rel;
    return `waiting ${rel.replace(' ago', '')}`;
  });
  readonly absolute = computed(() => absoluteTime(this.item().since));
}
