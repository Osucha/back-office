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
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSegmentedModule } from 'ng-zorro-antd/segmented';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSkeletonModule } from 'ng-zorro-antd/skeleton';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { MembersApi } from '../../../core/api/members.service';
import type { PrincipalType, ProjectMember } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { roleName } from '../../../core/i18n/catalog-labels';
import { ProjectRolesService } from '../../../core/state/project-roles.service';
import { ActorComponent } from '../../../core/ui/actor.component';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { TimeAgoPipe } from '../../../core/ui/pipes/time-ago.pipe';
import { PrincipalPickerComponent } from '../../../core/ui/principal-picker.component';

/**
 * Project members tab: users and groups with a project role. Everyone with `project.view` sees the
 * list; `project.members.manage` adds, changes roles and removes. The last owner cannot be removed
 * or demoted (409, the problem detail is shown).
 */
@Component({
  selector: 'app-project-members-panel',
  imports: [
    FormsModule,
    NzCardModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    NzSegmentedModule,
    NzSkeletonModule,
    NzTagModule,
    NzTooltipModule,
    ActorComponent,
    TimeAgoPipe,
    PrincipalPickerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (canManage()) {
      <nz-card
        nzTitle="Add member"
        i18n-nzTitle="@@projects.members.addTitle"
        style="margin-bottom: 16px"
      >
        <div class="add-row">
          <nz-segmented
            [nzOptions]="principalOptions"
            [ngModel]="addType()"
            (ngModelChange)="setAddType($event)"
            aria-label="Member type"
            i18n-aria-label="@@projects.members.type"
          />
          <div class="picker">
            <app-principal-picker
              [kind]="addType() === 'USER' ? 'user' : 'group'"
              [placeholder]="addType() === 'USER' ? text.searchUsers : text.searchGroups"
              ariaLabel="Member to add"
              i18n-ariaLabel="@@projects.members.toAdd"
              [(value)]="addPrincipal"
            />
          </div>
          <nz-select
            style="width: 180px"
            [ngModel]="addRole()"
            (ngModelChange)="addRole.set($event)"
            nzPlaceHolder="Role"
            i18n-nzPlaceHolder="@@projects.members.rolePlaceholder"
            aria-label="Project role"
            i18n-aria-label="@@projects.members.projectRole"
          >
            @for (r of roles.roles(); track r.key) {
              <nz-option [nzValue]="r.key" [nzLabel]="r.name" />
            }
          </nz-select>
          <button
            nz-button
            nzType="primary"
            type="button"
            [disabled]="!addPrincipal() || !addRole()"
            [nzLoading]="adding()"
            (click)="add()"
          >
            <nz-icon nzType="user-add" /> <span i18n="@@projects.members.add">Add</span>
          </button>
        </div>
        <p class="muted hint" i18n="@@projects.members.hint">
          Members of a group get the group's project role. The effective permissions of a user are
          the union of all roles from direct and group memberships.
        </p>
      </nz-card>
    }

    @if (loading() && !members().length) {
      <nz-skeleton [nzActive]="true" [nzParagraph]="{ rows: 4 }" />
    } @else {
      <nz-table [nzData]="members()" [nzShowPagination]="members().length > 50" [nzPageSize]="50">
        <thead>
          <tr>
            <th i18n="@@projects.members.member">Member</th>
            <th nzWidth="90px" i18n="@@projects.members.typeColumn">Type</th>
            <th nzWidth="200px" i18n="@@projects.members.role">Role</th>
            <th nzWidth="200px" i18n="@@projects.members.added">Added</th>
            @if (canManage()) {
              <th nzWidth="80px"></th>
            }
          </tr>
        </thead>
        <tbody>
          @for (m of members(); track m.id) {
            <tr>
              <td>
                <div style="font-weight: 500">
                  <nz-icon [nzType]="m.principal?.type === 'GROUP' ? 'team' : 'user'" />
                  {{ m.principal?.name }}
                  @if (m.principal?.id === identity.user()?.id) {
                    <nz-tag i18n="@@projects.members.you">you</nz-tag>
                  }
                </div>
                @if (m.principal?.email) {
                  <div class="muted">{{ m.principal?.email }}</div>
                }
              </td>
              <td>
                @if (m.principal?.type === 'GROUP') {
                  <span i18n="@@projects.members.group">Group</span>
                } @else {
                  <span i18n="@@projects.members.user">User</span>
                }
              </td>
              <td>
                @if (canManage()) {
                  <nz-select
                    style="width: 100%"
                    nzSize="small"
                    [ngModel]="m.role"
                    (ngModelChange)="changeRole(m, $event)"
                    [nzLoading]="busy() === m.id"
                    [attr.aria-label]="roleOfLabel(m)"
                  >
                    @for (r of roleOptionsFor(m); track r.key) {
                      <nz-option [nzValue]="r.key" [nzLabel]="r.name" />
                    }
                  </nz-select>
                } @else {
                  <nz-tag [title]="m.role ?? ''">{{ displayRole(m.role) }}</nz-tag>
                }
              </td>
              <td>
                <div>{{ m.addedAt | timeAgo }}</div>
                @if (m.addedBy) {
                  <div class="muted" i18n="@@projects.members.addedBy">
                    by <app-actor [actor]="m.addedBy" />
                  </div>
                }
              </td>
              @if (canManage()) {
                <td>
                  <button
                    nz-button
                    nzSize="small"
                    nzDanger
                    nzType="text"
                    type="button"
                    [nzLoading]="busy() === m.id + ':remove'"
                    (click)="remove(m)"
                    [attr.aria-label]="removeLabel(m)"
                    nz-tooltip
                    nzTooltipTitle="Remove from project"
                    i18n-nzTooltipTitle="@@projects.members.removeTooltip"
                  >
                    <nz-icon nzType="delete" />
                  </button>
                </td>
              }
            </tr>
          }
        </tbody>
      </nz-table>
    }
  `,
  styles: [
    `
      .add-row {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      .picker {
        flex: 1;
        min-width: 260px;
      }
      .hint {
        margin: 8px 0 0;
        font-size: 12px;
      }
    `,
  ],
})
export class ProjectMembersPanelComponent {
  private readonly api = inject(MembersApi);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly confirm = inject(ConfirmService);
  readonly identity = inject(IdentityService);
  readonly roles = inject(ProjectRolesService);

  readonly projectId = input.required<string>();
  /** `project.members.manage` on this project. */
  readonly canManage = input(false);

  readonly members = signal<ProjectMember[]>([]);
  readonly loading = signal(false);
  readonly busy = signal<string | null>(null);
  readonly adding = signal(false);
  readonly addType = signal<PrincipalType>('USER');
  readonly addPrincipal = signal<string | string[] | null>(null);
  readonly addRole = signal<string | null>('developer');

  readonly principalOptions = [
    { label: $localize`:@@projects.members.optionUser:User`, value: 'USER' },
    { label: $localize`:@@projects.members.optionGroup:Group`, value: 'GROUP' },
  ];
  readonly text = {
    searchUsers: $localize`:@@projects.members.searchUsers:Search users by name or e-mail`,
    searchGroups: $localize`:@@projects.members.searchGroups:Search groups`,
  };

  roleOfLabel(m: ProjectMember): string {
    return $localize`:@@projects.members.roleOf:Role of ${m.principal?.name ?? ''}:name:`;
  }

  removeLabel(m: ProjectMember): string {
    return $localize`:@@projects.members.removeLabel:Remove ${m.principal?.name ?? ''}:name:`;
  }

  private readonly roleKeys = computed(() => new Set(this.roles.roles().map((r) => r.key)));

  constructor() {
    this.roles.load();
    effect(() => {
      const id = this.projectId();
      untracked(() => this.load(id));
    });
  }

  /** The member's current role is always selectable, even if it is not in the loaded catalogue. */
  roleOptionsFor(m: ProjectMember): Array<{ key: string; name: string }> {
    const list = this.roles.roles();
    if (!m.role || this.roleKeys().has(m.role)) return list;
    return [...list, { key: m.role, name: roleName(m.role) }];
  }

  /** Display name of a role key: the loaded catalogue's (translated) name, else the built-in name or the key. */
  displayRole(key: string | null | undefined): string {
    if (!key) return '';
    return this.roles.roles().find((r) => r.key === key)?.name ?? roleName(key);
  }

  setAddType(value: string | number): void {
    this.addType.set(value as PrincipalType);
    this.addPrincipal.set(null);
  }

  load(id = this.projectId()): void {
    this.loading.set(true);
    this.api.list(id).subscribe({
      next: (list) => {
        this.members.set(list);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  add(): void {
    const principalId = this.addPrincipal();
    const role = this.addRole();
    if (!principalId || Array.isArray(principalId) || !role) return;
    this.adding.set(true);
    this.api.add(this.projectId(), { principalType: this.addType(), principalId, role }).subscribe({
      next: (m) => {
        this.adding.set(false);
        this.addPrincipal.set(null);
        this.message.success(
          m.principal?.name
            ? $localize`:@@projects.members.addedToast:${m.principal.name}:name: added as ${this.displayRole(m.role)}:role:`
            : $localize`:@@projects.members.addedToastNoName:Member added as ${this.displayRole(m.role)}:role:`,
        );
        this.load();
        this.refreshOwnAccess(m);
      },
      error: (err: unknown) => {
        this.adding.set(false);
        this.showError($localize`:@@projects.members.addFailed:Could not add the member`, err);
      },
    });
  }

  changeRole(m: ProjectMember, role: string): void {
    if (!m.id || role === m.role) return;
    this.busy.set(m.id);
    this.api.changeRole(this.projectId(), m.id, role).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.members.update((list) => list.map((x) => (x.id === updated.id ? updated : x)));
        this.message.success(
          $localize`:@@projects.members.roleChanged:Role changed to ${this.displayRole(updated.role)}:role:`,
        );
        this.refreshOwnAccess(updated);
      },
      error: (err: unknown) => {
        this.busy.set(null);
        // Re-render the select with the unchanged role.
        this.members.update((list) => [...list]);
        this.showError($localize`:@@projects.members.roleFailed:Could not change the role`, err);
      },
    });
  }

  async remove(m: ProjectMember): Promise<void> {
    if (!m.id) return;
    const ok = await this.confirm.danger(
      m.principal?.name
        ? $localize`:@@projects.members.removeTitle:Remove ${m.principal.name}:name: from the project?`
        : $localize`:@@projects.members.removeTitleNoName:Remove this member from the project?`,
      m.principal?.type === 'GROUP'
        ? $localize`:@@projects.members.removeGroupText:Members of the group lose the access they had through it.`
        : $localize`:@@projects.members.removeUserText:The user loses the access they had through this membership.`,
      $localize`:@@projects.members.removeOk:Remove`,
    );
    if (!ok) return;
    this.busy.set(m.id + ':remove');
    this.api.remove(this.projectId(), m.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.members.update((list) => list.filter((x) => x.id !== m.id));
        this.message.success($localize`:@@projects.members.removed:Member removed`);
        this.refreshOwnAccess(m);
      },
      error: (err: unknown) => {
        this.busy.set(null);
        this.showError(
          $localize`:@@projects.members.removeFailed:Could not remove the member`,
          err,
        );
      },
    });
  }

  /** Changing one's own (or one's group's) membership changes one's permissions. */
  private refreshOwnAccess(m: ProjectMember): void {
    const me = this.identity.me();
    const mine =
      m.principal?.id === me?.user?.id || (me?.groups ?? []).some((g) => g.id === m.principal?.id);
    if (mine) void this.identity.refresh();
  }

  private showError(title: string, err: unknown): void {
    // 0 / 401 / 403 are reported by the interceptor already.
    if (err instanceof HttpErrorResponse && [0, 401, 403].includes(err.status)) return;
    this.notify.error(title, problemMessage(err));
  }
}
