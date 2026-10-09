import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { filter, interval } from 'rxjs';
import { CiApi } from '../../../core/api/ci.service';
import {
  FINDING_SEVERITIES,
  type CiExecution,
  type Finding,
  type FindingSeverity,
} from '../../../core/api/types';
import { EventStreamService } from '../../../core/events/event-stream.service';
import { ProjectsStore } from '../../../core/state/projects.store';
import { JsonViewComponent } from '../../../core/ui/json-view.component';
import { MarkdownViewerComponent } from '../../../core/ui/markdown-viewer.component';
import { durationBetween } from '../../../core/ui/pipes/duration.pipe';
import { batonOf, type BatonHolder } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { PageHeaderComponent, type PageAction } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';

const SEVERITY: Record<FindingSeverity, { label: string; holder: BatonHolder }> = {
  BLOCKER: { label: $localize`:@@ci.detail.severity.blocker:Blocker`, holder: 'blocked' },
  MAJOR: { label: $localize`:@@ci.detail.severity.major:Major`, holder: 'you' },
  MINOR: { label: $localize`:@@ci.detail.severity.minor:Minor`, holder: 'agent' },
  NIT: { label: $localize`:@@ci.detail.severity.nit:Nit`, holder: 'idle' },
};

const VERDICT: Record<string, string> = {
  APPROVE: $localize`:@@ci.detail.verdict.approve:Approve`,
  COMMENT: $localize`:@@ci.detail.verdict.comment:Comment`,
  REQUEST_CHANGES: $localize`:@@ci.detail.verdict.requestChanges:Request changes`,
};

/**
 * CI execution: gate result as the baton, provenance (reviewing model, time, cost), merge request /
 * commit / job links, the review report with findings grouped by severity, and the CI context.
 */
