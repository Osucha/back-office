import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import {
  SLOT_KINDS,
  type Slot,
  type TaskInterface,
  type TaskInterfaceInput,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { SLUG_PATTERN } from '../../../core/forms/patterns';
import { icu } from '../../../core/i18n/icu';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SidePanelComponent, panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import { LibraryNavComponent } from '../components/library-nav.component';
import { filterInterfaces } from '../data/library-filters';

const EMPTY: TaskInterfaceInput = {
  key: '',
  name: '',
  description: '',
  inputs: [],
  outputs: [],
  ports: ['success', 'failure'],
};

/**
 * Library: task interfaces (contracts). The editor is a side panel next to the list,
 * deep-linkable with `?interface=<key>` (`?interface=new` for a new one).
 */
@Component({
  selector: 'app-interfaces-page',
  imports: [
    ReadonlyFormDirective,
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzSelectModule,
    NzSwitchModule,
    NzFormModule,
    NzTooltipModule,
    ActionBarComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SidePanelComponent,
    SkeletonComponent,
    LibraryNavComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="interfaces"
        title="Library"
        i18n-title="@@library.interfaces.header.title"
        subtitle="Interfaces are contracts: the inputs a task needs, the outputs it produces and the ports it exposes."
        i18n-subtitle="@@library.interfaces.header.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        @if (canManage()) {
          <button boHeaderActions nz-button nzType="primary" type="button" (click)="create()">
            <nz-icon nzType="plus" />
            <span i18n="@@library.interfaces.create">Create interface</span>
          </button>
        }
        <app-library-nav
          boHeaderBelow
          active="interfaces"
          [counts]="{ interfaces: items().length }"
        />
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search key, name, slot or template"
        i18n-placeholder="@@library.interfaces.filter.placeholder"
        label="Filter interfaces"
        i18n-label="@@library.interfaces.filter.label"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        (cleared)="f.clear()"
      />

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="6"
          [columns]="5"
          label="Loading interfaces"
          i18n-label="@@library.interfaces.loading"
        />
      } @else if (!filtered().length) {
        @if (f.active()) {
          <bo-empty
            title="No interface matches this search."
            i18n-title="@@library.interfaces.empty.noMatch"
            actionLabel="Clear search"
            i18n-actionLabel="@@library.interfaces.empty.clear"
            [primary]="false"
            (action)="f.clear()"
          />
        } @else {
          <bo-empty
            title="No interfaces yet."
            i18n-title="@@library.interfaces.empty.title"
            detail="An interface names the inputs and outputs a family of templates shares."
            i18n-detail="@@library.interfaces.empty.detail"
            [actionLabel]="canManage() ? text.create : null"
            (action)="create()"
            help="interfaces"
            guideLabel="How interfaces work"
            i18n-guideLabel="@@library.interfaces.empty.guide"
          />
        }
      } @else {
        <nz-table [nzData]="filtered()" [nzShowPagination]="false" nzSize="middle">
          <thead>
            <tr>
              <th i18n="@@library.interfaces.col.interface">Interface</th>
              <th nzWidth="18%" i18n="@@library.interfaces.inputs">Inputs</th>
              <th nzWidth="16%" i18n="@@library.interfaces.outputs">Outputs</th>
              <th nzWidth="130px" i18n="@@library.interfaces.ports">Ports</th>
              <th nzWidth="16%" i18n="@@library.interfaces.col.implementedBy">Implemented by</th>
              <th nzWidth="84px">
                <span class="sr-only" i18n="@@library.interfaces.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (i of filtered(); track i.key; let idx = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === idx || panel.value() === i.key"
                [attr.data-nav-index]="idx"
                tabindex="0"
                (click)="edit(i)"
                (keydown.enter)="edit(i)"
                [attr.aria-label]="i.name + ', ' + i.key"
              >
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      <span class="cell-title">{{ i.name }}</span>
                      <span class="key">{{ i.key }}</span>
                      @if (i.builtIn) {
                        <span class="chip outline"
                          ><nz-icon nzType="lock" />
                          <span i18n="@@library.interfaces.builtIn">built-in</span></span
                        >
                      }
                    </div>
                    @if (i.description) {
                      <span class="cell-sub" [attr.title]="i.description">{{ i.description }}</span>
                    }
                    @if (i.updatedBy) {
                      <bo-provenance
                        verb="changed by"
                        i18n-verb="@@library.interfaces.changedBy"
                        [actor]="i.updatedBy"
                      />
                    }
                  </div>
                </td>
                <td>
                  <div class="chips">
                    @for (s of i.inputs ?? []; track s.name + ':' + s.kind) {
                      <span
                        class="chip mono"
                        [class.outline]="s.required === false"
                        nz-tooltip
                        [nzTooltipTitle]="
                          s.required === false ? optionalLabel(s.kind) : kindLabel(s.kind)
                        "
                        >{{ s.name }}{{ s.required === false ? '?' : '' }}</span
                      >
                    } @empty {
                      <span class="quiet">-</span>
                    }
                  </div>
                </td>
                <td>
                  <div class="chips">
                    @for (s of i.outputs ?? []; track s.name + ':' + s.kind) {
                      <span
                        class="chip mono accent"
                        nz-tooltip
                        [nzTooltipTitle]="kindLabel(s.kind)"
                        >{{ s.name }}</span
                      >
                    } @empty {
                      <span class="quiet">-</span>
                    }
                  </div>
                </td>
                <td>
                  <span class="mono-cell">{{ i.ports.join(' · ') || '-' }}</span>
                </td>
                <td>
                  @if (i.implementedBy?.length) {
                    <span class="mono-cell" [attr.title]="i.implementedBy?.join(', ')">{{
                      i.implementedBy?.join(', ')
                    }}</span>
                  } @else {
                    <span class="quiet" i18n="@@library.interfaces.notImplemented"
                      >not implemented yet</span
                    >
                  }
                </td>
                <td (click)="$event.stopPropagation()">
                  <div class="row-actions">
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      (click)="edit(i)"
                      [attr.aria-label]="rowActionLabel(i.name)"
                    >
                      <nz-icon [nzType]="canManage() ? 'edit' : 'eye'" />
                    </button>
                    @if (canManage()) {
                      <button
                        nz-button
                        nzType="text"
                        nzSize="small"
                        class="del"
                        type="button"
                        (click)="remove(i)"
                        [disabled]="!!i.builtIn"
                        nz-tooltip
                        [nzTooltipTitle]="i.builtIn ? text.builtInNoDelete : text.delete"
                        [attr.aria-label]="deleteLabel(i.name)"
                      >
                        <nz-icon nzType="delete" />
                      </button>
                    }
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>

    <bo-side-panel
      [open]="panelOpen()"
      [title]="editing() ? editing()!.name : text.newTitle"
      [subtitle]="editing() ? editing()!.key : text.newSubtitle"
      [defaultWidth]="640"
      storageKey="backoffice.panelWidth.interface"
      [bodyLabel]="canManage() ? null : text.details"
      initialFocus="#ti-name"
      (closed)="requestClose()"
    >
      @if (editing()?.builtIn) {
        <span boPanelHeader class="chip outline"
          ><nz-icon nzType="lock" /> <span i18n="@@library.interfaces.builtIn">built-in</span></span
        >
      }
      <form
        nz-form
        nzLayout="vertical"
        [boReadonly]="!canManage()"
        (submit)="$event.preventDefault()"
      >
        <div class="grid">
          <nz-form-item>
            <nz-form-label nzRequired nzFor="ti-key" i18n="@@library.interfaces.key"
              >Key</nz-form-label
            >
            <nz-form-control
              [nzValidateStatus]="keyError() ? 'error' : ''"
              [nzErrorTip]="keyError() ?? ''"
            >
              <input
                nz-input
                id="ti-key"
                name="key"
                class="mono"
                [ngModel]="draft().key"
                (ngModelChange)="patch({ key: $event })"
                [readOnly]="!!editing()"
                placeholder="reviewer"
                i18n-placeholder="@@library.interfaces.key.placeholder"
              />
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzRequired nzFor="ti-name" i18n="@@library.interfaces.name"
              >Name</nz-form-label
            >
            <nz-form-control>
              <input
                nz-input
                id="ti-name"
                name="name"
                [ngModel]="draft().name"
                (ngModelChange)="patch({ name: $event })"
                placeholder="Reviewer"
                i18n-placeholder="@@library.interfaces.name.placeholder"
              />
            </nz-form-control>
          </nz-form-item>
        </div>
        <nz-form-item>
          <nz-form-label nzFor="ti-desc" i18n="@@library.interfaces.description"
            >Description</nz-form-label
          >
          <nz-form-control>
            <textarea
              nz-input
              id="ti-desc"
              name="description"
              [ngModel]="draft().description ?? ''"
              (ngModelChange)="patch({ description: $event })"
              [nzAutosize]="{ minRows: 2, maxRows: 4 }"
            ></textarea>
          </nz-form-control>
        </nz-form-item>

        @for (list of lists; track list.id) {
          <div class="slots-head">
            <h3 class="sec">
              {{ list.title }} <span class="count">{{ (draft()[list.id] ?? []).length }}</span>
            </h3>
            @if (canManage()) {
              <button nz-button nzSize="small" type="button" (click)="addSlot(list.id)">
                <nz-icon nzType="plus" /> {{ list.add }}
              </button>
            }
          </div>
          @if (!(draft()[list.id] ?? []).length) {
            <p class="none">{{ list.none }}</p>
          }
          @for (s of draft()[list.id] ?? []; track $index; let i = $index) {
            <div class="slot" [class.inputs]="list.id === 'inputs'">
              <input
                nz-input
                nzSize="small"
                class="mono"
                [ngModel]="s.name"
                (ngModelChange)="patchSlot(list.id, i, { name: $event })"
                [name]="list.id + '-name-' + i"
                [attr.aria-label]="slotLabel(list.id, i, 'name')"
                placeholder="name"
                i18n-placeholder="@@library.interfaces.slot.namePlaceholder"
              />
              <nz-select
                nzSize="small"
                [ngModel]="s.kind"
                (ngModelChange)="patchSlot(list.id, i, { kind: $event })"
                [name]="list.id + '-kind-' + i"
                [attr.aria-label]="slotLabel(list.id, i, 'kind')"
              >
                @for (k of list.id === 'inputs' ? slotKinds : outputKinds; track k) {
                  <nz-option [nzValue]="k" [nzLabel]="kindLabel(k)" />
                }
              </nz-select>
              @if (list.id === 'inputs') {
                <label class="req">
                  <nz-switch
                    nzSize="small"
                    [ngModel]="s.required !== false"
                    (ngModelChange)="patchSlot('inputs', i, { required: $event })"
                    [name]="'inputs-req-' + i"
                    [attr.aria-label]="slotLabel('inputs', i, 'required')"
                  />
                  <span i18n="@@library.interfaces.slot.required">required</span>
                </label>
              }
              <input
                nz-input
                nzSize="small"
                class="desc"
                [ngModel]="s.description ?? ''"
                (ngModelChange)="patchSlot(list.id, i, { description: $event || null })"
                [name]="list.id + '-desc-' + i"
                [attr.aria-label]="slotLabel(list.id, i, 'description')"
                placeholder="What it holds"
                i18n-placeholder="@@library.interfaces.slot.descPlaceholder"
              />
              @if (canManage()) {
                <button
                  nz-button
                  nzSize="small"
                  nzType="text"
                  type="button"
                  (click)="removeSlot(list.id, i)"
                  [attr.aria-label]="removeSlotLabel(list.id, s.name || '' + (i + 1))"
                >
                  <nz-icon nzType="close" />
                </button>
              }
            </div>
          }
        }

        <nz-form-item style="margin-top: 16px">
          <nz-form-label nzFor="ti-ports" i18n="@@library.interfaces.ports">Ports</nz-form-label>
          <nz-form-control
            nzExtra="Output ports a task node of this interface exposes on the canvas."
            i18n-nzExtra="@@library.interfaces.ports.extra"
          >
            <nz-select
              id="ti-ports"
              name="ports"
              nzMode="tags"
              [ngModel]="draft().ports"
              (ngModelChange)="patch({ ports: $event })"
              nzPlaceHolder="success, failure"
              i18n-nzPlaceHolder="@@library.interfaces.ports.placeholder"
            >
              <nz-option
                nzValue="success"
                nzLabel="success"
                i18n-nzLabel="@@library.interfaces.port.success"
              />
              <nz-option
                nzValue="failure"
                nzLabel="failure"
                i18n-nzLabel="@@library.interfaces.port.failure"
              />
            </nz-select>
          </nz-form-control>
        </nz-form-item>
        @if (editing()?.implementedBy?.length) {
          <p class="impl" i18n="@@library.interfaces.implementedByNote">
            Implemented by
            <span class="mono">{{ editing()?.implementedBy?.join(', ') }}</span
            >. Changing the contract is checked against them when they resolve.
          </p>
        }
      </form>
      <div boPanelFooter>
        <bo-action-bar
          [sticky]="false"
          [dirty]="dirty()"
          [saving]="saving()"
          [canSave]="!keyError() && !!draft().name.trim()"
          [showSave]="panelOpen() && canManage()"
          [saveLabel]="editing() ? text.save : text.create"
          [cleanLabel]="canManage() ? text.noChanges : text.readOnly"
          (save)="save()"
        >
          <button nz-button type="button" (click)="requestClose()">
            {{ dirty() ? text.discard : text.close }}
          </button>
        </bo-action-bar>
      </div>
    </bo-side-panel>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 0 16px;
      }
      .slots-head {
        display: flex;
        align-items: center;
        gap: 12px;
        margin: 16px 0 8px;
      }
      .sec {
        margin: 0;
        font-size: 13px;
        font-weight: 600;
        flex: 1;
      }
      .count {
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
        font-weight: 400;
        color: var(--bo-ink-3);
      }
      .none {
        margin: 0 0 8px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .slot {
        display: grid;
        grid-template-columns: 130px 110px minmax(0, 1fr) 28px;
        gap: 6px;
        align-items: center;
        margin-bottom: 6px;
      }
      .slot.inputs {
        grid-template-columns: 130px 110px 92px minmax(0, 1fr) 28px;
      }
      .req {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .impl {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      [boPanelFooter] {
        display: block;
        margin: -13px -20px -12px;
      }
    `,
  ],
})
export class InterfacesPageComponent {
  private readonly api = inject(TaskLibraryApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly identity = inject(IdentityService);
  private readonly bar = viewChild<FilterBarComponent>('bar');
  /** Interfaces are global: every change needs `library.manage`. */
  readonly canManage = computed(() => this.identity.can('library.manage'));

  readonly f = urlFilters({ q: 'string' });
  readonly panel = panelParam('interface');
  readonly items = signal<TaskInterface[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly saving = signal(false);
  readonly draft = signal<TaskInterfaceInput>(structuredClone(EMPTY));
  private readonly initialJson = signal(JSON.stringify(EMPTY));
  readonly slotKinds = SLOT_KINDS;
  readonly outputKinds = ['STATE', 'ARTIFACT'] as const;
  readonly lists = [
    {
      id: 'inputs',
      title: $localize`:@@library.interfaces.inputs:Inputs`,
      add: $localize`:@@library.interfaces.addInput:Add input`,
      none: $localize`:@@library.interfaces.noInputs:No inputs.`,
    },
    {
      id: 'outputs',
      title: $localize`:@@library.interfaces.outputs:Outputs`,
      add: $localize`:@@library.interfaces.addOutput:Add output`,
      none: $localize`:@@library.interfaces.noOutputs:No outputs.`,
    },
  ] as const;
  readonly text = {
    reload: $localize`:@@library.interfaces.reload:Reload interfaces`,
    create: $localize`:@@library.interfaces.create:Create interface`,
    save: $localize`:@@library.interfaces.save:Save interface`,
    builtInNoDelete: $localize`:@@library.interfaces.builtInNoDelete:Built-in interfaces cannot be deleted`,
    delete: $localize`:@@library.interfaces.delete:Delete interface`,
    newTitle: $localize`:@@library.interfaces.newTitle:New interface`,
    newSubtitle: $localize`:@@library.interfaces.newSubtitle:A contract templates can implement`,
    details: $localize`:@@library.interfaces.details:Interface details`,
    noChanges: $localize`:@@library.interfaces.noChanges:No changes`,
    readOnly: $localize`:@@library.interfaces.readOnly:Read-only: needs library.manage`,
    discard: $localize`:@@library.interfaces.discard:Discard changes`,
    close: $localize`:@@library.interfaces.close:Close`,
  };

  readonly filtered = computed(() => filterInterfaces(this.items(), this.f.values().q));
  readonly editing = computed(() => {
    const key = this.panel.value();
    return key && key !== 'new' ? (this.items().find((i) => i.key === key) ?? null) : null;
  });
  readonly panelOpen = computed(() => {
    const key = this.panel.value();
    return key === 'new' ? this.canManage() : !!this.editing();
  });
  readonly dirty = computed(
    () =>
      this.panelOpen() && this.canManage() && JSON.stringify(this.draft()) !== this.initialJson(),
  );

  readonly keyError = computed(() => {
    const k = this.draft().key;
    if (!k) return $localize`:@@library.interfaces.required:Required`;
    if (!SLUG_PATTERN.test(k) || k.length < 2)
      return $localize`:@@library.interfaces.keySlug:Slug: lowercase letters, digits, dashes`;
    if (!this.editing() && this.items().some((i) => i.key === k))
      return $localize`:@@library.interfaces.keyTaken:Key already exists`;
    return null;
  });

  readonly nav = listNavigation({
    items: this.filtered,
    open: (i) => this.edit(i),
    group: $localize`:@@library.interfaces.group:Interfaces`,
    when: () => !this.panelOpen(),
  });
  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@library.interfaces.crumb.library:Library`, link: '/library/templates' },
      { label: $localize`:@@library.interfaces.crumb.interfaces:Interfaces` },
    ]);
    this.load();
    effect(() => {
      const e = this.editing();
      this.panel.value();
      untracked(() => {
        const d: TaskInterfaceInput = e
          ? {
              key: e.key,
              name: e.name,
              description: e.description ?? '',
              inputs: structuredClone(e.inputs ?? []),
              outputs: structuredClone(e.outputs ?? []),
              ports: [...(e.ports ?? ['success', 'failure'])],
            }
          : structuredClone(EMPTY);
        this.draft.set(d);
        this.initialJson.set(JSON.stringify(d));
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@library.interfaces.key.search:Search interfaces`,
          group: $localize`:@@library.interfaces.group:Interfaces`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'c',
          description: $localize`:@@library.interfaces.create:Create interface`,
          group: $localize`:@@library.interfaces.group:Interfaces`,
          when: () => this.canManage() && !this.panelOpen(),
          handler: () => this.create(),
        },
      ],
      inject(DestroyRef),
    );
  }

  kindLabel(kind: string | null | undefined): string {
    if (kind === 'ARTIFACT') return $localize`:@@library.interfaces.kind.artifact:Artefact`;
    if (kind === 'STATE') return $localize`:@@library.interfaces.kind.state:State`;
    if (kind === 'INPUT') return $localize`:@@library.interfaces.kind.input:Run input`;
    return kind ?? '-';
  }

  optionalLabel(kind: string | null | undefined): string {
    return $localize`:@@library.interfaces.kindOptional:${this.kindLabel(kind)}:kind:, optional`;
  }

  rowActionLabel(name: string): string {
    return this.canManage()
      ? $localize`:@@library.interfaces.editRow:Edit ${name}:name:`
      : $localize`:@@library.interfaces.viewRow:View ${name}:name:`;
  }

  deleteLabel(name: string): string {
    return $localize`:@@library.interfaces.deleteRow:Delete ${name}:name:`;
  }

  slotLabel(
    list: 'inputs' | 'outputs',
    index: number,
    part: 'name' | 'kind' | 'description' | 'required',
  ): string {
    const n = index + 1;
    if (list === 'inputs') {
      switch (part) {
        case 'name':
          return $localize`:@@library.interfaces.slot.inputName:Input ${n}:number: name`;
        case 'kind':
          return $localize`:@@library.interfaces.slot.inputKind:Input ${n}:number: kind`;
        case 'description':
          return $localize`:@@library.interfaces.slot.inputDescription:Input ${n}:number: description`;
        case 'required':
          return $localize`:@@library.interfaces.slot.inputRequired:Input ${n}:number: required`;
      }
    }
    switch (part) {
      case 'kind':
        return $localize`:@@library.interfaces.slot.outputKind:Output ${n}:number: kind`;
      case 'description':
        return $localize`:@@library.interfaces.slot.outputDescription:Output ${n}:number: description`;
      default:
        return $localize`:@@library.interfaces.slot.outputName:Output ${n}:number: name`;
    }
  }

  removeSlotLabel(list: 'inputs' | 'outputs', name: string): string {
    return list === 'inputs'
      ? $localize`:@@library.interfaces.slot.removeInput:Remove input ${name}:name:`
      : $localize`:@@library.interfaces.slot.removeOutput:Remove output ${name}:name:`;
  }

  load(): void {
    this.loading.set(true);
    this.api.listInterfaces().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  create(): void {
    this.panel.open('new');
  }

  edit(i: TaskInterface): void {
    this.panel.open(i.key);
  }

  async requestClose(): Promise<void> {
    if (this.dirty()) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@library.interfaces.discard.title:Discard unsaved changes?`,
        content: $localize`:@@library.interfaces.discard.content:The changes to this interface have not been saved.`,
        okText: $localize`:@@library.interfaces.discard:Discard changes`,
        danger: true,
      });
      if (!ok) return;
    }
    this.panel.close();
  }

  patch(changes: Partial<TaskInterfaceInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
  }

  addSlot(list: 'inputs' | 'outputs'): void {
    const slot: Slot = { name: '', kind: 'ARTIFACT', required: true, description: null };
    this.draft.update((d) => ({ ...d, [list]: [...(d[list] ?? []), slot] }));
  }

  patchSlot(list: 'inputs' | 'outputs', index: number, changes: Partial<Slot>): void {
    this.draft.update((d) => ({
      ...d,
      [list]: (d[list] ?? []).map((s, i) => (i === index ? { ...s, ...changes } : s)),
    }));
  }

  removeSlot(list: 'inputs' | 'outputs', index: number): void {
    this.draft.update((d) => ({ ...d, [list]: (d[list] ?? []).filter((_, i) => i !== index) }));
  }

  save(): void {
    const d = this.draft();
    const input: TaskInterfaceInput = {
      ...d,
      inputs: (d.inputs ?? []).filter((s) => s.name.trim()),
      outputs: (d.outputs ?? []).filter((s) => s.name.trim()),
    };
    this.saving.set(true);
    const existing = this.editing();
    const req = existing
      ? this.api.updateInterface(existing.key, input)
      : this.api.createInterface(input);
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.initialJson.set(JSON.stringify(this.draft()));
        this.panel.close();
        this.message.success(
          existing
            ? $localize`:@@library.interfaces.saved:Interface "${saved.key}:key:" saved`
            : $localize`:@@library.interfaces.created:Interface "${saved.key}:key:" created`,
        );
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  async remove(i: TaskInterface): Promise<void> {
    if (i.builtIn) return;
    const used = i.implementedBy?.length ?? 0;
    const ok = await this.confirm.danger(
      $localize`:@@library.interfaces.deleteConfirm.title:Delete interface "${i.key}:key:"?`,
      used
        ? icu(
            $localize`:@@library.interfaces.deleteConfirm.used:{count, plural, one {# template implements} other {# templates implement}} it ({templates}); the server refuses the delete until they no longer do.`,
            { count: used, templates: i.implementedBy?.join(', ') ?? '' },
          )
        : $localize`:@@library.interfaces.deleteConfirm.unused:No template implements it; workflows are not affected.`,
      $localize`:@@library.interfaces.delete:Delete interface`,
    );
    if (!ok) return;
    this.api.deleteInterface(i.key).subscribe(() => {
      this.message.success($localize`:@@library.interfaces.deleted:Interface deleted`);
      if (this.panel.value() === i.key) this.panel.close();
      this.load();
    });
  }
}
