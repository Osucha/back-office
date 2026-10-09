import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDividerModule } from 'ng-zorro-antd/divider';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { ChecklistItem, GateApprovers } from '../../../core/api/types';
import { roleName } from '../../../core/i18n/catalog-labels';
import { ProjectRolesService } from '../../../core/state/project-roles.service';
import { PrincipalPickerComponent } from '../../../core/ui/principal-picker.component';
import {
  accountableOptions,
  addChecklistItem,
  compactApprovers,
  gateRuleProblems,
  normalizeApprovers,
  patchChecklistItem,
  removeChecklistItem,
  withProjectRoles,
} from '../data/gate-rules';

/** Approvers (roles, users, accountable, self-approval) and checklist of a HUMAN_GATE node. */
@Component({
  selector: 'app-gate-rules-editor',
  imports: [
    FormsModule,
    NzFormModule,
    NzSelectModule,
    NzSwitchModule,
    NzInputModule,
    NzButtonModule,
    NzIconModule,
    NzDividerModule,
    NzTooltipModule,
    PrincipalPickerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-divider
      nzText="Who decides this step"
      i18n-nzText="@@workflows.gate.approvers"
      nzOrientation="left"
      nzPlain
    />
    <p class="muted small" i18n="@@workflows.gate.reassignHint">
      This is the rule for every run. Project owners and maintainers can reassign a waiting step to
      other people in a single run (Assign… on the gate in Your turn or on the run).
    </p>
    <nz-form-item>
      <nz-form-label nzFor="gate-roles" i18n="@@workflows.gate.projectRoles"
        >Project roles</nz-form-label
      >
      <nz-form-control
        nzExtra="Empty = anyone with run.approve on the project."
        i18n-nzExtra="@@workflows.gate.projectRoles.extra"
      >
        <nz-select
          id="gate-roles"
          nzMode="multiple"
          nzPlaceHolder="Any approver"
          i18n-nzPlaceHolder="@@workflows.gate.anyApprover"
          [ngModel]="current().projectRoles ?? []"
          (ngModelChange)="setRoles($event)"
          [nzDisabled]="readonly()"
        >
          @for (r of roles.roles(); track r.key) {
            <nz-option [nzValue]="r.key" [nzLabel]="r.name" />
          }
        </nz-select>
      </nz-form-control>
    </nz-form-item>
    <nz-form-item>
      <nz-form-label i18n="@@workflows.gate.users">Named people</nz-form-label>
      <nz-form-control
        nzExtra="Only these people may decide (each also needs one of the roles above, if set). Empty = anyone in those roles."
        i18n-nzExtra="@@workflows.gate.users.extra"
      >
        <app-principal-picker
          kind="user"
          [multiple]="true"
          [disabled]="readonly()"
          ariaLabel="Approver users"
          i18n-ariaLabel="@@workflows.gate.users.aria"
          [value]="current().users ?? []"
          (valueChange)="setUsers($event)"
        />
      </nz-form-control>
    </nz-form-item>
    <nz-form-item>
      <nz-form-label nzFor="gate-accountable" i18n="@@workflows.gate.accountable"
        >Accountable role</nz-form-label
      >
      <nz-form-control
        nzExtra='The single "A" of RACI, shown in the inbox.'
        i18n-nzExtra="@@workflows.gate.accountable.extra"
      >
        <nz-select
          id="gate-accountable"
          nzAllowClear
          nzPlaceHolder="Not set"
          i18n-nzPlaceHolder="@@workflows.gate.accountable.notSet"
          [ngModel]="current().accountable ?? null"
          (ngModelChange)="patchApprovers({ accountable: $event ?? null })"
          [nzDisabled]="readonly()"
        >
          @for (r of accountableRoles(); track r) {
            <nz-option [nzValue]="r" [nzLabel]="roleLabel(r)" />
          }
        </nz-select>
      </nz-form-control>
    </nz-form-item>
    <nz-form-item>
      <nz-form-control>
        <nz-switch
          [ngModel]="current().forbidSelfApproval"
          (ngModelChange)="patchApprovers({ forbidSelfApproval: $event })"
          [nzDisabled]="readonly()"
          aria-label="Forbid self-approval"
          i18n-aria-label="@@workflows.gate.forbidSelf"
        />
        <span class="small" style="margin-left: 8px" i18n="@@workflows.gate.forbidSelf"
          >Forbid self-approval</span
        >
        <div class="muted small" i18n="@@workflows.gate.forbidSelf.hint">
          The user who started the run cannot decide this gate.
        </div>
      </nz-form-control>
    </nz-form-item>

    @if (showChecklist()) {
      <nz-divider
        nzText="Checklist"
        i18n-nzText="@@workflows.gate.checklist"
        nzOrientation="left"
        nzPlain
      />
      <p class="muted small" i18n="@@workflows.gate.checklist.hint">
        Definition of Ready/Done items shown in the inbox; approving needs every required item
        ticked.
      </p>
      @for (item of checklist(); track $index; let i = $index) {
        <div class="check-row">
          <input
            nz-input
            nzSize="small"
            class="mono id"
            [ngModel]="item.id"
            (ngModelChange)="patchItem(i, { id: $event })"
            [readonly]="readonly()"
            [attr.aria-label]="itemLabel(i, 'id')"
          />
          <input
            nz-input
            nzSize="small"
            class="text"
            placeholder="Tests are green"
            i18n-placeholder="@@workflows.gate.checklist.textPlaceholder"
            [ngModel]="item.text"
            (ngModelChange)="patchItem(i, { text: $event })"
            [readonly]="readonly()"
            [attr.aria-label]="itemLabel(i, 'text')"
          />
          <nz-switch
            nzSize="small"
            [ngModel]="item.required !== false"
            (ngModelChange)="patchItem(i, { required: $event })"
            [nzDisabled]="readonly()"
            nz-tooltip
            nzTooltipTitle="Required"
            i18n-nzTooltipTitle="@@workflows.gate.checklist.required"
            [attr.aria-label]="itemLabel(i, 'required')"
          />
          @if (!readonly()) {
            <button
              nz-button
              nzType="text"
              nzSize="small"
              nzDanger
              type="button"
              (click)="removeItem(i)"
              [attr.aria-label]="removeLabel(i)"
            >
              <nz-icon nzType="delete" />
            </button>
          }
        </div>
      }
      @if (!readonly()) {
        <button nz-button nzType="dashed" nzSize="small" type="button" (click)="addItem()">
          <nz-icon nzType="plus" />
          <span i18n="@@workflows.gate.checklist.add">Add checklist item</span>
        </button>
      } @else if (!checklist().length) {
        <p class="muted small" i18n="@@workflows.gate.checklist.none">No checklist.</p>
      }
    }
    @for (p of problems(); track p) {
      <div class="danger small">{{ p }}</div>
    }
  `,
  styles: [
    `
      .check-row {
        display: flex;
        gap: 6px;
        align-items: center;
        margin-bottom: 6px;
      }
      .check-row .id {
        width: 90px;
        flex: none;
      }
      .check-row .text {
        flex: 1;
      }
      .small {
        font-size: 12px;
      }
    `,
  ],
})
export class GateRulesEditorComponent {
  readonly roles = inject(ProjectRolesService);

  readonly approvers = input<GateApprovers | null | undefined>(null);
  readonly checklist = input<ChecklistItem[]>([]);
  readonly readonly = input(false);
  /** False for rules without a checklist (write confirmations, ADR 0033). */
  readonly showChecklist = input(true);
  readonly approversChange = output<GateApprovers | null>();
  readonly checklistChange = output<ChecklistItem[]>();

  readonly current = computed(() => normalizeApprovers(this.approvers()));
  readonly accountableRoles = computed(() =>
    accountableOptions(
      this.current(),
      this.roles.roles().map((r) => r.key),
    ),
  );
  readonly problems = computed(() => gateRuleProblems(this.approvers(), this.checklist()));

  /** Display name of a role key (the loaded catalogue's name, else the built-in name or the key). */
  roleLabel(key: string): string {
    return this.roles.roles().find((r) => r.key === key)?.name ?? roleName(key);
  }

  itemLabel(index: number, part: 'id' | 'text' | 'required'): string {
    const n = index + 1;
    switch (part) {
      case 'id':
        return $localize`:@@workflows.gate.checklist.itemId:Checklist item ${n}:number: id`;
      case 'text':
        return $localize`:@@workflows.gate.checklist.itemText:Checklist item ${n}:number: text`;
      case 'required':
        return $localize`:@@workflows.gate.checklist.itemRequired:Checklist item ${n}:number: required`;
    }
  }

  removeLabel(index: number): string {
    return $localize`:@@workflows.gate.checklist.remove:Remove checklist item ${index + 1}:number:`;
  }

  constructor() {
    this.roles.load();
  }

  setRoles(roles: string[]): void {
    this.approversChange.emit(compactApprovers(withProjectRoles(this.current(), roles)));
  }

  setUsers(value: string | string[] | null): void {
    const users = Array.isArray(value) ? value : value ? [value] : [];
    this.patchApprovers({ users });
  }

  patchApprovers(changes: Partial<GateApprovers>): void {
    this.approversChange.emit(compactApprovers({ ...this.current(), ...changes }));
  }

  addItem(): void {
    this.checklistChange.emit(addChecklistItem(this.checklist()));
  }

  patchItem(index: number, changes: Partial<ChecklistItem>): void {
    this.checklistChange.emit(patchChecklistItem(this.checklist(), index, changes));
  }

  removeItem(index: number): void {
    this.checklistChange.emit(removeChecklistItem(this.checklist(), index));
  }
}
