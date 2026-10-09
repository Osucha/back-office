import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import {
  SANDBOX_CACHES,
  type SandboxCache,
  type SandboxIsolation,
  type SandboxPolicy,
} from '../../../core/api/types';
import {
  ISOLATION_OPTIONS,
  ISOLATION_RANK,
  SOURCE_LABELS,
  compactPolicy,
  coveringPattern,
  formatLabels,
  formatSeconds,
  globMatches,
  hostPatternError,
  isolationName,
  narrowingIssues,
  parseLabels,
  sourceOf,
  toolAllowedBy,
  type PolicyIssue,
} from '../data/policy';
import { formatNumber } from '../../../core/i18n/numbers';
import { ChipListComponent } from './chip-list.component';

type Section =
  'isolation' | 'resources' | 'network' | 'tools' | 'secrets' | 'environment' | 'caps' | 'pool';

let nextId = 0;

/**
 * Sandbox policy editor (docs/architecture/10-agent-execution.md): isolation, resources, timeout,
 * network allowlist, tools, secret names, environment, cost and token caps and the pool selector.
 * It shows the `ceiling` (the level above) next to every field and marks what cannot be widened;
 * the rules are the pure functions of `data/policy.ts` (the backend applies the same ones).
 *
 *   <app-sandbox-policy-editor [policy]="p" [ceiling]="c" [allowedImages]="imgs"
 *        level="project" (policyChange)="p = $event" (issuesChange)="issues = $event" />
 *
 * Unset fields inherit the ceiling; the emitted policy is compacted (`compactPolicy`), so an
 * untouched editor emits `{}`.
 */
