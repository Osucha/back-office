import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { Observable } from 'rxjs';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import type {
  Agent,
  AgentAudit,
  CoherenceFinding,
  CoherenceReport,
  Problem,
  WorkflowNode,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { HelpTipComponent } from '../../../core/ui/help-tip.component';
import { MoneyPipe } from '../../../core/ui/pipes/money.pipe';
import { agentDraft, customizeAgentForProject } from '../../library/public-api';
import {
  type AuditAgentView,
  addSkillPlan,
  auditView,
  coherenceSeverityLabel,
  sortCoherenceFindings,
} from '../data/agent-audit';

interface CoherenceState {
  loading: boolean;
  report: CoherenceReport | null;
  findings: CoherenceFinding[];
  error: string | null;
}

/**
 * ADR 0061: "Agents in this workflow" in the designer. Per agent the draft's steps use: kind,
 * scope, steps (a click selects the node), rules, skills, the required skills with the steps that
 * need them (missing ones with "Add to agent") and the deterministic findings; "Check coherence"
 * asks a model (on request only, after a confirmation) to find contradictions in the agent's whole
 * environment. The page loads the audit; this panel changes agents and tells the page to reload.
 */
@Component({
  selector: 'app-agent-audit-panel',
  imports: [
    NzButtonModule,
    NzIconModule,
    NzTagModule,
    NzTooltipModule,
    HelpTipComponent,
    MoneyPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="audit" data-agent-audit>
      <div class="intro">
        <span class="muted small" i18n="@@workflows.audit.intro"
          >Rules, skills and findings per agent</span
        >
        <bo-help-tip [text]="text.help" />
        @if (loading()) {
          <nz-icon nzType="loading" nzSpin />
        }
      </div>
      @if (error()) {
        <p class="danger small" role="alert">{{ error() }}</p>
      }
      @for (f of view().findings; track $index) {
        <div class="finding" [attr.data-sev]="f.severity" data-audit-finding>
          <nz-icon [nzType]="f.severity === 'ERROR' ? 'close-circle' : 'warning'" />
          <div>
            <div class="f-title">{{ f.title }}</div>
            <div class="f-msg">{{ f.message }}</div>
            @for (nd of f.nodes; track nd.id) {
              <button type="button" class="node-link" (click)="selectNode.emit(nd.id)">
                {{ nd.name }}
              </button>
            }
          </div>
        </div>
      }
      @for (a of view().agents; track a.key) {
        <section class="agent" [attr.data-audit-agent]="a.key" [attr.aria-label]="a.name">
          <header class="a-head">
            <span class="a-name">{{ a.name }}</span>
            <nz-tag [nzColor]="a.coding ? 'purple' : 'default'">{{ a.kind }}</nz-tag>
            <span class="muted small">{{
              a.scope === 'PROJECT' ? text.project : text.global
            }}</span>
            <span class="spacer"></span>
            @if (a.problems) {
              <span class="count" data-sev="ERROR">{{ a.problems }}</span>
            }
          </header>

          <div class="row">
            <span class="label" i18n="@@workflows.audit.steps">Steps</span>
            <span class="chips">
              @for (nd of a.nodes; track nd.id) {
                <button type="button" class="node-link" (click)="selectNode.emit(nd.id)">
                  {{ nd.name }}
                </button>
              }
            </span>
          </div>
          <div class="row">
            <span class="label" i18n="@@workflows.audit.rules">Rules</span>
            <span class="chips">
              @for (r of a.rules; track r.key) {
                <nz-tag [nzColor]="r.automatic ? 'gold' : 'default'">{{ r.name }}</nz-tag>
              } @empty {
                <span class="muted small" i18n="@@workflows.audit.none">none</span>
              }
            </span>
          </div>
          <div class="row">
            <span class="label" i18n="@@workflows.audit.skills">Skills</span>
            <span class="chips">
              @for (s of a.skills; track s.key) {
                <nz-tag nzColor="blue">{{ s.name }}</nz-tag>
              } @empty {
                <span class="muted small" i18n="@@workflows.audit.none">none</span>
              }
            </span>
          </div>
          @if (a.required.length) {
            <div class="row">
              <span class="label" i18n="@@workflows.audit.required">Required skills</span>
              <ul class="required">
                @for (r of a.required; track r.key) {
                  <li [class.missing]="r.missing" [attr.data-required-skill]="r.key">
                    <span class="r-name">
                      @if (r.missing) {
                        <nz-icon nzType="warning" />
                      }
                      {{ r.name }}
                    </span>
                    <span class="r-steps">
                      @for (nd of r.nodes; track nd.id) {
                        <button type="button" class="node-link" (click)="selectNode.emit(nd.id)">
                          {{ nd.name }}
                        </button>
                      }
                    </span>
                    @if (r.missing && canChange()) {
                      <button
                        nz-button
                        nzSize="small"
                        type="button"
                        [nzLoading]="adding() === a.key + ':' + r.key"
                        (click)="addSkill(a, r.key, r.name)"
                        data-add-skill
                        i18n="@@workflows.audit.addToAgent"
                      >
                        Add to agent
                      </button>
                    }
                  </li>
                }
              </ul>
            </div>
          }
          @for (f of a.findings; track $index) {
            <div class="finding" [attr.data-sev]="f.severity" data-audit-finding>
              <nz-icon [nzType]="f.severity === 'ERROR' ? 'close-circle' : 'warning'" />
              <div>
                <div class="f-title">{{ f.title }}</div>
                <div class="f-msg">{{ f.message }}</div>
                @for (nd of f.nodes; track nd.id) {
                  <button type="button" class="node-link" (click)="selectNode.emit(nd.id)">
                    {{ nd.name }}
                  </button>
                }
              </div>
            </div>
          }

          @if (a.agentId) {
            <div class="coherence">
              <button
                nz-button
                nzSize="small"
                type="button"
                [nzLoading]="coherence()[a.key]?.loading"
                (click)="checkCoherence(a)"
                data-coherence-check
              >
                <nz-icon nzType="audit" />
                <span i18n="@@workflows.audit.checkCoherence">Check coherence</span>
              </button>
              @if (coherence()[a.key]; as c) {
                @if (c.error) {
                  <p class="danger small" role="alert">{{ c.error }}</p>
                }
                @if (c.report; as rep) {
                  <div class="c-meta muted small" data-coherence-meta>
                    @if (rep.model) {
                      <span>{{ rep.model }}</span>
                    }
                    @if (rep.cost) {
                      <span>{{ rep.cost | money }}</span>
                    }
                  </div>
                  @for (cf of c.findings; track $index) {
                    <div class="c-finding" [attr.data-coherence]="cf.severity">
                      <nz-tag [nzColor]="severityColor(cf.severity)">{{
                        severityLabel(cf.severity)
                      }}</nz-tag>
                      <span class="c-summary">{{ cf.summary }}</span>
                      @for (q of cf.quotes ?? []; track $index) {
                        <blockquote>
                          <span class="mono src">{{ q.source }}</span>
                          {{ q.text }}
                        </blockquote>
                      }
                      @if (cf.suggestion) {
                        <p class="suggestion">
                          <span class="label" i18n="@@workflows.audit.coherence.suggestion"
                            >Suggestion:</span
                          >
                          {{ cf.suggestion }}
                        </p>
                      }
                    </div>
                  } @empty {
                    <p class="ok small" i18n="@@workflows.audit.coherence.none">
                      No contradictions found.
                    </p>
                  }
                }
              }
            </div>
          }
        </section>
      } @empty {
        @if (!loading()) {
          <p class="muted small" i18n="@@workflows.audit.empty">
            This workflow has no task steps yet.
          </p>
        }
      }
    </div>
  `,
  styles: [
    `
      .audit {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .intro {
        display: flex;
        gap: 6px;
        align-items: center;
      }
      .agent {
        border: 1px solid var(--bo-line);
        border-radius: 6px;
        padding: 10px 12px;
        display: flex;
        flex-direction: column;
        gap: 6px;
        background: var(--bo-surface);
      }
      .a-head {
        display: flex;
        gap: 8px;
        align-items: center;
      }
      .a-name {
        font-weight: 600;
      }
      .spacer {
        flex: 1;
      }
      .count {
        min-width: 18px;
        padding: 0 5px;
        border-radius: 9px;
        font-size: 11px;
        text-align: center;
        color: #fff;
        background: var(--bo-blocked-ink);
      }
      .row {
        display: grid;
        grid-template-columns: 96px minmax(0, 1fr);
        gap: 8px;
        align-items: baseline;
        font-size: 12px;
      }
      .label {
        color: var(--bo-ink-3);
        font-size: 12px;
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      .node-link {
        border: 0;
        background: none;
        padding: 0 4px 0 0;
        color: var(--bo-accent);
        cursor: pointer;
        font-size: 12px;
        text-decoration: underline dotted;
      }
      .required {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .required li {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        align-items: center;
      }
      .required li.missing .r-name {
        color: var(--bo-you-ink);
        font-weight: 600;
      }
      .finding {
        display: flex;
        gap: 8px;
        font-size: 12px;
        padding: 6px 8px;
        border-radius: 4px;
        background: var(--bo-surface-2);
      }
      .finding[data-sev='ERROR'] nz-icon {
        color: var(--bo-blocked-ink);
      }
      .finding[data-sev='WARNING'] nz-icon {
        color: var(--bo-you-ink);
      }
      .f-title {
        font-weight: 600;
      }
      .f-msg {
        color: var(--bo-ink-2);
      }
      .coherence {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin-top: 4px;
      }
      .c-meta {
        display: flex;
        gap: 8px;
      }
      .c-finding {
        display: flex;
        flex-direction: column;
        gap: 4px;
        font-size: 12px;
        border-left: 2px solid var(--bo-line-strong);
        padding-left: 8px;
      }
      blockquote {
        margin: 0;
        padding: 4px 8px;
        background: var(--bo-surface-2);
        border-radius: 4px;
        white-space: pre-wrap;
      }
      .src {
        display: block;
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .suggestion {
        margin: 0;
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
      }
      .danger {
        color: var(--bo-blocked-ink);
        margin: 0;
      }
      .ok {
        color: var(--bo-done-ink, var(--bo-ink-2));
        margin: 0;
      }
    `,
  ],
})
export class AgentAuditPanelComponent {
  private readonly library = inject(TaskLibraryApi);
  private readonly confirm = inject(ConfirmService);
  private readonly message = inject(NzMessageService);
  private readonly identity = inject(IdentityService);

  readonly projectId = input<string | null>(null);
  /** The audit of the draft (loaded by the page); null while loading for the first time. */
  readonly audit = input<AgentAudit | null>(null);
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  /** The draft's nodes, to name steps. */
  readonly nodes = input<readonly WorkflowNode[]>([]);
  /** The agents of the project (global plus the project's), for "Add to agent". */
  readonly agents = input<readonly Agent[]>([]);
  readonly readonly = input(false);

  /** A step was clicked: the page selects the node. */
  readonly selectNode = output<string>();
  /** An agent changed: the page reloads the agents, the palette and the audit. */
  readonly changed = output<void>();

  readonly adding = signal<string | null>(null);
  readonly coherence = signal<Record<string, CoherenceState>>({});

  readonly text = {
    help: $localize`:@@workflows.audit.help:Per agent the steps of this draft use: its rules and skills, the skills its steps require and which are missing, and problems found without a model. Fix them before a run.`,
    global: $localize`:@@workflows.audit.scope.global:Global`,
    project: $localize`:@@workflows.audit.scope.project:This project`,
  };

  readonly view = computed(() =>
    auditView(this.audit(), new Map(this.nodes().map((n) => [n.id, n.name || n.id]))),
  );
  /** "Add to agent" needs `library.project.manage` on the project (a copy or a project agent). */
  readonly canChange = computed(() => {
    const projectId = this.projectId();
    return (
      !this.readonly() &&
      !!projectId &&
      this.identity.canInProject('library.project.manage', projectId)
    );
  });

  severityLabel = coherenceSeverityLabel;

  severityColor(severity: CoherenceFinding['severity']): string {
    return severity === 'CONTRADICTION' ? 'red' : severity === 'AMBIGUITY' ? 'orange' : 'default';
  }

  /** ADR 0061: adds a missing skill to the agent; a global agent is copied into the project first. */
  async addSkill(a: AuditAgentView, skillKey: string, skillName: string): Promise<void> {
    const projectId = this.projectId();
    if (!projectId) return;
    const agent = this.agents().find((x) => x.id === a.agentId) ?? null;
    const plan = addSkillPlan(agent, skillKey, projectId, agentDraft, (x, p) =>
      customizeAgentForProject(x, p),
    );
    if (plan.kind === 'none') return;
    if (plan.kind === 'copy') {
      const ok = await this.confirm.confirm({
        title: $localize`:@@workflows.audit.copyConfirm.title:Copy ${a.name}:agent: into this project?`,
        content: $localize`:@@workflows.audit.copyConfirm.content:${a.name}:agent: is a global agent. This project gets its own copy with the same key and the skill ${skillName}:skill:; it replaces the global agent in this project's steps, other projects keep theirs.`,
        okText: $localize`:@@workflows.audit.copyConfirm.ok:Copy and add`,
      });
      if (!ok) return;
    }
    const request: Observable<Agent> =
      plan.kind === 'copy'
        ? this.library.createAgent(plan.input)
        : this.library.updateAgent(plan.agentId, plan.input);
    this.adding.set(`${a.key}:${skillKey}`);
    request.subscribe({
      next: () => {
        this.adding.set(null);
        this.message.success(
          $localize`:@@workflows.audit.added:Skill ${skillName}:skill: added to ${a.name}:agent:`,
        );
        this.changed.emit();
      },
      error: () => this.adding.set(null),
    });
  }

  /** ADR 0061: one model call on request; the result stays under the agent. */
  async checkCoherence(a: AuditAgentView): Promise<void> {
    if (!a.agentId) return;
    const ok = await this.confirm.confirm({
      title: $localize`:@@workflows.audit.coherenceConfirm.title:Check the coherence of ${a.name}:agent:?`,
      content: $localize`:@@workflows.audit.coherenceConfirm.content:A model reads the agent's whole environment (constitution, rules, skills, instructions and the repository's AGENTS.md / CLAUDE.md) and lists contradictions. This costs one model call, booked as usage.`,
      okText: $localize`:@@workflows.audit.checkCoherence:Check coherence`,
    });
    if (!ok) return;
    const key = a.key;
    this.patchCoherence(key, { loading: true, error: null });
    this.library.checkAgentCoherence(a.agentId, this.projectId()).subscribe({
      next: (report) =>
        this.patchCoherence(key, {
          loading: false,
          report,
          findings: sortCoherenceFindings(report.findings ?? []),
        }),
      error: (e: unknown) =>
        this.patchCoherence(key, { loading: false, report: null, error: coherenceError(e) }),
    });
  }

  private patchCoherence(key: string, change: Partial<CoherenceState>): void {
    this.coherence.update((all) => ({
      ...all,
      [key]: {
        ...{ loading: false, report: null, findings: [], error: null },
        ...all[key],
        ...change,
      },
    }));
  }
}

/** The message of a failed coherence check; 409 = no model is configured for it. */
export function coherenceError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409)
    return $localize`:@@workflows.audit.coherence.noModel:No model is set for model answers: an administrator sets the platform default under Models, then try again.`;
  const detail =
    e instanceof HttpErrorResponse ? ((e.error as Problem | null)?.detail ?? e.message) : '';
  return detail
    ? $localize`:@@workflows.audit.coherence.failedDetail:The check failed: ${detail}:detail:`
    : $localize`:@@workflows.audit.coherence.failed:The check failed.`;
}
