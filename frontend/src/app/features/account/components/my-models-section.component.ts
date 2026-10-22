import {
  ChangeDetectionStrategy,
  Component,
  type OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { BenchmarksApi } from '../../../core/api/benchmarks.service';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import type {
  ExecutionTarget,
  ModelDefaults,
  ModelOption,
  StepKind,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { icu } from '../../../core/i18n/icu';
import { routeLabel } from '../../../core/ui/model-options';
import { harnessName, personalRunnerStatus, splitModelOptions } from '../data/my-models';

interface KindRow {
  stepKind: StepKind;
  label: string;
}

/**
 * Account → My models (ADR 0050 step 3), read-only: what the user can use right now. The personal runner as the
 * execution targets report it (online, own login, Claude login reported, coding agents), the user's and the
 * platform's default models, and the models LLM steps and Claude Code steps of private work may use.
 */
@Component({
  selector: 'app-my-models-section',
  imports: [RouterLink, NzTagModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="intro" i18n="@@account.models.intro">
      What your private projects and personal benchmarks can use right now.
    </p>

    <h3 class="sub" i18n="@@account.models.runner">Personal runner</h3>
    @if (!canSeeRunner) {
      <p class="muted small" i18n="@@account.models.runnerNotAllowed">
        Your role cannot see execution targets (permission evaluation.view).
      </p>
    } @else if (runner(); as r) {
      <dl class="facts">
        <div>
          <dt i18n="@@account.models.runnerStatus">Status</dt>
          <dd>
            @if (r.setUp) {
              <span class="dot" [class.on]="r.online > 0" aria-hidden="true"></span>
              {{ onlineText(r.online) }}
            } @else {
              <span class="muted" i18n="@@account.models.runnerNotSetUp">not set up</span>
            }
            @if (canManageRunner) {
              <a
                class="more"
                [routerLink]="[]"
                [queryParams]="{ section: 'runner' }"
                queryParamsHandling="merge"
                i18n="@@account.models.runnerManage"
                >Manage under My computer as a runner</a
              >
            }
          </dd>
        </div>
        @if (r.setUp) {
          <div>
            <dt i18n="@@account.models.ownLogin">Claude Code uses</dt>
            <dd>
              @if (r.localLogin) {
                <span i18n="@@account.models.ownLoginYes">my own Claude login</span>
              } @else {
                <span i18n="@@account.models.ownLoginNo">the Back Office LLM gateway</span>
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@account.models.loginReported">Claude login</dt>
            <dd>
              @switch (r.login) {
                @case ('reported') {
                  <span class="ok" i18n="@@account.models.loginYes">reported by the runner</span>
                }
                @case ('none') {
                  <span class="warn" i18n="@@account.models.loginNo">none reported</span>
                  <span class="hint" i18n="@@account.models.loginNoHint"
                    >Start it with -e CLAUDE_CODE_OAUTH_TOKEN (create the token with claude
                    setup-token); until then the "my login" models cannot run.</span
                  >
                }
                @default {
                  <span class="muted" i18n="@@account.models.loginUnknown">not reported</span>
                }
              }
            </dd>
          </div>
          <div>
            <dt i18n="@@account.models.harnesses">Coding agents</dt>
            <dd>
              @for (h of r.harnesses; track h) {
                <span class="chip">{{ harness(h) }}</span>
              } @empty {
                <span class="muted" i18n="@@account.models.harnessesNone">none reported</span>
              }
            </dd>
          </div>
        }
      </dl>
    } @else if (targetsFailed()) {
      <p class="muted small" i18n="@@account.models.runnerFailed">
        The runner status could not be loaded.
      </p>
    } @else {
      <div class="ph" aria-hidden="true"></div>
    }

    @if (!canSeeModels) {
      <p class="muted small" i18n="@@account.models.modelsNotAllowed">
        Your role cannot see models (permission models.view).
      </p>
    } @else {
      <h3 class="sub" i18n="@@account.models.defaults">My default models</h3>
      <div
        class="defaults"
        role="table"
        aria-label="My default models"
        i18n-aria-label="@@account.models.defaults"
      >
        <div class="drow head" role="row">
          <span role="columnheader" i18n="@@account.models.kind">Kind of step</span>
          <span role="columnheader" i18n="@@account.models.mine">My default</span>
          <span role="columnheader" i18n="@@account.models.platform">Platform default</span>
        </div>
        @for (k of kinds; track k.stepKind) {
          <div class="drow" role="row">
            <span role="rowheader">{{ k.label }}</span>
            <span role="cell" [class.muted]="!mine(k.stepKind)">{{
              mine(k.stepKind) || text.platformApplies
            }}</span>
            <span role="cell" [class.muted]="!platform(k.stepKind)">{{
              platform(k.stepKind) || text.notSet
            }}</span>
          </div>
        }
      </div>
      <p class="small">
        <a routerLink="/models" i18n="@@account.models.change"
          >Change them on the Models page (Default models)</a
        >
      </p>

      <h3 class="sub" i18n="@@account.models.usable">Models I can use</h3>
      <div class="lists">
        @for (k of kinds; track k.stepKind) {
          <div class="list">
            <h4>
              {{ k.label }}
              @if (k.stepKind === 'CODING_AGENT') {
                <span class="muted" i18n="@@account.models.withClaudeCode">(Claude Code)</span>
              }
            </h4>
            @if (split()[k.stepKind]; as s) {
              <ul>
                @for (o of s.usable; track o.profileId) {
                  <li>
                    <span class="name" [title]="o.modelId">{{ o.displayName }}</span>
                    <nz-tag [nzColor]="o.route === 'OWN_LOGIN' ? 'purple' : 'default'">{{
                      route(o)
                    }}</nz-tag>
                    @if (o.personal) {
                      <span class="muted tiny" i18n="@@account.models.personal">personal</span>
                    }
                    @if (o.note) {
                      <div class="note-warn">{{ o.note }}</div>
                    }
                  </li>
                } @empty {
                  <li class="muted" i18n="@@account.models.noneUsable">None right now.</li>
                }
              </ul>
              @if (s.unusable.length) {
                <details>
                  <summary>{{ unusableText(s.unusable.length) }}</summary>
                  <ul class="unusable">
                    @for (o of s.unusable; track o.profileId) {
                      <li>
                        <span class="name">{{ o.displayName }}</span>
                        <nz-tag>{{ route(o) }}</nz-tag>
                        @if (o.problem) {
                          <div class="problem">{{ o.problem }}</div>
                        }
                      </li>
                    }
                  </ul>
                </details>
              }
            } @else if (optionsFailed()) {
              <p class="muted small" i18n="@@account.models.optionsFailed">
                The models could not be loaded.
              </p>
            } @else {
              <div class="ph" aria-hidden="true"></div>
            }
          </div>
        }
      </div>
    }
  `,
  styles: [
    `
      .intro {
        margin: 0 0 12px;
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .sub {
        margin: 16px 0 8px;
        font-size: var(--bo-fs-dense);
        font-weight: 600;
      }
      .intro + .sub {
        margin-top: 0;
      }
      .facts {
        margin: 0;
        display: grid;
        gap: 4px;
        font-size: 13px;
      }
      .facts > div {
        display: grid;
        grid-template-columns: 160px minmax(0, 1fr);
        gap: 12px;
      }
      dt {
        color: var(--bo-ink-3);
        font-size: var(--bo-fs-dense);
      }
      dd {
        margin: 0;
      }
      .dot {
        display: inline-block;
        width: 8px;
        height: 8px;
        margin-right: 4px;
        border-radius: 50%;
        background: var(--bo-ink-3);
      }
      .dot.on {
        background: var(--bo-done);
      }
      .more {
        margin-left: 12px;
        font-size: 12px;
      }
      .ok {
        color: var(--bo-done-ink);
      }
      .warn {
        color: var(--bo-you-ink);
        font-weight: 500;
      }
      .hint {
        display: block;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .chip {
        display: inline-block;
        margin: 1px 6px 1px 0;
        padding: 0 8px;
        border-radius: var(--bo-radius-chip);
        border: 1px solid var(--bo-line);
        background: var(--bo-surface-2);
        font-size: 12px;
        line-height: 20px;
        color: var(--bo-ink-2);
      }
      .defaults {
        font-size: 13px;
      }
      .drow {
        display: grid;
        grid-template-columns: 160px minmax(0, 1fr) minmax(0, 1fr);
        gap: 12px;
        padding: 3px 0;
      }
      .drow.head {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .lists {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 16px;
      }
      h4 {
        margin: 0 0 4px;
        font-size: 13px;
        font-weight: 500;
      }
      ul {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 4px;
        font-size: 13px;
      }
      li nz-tag {
        margin-left: 6px;
      }
      .name {
        font-weight: 500;
      }
      .note-warn {
        font-size: 12px;
        color: var(--bo-you-ink);
      }
      details {
        margin-top: 6px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      summary {
        cursor: pointer;
      }
      .unusable {
        margin-top: 4px;
        color: var(--bo-ink-3);
      }
      .unusable .name {
        font-weight: 400;
      }
      .problem {
        font-size: 12px;
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
        margin: 6px 0 0;
      }
      .tiny {
        font-size: 11px;
        margin-left: 4px;
      }
      .ph {
        height: 48px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
      }
      @media (max-width: 720px) {
        .facts > div,
        .drow {
          grid-template-columns: minmax(0, 1fr);
          gap: 2px;
        }
        .drow.head {
          display: none;
        }
        .lists {
          grid-template-columns: minmax(0, 1fr);
        }
      }
    `,
  ],
})
export class MyModelsSectionComponent implements OnInit {
  private readonly identity = inject(IdentityService);
  private readonly benchmarks = inject(BenchmarksApi);
  private readonly models = inject(ModelProfilesApi);

  readonly canSeeRunner = this.identity.can('evaluation.view');
  readonly canManageRunner = this.identity.can('fleet.personal');
  readonly canSeeModels = this.identity.can('models.view');

  readonly kinds: KindRow[] = [
    { stepKind: 'LLM_CALL', label: $localize`:@@account.models.llm:LLM steps` },
    { stepKind: 'CODING_AGENT', label: $localize`:@@account.models.coding:Coding steps` },
  ];
  readonly text = {
    notSet: $localize`:@@account.models.notSet:not set`,
    platformApplies: $localize`:@@account.models.platformApplies:none (the platform default applies)`,
  };

  private readonly targets = signal<ExecutionTarget[] | null>(null);
  readonly targetsFailed = signal(false);
  readonly defaults = signal<ModelDefaults | null>(null);
  private readonly options = signal<Partial<Record<StepKind, ModelOption[]>>>({});
  readonly optionsFailed = signal(false);

  readonly runner = computed(() => personalRunnerStatus(this.targets()));
  readonly split = computed(() => {
    const o = this.options();
    return {
      LLM_CALL: o.LLM_CALL ? splitModelOptions(o.LLM_CALL) : null,
      CODING_AGENT: o.CODING_AGENT ? splitModelOptions(o.CODING_AGENT) : null,
    } as Record<StepKind, ReturnType<typeof splitModelOptions> | null>;
  });

  readonly route = (o: ModelOption) => routeLabel(o.route);
  readonly harness = harnessName;

  ngOnInit(): void {
    if (this.canSeeRunner) {
      this.benchmarks.executionTargets().subscribe({
        next: (t) => this.targets.set(t ?? []),
        error: () => this.targetsFailed.set(true),
      });
    }
    if (this.canSeeModels) {
      this.models.defaults().subscribe({
        next: (d) => this.defaults.set(d),
        error: () => undefined,
      });
      this.models.defaultOptions('LLM_CALL', 'PERSONAL').subscribe({
        next: (list) => this.options.update((o) => ({ ...o, LLM_CALL: list ?? [] })),
        error: () => this.optionsFailed.set(true),
      });
      this.models.defaultOptions('CODING_AGENT', 'PERSONAL', 'CLAUDE_CODE').subscribe({
        next: (list) => this.options.update((o) => ({ ...o, CODING_AGENT: list ?? [] })),
        error: () => this.optionsFailed.set(true),
      });
    }
  }

  mine(kind: StepKind): string | null {
    return this.defaults()?.mine?.[kind]?.displayName ?? null;
  }

  platform(kind: StepKind): string | null {
    return this.defaults()?.platform?.[kind]?.displayName ?? null;
  }

  onlineText(count: number): string {
    return icu(
      $localize`:@@account.models.online:{count, plural, =0 {set up, no computer online} one {# computer online} other {# computers online}}`,
      { count },
    );
  }

  unusableText(count: number): string {
    return icu(
      $localize`:@@account.models.unusable:{count, plural, one {# model not usable now} other {# models not usable now}}`,
      { count },
    );
  }
}