@Component({
  selector: 'app-sandbox-policy-editor',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    NzIconModule,
    NzInputModule,
    NzInputNumberModule,
    ChipListComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let d = draft();
    @let c = ceil();
    <div class="editor" [class.readonly]="readonly()">
      @if (hasCeiling()) {
        <p class="lede">
          <nz-icon nzType="lock" aria-hidden="true" />
          <ng-container i18n="@@fleet.policyEditor.lede"
            >{{ ceilingLabel() }} sets the ceiling. Values here can only narrow it; locked chips and
            "at most" hints come from there, and empty fields inherit it.</ng-container
          >
        </p>
      }

      @if (show('isolation')) {
        <section class="sec" [attr.aria-labelledby]="id + '-iso'">
          <header>
            <h3 [id]="id + '-iso'" i18n="@@fleet.policyEditor.isolation">Isolation</h3>
            <ng-container *ngTemplateOutlet="src; context: { $implicit: 'isolation' }" />
          </header>
          <div class="iso" role="radiogroup" [attr.aria-labelledby]="id + '-iso'">
            @for (o of isolations; track o.value) {
              @let blocked = weaker(o.value);
              <label
                class="iso-card"
                [class.on]="d.isolation === o.value"
                [class.inherited]="!d.isolation && c.isolation === o.value"
                [class.blocked]="blocked"
              >
                <input
                  type="radio"
                  [name]="id + '-isolation'"
                  [value]="o.value"
                  [checked]="d.isolation === o.value"
                  [disabled]="readonly() || blocked"
                  (change)="patch({ isolation: o.value })"
                />
                <span class="iso-head">
                  <span class="iso-name">{{ o.label }}</span>
                  <span class="iso-rt">{{ o.runtime }}</span>
                  @if (blocked) {
                    <span class="lock-note"
                      ><nz-icon nzType="lock" />
                      <ng-container i18n="@@fleet.policyEditor.belowCeiling"
                        >below the ceiling</ng-container
                      ></span
                    >
                  } @else if (!d.isolation && c.isolation === o.value) {
                    <span class="inherit-note" i18n="@@fleet.policyEditor.inherited"
                      >inherited</span
                    >
                  }
                </span>
                <span class="iso-text">{{ o.explanation }}</span>
              </label>
            }
          </div>
          @if (d.isolation && !readonly()) {
            <button type="button" class="link" (click)="patch({ isolation: undefined })">
              @if (c.isolation) {
                <ng-container i18n="@@fleet.policyEditor.inheritLevel"
                  >Inherit {{ isolationName(c.isolation) }}</ng-container
                >
              } @else {
                <ng-container i18n="@@fleet.policyEditor.inheritDefault"
                  >Inherit the default</ng-container
                >
              }
            </button>
          }
          <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'isolation' }" />
        </section>
      }

      @if (show('resources')) {
        <section class="sec" [attr.aria-labelledby]="id + '-res'">
          <header>
            <h3 [id]="id + '-res'" i18n="@@fleet.policyEditor.resources">Resources and time</h3>
            <ng-container *ngTemplateOutlet="src; context: { $implicit: 'resources' }" />
          </header>
          <div class="grid4">
            <div class="field">
              <label [for]="id + '-cpu'" i18n="@@fleet.policyEditor.cpu">CPU</label>
              <input
                nz-input
                [id]="id + '-cpu'"
                class="mono"
                [placeholder]="c.resources?.cpu ?? '2'"
                [ngModel]="d.resources?.cpu ?? ''"
                (ngModelChange)="patchRes('cpu', $event)"
                [disabled]="readonly()"
                [attr.aria-describedby]="id + '-cpu-h'"
              />
              <span class="hint" [id]="id + '-cpu-h'">{{
                maxHint(c.resources?.cpu, text.cpuHint)
              }}</span>
              <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'resources.cpu' }" />
            </div>
            <div class="field">
              <label [for]="id + '-mem'" i18n="@@fleet.policyEditor.memory">Memory</label>
              <input
                nz-input
                [id]="id + '-mem'"
                class="mono"
                [placeholder]="c.resources?.memory ?? '4Gi'"
                [ngModel]="d.resources?.memory ?? ''"
                (ngModelChange)="patchRes('memory', $event)"
                [disabled]="readonly()"
                [attr.aria-describedby]="id + '-mem-h'"
              />
              <span class="hint" [id]="id + '-mem-h'">{{
                maxHint(c.resources?.memory, text.memoryHint)
              }}</span>
              <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'resources.memory' }" />
            </div>
            <div class="field">
              <label [for]="id + '-disk'" i18n="@@fleet.policyEditor.disk">Ephemeral storage</label>
              <input
                nz-input
                [id]="id + '-disk'"
                class="mono"
                [placeholder]="c.resources?.ephemeralStorage ?? '10Gi'"
                [ngModel]="d.resources?.ephemeralStorage ?? ''"
                (ngModelChange)="patchRes('ephemeralStorage', $event)"
                [disabled]="readonly()"
                [attr.aria-describedby]="id + '-disk-h'"
              />
              <span class="hint" [id]="id + '-disk-h'">{{
                maxHint(c.resources?.ephemeralStorage, text.diskHint)
              }}</span>
              <ng-container
                *ngTemplateOutlet="errs; context: { $implicit: 'resources.ephemeralStorage' }"
              />
            </div>
            <div class="field">
              <label [for]="id + '-pids'" i18n="@@fleet.policyEditor.pids">Processes</label>
              <nz-input-number
                [nzId]="id + '-pids'"
                [nzMin]="1"
                [nzStep]="256"
                [nzPlaceHolder]="c.resources?.pids ? '' + c.resources?.pids : '2048'"
                [ngModel]="d.resources?.pids ?? null"
                (ngModelChange)="patchRes('pids', $event)"
                [nzDisabled]="readonly()"
              />
              <span class="hint">{{ maxHint(c.resources?.pids, text.pidsHint) }}</span>
              <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'resources.pids' }" />
            </div>
            <div class="field">
              <label [for]="id + '-timeout'" i18n="@@fleet.policyEditor.timeout"
                >Timeout (minutes)</label
              >
              <nz-input-number
                [nzId]="id + '-timeout'"
                [nzMin]="1"
                [nzStep]="5"
                [nzPlaceHolder]="c.timeoutSeconds ? '' + c.timeoutSeconds / 60 : '60'"
                [ngModel]="d.timeoutSeconds ? d.timeoutSeconds / 60 : null"
                (ngModelChange)="
                  patch({ timeoutSeconds: $event ? Math.round($event * 60) : undefined })
                "
                [nzDisabled]="readonly()"
              />
              <span class="hint">{{
                c.timeoutSeconds ? maxHint(fmtSeconds(c.timeoutSeconds), '') : text.timeoutHint
              }}</span>
              <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'timeoutSeconds' }" />
            </div>
          </div>
        </section>
      }

      @if (show('network')) {
        <section class="sec" [attr.aria-labelledby]="id + '-net'">
          <header>
            <h3 [id]="id + '-net'" i18n="@@fleet.policyEditor.network">Network allowlist</h3>
            <ng-container *ngTemplateOutlet="src; context: { $implicit: 'network.allow' }" />
          </header>
          <p class="help" i18n="@@fleet.policyEditor.networkHelp">
            Hosts the egress proxy lets the agent reach, as <code>host</code> or
            <code>host:port</code>; <code>*</code> stands for one label. The LLM gateway and the
            project's git host are always added. Everything else is refused and reported.
          </p>
          <app-chip-list
            label="Allowed hosts"
            i18n-label="@@fleet.policyEditor.allowedHosts"
            [placeholder]="examples.hosts"
            [values]="d.network?.allow ?? []"
            [lowercase]="true"
            [validate]="validateHost"
            [invalid]="invalidAt('network.allow')"
            [readonly]="readonly()"
            [lockedHint]="text.lockedHint"
            [emptyText]="c.network?.allow?.length ? text.inheritsCeiling : text.gatewayOnly"
            (valuesChange)="patch({ network: { allow: $event } })"
          />
          @if (c.network?.allow?.length) {
            <div class="ceiling-line">
              <span class="cl-label"
                ><nz-icon nzType="lock" />
                <ng-container i18n="@@fleet.policyEditor.ceilingAllows"
                  >Ceiling allows</ng-container
                ></span
              >
              @for (h of c.network!.allow!; track h) {
                <span class="mini mono">{{ h }}</span>
              }
            </div>
          }
        </section>
      }

      @if (show('tools')) {
        <section class="sec" [attr.aria-labelledby]="id + '-tools'">
          <header>
            <h3 [id]="id + '-tools'" i18n="@@fleet.policyEditor.tools">Tools</h3>
            <ng-container *ngTemplateOutlet="src; context: { $implicit: 'tools' }" />
          </header>
          <p class="help" i18n="@@fleet.policyEditor.toolsHelp">
            Shell command patterns for the agent harness (OpenCode <code>permission</code>, Claude
            Code <code>permissions</code>). Deny always wins; unattended jobs treat "ask" as deny
            and report it.
          </p>
          <div class="grid3">
            <div class="field">
              <span class="label" [id]="id + '-allow'" i18n="@@fleet.policyEditor.allow"
                >Allow</span
              >
              <app-chip-list
                label="Allowed tools"
                i18n-label="@@fleet.policyEditor.allowedTools"
                [placeholder]="examples.allow"
                [values]="d.tools?.allow ?? []"
                [validate]="validateTool"
                [invalid]="invalidAt('tools.allow')"
                [readonly]="readonly()"
                [lockedHint]="text.lockedHint"
                [emptyText]="c.tools?.allow?.length ? text.inheritsCeiling : text.anyTool"
                (valuesChange)="patchTools('allow', $event)"
              />
              @if (c.tools?.allow?.length) {
                <span class="hint" i18n="@@fleet.policyEditor.onlyWithin"
                  >Only within: {{ c.tools!.allow!.join(', ') }}</span
                >
              }
            </div>
            <div class="field">
              <span class="label" i18n="@@fleet.policyEditor.deny">Deny</span>
              <app-chip-list
                label="Denied tools"
                i18n-label="@@fleet.policyEditor.deniedTools"
                [placeholder]="examples.deny"
                [values]="ownDeny()"
                [locked]="c.tools?.deny ?? []"
                [lockedHint]="deniedBy()"
                [emptyText]="text.none"
                [readonly]="readonly()"
                (valuesChange)="patchTools('deny', $event)"
              />
            </div>
            <div class="field">
              <span class="label" i18n="@@fleet.policyEditor.ask"
                >Ask (treated as deny when unattended)</span
              >
              <app-chip-list
                label="Tools that ask"
                i18n-label="@@fleet.policyEditor.askTools"
                [placeholder]="examples.ask"
                [values]="ownAsk()"
                [locked]="c.tools?.ask ?? []"
                [lockedHint]="setBy()"
                [emptyText]="text.none"
                [readonly]="readonly()"
                (valuesChange)="patchTools('ask', $event)"
              />
            </div>
          </div>
        </section>
      }

      @if (show('secrets') || show('environment')) {
        <section class="sec" [attr.aria-labelledby]="id + '-env'">
          <header>
            <h3 [id]="id + '-env'" i18n="@@fleet.policyEditor.environment">
              Environment and secrets
            </h3>
            <ng-container *ngTemplateOutlet="src; context: { $implicit: 'environment' }" />
          </header>
          <div class="grid2">
            <div class="field">
              <label [for]="id + '-image'" i18n="@@fleet.policyEditor.image">Runner image</label>
              <input
                nz-input
                [id]="id + '-image'"
                class="mono"
                [placeholder]="
                  c.environment?.image ?? 'ghcr.io/org/back-office-runner:2.0-jdk21-node22'
                "
                [ngModel]="d.environment?.image ?? ''"
                (ngModelChange)="patchEnv({ image: $event })"
                [disabled]="readonly()"
              />
              <span class="hint">{{ imageHint() }}</span>
              <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'environment.image' }" />
            </div>
            <div class="field">
              <label [for]="id + '-setup'" i18n="@@fleet.policyEditor.setup">Setup command</label>
              <input
                nz-input
                [id]="id + '-setup'"
                class="mono"
                [placeholder]="examples.setup"
                [ngModel]="d.environment?.setupCommand ?? ''"
                (ngModelChange)="patchEnv({ setupCommand: $event })"
                [disabled]="readonly()"
              />
              <span class="hint" i18n="@@fleet.policyEditor.setupHint"
                >Runs once in the sandbox before the agent starts.</span
              >
            </div>
          </div>
          <div class="field">
            <span class="label" [id]="id + '-caches'" i18n="@@fleet.policyEditor.caches"
              >Caches</span
            >
            <div class="toggles" role="group" [attr.aria-labelledby]="id + '-caches'">
              @for (x of caches; track x) {
                @let off = !cacheAvailable(x);
                <button
                  type="button"
                  class="toggle"
                  [class.on]="(d.environment?.caches ?? []).includes(x)"
                  [attr.aria-pressed]="(d.environment?.caches ?? []).includes(x)"
                  [disabled]="readonly() || (off && !(d.environment?.caches ?? []).includes(x))"
                  [attr.title]="off ? text.notAvailable : null"
                  (click)="toggleCache(x)"
                >
                  {{ x }}
                </button>
              }
            </div>
            <span class="hint" i18n="@@fleet.policyEditor.cachesHint"
              >Per-pool cache volumes, keyed per project.</span
            >
            <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'environment.caches' }" />
          </div>
          <div class="field">
            <span class="label" i18n="@@fleet.policyEditor.secrets">Secret names</span>
            <app-chip-list
              label="Secret names"
              i18n-label="@@fleet.policyEditor.secretsLabel"
              [placeholder]="examples.secret"
              [values]="d.secrets ?? []"
              [validate]="validateSecret"
              [invalid]="invalidAt('secrets')"
              [readonly]="readonly()"
              [lockedHint]="text.lockedHint"
              [emptyText]="text.none"
              (valuesChange)="patch({ secrets: $event })"
            />
            <span class="hint"
              ><ng-container i18n="@@fleet.policyEditor.secretsHint"
                >Names only: the runner cluster resolves them from its Secret store; Back Office
                never sees the values.</ng-container
              >
              @if (c.secrets?.length) {
                <ng-container i18n="@@fleet.policyEditor.secretsAvailable"
                  >Available: {{ c.secrets!.join(', ') }}.</ng-container
                >
              }
            </span>
          </div>
        </section>
      }

      @if (show('caps')) {
        <section class="sec" [attr.aria-labelledby]="id + '-caps'">
          <header>
            <h3 [id]="id + '-caps'" i18n="@@fleet.policyEditor.caps">
              Cost and token caps per job
            </h3>
            <ng-container *ngTemplateOutlet="src; context: { $implicit: 'maxCostUsd' }" />
          </header>
          <div class="grid4">
            <div class="field">
              <label [for]="id + '-cost'" i18n="@@fleet.policyEditor.maxCost">Max cost (USD)</label>
              <nz-input-number
                [nzId]="id + '-cost'"
                [nzMin]="0.01"
                [nzStep]="0.5"
                [nzPrecision]="2"
                [nzPlaceHolder]="c.maxCostUsd ? '' + c.maxCostUsd : text.noCap"
                [ngModel]="d.maxCostUsd ?? null"
                (ngModelChange)="patch({ maxCostUsd: $event ?? undefined })"
                [nzDisabled]="readonly()"
              />
              <span class="hint">{{
                c.maxCostUsd ? maxHint('$' + fmtNumber(c.maxCostUsd), '') : text.costHint
              }}</span>
              <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'maxCostUsd' }" />
            </div>
            <div class="field">
              <label [for]="id + '-tokens'" i18n="@@fleet.policyEditor.maxTokens">Max tokens</label>
              <nz-input-number
                [nzId]="id + '-tokens'"
                [nzMin]="1000"
                [nzStep]="100000"
                [nzPlaceHolder]="c.maxTokens ? '' + c.maxTokens : text.noCap"
                [ngModel]="d.maxTokens ?? null"
                (ngModelChange)="patch({ maxTokens: $event ?? undefined })"
                [nzDisabled]="readonly()"
              />
              <span class="hint">{{
                c.maxTokens ? maxHint(fmtNumber(c.maxTokens), '') : text.tokensHint
              }}</span>
              <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'maxTokens' }" />
            </div>
          </div>
        </section>
      }

      @if (show('pool')) {
        <section class="sec" [attr.aria-labelledby]="id + '-pool'">
          <header>
            <h3 [id]="id + '-pool'" i18n="@@fleet.policyEditor.poolSelector">Pool selector</h3>
            <ng-container *ngTemplateOutlet="src; context: { $implicit: 'poolSelector' }" />
          </header>
          <div class="field">
            <label [for]="id + '-sel'" i18n="@@fleet.policyEditor.selectorLabel"
              >Labels the runner pool must have</label
            >
            <input
              nz-input
              [id]="id + '-sel'"
              class="mono"
              [placeholder]="examples.selector"
              [ngModel]="selectorText()"
              (ngModelChange)="onSelector($event)"
              [disabled]="readonly()"
            />
            @if (selectorError()) {
              <div class="err" role="alert">{{ selectorError() }}</div>
            } @else {
              @if (c.poolSelector && objectKeys(c.poolSelector).length) {
                <span class="hint" i18n="@@fleet.policyEditor.selectorKept"
                  >Kept from the ceiling: {{ fmtLabels(c.poolSelector) }}</span
                >
              } @else {
                <span class="hint" i18n="@@fleet.policyEditor.selectorEmpty"
                  >Empty: any pool the project may use</span
                >
              }
            }
            <ng-container *ngTemplateOutlet="errs; context: { $implicit: 'poolSelector' }" />
          </div>
        </section>
      }
    </div>

    <ng-template #errs let-path>
      @for (e of issuesAt(path); track e.path + e.message) {
        <div class="err" role="alert">{{ e.message }}</div>
      }
    </ng-template>
    <ng-template #src let-path>
      @if (sources(); as s) {
        @if (sourceOf(s, path); as level) {
          <span class="src" [attr.title]="effectiveSetBy(level)" i18n="@@fleet.policyEditor.setBy"
            >set by {{ sourceLabels[level] || level.toLowerCase() }}</span
          >
        }
      }
    </ng-template>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .lede {
        display: flex;
        gap: 8px;
        align-items: baseline;
        margin: 0 0 16px;
        padding: 8px 12px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .sec {
        padding: 16px 0;
        border-top: 1px solid var(--bo-line);
      }
      .sec:first-of-type {
        border-top: 0;
        padding-top: 0;
      }
      header {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;
      }
      h3 {
        margin: 0;
        font-size: 13px;
        font-weight: 600;
      }
      .src {
        font-size: 11px;
        padding: 0 7px;
        line-height: 18px;
        border-radius: var(--bo-radius-chip);
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        color: var(--bo-ink-2);
      }
      .help {
        margin: 0 0 8px;
        font-size: 12px;
        color: var(--bo-ink-3);
        max-width: 80ch;
      }
      code {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
      }
      .iso {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 8px;
      }
      @media (max-width: 900px) {
        .iso {
          grid-template-columns: 1fr;
        }
      }
      .iso-card {
        position: relative;
        display: flex;
        flex-direction: column;
        gap: 4px;
        padding: 10px 12px 10px 34px;
        border: 1px solid var(--bo-line-strong);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        cursor: pointer;
      }
      .iso-card input {
        position: absolute;
        left: 12px;
        top: 13px;
        accent-color: var(--bo-ink);
      }
      .iso-card.on {
        border-color: var(--bo-ink);
        box-shadow: inset 0 0 0 1px var(--bo-ink);
      }
      .iso-card.inherited {
        border-style: dashed;
      }
      .iso-card.blocked {
        cursor: not-allowed;
        background: var(--bo-surface-2);
      }
      .iso-card.blocked .iso-name,
      .iso-card.blocked .iso-text {
        color: var(--bo-ink-3);
      }
      .iso-card:focus-within {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .iso-head {
        display: flex;
        align-items: baseline;
        gap: 8px;
        flex-wrap: wrap;
      }
      .iso-name {
        font-weight: 600;
        font-size: 13px;
      }
      .iso-rt {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
        color: var(--bo-ink-2);
      }
      .iso-text {
        font-size: 12px;
        color: var(--bo-ink-2);
        line-height: 1.4;
      }
      .lock-note,
      .inherit-note {
        margin-left: auto;
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .grid2,
      .grid3,
      .grid4 {
        display: grid;
        gap: 12px 16px;
        margin-bottom: 12px;
      }
      .grid2 {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
      .grid3 {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
      .grid4 {
        grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
      }
      @media (max-width: 900px) {
        .grid2,
        .grid3 {
          grid-template-columns: 1fr;
        }
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        min-width: 0;
      }
      .field label,
      .field .label {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .field nz-input-number {
        width: 100%;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .hint {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .err {
        font-size: 12px;
        color: var(--bo-blocked-ink);
      }
      .ceiling-line {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        align-items: center;
        margin-top: 8px;
      }
      .cl-label {
        font-size: 11.5px;
        color: var(--bo-ink-3);
        margin-right: 4px;
      }
      .mini {
        font-size: 11px;
        padding: 0 6px;
        line-height: 18px;
        border-radius: var(--bo-radius-chip);
        background: var(--bo-surface-2);
        box-shadow: inset 0 0 0 1px var(--bo-line);
        color: var(--bo-ink-2);
      }
      .toggles {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      .toggle {
        height: 26px;
        padding: 0 10px;
        border-radius: var(--bo-radius-chip);
        border: 1px solid var(--bo-line-strong);
        background: var(--bo-surface);
        color: var(--bo-ink-2);
        font-family: var(--bo-font-mono);
        font-size: 12px;
        cursor: pointer;
      }
      .toggle.on {
        background: var(--bo-ink);
        border-color: var(--bo-ink);
        color: var(--bo-on-ink);
      }
      .toggle:disabled {
        cursor: not-allowed;
        opacity: 0.55;
      }
      .toggle:focus-visible,
      .link:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .link {
        margin-top: 8px;
        padding: 0;
        border: 0;
        background: none;
        color: var(--bo-accent);
        font-size: 12px;
        cursor: pointer;
      }
    `,
  ],
})
export class SandboxPolicyEditorComponent {
  /** The policy at this level (unset fields inherit the ceiling). */
  readonly policy = input<SandboxPolicy | null | undefined>(null);
  /** The level above; nothing here may widen it. */
  readonly ceiling = input<SandboxPolicy | null | undefined>(null);
  /** Who sets the ceiling, for the explanations ("The pool", "The platform"). */
  readonly ceilingLabel = input($localize`:@@fleet.policyEditor.levelAbove:The level above`);
  /** Allowed image patterns of the pool (checked against `environment.image`). */
  readonly allowedImages = input<readonly string[] | null>(null);
  /** `SandboxPolicyView.sources`: shows "set by pool" badges next to the sections. */
  readonly sources = input<Record<string, string> | null>(null);
  readonly readonly = input(false);
  /** Sections to show (default all). */
  readonly sections = input<readonly Section[] | null>(null);
  /** Field errors from the server (`path` as in `PolicyIssue`), shown with the local ones. */
  readonly serverIssues = input<readonly PolicyIssue[]>([]);

  readonly policyChange = output<SandboxPolicy>();
  readonly issuesChange = output<PolicyIssue[]>();

  readonly id = `bo-policy-${nextId++}`;
  readonly isolations = ISOLATION_OPTIONS;
  readonly caches = SANDBOX_CACHES;
  readonly sourceLabels = SOURCE_LABELS;
  readonly isolationName = isolationName;
  readonly fmtSeconds = formatSeconds;
  readonly fmtLabels = formatLabels;
  readonly sourceOf = sourceOf;
  readonly objectKeys = Object.keys;
  readonly Math = Math;
  readonly fmtNumber = (n: number) => formatNumber(n);
  /** Sample values (code, the same in every language). */
  readonly examples = {
    hosts: 'registry.npmjs.org, *.gradle.org', // i18n-ignore: sample hosts
    allow: 'git *', // i18n-ignore: command pattern
    deny: 'curl *', // i18n-ignore: command pattern
    ask: 'rm -rf *', // i18n-ignore: command pattern
    setup: './gradlew --offline help || true', // i18n-ignore: command
    secret: 'npm-read-token', // i18n-ignore: secret name
    selector: 'region=eu, gpu=no', // i18n-ignore: labels
  };
  readonly text = {
    cpuHint: $localize`:@@fleet.policyEditor.cpuHint:cores or 500m`,
    memoryHint: $localize`:@@fleet.policyEditor.memoryHint:e.g. 4Gi`,
    diskHint: $localize`:@@fleet.policyEditor.diskHint:e.g. 10Gi`,
    pidsHint: $localize`:@@fleet.policyEditor.pidsHint:pids limit`,
    timeoutHint: $localize`:@@fleet.policyEditor.timeoutHint:Deadline of the job`,
    inheritsCeiling: $localize`:@@fleet.policyEditor.inheritsCeiling:Inherits the ceiling`,
    gatewayOnly: $localize`:@@fleet.policyEditor.gatewayOnly:Only the gateway and git host`,
    anyTool: $localize`:@@fleet.policyEditor.anyTool:Any tool not denied`,
    none: $localize`:@@fleet.policyEditor.none:None`,
    lockedHint: $localize`:@@fleet.policyEditor.lockedHint:Set by the level above; cannot be removed here`,
    notAvailable: $localize`:@@fleet.policyEditor.notAvailable:Not available at this level`,
    noCap: $localize`:@@fleet.policyEditor.noCap:no cap`,
    costHint: $localize`:@@fleet.policyEditor.costHint:Enforced at the LLM gateway`,
    tokensHint: $localize`:@@fleet.policyEditor.tokensHint:Input + output`,
  };
  readonly deniedBy = computed(() => {
    const level = this.ceilingLabel().toLowerCase();
    return $localize`:@@fleet.policyEditor.deniedBy:Denied by ${level}:level:`;
  });
  readonly setBy = computed(() => {
    const level = this.ceilingLabel().toLowerCase();
    return $localize`:@@fleet.policyEditor.setByLevel:Set by ${level}:level:`;
  });

  readonly draft = signal<SandboxPolicy>({});
  readonly selectorText = signal('');
  readonly selectorError = signal<string | null>(null);

  readonly ceil = computed<SandboxPolicy>(() => this.ceiling() ?? {});
  readonly hasCeiling = computed(() => Object.keys(this.ceiling() ?? {}).length > 0);
  readonly issues = computed(() => [
    ...narrowingIssues(this.draft(), this.ceiling(), { allowedImages: this.allowedImages() }),
    ...this.serverIssues(),
  ]);
  readonly ownDeny = computed(() => {
    const locked = new Set(this.ceiling()?.tools?.deny ?? []);
    return (this.draft().tools?.deny ?? []).filter((t) => !locked.has(t));
  });
  readonly ownAsk = computed(() => {
    const locked = new Set(this.ceiling()?.tools?.ask ?? []);
    return (this.draft().tools?.ask ?? []).filter((t) => !locked.has(t));
  });
  readonly imageHint = computed(() => {
    const pats = this.allowedImages()?.length
      ? this.allowedImages()!
      : this.ceiling()?.environment?.image
        ? [this.ceiling()!.environment!.image!]
        : [];
    if (!pats.length) return $localize`:@@fleet.policyEditor.anyImage:Any image the pool allows`;
    const list = pats.join($localize`:@@fleet.policy.issue.or: or `);
    return $localize`:@@fleet.policyEditor.mustMatch:Must match ${list}:patterns:`;
  });

  private lastEmitted = '';

  constructor() {
    effect(() => {
      const p = this.policy() ?? {};
      const json = JSON.stringify(compactPolicy(p));
      untracked(() => {
        // Our own emission coming back through the parent: keep the draft (and the field text).
        if (json === this.lastEmitted) return;
        this.draft.set(structuredClone(p));
        this.selectorText.set(formatLabels(p.poolSelector));
        this.selectorError.set(null);
      });
    });
    effect(() => {
      const issues = this.issues();
      untracked(() => this.issuesChange.emit(issues));
    });
  }

  show(s: Section): boolean {
    const list = this.sections();
    return !list || list.includes(s);
  }

  weaker(i: SandboxIsolation): boolean {
    const c = this.ceiling()?.isolation;
    return !!c && ISOLATION_RANK[i] < ISOLATION_RANK[c];
  }

  maxHint(max: string | number | null | undefined, fallback: string): string {
    if (max === null || max === undefined || max === '') return fallback;
    return $localize`:@@fleet.policyEditor.atMost:At most ${max}:max:`;
  }

  effectiveSetBy(level: string): string {
    const name = this.sourceLabels[level] || level.toLowerCase();
    return $localize`:@@fleet.policyEditor.effectiveSetBy:Effective value set by: ${name}:level:`;
  }

  issuesAt(path: string): PolicyIssue[] {
    return this.issues().filter((i) => i.path === path || i.path.startsWith(path + '.'));
  }

  /** Per-index reasons for a list field (`network.allow.2` -> `{2: reason}`). */
  invalidAt(path: string): Record<number, string> {
    const out: Record<number, string> = {};
    for (const i of this.issues()) {
      const m = i.path.startsWith(path + '.') ? /\.(\d+)$/.exec(i.path) : null;
      if (m) out[Number(m[1])] = i.message;
    }
    return out;
  }

  cacheAvailable(x: SandboxCache): boolean {
    const allowed = this.ceiling()?.environment?.caches;
    return !allowed?.length || allowed.includes(x);
  }

  readonly validateHost = (v: string): string | null => {
    const err = hostPatternError(v);
    if (err) return err;
    return coveringPattern(v, this.ceiling()?.network?.allow)
      ? null
      : $localize`:@@fleet.policyEditor.hostOutside:${v}:host: is outside the ceiling's allowlist (${this.ceiling()!.network!.allow!.join(', ')}:allowlist:).`;
  };

  readonly validateTool = (v: string): string | null => {
    if (!toolAllowedBy(v, this.ceiling()?.tools?.allow))
      return $localize`:@@fleet.policyEditor.toolOnly:The ceiling allows only ${this.ceiling()!.tools!.allow!.join(', ')}:tools:.`;
    const denied = (this.ceiling()?.tools?.deny ?? []).find((d) => d === v || globMatches(d, v));
    return denied
      ? $localize`:@@fleet.policyEditor.toolDenied:Denied by the ceiling (${denied}:rule:).`
      : null;
  };

  readonly validateSecret = (v: string): string | null => {
    if (!/^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$/.test(v))
      return $localize`:@@fleet.policyEditor.secretChars:Secret names are lower-case letters, digits, dots and hyphens.`;
    const allowed = this.ceiling()?.secrets;
    return allowed?.length && !allowed.includes(v)
      ? $localize`:@@fleet.policy.issue.secret:${v}:secret: is not available at this level.`
      : null;
  };

  patch(changes: Partial<SandboxPolicy>): void {
    this.update({ ...this.draft(), ...changes });
  }

  patchRes(key: 'cpu' | 'memory' | 'ephemeralStorage' | 'pids', value: string | number | null) {
    const res = { ...(this.draft().resources ?? {}) } as Record<string, unknown>;
    if (value === null || value === '' || value === undefined) delete res[key];
    else res[key] = typeof value === 'string' ? value.trim() : value;
    this.patch({ resources: res });
  }

  patchTools(key: 'allow' | 'deny' | 'ask', values: string[]): void {
    const locked = key === 'allow' ? [] : (this.ceiling()?.tools?.[key] ?? []);
    // Locked entries are the ceiling's; the level stores only its own additions.
    this.patch({
      tools: { ...(this.draft().tools ?? {}), [key]: values.filter((v) => !locked.includes(v)) },
    });
  }

  patchEnv(changes: Partial<NonNullable<SandboxPolicy['environment']>>): void {
    this.patch({ environment: { ...(this.draft().environment ?? {}), ...changes } });
  }

  toggleCache(x: SandboxCache): void {
    const cur = this.draft().environment?.caches ?? [];
    this.patchEnv({ caches: cur.includes(x) ? cur.filter((c) => c !== x) : [...cur, x] });
  }

  onSelector(text: string): void {
    this.selectorText.set(text);
    const { labels, error } = parseLabels(text);
    this.selectorError.set(error);
    if (!error) this.patch({ poolSelector: labels });
  }

  private update(next: SandboxPolicy): void {
    this.draft.set(next);
    const compact = compactPolicy(next);
    this.lastEmitted = JSON.stringify(compact);
    this.policyChange.emit(compact);
  }
}