@Component({
  selector: 'app-ci-execution-detail-page',
  imports: [
    DatePipe,
    RouterLink,
    NzButtonModule,
    JsonViewComponent,
    MarkdownViewerComponent,
    BatonComponent,
    EmptyComponent,
    GlyphComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      @if (!execution()) {
        <bo-skeleton
          variant="detail"
          label="Loading the CI execution"
          i18n-label="@@ci.detail.loading"
        />
      } @else if (execution(); as x) {
        <bo-page-header
          help="ci"
          [title]="title()"
          [eyebrow]="
            x.projectKey +
            ' · ' +
            x.entrypoint +
            ' · ' +
            (x.mode === 'ADVISORY' ? text.advisory : text.blocking)
          "
          [menu]="menu()"
        >
          <bo-baton boHeaderStatus [baton]="baton()" />
          <bo-provenance
            boHeaderMeta
            [who]="reviewer()"
            [detail]="x.report?.modelRef?.provider ?? null"
            [at]="x.createdAt"
            [durationMs]="durationMs()"
            [cost]="x.cost"
            [usage]="x.report?.usage"
          />
          @if (x.runId) {
            <a
              boHeaderActions
              nz-button
              nzType="primary"
              [routerLink]="['/runs', x.runId]"
              i18n="@@ci.detail.openRun"
              >Open workflow run</a
            >
          }
        </bo-page-header>

        @if (x.error) {
          <section class="banner" data-holder="blocked" role="alert">
            <bo-glyph holder="blocked" [size]="18" />
            <div>
              <strong i18n="@@ci.detail.failed">The execution failed.</strong
              ><span class="pre">&nbsp;{{ x.error }}</span>
            </div>
          </section>
        }
        @if (x.status === 'QUEUED' || x.status === 'RUNNING') {
          <section class="banner" data-holder="agent" role="status">
            <bo-glyph holder="agent" [live]="true" [size]="18" />
            <div i18n="@@ci.detail.running">
              The review is running. The CI job is polling; this page refreshes by itself.
            </div>
          </section>
        }

        <div class="grid">
          <div class="main">
            @if (x.report; as rep) {
              <section
                class="surface verdict"
                aria-label="Review verdict"
                i18n-aria-label="@@ci.detail.verdictLabel"
              >
                <div class="score" [attr.data-holder]="scoreHolder()">
                  <span class="score-n">{{ rep.score ?? '–' }}</span>
                  <span class="score-l" i18n="@@ci.detail.score">score</span>
                </div>
                <div class="verdict-text">
                  <div class="v-label">{{ verdictLabel(rep.verdict) }}</div>
                  <div class="sev-row">
                    @for (s of severities; track s) {
                      <span
                        class="sev"
                        [attr.data-holder]="sev(s).holder"
                        [class.zero]="!(counts()[s] ?? 0)"
                      >
                        <bo-glyph [holder]="sev(s).holder" [size]="11" />
                        {{ severityCount(s) }}
                      </span>
                    }
                  </div>
                </div>
              </section>
            }

            <section class="surface block" aria-labelledby="ci-summary">
              <h2 id="ci-summary" class="section-title" i18n="@@ci.detail.summary">Summary</h2>
              @if (x.summary || x.report?.summary) {
                @defer (on immediate) {
                  <app-markdown [source]="x.summary || x.report?.summary" />
                } @placeholder {
                  <bo-skeleton variant="list" [rows]="3" />
                }
              } @else {
                <p class="muted" i18n="@@ci.detail.noSummary">No summary yet.</p>
              }
            </section>

            <section aria-labelledby="ci-findings">
              <h2 id="ci-findings" class="section-title">
                <span i18n="@@ci.detail.findings">Findings</span>
                <span class="count">{{ x.report?.findings?.length ?? 0 }}</span>
              </h2>
              @if (!x.report?.findings?.length) {
                <bo-empty
                  tone="quiet"
                  title="No findings."
                  i18n-title="@@ci.detail.noFindings"
                  [detail]="x.report ? text.nothingToChange : text.noReport"
                />
              } @else {
                @for (group of grouped(); track group.severity) {
                  <div class="group">
                    <h3>
                      <bo-baton
                        size="sm"
                        [holder]="sev(group.severity).holder"
                        [label]="sev(group.severity).label"
                      />
                      <span class="count">{{ group.items.length }}</span>
                    </h3>
                    <ul class="findings">
                      @for (f of group.items; track $index) {
                        <li class="finding surface">
                          <div class="loc mono">{{ f.file }}{{ f.line ? ':' + f.line : '' }}</div>
                          <div class="f-title">{{ f.title }}</div>
                          @if (f.detail) {
                            <div class="f-detail">{{ f.detail }}</div>
                          }
                          @if (f.suggestion) {
                            <div class="suggestion">
                              <span class="s-label" i18n="@@ci.detail.suggestion">Suggestion</span
                              >{{ f.suggestion }}
                            </div>
                          }
                        </li>
                      }
                    </ul>
                  </div>
                }
              }
            </section>
          </div>

          <aside class="side">
            <section class="surface block" aria-labelledby="ci-context">
              <h2 id="ci-context" class="section-title" i18n="@@ci.detail.context">CI context</h2>
              <dl class="ctx">
                @if (x.repositoryKey || x.context?.repositoryUrl) {
                  <div class="ctx-repository">
                    <dt i18n="@@ci.detail.ctx.repository">Repository</dt>
                    <dd>
                      @if (x.repositoryKey) {
                        <span class="mono">{{ x.repositoryKey }}</span>
                      } @else {
                        <span class="muted" i18n="@@ci.detail.ctx.repositoryUnmatched"
                          >not matched</span
                        >
                      }
                      @if (x.context?.repositoryUrl) {
                        <div class="mono small repo-url">{{ x.context?.repositoryUrl }}</div>
                      }
                    </dd>
                  </div>
                }
                @if (x.context?.mergeRequest; as mr) {
                  <div>
                    <dt i18n="@@ci.detail.ctx.mergeRequest">Merge request</dt>
                    <dd>
                      @if (mr.url) {
                        <a [href]="mr.url" target="_blank" rel="noopener noreferrer"
                          >!{{ mr.iid }} {{ mr.title }}</a
                        >
                      } @else {
                        !{{ mr.iid }} {{ mr.title }}
                      }
                      <div class="mono small">{{ mr.sourceBranch }} → {{ mr.targetBranch }}</div>
                      @if (mr.author) {
                        <div class="small muted" i18n="@@ci.detail.ctx.author">
                          by {{ mr.author }}
                        </div>
                      }
                    </dd>
                  </div>
                }
                <div>
                  <dt i18n="@@ci.detail.ctx.ref">Ref</dt>
                  <dd class="mono">{{ x.context?.ref || '-' }}</dd>
                </div>
                <div>
                  <dt i18n="@@ci.detail.ctx.commit">Commit</dt>
                  <dd class="mono" [attr.title]="x.context?.commitSha">
                    {{ (x.context?.commitSha || '-').slice(0, 12) }}
                  </dd>
                </div>
                <div>
                  <dt i18n="@@ci.detail.ctx.pipelineJob">Pipeline / job</dt>
                  <dd>
                    <span class="mono"
                      >{{ x.context?.pipelineId || '-' }} / {{ x.context?.jobId || '-' }}</span
                    >
                    @if (x.context?.jobUrl) {
                      &nbsp;·&nbsp;<a
                        [href]="x.context?.jobUrl"
                        target="_blank"
                        rel="noopener noreferrer"
                        i18n="@@ci.detail.ctx.openJob"
                        >Open job</a
                      >
                    }
                  </dd>
                </div>
                <div>
                  <dt i18n="@@ci.detail.ctx.projectPath">Project path</dt>
                  <dd class="mono">{{ x.context?.projectPath || '-' }}</dd>
                </div>
                <div>
                  <dt i18n="@@ci.detail.ctx.actor">Actor</dt>
                  <dd>{{ x.context?.actor || '-' }}</dd>
                </div>
                <div>
                  <dt i18n="@@ci.detail.ctx.provider">Provider</dt>
                  <dd>{{ x.context?.provider || '-' }}</dd>
                </div>
                <div>
                  <dt i18n="@@ci.detail.ctx.started">Started</dt>
                  <dd>{{ x.createdAt | date: 'medium' }}</dd>
                </div>
              </dl>
            </section>
            @if (x.changedFiles?.length) {
              <section class="surface block" aria-labelledby="ci-files">
                <h2 id="ci-files" class="section-title">
                  <span i18n="@@ci.detail.changedFiles">Changed files</span>
                  <span class="count">{{ x.changedFiles?.length }}</span>
                </h2>
                <ul class="files mono">
                  @for (file of x.changedFiles; track file) {
                    <li>{{ file }}</li>
                  }
                </ul>
              </section>
            }
            @if (hasVariables()) {
              <section class="surface block" aria-labelledby="ci-vars">
                <h2 id="ci-vars" class="section-title" i18n="@@ci.detail.variables">Variables</h2>
                <app-json-view [value]="x.variables" maxHeight="240px" />
              </section>
            }
          </aside>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .repo-url {
        overflow-wrap: anywhere;
        color: var(--bo-ink-3);
      }
      .banner {
        display: flex;
        gap: 12px;
        align-items: flex-start;
        padding: 12px 16px;
        margin-bottom: 12px;
        border-radius: var(--bo-radius-card);
        font-size: 13px;
      }
      .banner[data-holder='blocked'] {
        background: var(--bo-blocked-soft);
        box-shadow: inset 3px 0 0 var(--bo-blocked);
      }
      .banner[data-holder='agent'] {
        background: var(--bo-agent-soft);
        box-shadow: inset 3px 0 0 var(--bo-agent);
      }
      .pre {
        white-space: pre-wrap;
      }
      .grid {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 360px;
        gap: 16px;
        align-items: start;
      }
      @media (max-width: 1100px) {
        .grid {
          grid-template-columns: 1fr;
        }
      }
      .main,
      .side {
        display: flex;
        flex-direction: column;
        gap: 16px;
        min-width: 0;
      }
      .block {
        padding: 14px 16px;
      }
      .verdict {
        display: flex;
        gap: 20px;
        align-items: center;
        padding: 16px;
      }
      .score {
        --c: var(--bo-idle);
        flex: none;
        width: 76px;
        height: 76px;
        border-radius: 50%;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        box-shadow: inset 0 0 0 4px var(--c);
      }
      .score[data-holder='done'] {
        --c: var(--bo-done);
      }
      .score[data-holder='you'] {
        --c: var(--bo-you);
      }
      .score[data-holder='blocked'] {
        --c: var(--bo-blocked);
      }
      .score-n {
        font-family: var(--bo-font-mono);
        font-size: 22px;
        font-weight: 600;
        line-height: 1;
      }
      .score-l {
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .v-label {
        font-size: 16px;
        font-weight: 600;
        margin-bottom: 8px;
      }
      .sev-row {
        display: flex;
        flex-wrap: wrap;
        gap: 14px;
        font-size: 13px;
      }
      .sev {
        display: inline-flex;
        align-items: center;
        gap: 6px;
      }
      .sev.zero {
        color: var(--bo-ink-3);
      }
      .group + .group {
        margin-top: 16px;
      }
      .group h3 {
        display: flex;
        align-items: center;
        gap: 8px;
        margin: 0 0 8px;
      }
      .count {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-ink-3);
        font-weight: 400;
      }
      .findings {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .finding {
        padding: 12px 14px;
      }
      .loc {
        font-size: 11.5px;
        color: var(--bo-ink-3);
        overflow-wrap: anywhere;
      }
      .f-title {
        font-weight: 600;
        margin-top: 2px;
      }
      .f-detail {
        white-space: pre-wrap;
        color: var(--bo-ink-2);
        margin-top: 4px;
        font-size: 13px;
      }
      .suggestion {
        margin-top: 8px;
        padding: 8px 10px;
        border-radius: 6px;
        background: var(--bo-done-soft);
        white-space: pre-wrap;
        font-size: 13px;
      }
      .s-label {
        display: block;
        font-size: 11px;
        font-weight: 600;
        color: var(--bo-done-ink);
        margin-bottom: 2px;
      }
      .ctx {
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;
        font-size: 13px;
      }
      .ctx > div {
        display: grid;
        grid-template-columns: 110px minmax(0, 1fr);
        gap: 8px;
      }
      dt {
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
        overflow-wrap: anywhere;
      }
      .small {
        font-size: 12px;
      }
      .files {
        list-style: none;
        margin: 0;
        padding: 0;
        font-size: 11.5px;
        max-height: 240px;
        overflow: auto;
      }
      .files li {
        padding: 2px 0;
        overflow-wrap: anywhere;
      }
    `,
  ],
})
export class CiExecutionDetailPageComponent {
  private readonly api = inject(CiApi);
  private readonly events = inject(EventStreamService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly crumbs = inject(BreadcrumbService);
  private readonly projects = inject(ProjectsStore);

  readonly executionId = input.required<string>();
  readonly execution = signal<CiExecution | null>(null);
  readonly severities = FINDING_SEVERITIES;

  readonly baton = computed(() => batonOf.ci(this.execution() ?? {}));
  readonly title = computed(() => {
    const x = this.execution();
    const mr = x?.context?.mergeRequest;
    if (mr?.iid) return `!${mr.iid} ${mr.title ?? ''}`.trim();
    const entrypoint = x?.entrypoint ?? 'CI';
    const ref = x?.context?.ref ?? (x?.context?.commitSha ?? '').slice(0, 8);
    return $localize`:@@ci.detail.title:${entrypoint}:entrypoint: on ${ref}:ref:`;
  });
  readonly reviewer = computed(
    () => this.execution()?.report?.modelRef?.modelId ?? $localize`:@@ci.detail.reviewer:CI review`,
  );
  readonly text = {
    advisory: $localize`:@@ci.detail.advisory:advisory`,
    blocking: $localize`:@@ci.detail.blocking:blocking`,
    nothingToChange: $localize`:@@ci.detail.nothingToChange:The reviewer found nothing to change.`,
    noReport: $localize`:@@ci.detail.noReport:The review has not produced a report.`,
  };
  readonly durationMs = computed(() => {
    const x = this.execution();
    return x?.createdAt ? durationBetween(x.createdAt, x.finishedAt) : null;
  });
  readonly menu = computed<PageAction[]>(() => {
    const x = this.execution();
    const out: PageAction[] = [];
    if (x?.context?.mergeRequest?.url)
      out.push({
        label: $localize`:@@ci.detail.menu.mergeRequest:Open merge request`,
        icon: 'branches',
        href: x.context.mergeRequest.url,
      });
    if (x?.context?.jobUrl)
      out.push({
        label: $localize`:@@ci.detail.menu.job:Open CI job`,
        icon: 'cloud-server',
        href: x.context.jobUrl,
      });
    if (x?.projectId)
      out.push({
        label: $localize`:@@ci.detail.menu.projectSettings:Project CI settings`,
        icon: 'setting',
        link: ['/projects', x.projectId],
      });
    out.push({
      label: $localize`:@@ci.detail.menu.refresh:Refresh`,
      icon: 'reload',
      run: () => this.load(),
    });
    return out;
  });
  readonly counts = computed(() => {
    const out: Partial<Record<FindingSeverity, number>> = {};
    for (const f of this.execution()?.report?.findings ?? [])
      if (f.severity) out[f.severity] = (out[f.severity] ?? 0) + 1;
    return out;
  });
  readonly grouped = computed(() =>
    FINDING_SEVERITIES.map((severity) => ({
      severity,
      items: (this.execution()?.report?.findings ?? []).filter(
        (f) => f.severity === severity,
      ) as Finding[],
    })).filter((g) => g.items.length),
  );
  readonly scoreHolder = computed<BatonHolder>(() => {
    const s = this.execution()?.report?.score;
    if (s === null || s === undefined) return 'idle';
    return s >= 70 ? 'done' : s >= 40 ? 'you' : 'blocked';
  });
  readonly hasVariables = computed(() => Object.keys(this.execution()?.variables ?? {}).length > 0);

  constructor() {
    effect(() => {
      this.executionId();
      untracked(() => this.load());
    });
    this.events
      .of('CiExecutionFinished', 'WorkflowRunFinished')
      .pipe(
        filter((e) => {
          const p = e.payload as Record<string, unknown>;
          return (
            p?.['ciExecutionId'] === this.executionId() ||
            p?.['executionId'] === this.executionId() ||
            (!!this.execution()?.runId && p?.['runId'] === this.execution()?.runId)
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.load());
    interval(10000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const s = this.execution()?.status;
        if (s === 'QUEUED' || s === 'RUNNING') this.load();
      });
  }

  sev(s: FindingSeverity) {
    return SEVERITY[s];
  }

  severityCount(s: FindingSeverity): string {
    return $localize`:@@ci.detail.severityCount:${this.counts()[s] ?? 0}:count: ${SEVERITY[s].label}:severity:`;
  }

  verdictLabel(v: string | undefined): string {
    return (v && VERDICT[v]) || $localize`:@@ci.detail.noVerdict:No verdict yet`;
  }

  load(): void {
    this.api.getExecution(this.executionId()).subscribe((x) => {
      this.execution.set(x);
      this.projects.setCurrent(x.projectId);
      this.crumbs.set([
        { label: 'CI', link: '/ci' },
        {
          label: x.projectKey ?? $localize`:@@ci.detail.crumb.project:Project`,
          link: ['/projects', x.projectId],
          mono: true,
        },
        { label: x.entrypoint ?? $localize`:@@ci.detail.crumb.execution:Execution`, mono: true },
      ]);
    });
  }
}
