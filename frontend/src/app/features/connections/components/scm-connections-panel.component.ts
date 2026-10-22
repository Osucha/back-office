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
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { ProjectRepository } from '../../../core/api/types';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { shortRepoUrl } from '../../../core/ui/repositories';
import { expiryChip } from '../data/connection-model';
import type { ScmConnection } from '../data/connection-types';
import {
  agentChip,
  groupByRepository,
  isScmWriteCapability,
  scmCapabilityLabel,
  scmVerificationChip,
  sortScmCapabilities,
} from '../data/scm-connection-model';
import { ScmConnectionsApi } from '../data/scm-connections-api.service';
import { CONNECTION_CHIP_STYLES } from './chip-styles';
import {
  ScmConnectionEditorComponent,
  type ScmEditorMode,
} from './scm-connection-editor.component';
import { ScmVerificationResultComponent } from './scm-verification-result.component';

/**
 * Project → Sources, "Repository access tokens" (ADR 0051): the tokens of each project repository with
 * their capabilities, whether agents may get them (verified read-only), the last test and the expiry.
 * Owners (`project.connections.manage`) add, edit, replace, test and delete tokens; everyone with
 * `project.view` sees the list. Tokens of a repository removed from the project are marked orphaned.
 */
@Component({
  selector: 'app-scm-connections-panel',
  imports: [
    DatePipe,
    NzButtonModule,
    NzCardModule,
    NzDropDownModule,
    NzIconModule,
    NzTooltipModule,
    ScmConnectionEditorComponent,
    ScmVerificationResultComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-card [nzTitle]="text.title" [nzExtra]="extra">
      <p class="lede" i18n="@@connections.scm.panel.lede">
        GitLab access tokens of this project's repositories. Steps get the least-privileged token
        with the capabilities they need. Read-only tokens may be given to agents as tools; tokens
        with a write capability are used only by Back Office itself.
      </p>
      @if (canManage()) {
        <p class="hint" i18n="@@connections.scm.panel.leastPrivilege">
          Least privilege: one token per permission level, for example a Reporter token with
          read_repository and read_api for reading and CI logs, and a Developer token with
          write_repository for pushing. Never a Maintainer token.
        </p>
      }

      @if (!loaded()) {
        <p class="quiet" i18n="@@connections.scm.panel.loading">Loading tokens…</p>
      } @else if (!groups().length) {
        <p class="quiet" i18n="@@connections.scm.panel.noRepositories">
          This project has no repositories. Add one above; its tokens are managed here.
        </p>
      } @else {
        @for (g of groups(); track g.key + ':' + g.exists) {
          <section class="repo" [attr.aria-label]="g.key">
            <header class="repo-head">
              <span class="repo-key mono">{{ g.key }}</span>
              @if (g.url) {
                <span class="quiet mono">{{ shortUrl(g.url) }}</span>
              }
              @if (g.primary) {
                <span class="cchip" i18n="@@connections.scm.panel.primary">primary</span>
              }
              @if (!g.exists) {
                <span
                  class="cchip bad"
                  [title]="text.orphanTitle"
                  i18n="@@connections.scm.panel.orphaned"
                  >repository removed</span
                >
              }
              <span class="spacer"></span>
              @if (canManage() && g.exists) {
                <button
                  nz-button
                  nzSize="small"
                  type="button"
                  (click)="openEditor('create', null, g.key)"
                  [attr.aria-label]="addLabel(g.key)"
                >
                  <nz-icon nzType="plus" />
                  <span i18n="@@connections.scm.panel.addToRepo">Add token</span>
                </button>
              }
            </header>
            @if (!g.connections.length) {
              <p class="quiet empty" i18n="@@connections.scm.panel.none">
                No tokens for this repository yet.
              </p>
            } @else {
              <ul class="tokens">
                @for (c of g.connections; track c.id) {
                  <li>
                    <div class="token">
                      <div class="main">
                        <div class="line">
                          <span class="name">{{ c.name }}</span>
                          <span class="cchip">GitLab</span>
                          <span
                            class="cchip"
                            [class]="achip(c).tone"
                            [title]="achip(c).title ?? ''"
                            >{{ achip(c).label }}</span
                          >
                        </div>
                        <span class="cchips">
                          @for (cap of caps(c); track cap) {
                            <span
                              class="cchip"
                              [class.cap]="!isWrite(cap)"
                              [class.warn]="isWrite(cap)"
                              [title]="isWrite(cap) ? text.write : text.read"
                              >{{ capLabel(cap) }}</span
                            >
                          }
                        </span>
                      </div>
                      <span class="cchips status">
                        <span class="cchip" [class]="vchip(c).tone">{{ vchip(c).label }}</span>
                        @if (echip(c); as e) {
                          <span class="cchip" [class]="e.tone" [title]="e.title ?? ''">{{
                            e.label
                          }}</span>
                        }
                      </span>
                      <div class="row-actions">
                        <button
                          nz-button
                          nzSize="small"
                          nzType="text"
                          type="button"
                          (click)="toggle(c.id)"
                          [attr.aria-expanded]="expanded().has(c.id)"
                          [attr.aria-label]="detailsLabel(c)"
                          nz-tooltip
                          [nzTooltipTitle]="text.details"
                        >
                          <nz-icon [nzType]="expanded().has(c.id) ? 'up' : 'down'" />
                        </button>
                        @if (canManage()) {
                          @if (g.exists) {
                            <button
                              nz-button
                              nzSize="small"
                              type="button"
                              [nzLoading]="busy() === c.id"
                              (click)="test(c)"
                              i18n="@@connections.scm.panel.testAgain"
                            >
                              Test again
                            </button>
                          }
                          <button
                            nz-button
                            nzSize="small"
                            nzType="text"
                            type="button"
                            nz-dropdown
                            [nzDropdownMenu]="menu"
                            nzTrigger="click"
                            nzPlacement="bottomRight"
                            [attr.aria-label]="moreLabel(c)"
                          >
                            <nz-icon nzType="more" />
                          </button>
                          <nz-dropdown-menu #menu="nzDropdownMenu">
                            <ul nz-menu>
                              <li nz-menu-item (click)="openEditor('edit', c)">
                                <nz-icon nzType="edit" />
                                <span i18n="@@connections.scm.panel.edit">Edit</span>
                              </li>
                              @if (g.exists) {
                                <li nz-menu-item (click)="openEditor('rotate', c)">
                                  <nz-icon nzType="key" />
                                  <span i18n="@@connections.scm.panel.replaceToken"
                                    >Replace token</span
                                  >
                                </li>
                              }
                              <li nz-menu-item nzDanger (click)="remove(c)">
                                <nz-icon nzType="delete" />
                                <span i18n="@@connections.scm.panel.delete">Delete</span>
                              </li>
                            </ul>
                          </nz-dropdown-menu>
                        }
                      </div>
                    </div>
                    @if (expanded().has(c.id)) {
                      <div class="details">
                        @if (c.verification) {
                          <app-scm-verification-result [verification]="c.verification" />
                        } @else {
                          <p class="quiet" i18n="@@connections.scm.panel.notTested">
                            Not tested yet.
                          </p>
                        }
                        <p class="quiet small">
                          @if (c.secretExpiresAt) {
                            <span i18n="@@connections.scm.panel.expires"
                              >Token expires {{ c.secretExpiresAt | date: 'mediumDate' }}.</span
                            >
                          }
                          <span i18n="@@connections.scm.panel.updated"
                            >Last changed {{ c.updatedAt | date: 'medium' }} by
                            {{ c.updatedBy }}.</span
                          >
                        </p>
                      </div>
                    }
                  </li>
                }
              </ul>
            }
          </section>
        }
      }
    </nz-card>
    <ng-template #extra>
      @if (canManage() && repositories().length) {
        <button nz-button nzType="primary" type="button" (click)="openEditor('create', null)">
          <nz-icon nzType="plus" /> <span i18n="@@connections.scm.panel.add">Add token</span>
        </button>
      }
    </ng-template>

    <app-scm-connection-editor
      [open]="!!editing()"
      [projectId]="projectId()"
      [repositories]="repositories()"
      [connection]="editing()?.connection ?? null"
      [mode]="editing()?.mode ?? 'create'"
      [repositoryKey]="editing()?.repositoryKey ?? null"
      (saved)="replace($event)"
      (closed)="editing.set(null)"
    />
  `,
  styles: [
    CONNECTION_CHIP_STYLES,
    `
      .lede {
        margin: 0 0 6px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
        max-width: 90ch;
      }
      .hint {
        margin: 0 0 12px;
        font-size: 12px;
        color: var(--bo-ink-3);
        max-width: 90ch;
      }
      .quiet {
        margin: 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .repo {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        margin-top: 12px;
      }
      .repo-head {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        padding: 8px 12px;
        background: var(--bo-surface-2);
        border-bottom: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card) var(--bo-radius-card) 0 0;
      }
      .repo-key {
        font-weight: 600;
        color: var(--bo-ink);
      }
      .spacer {
        flex: 1;
      }
      .empty {
        padding: 8px 12px;
      }
      .tokens {
        margin: 0;
        padding: 0;
        list-style: none;
      }
      .tokens > li {
        padding: 8px 12px;
        border-bottom: 1px solid var(--bo-line);
      }
      .tokens > li:last-child {
        border-bottom: 0;
      }
      .token {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px 16px;
      }
      .main {
        flex: 1;
        min-width: 240px;
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .line {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 6px;
      }
      .name {
        font-weight: 500;
      }
      .row-actions {
        display: inline-flex;
        gap: 4px;
        align-items: center;
      }
      .details {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 8px 0 2px;
      }
      .small {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
    `,
  ],
})
export class ScmConnectionsPanelComponent {
  private readonly api = inject(ScmConnectionsApi);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly confirm = inject(ConfirmService);

  readonly projectId = input.required<string>();
  /** The project's repositories (ADR 0034). */
  readonly repositories = input<readonly ProjectRepository[]>([]);
  /** `project.connections.manage` on this project. */
  readonly canManage = input(false);

  readonly connections = signal<ScmConnection[]>([]);
  readonly loaded = signal(false);
  readonly busy = signal<string | null>(null);
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  readonly editing = signal<{
    mode: ScmEditorMode;
    connection: ScmConnection | null;
    repositoryKey: string | null;
  } | null>(null);

  readonly groups = computed(() => groupByRepository(this.connections(), this.repositories()));

  readonly capLabel = scmCapabilityLabel;
  readonly isWrite = isScmWriteCapability;
  readonly shortUrl = shortRepoUrl;
  readonly caps = (c: ScmConnection) => sortScmCapabilities(c.capabilities ?? []);
  readonly vchip = (c: ScmConnection) => scmVerificationChip(c.verification);
  readonly echip = (c: ScmConnection) => expiryChip(c);
  readonly achip = agentChip;

  readonly text = {
    title: $localize`:@@connections.scm.panel.title:Repository access tokens`,
    details: $localize`:@@connections.scm.panel.details:Test details`,
    read: $localize`:@@connections.scm.panel.readCap:read capability: may be given to agents`,
    write: $localize`:@@connections.scm.panel.writeCap:write capability: used only by Back Office`,
    orphanTitle: $localize`:@@connections.scm.panel.orphanTitle:This repository is no longer part of the project: its tokens are never used. Delete them, or move them to another repository with Edit.`,
  };

  constructor() {
    effect(() => {
      this.projectId();
      untracked(() => this.load());
    });
  }

  addLabel(key: string): string {
    return $localize`:@@connections.scm.panel.addLabel:Add a token for ${key}:repository:`;
  }

  detailsLabel(c: ScmConnection): string {
    return $localize`:@@connections.scm.panel.detailsLabel:Test details of ${c.name}:name:`;
  }

  moreLabel(c: ScmConnection): string {
    return $localize`:@@connections.scm.panel.moreLabel:More actions for ${c.name}:name:`;
  }

  load(): void {
    this.api.list(this.projectId()).subscribe({
      next: (list) => {
        this.connections.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  replace(c: ScmConnection): void {
    const list = this.connections();
    this.connections.set(
      list.some((x) => x.id === c.id) ? list.map((x) => (x.id === c.id ? c : x)) : [...list, c],
    );
  }

  toggle(id: string): void {
    this.expanded.update((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  openEditor(
    mode: ScmEditorMode,
    connection: ScmConnection | null,
    repositoryKey: string | null = null,
  ): void {
    this.editing.set({ mode, connection, repositoryKey });
  }

  test(c: ScmConnection): void {
    this.busy.set(c.id);
    this.api.verify(this.projectId(), c.id).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.replace(updated);
        this.expanded.update((s) => new Set([...s, c.id]));
      },
      error: (err: unknown) => {
        this.busy.set(null);
        if (err instanceof HttpErrorResponse && [0, 401, 403].includes(err.status)) return;
        this.notify.error(
          $localize`:@@connections.scm.panel.testFailed:The token could not be tested`,
          problemMessage(err),
        );
      },
    });
  }

  async remove(c: ScmConnection): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@connections.scm.panel.deleteTitle:Delete token ${c.name}:name:?`,
      $localize`:@@connections.scm.panel.deleteText:Back Office forgets the token at once; steps that need its capabilities take another token of the repository. Revoke the token in GitLab as well.`,
      $localize`:@@connections.scm.panel.deleteOk:Delete`,
    );
    if (!ok) return;
    this.busy.set(c.id);
    this.api.remove(this.projectId(), c.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.connections.set(this.connections().filter((x) => x.id !== c.id));
        this.message.success($localize`:@@connections.scm.panel.deleted:Token deleted`);
      },
      error: () => this.busy.set(null),
    });
  }
}
