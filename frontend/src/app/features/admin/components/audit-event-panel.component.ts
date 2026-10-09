import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import type { AuditEntry } from '../../../core/api/types';
import { ActorComponent } from '../../../core/ui/actor.component';
import { JsonViewComponent } from '../../../core/ui/json-view.component';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { operationLabel, resultBadge } from '../data/audit-operations';
import { StateChipComponent } from './state-chip.component';

/** One audit entry in the side panel: facts, links to related entries and the sanitised details. */
@Component({
  selector: 'app-audit-event-panel',
  imports: [
    DatePipe,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    ActorComponent,
    JsonViewComponent,
    SidePanelComponent,
    StateChipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      class="audit-panel"
      [open]="!!entry()"
      [title]="title()"
      [subtitle]="entry()?.operation ?? null"
      [defaultWidth]="600"
      storageKey="backoffice.auditPanelWidth"
      (closed)="closed.emit()"
    >
      @if (entry(); as e) {
        <ng-container boPanelHeader>
          <app-state-chip [tone]="result().holder" [label]="result().label" />
          <span class="since">{{ ago(e.eventTime) }}</span>
        </ng-container>
      }
      @if (entry(); as e) {
        <dl class="facts">
          <div>
            <dt i18n="@@admin.auditEvent.time">Time</dt>
            <dd class="num">{{ e.eventTime | date: timeFormat }}</dd>
          </div>
          <div>
            <dt i18n="@@admin.auditEvent.actor">Actor</dt>
            <dd>
              <app-actor [actor]="e.actor" />
              @if (e.actor?.userId) {
                <button
                  nz-button
                  nzType="link"
                  nzSize="small"
                  type="button"
                  (click)="filter.emit({ actorId: e.actor!.userId! })"
                >
                  <ng-container i18n="@@admin.auditEvent.byActor"
                    >Show entries by this actor</ng-container
                  >
                </button>
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@admin.auditEvent.entity">Entity</dt>
            <dd>
              <span class="mono">{{ e.entityType }}{{ e.entityId ? ' / ' + e.entityId : '' }}</span>
              @if (e.entityType === 'USER' && e.entityId) {
                <a
                  [routerLink]="['/admin/users', e.entityId]"
                  (click)="closed.emit()"
                  i18n="@@admin.auditEvent.openUser"
                  >Open user</a
                >
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@admin.auditEvent.project">Project</dt>
            <dd class="mono">{{ e.projectId || '-' }}</dd>
          </div>
          <div>
            <dt i18n="@@admin.auditEvent.sourceIp">Source IP</dt>
            <dd class="mono">{{ e.sourceIp || '-' }}</dd>
          </div>
          <div>
            <dt i18n="@@admin.auditEvent.correlationId">Correlation id</dt>
            <dd>
              <span class="mono">{{ e.correlationId || '-' }}</span>
              @if (e.correlationId) {
                <button
                  nz-button
                  nzType="link"
                  nzSize="small"
                  type="button"
                  (click)="correlate.emit(e.correlationId)"
                >
                  <nz-icon nzType="filter" />
                  <span i18n="@@admin.auditEvent.wholeRequest">Show the whole request</span>
                </button>
              }
            </dd>
          </div>
        </dl>
        <h3 i18n="@@admin.auditEvent.details">Details</h3>
        <app-json-view [value]="e.details ?? {}" maxHeight="55vh" />
      }
    </bo-side-panel>
  `,
  styles: [
    `
      .since {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .facts {
        margin: 0 0 20px;
        display: grid;
        gap: 6px;
        font-size: 13px;
      }
      .facts > div {
        display: grid;
        grid-template-columns: 110px minmax(0, 1fr);
        gap: 12px;
        align-items: baseline;
      }
      dt {
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
        overflow-wrap: anywhere;
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 4px 8px;
      }
      h3 {
        font-size: 13px;
        font-weight: 600;
        margin: 0 0 8px;
      }
    `,
  ],
})
export class AuditEventPanelComponent {
  readonly entry = input<AuditEntry | null>(null);
  readonly closed = output<void>();
  readonly correlate = output<string>();
  readonly filter = output<{ actorId: string }>();

  readonly title = computed(
    () =>
      operationLabel(this.entry()?.operation) || $localize`:@@admin.auditEvent.title:Audit entry`,
  );
  readonly result = computed(() => resultBadge(this.entry()?.result));
  readonly ago = timeAgo;
  readonly timeFormat = 'yyyy-MM-dd HH:mm:ss.SSS'; // i18n-ignore: a date pattern, the same in every language
}
