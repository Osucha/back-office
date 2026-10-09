import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { ProvenanceApi } from '../../../../core/api/provenance.service';
import type {
  AttestationSummary,
  AttestationVerification,
  DsseEnvelope,
} from '../../../../core/api/types';
import { HelpLinkComponent } from '../../../../core/help/help-link.component';
import { JsonViewComponent } from '../../../../core/ui/json-view.component';
import { timeAgo } from '../../../../core/ui/pipes/time-ago.pipe';
import type { Baton } from '../../../../core/ui/relay/baton';
import { BatonComponent } from '../../../../core/ui/relay/baton.component';
import { EmptyComponent } from '../../../../core/ui/relay/empty.component';
import { absoluteTime } from '../../../../core/ui/relay/format';
import { SkeletonComponent } from '../../../../core/ui/relay/skeleton.component';
import {
  attestationFileName,
  decodeStatement,
  shortSubject,
  summarizeStatement,
  toJsonl,
} from '../../data/provenance-statement';

/**
 * Run detail, tab "Provenance": the signed in-toto attestations of the run (one per agent job and
 * one for the run), the decoded statement with its key facts, download as `.intoto.jsonl` and
 * "Verify" against the published keys (`POST /attestations/verify`).
 */
@Component({
  selector: 'app-provenance-panel',
  imports: [
    NzButtonModule,
    NzIconModule,
    BatonComponent,
    EmptyComponent,
    HelpLinkComponent,
    JsonViewComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="explain" i18n="@@runs.provenance.explain">
      An attestation is a statement signed by Back Office (Ed25519, DSSE envelope) that binds the
      commit and diff an agent produced to how they were produced: model, template and prompts,
      sandbox policy and image, allowed hosts, tokens and cost, the verification result and who
      approved the gates on the way. Anyone with the public keys can check it was not changed; it
      does not prove the change is correct.
      <bo-help-link topic="provenance" variant="text">How to verify attestations</bo-help-link>
    </p>
    @if (loading() && !list().length) {
      <bo-skeleton
        variant="list"
        [rows]="3"
        label="Loading attestations"
        i18n-label="@@runs.provenance.loading"
      />
    } @else if (!list().length) {
      <bo-empty
        tone="quiet"
        [title]="failed() ? text.unavailable : text.none"
        [detail]="failed() ? text.unavailableDetail : text.noneDetail"
      />
    } @else {
      <div class="layout">
        <ul
          class="list"
          role="listbox"
          aria-label="Attestations"
          i18n-aria-label="@@runs.provenance.list"
        >
          @for (a of list(); track a.id) {
            <li
              role="option"
              tabindex="0"
              [attr.aria-selected]="selectedId() === a.id"
              [class.on]="selectedId() === a.id"
              (click)="select(a)"
              (keydown.enter)="select(a)"
            >
              <span class="kind"
                >{{ a.kind === 'RUN' ? text.run : text.agentJob
                }}{{ a.nodeId ? ' · ' + a.nodeId : '' }}</span
              >
              <span class="subj mono">{{ subject(a) }}</span>
              <span class="meta"
                ><span class="mono">{{ a.keyId }}</span> ·
                <span [attr.title]="abs(a.createdAt)">{{ ago(a.createdAt) }}</span></span
              >
            </li>
          }
        </ul>

        <section class="detail" aria-live="polite">
          @if (selected(); as a) {
            <header class="d-head">
              <div>
                @if (a.kind === 'RUN') {
                  <h3 i18n="@@runs.provenance.runAttestation">Run attestation</h3>
                } @else {
                  <h3 i18n="@@runs.provenance.jobAttestation">Agent job attestation</h3>
                }
                <span class="meta mono">{{ a.id }}</span>
              </div>
              <div class="actions">
                @if (verifyBaton(); as b) {
                  <bo-baton [baton]="b" />
                }
                <button
                  nz-button
                  type="button"
                  [disabled]="!envelope()"
                  [nzLoading]="verifying()"
                  (click)="verify()"
                >
                  <nz-icon nzType="safety-certificate" />
                  <span i18n="@@runs.provenance.verify">Verify</span>
                </button>
                <button nz-button type="button" [disabled]="!envelope()" (click)="download()">
                  <nz-icon nzType="download" />
                  <span i18n="@@runs.provenance.download">Download .intoto.jsonl</span>
                </button>
              </div>
            </header>
            @if (verification()?.reason && !verification()?.valid) {
              <p class="bad">{{ verification()?.reason }}</p>
            }
            @if (!envelope()) {
              <bo-skeleton variant="detail" />
            } @else {
              <h4 i18n="@@runs.provenance.subjects">Subjects</h4>
              <ul class="subjects">
                @for (s of summary().subjects; track s.name) {
                  <li>
                    <span class="mono">{{ s.name }}</span>
                    <span class="digest mono">{{ s.digest }}</span>
                  </li>
                } @empty {
                  @for (s of a.subjects ?? []; track s.name) {
                    <li>
                      <span class="mono">{{ s.name }}</span>
                    </li>
                  }
                }
              </ul>
              <h4 i18n="@@runs.provenance.keyFacts">Key facts</h4>
              <dl class="facts">
                @for (f of summary().facts; track f.label) {
                  <div>
                    <dt>{{ f.label }}</dt>
                    <dd [class.mono]="f.mono">{{ f.value }}</dd>
                  </div>
                }
                @if (summary().allowedHosts.length) {
                  <div>
                    <dt i18n="@@runs.provenance.allowedHosts">Allowed hosts</dt>
                    <dd>
                      @for (h of summary().allowedHosts; track h) {
                        <span class="chip mono">{{ h }}</span>
                      }
                    </dd>
                  </div>
                }
                @if (summary().approvers.length) {
                  <div>
                    <dt i18n="@@runs.provenance.approvals">Approvals</dt>
                    <dd class="col">
                      @for (g of summary().approvers; track $index) {
                        <span
                          >{{ g.who }}{{ g.option ? ': ' + g.option.toLowerCase() : ''
                          }}{{ g.at ? ' · ' + abs(g.at) : '' }}</span
                        >
                      }
                    </dd>
                  </div>
                }
              </dl>
              <h4 i18n="@@runs.provenance.statement">Statement</h4>
              <app-json-view [value]="statement() ?? {}" maxHeight="50vh" />
            }
          } @else {
            <bo-empty
              tone="quiet"
              title="Select an attestation to see its statement."
              i18n-title="@@runs.provenance.selectHint"
            />
          }
        </section>
      </div>
    }
  `,
  styles: [
    `
      .explain {
        margin: 0 0 16px;
        max-width: 100ch;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        line-height: 1.5;
      }
      .layout {
        display: grid;
        grid-template-columns: minmax(240px, 1fr) minmax(0, 2.4fr);
        gap: 16px;
        align-items: start;
      }
      @media (max-width: 1000px) {
        .layout {
          grid-template-columns: 1fr;
        }
      }
      .list {
        list-style: none;
        margin: 0;
        padding: 4px;
        display: grid;
        gap: 2px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .list li {
        display: flex;
        flex-direction: column;
        gap: 2px;
        padding: 8px 10px;
        border-radius: var(--bo-radius-control);
        cursor: pointer;
      }
      .list li:hover {
        background: var(--bo-surface-2);
      }
      .list li.on {
        background: var(--bo-accent-soft);
      }
      .list li:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .kind {
        font-weight: 500;
        font-size: 13px;
      }
      .subj {
        font-size: 11.5px;
        color: var(--bo-ink-2);
      }
      .meta {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .list li.on .meta {
        color: var(--bo-ink-2);
      }
      .detail {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        padding: 14px 18px;
        min-width: 0;
      }
      .d-head {
        display: flex;
        flex-wrap: wrap;
        justify-content: space-between;
        gap: 8px 16px;
        align-items: flex-start;
        margin-bottom: 8px;
      }
      h3 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
      }
      h4 {
        margin: 14px 0 6px;
        font-size: 12.5px;
        font-weight: 600;
        color: var(--bo-ink-2);
      }
      .actions {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        align-items: center;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .bad {
        margin: 4px 0;
        font-size: 12.5px;
        color: var(--bo-blocked-ink);
      }
      .subjects {
        margin: 0;
        padding-left: 18px;
        font-size: 12px;
        display: grid;
        gap: 2px;
      }
      .digest {
        margin-left: 6px;
        color: var(--bo-ink-3);
        font-size: 11px;
        overflow-wrap: anywhere;
      }
      .facts {
        margin: 0;
        display: grid;
        gap: 6px;
        font-size: 13px;
      }
      .facts > div {
        display: grid;
        grid-template-columns: 120px minmax(0, 1fr);
        gap: 12px;
      }
      dt {
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
        overflow-wrap: anywhere;
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      dd.col {
        flex-direction: column;
      }
      .chip {
        padding: 0 7px;
        line-height: 20px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-size: 11.5px;
      }
    `,
  ],
})
export class ProvenancePanelComponent {
  private readonly api = inject(ProvenanceApi);
  readonly runId = input.required<string>();

  readonly list = signal<AttestationSummary[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly selectedId = signal<string | null>(null);
  readonly envelope = signal<DsseEnvelope | null>(null);
  readonly verification = signal<AttestationVerification | null>(null);
  readonly verifying = signal(false);

  readonly selected = computed(() => this.list().find((a) => a.id === this.selectedId()) ?? null);
  readonly statement = computed(
    () =>
      (this.verification()?.statement as Record<string, unknown> | undefined) ??
      decodeStatement(this.envelope()),
  );
  readonly summary = computed(() => summarizeStatement(this.statement()));
  readonly verifyBaton = computed<Baton | null>(() => {
    if (this.verifying())
      return {
        holder: 'agent',
        label: $localize`:@@runs.provenance.verifying:verifying`,
        live: true,
      };
    const v = this.verification();
    if (!v) return null;
    return v.valid
      ? {
          holder: 'done',
          label: $localize`:@@runs.provenance.valid:signature valid`,
          detail: v.keyId ?? null,
        }
      : { holder: 'blocked', label: $localize`:@@runs.provenance.notValid:not valid` };
  });

  readonly text = {
    unavailable: $localize`:@@runs.provenance.unavailable:Attestations are not available.`,
    unavailableDetail: $localize`:@@runs.provenance.unavailableDetail:The API did not answer ${'/runs/{id}/attestations'}:path:.`,
    none: $localize`:@@runs.provenance.none:No attestations for this run yet.`,
    noneDetail: $localize`:@@runs.provenance.noneDetail:They are signed when an agent job finishes and when the run ends.`,
    run: $localize`:@@runs.provenance.kindRun:Run`,
    agentJob: $localize`:@@runs.provenance.kindJob:Agent job`,
  };

  ago = timeAgo;
  abs = absoluteTime;

  constructor() {
    effect(() => {
      const id = this.runId();
      untracked(() => this.load(id));
    });
  }

  subject(a: AttestationSummary): string {
    const git = (a.subjects ?? []).find((s) => s.name?.startsWith('git+'));
    return shortSubject(git?.name ?? a.subjects?.[0]?.name);
  }

  load(runId: string): void {
    this.loading.set(true);
    this.api.forRun(runId).subscribe({
      next: (l) => {
        const list = [...(l ?? [])].sort((a, b) =>
          a.kind === 'RUN' ? -1 : b.kind === 'RUN' ? 1 : 0,
        );
        this.list.set(list);
        this.loading.set(false);
        this.failed.set(false);
        if (list.length) this.select(list[0]);
      },
      error: () => {
        this.loading.set(false);
        this.failed.set(true);
      },
    });
  }

  select(a: AttestationSummary): void {
    if (!a.id || a.id === this.selectedId()) return;
    this.selectedId.set(a.id);
    this.envelope.set(null);
    this.verification.set(null);
    this.api.envelope(a.id).subscribe({
      next: (e) => {
        if (this.selectedId() === a.id) this.envelope.set(e);
      },
      error: () => undefined,
    });
  }

  verify(): void {
    const e = this.envelope();
    if (!e) return;
    this.verifying.set(true);
    this.api.verify(e).subscribe({
      next: (v) => {
        this.verification.set(v);
        this.verifying.set(false);
      },
      error: () => {
        this.verification.set({
          valid: false,
          reason: $localize`:@@runs.provenance.verifyFailed:The verification request failed.`,
        });
        this.verifying.set(false);
      },
    });
  }

  download(): void {
    const e = this.envelope();
    const a = this.selected();
    if (!e || !a?.id) return;
    const blob = new Blob([toJsonl(e)], { type: 'application/x-ndjson' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = attestationFileName(this.runId(), a.id);
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
