import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSegmentedModule } from 'ng-zorro-antd/segmented';
import { FormsModule } from '@angular/forms';
import { ActivityFeedService } from '../core/state/activity-feed.service';
import { ProjectsStore } from '../core/state/projects.store';
import { timeAgo } from '../core/ui/pipes/time-ago.pipe';
import { absoluteTime } from '../core/ui/relay/format';
import { GlyphComponent } from '../core/ui/relay/glyph.component';

/** Right-hand activity dock: live SSE events, current project or all; toggled with `.`. */
@Component({
  selector: 'app-activity-dock',
  imports: [RouterLink, NzIconModule, NzSegmentedModule, FormsModule, GlyphComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <aside class="dock" aria-labelledby="bo-dock-title">
      <header>
        <h2 id="bo-dock-title" i18n="@@shell.dock.title">Activity</h2>
        <button
          type="button"
          class="close"
          (click)="closed.emit()"
          aria-label="Close activity"
          i18n-aria-label="@@shell.dock.close"
        >
          <nz-icon nzType="close" />
        </button>
      </header>
      @if (store.current(); as p) {
        <nz-segmented
          class="scope"
          nzSize="small"
          [nzOptions]="[
            { label: p.key, value: 'project' },
            { label: allProjects, value: 'all' },
          ]"
          [ngModel]="scope()"
          (ngModelChange)="scope.set($event)"
        />
      }
      <ol class="list" aria-live="polite" aria-relevant="additions">
        @for (e of visible(); track e.id) {
          <li>
            <bo-glyph [holder]="e.holder" [size]="12" />
            <div class="body">
              @if (e.link) {
                <a [routerLink]="e.link" class="text">{{ e.text }}</a>
              } @else {
                <span class="text">{{ e.text }}</span>
              }
              <div class="meta">
                @if (e.projectKey) {
                  <span class="key">{{ e.projectKey }}</span>
                }
                @if (e.detail) {
                  <span class="detail">{{ e.detail }}</span>
                }
                <time [attr.datetime]="e.at" [attr.title]="abs(e.at)">{{ ago(e.at) }}</time>
              </div>
            </div>
          </li>
        } @empty {
          <li class="empty" i18n="@@shell.dock.empty">
            Quiet so far. Live events appear here as agents and CI hand work back.
          </li>
        }
      </ol>
    </aside>
  `,
  styles: [
    `
      :host {
        position: fixed;
        top: var(--bo-topbar-h);
        right: 0;
        bottom: 0;
        width: var(--bo-dock-w);
        z-index: 700;
      }
      .dock {
        height: 100%;
        display: flex;
        flex-direction: column;
        background: var(--bo-surface);
        border-left: 1px solid var(--bo-line);
      }
      header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 12px 16px 8px;
      }
      h2 {
        margin: 0;
        font-size: 14px;
      }
      .close {
        all: unset;
        cursor: pointer;
        display: inline-flex;
        padding: 4px;
        border-radius: 6px;
        color: var(--bo-ink-3);
      }
      .close:hover {
        color: var(--bo-ink);
        background: var(--bo-surface-3);
      }
      .close:focus-visible {
        outline: 2px solid var(--bo-accent);
      }
      .scope {
        margin: 0 16px 8px;
      }
      .list {
        list-style: none;
        margin: 0;
        padding: 0 8px 16px;
        overflow: auto;
        flex: 1;
      }
      li {
        display: flex;
        gap: 10px;
        padding: 8px;
        border-radius: 8px;
        align-items: flex-start;
      }
      li:hover {
        background: var(--bo-surface-2);
      }
      li bo-glyph {
        margin-top: 4px;
      }
      .body {
        min-width: 0;
        flex: 1;
      }
      .text {
        font-size: 13px;
        color: var(--bo-ink);
        text-decoration: none;
      }
      a.text:hover {
        text-decoration: underline;
      }
      .meta {
        display: flex;
        flex-wrap: wrap;
        gap: 0 8px;
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .key,
      .detail {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        overflow: hidden;
        text-overflow: ellipsis;
        max-width: 160px;
        white-space: nowrap;
      }
      .empty {
        font-size: 12.5px;
        color: var(--bo-ink-3);
        padding: 16px 8px;
      }
    `,
  ],
})
export class ActivityDockComponent {
  readonly feed = inject(ActivityFeedService);
  readonly store = inject(ProjectsStore);
  readonly closed = output<void>();
  readonly scope = signal<'project' | 'all'>('project');

  readonly visible = computed(() => {
    const cur = this.store.current();
    const list = this.feed.entries();
    if (!cur || this.scope() === 'all') return list;
    return list.filter((e) => !e.projectId || e.projectId === cur.id || e.projectKey === cur.key);
  });

  readonly allProjects = $localize`:@@shell.dock.allProjects:All projects`;
  ago = timeAgo;
  abs = absoluteTime;
}
