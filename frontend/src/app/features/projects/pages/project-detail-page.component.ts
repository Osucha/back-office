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
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { catchError, of } from 'rxjs';
import { ProjectsApi } from '../../../core/api/projects.service';
import { RunsApi } from '../../../core/api/runs.service';
import { WorkflowsApi } from '../../../core/api/workflows.service';
import type {
  LibraryLink,
  Project,
  ProjectInput,
  StepModelPlan,
  WorkflowDefinition,
  WorkflowInsights,
  WorkflowRunSummary,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { helpTopicForUrl } from '../../../core/help/help-map';
import { projectCan } from '../../../core/auth/project-access';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ProjectsStore, monogram } from '../../../core/state/projects.store';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { planSourceLabel, routeLabel, summarizeModelPlan } from '../../../core/ui/model-options';
import { SpecKitCardComponent } from '../components/spec-kit-card.component';
import { projectRepositories, purposeLabel, shortRepoUrl } from '../../../core/ui/repositories';
import { batonOf, type BatonHolder } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { roleName } from '../../../core/i18n/catalog-labels';
import { icu } from '../../../core/i18n/icu';
import { CostComponent } from '../../../core/ui/relay/cost.component';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { ProjectBudgetPanelComponent } from '../../budgets/public-api';
import {
  ProjectConnectionsPanelComponent,
  ScmConnectionsPanelComponent,
} from '../../connections/public-api';
import { ProjectSandboxPanelComponent } from '../../fleet/public-api';
import { RunsTableComponent } from '../../runs/public-api';
import {
  AdoptWorkflowDialogComponent,
  PublishWorkflowDialogComponent,
  WorkflowMetricsPanelComponent,
  updateAvailable,
} from '../../library/public-api';
import { InsightsApi } from '../../../core/api/insights.service';
import { WorkflowLibraryApi } from '../../../core/api/workflow-library.service';
import { ProjectCiPanelComponent } from '../components/project-ci-panel.component';
import { ProjectFormComponent } from '../components/project-form.component';
import { JiraIntakePanelComponent } from '../components/jira-intake-panel.component';
import { ProjectMembersPanelComponent } from '../components/project-members-panel.component';
import { ProjectSourcesPanelComponent } from '../components/project-sources-panel.component';
import { ProjectWorkspacesPanelComponent } from '../components/project-workspaces-panel.component';

const TABS = [
  'workflows',
  'sources',
  'workspaces',
  'runs',
  'ci',
  'members',
  'connections',
  'sandbox',
  'budget',
  'settings',
];

/**
 * Project: header (monogram, repository, provenance, your roles, what is in flight), then
 * Workflows, Sources, Workspaces, Runs, CI, Members, Connections, Sandbox, Budget and Settings tabs (`?tab=`). `?start=<workflowId>` opens the start
 * dialog (the command palette's "Start run…").
 */
@Component({
  selector: 'app-project-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    NzTabsModule,
    NzButtonModule,
    NzIconModule,
    NzCardModule,
    NzTableModule,
    NzDropDownModule,
    NzModalModule,
    NzInputModule,
    SpecKitCardComponent,
    RunsTableComponent,
    ProjectCiPanelComponent,
    ProjectFormComponent,
    ProjectMembersPanelComponent,
    ProjectSourcesPanelComponent,
    ProjectWorkspacesPanelComponent,
    ProjectBudgetPanelComponent,
    ProjectConnectionsPanelComponent,
    ScmConnectionsPanelComponent,
    ProjectSandboxPanelComponent,
    BatonComponent,
    CostComponent,
    EmptyComponent,
    GlyphComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    SkeletonComponent,
    SortHeaderDirective,
    JiraIntakePanelComponent,
    AdoptWorkflowDialogComponent,
    PublishWorkflowDialogComponent,
    WorkflowMetricsPanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      @if (!project()) {
        <bo-skeleton
          variant="detail"
          label="Loading the project"
          i18n-label="@@projects.detail.loading"
        />
      } @else if (project(); as p) {
        <bo-page-header [title]="p.name" [eyebrow]="p.key" [help]="helpKey()">
          <span boHeaderLead class="tile" aria-hidden="true">{{ mono(p.key) }}</span>
          <div boHeaderMeta class="meta">
            @if (p.personal) {
              <span class="private" i18n="@@projects.detail.private">private project</span
              ><span class="sep">·</span>
            }
            <span>{{ repoSummary() }}</span>
            @if (p.issueTracker?.jiraProjectKey) {
              <span class="sep">·</span><span>Jira {{ p.issueTracker?.jiraProjectKey }}</span>
            }
            @if (p.createdBy) {
              <span class="sep">·</span
              ><bo-provenance
                verb="created by"
                i18n-verb="@@projects.detail.createdBy"
                [actor]="p.createdBy"
                [at]="p.createdAt"
              />
            }
            @if (myRoles().length) {
              <span class="sep">·</span>
              <span>{{ rolesText() }}</span>
            }
          </div>
          @if (repositories().length) {
            <ul
              boHeaderMeta
              class="repo-list"
              aria-label="Repositories"
              i18n-aria-label="@@projects.detail.repositories"
            >
              @for (r of repositories(); track r.key) {
                <li>
                  <span class="mono repo-key">{{ r.key }}</span>
                  @if (r.purpose) {
                    <span class="purpose" [attr.data-purpose]="r.purpose">{{
                      purposeLabel(r.purpose)
                    }}</span>
                  }
                  @if (r.primary) {
                    <span class="primary-mark" i18n="@@projects.detail.primary">primary</span>
                  }
                  <span class="mono repo" [attr.title]="r.description || r.url"
                    >{{ shortUrl(r.url) }} &#64; {{ r.defaultBranch || 'main' }}</span
                  >
                </li>
              }
            </ul>
          }
          @if (can('workflow.edit') && canReadLibrary()) {
            <button
              boHeaderActions
              nz-button
              type="button"
              (click)="adoptOpen.set(true)"
              i18n="@@projects.detail.addFromLibrary"
            >
              Add from library
            </button>
          }
          @if (can('workflow.edit')) {
            <a
              boHeaderActions
              nz-button
              nzType="primary"
              [routerLink]="['/projects', p.id, 'workflows', 'new']"
              i18n="@@projects.detail.newWorkflow"
            >
              New workflow
            </a>
          }
          <dl boHeaderBelow class="stats">
            <div>
              <dt i18n="@@projects.detail.inFlight">In flight</dt>
              <dd>
                @for (h of holdings(); track h.holder) {
                  <span class="hold"
                    ><bo-glyph [holder]="h.holder" [live]="h.holder !== 'you'" [size]="11" />
                    {{ h.label }}</span
                  >
                } @empty {
                  <span class="quiet" i18n="@@projects.detail.nothing">nothing</span>
                }
              </dd>
            </div>
            <div>
              <dt i18n="@@projects.detail.openGates">Open gates</dt>
              <dd [class.you]="(p.stats?.openApprovals ?? 0) > 0">
                {{ p.stats?.openApprovals ?? 0 }}
              </dd>
            </div>
            <div>
              <dt i18n="@@projects.detail.ci7days">CI, 7 days</dt>
              <dd>{{ p.stats?.ciExecutionsLast7Days ?? 0 }}</dd>
            </div>
            <div>
              <dt i18n="@@projects.detail.cost30days">Cost, 30 days</dt>
              <dd><bo-cost [value]="p.stats?.costLast30Days" /></dd>
            </div>
          </dl>
        </bo-page-header>

        <nz-tabs
          [nzSelectedIndex]="tabIndex()"
          (nzSelectedIndexChange)="setTab($event)"
          [nzAnimated]="false"
        >
          <nz-tab
            [nzTitle]="text.workflowsTab + (workflows().length ? ' ' + workflows().length : '')"
          >
            @if (workflowsLoading()) {
              <bo-skeleton variant="table" [rows]="3" [columns]="5" />
            } @else if (!workflows().length) {
              <bo-empty
                [title]="can('workflow.edit') ? text.noWorkflows : text.noWorkflowsReadOnly"
                [detail]="can('workflow.edit') ? text.noWorkflowsDetail : null"
                [actionLabel]="can('workflow.edit') ? text.newWorkflow : null"
                help="designer"
                guideLabel="How workflows work"
                i18n-guideLabel="@@projects.detail.workflows.guide"
                (action)="router.navigate(['/projects', p.id, 'workflows', 'new'])"
              />
            } @else {
              <nz-table [nzData]="workflowRows()" [nzShowPagination]="false" nzSize="middle">
                <thead>
                  <tr>
                    <th
                      nzShowSort
                      [boSort]="workflowSort"
                      boSortKey="name"
                      i18n="@@projects.detail.workflows.workflow"
                    >
                      Workflow
                    </th>
                    <th
                      nzShowSort
                      [boSort]="workflowSort"
                      boSortKey="triggers"
                      nzWidth="186px"
                      i18n="@@projects.detail.workflows.triggers"
                    >
                      Triggers
                    </th>
                    <th
                      nzShowSort
                      [boSort]="workflowSort"
                      boSortKey="state"
                      nzWidth="136px"
                      i18n="@@projects.detail.workflows.state"
                    >
                      State
                    </th>
                    <th
                      nzShowSort
                      [boSort]="workflowSort"
                      boSortKey="lastChange"
                      nzWidth="220px"
                      i18n="@@projects.detail.workflows.lastChange"
                    >
                      Last change
                    </th>
                    <th nzWidth="230px" nzAlign="right">
                      <span class="sr-only" i18n="@@projects.detail.workflows.actions"
                        >Actions</span
                      >
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (w of workflowRows(); track w.id) {
                    <tr>
                      <td>
                        <a class="wf-name" [routerLink]="['/workflows', w.id, 'edit']">{{
                          w.name
                        }}</a>
                        <span class="muted mono small"> v{{ w.version }} · {{ nodesText(w) }}</span>
                        @if (w.library; as lib) {
                          <div class="lib-line">
                            <a
                              class="chip outline mono"
                              [routerLink]="['/library/workflows', lib.libraryWorkflowId]"
                              [attr.title]="libraryTitle(lib.name)"
                              >{{ lib.key }} v{{ lib.version }}</a
                            >
                            @if (behind(lib)) {
                              <span
                                class="chip you"
                                i18n="@@projects.detail.workflows.libraryUpdate"
                                >v{{ lib.latestVersion }} available</span
                              >
                            }
                            @if (lib.modified) {
                              <span
                                class="chip outline"
                                i18n="@@projects.detail.workflows.libraryModified"
                                >changed locally</span
                              >
                            }
                            @if (lib.deprecated) {
                              <span
                                class="chip outline"
                                i18n="@@projects.detail.workflows.libraryDeprecated"
                                >deprecated in the library</span
                              >
                            }
                          </div>
                        }
                        @if (w.description) {
                          <div class="muted small clamp">{{ w.description }}</div>
                        }
                      </td>
                      <td class="small">{{ triggerText(w) }}</td>
                      <td>
                        <bo-baton
                          size="sm"
                          appearance="plain"
                          [holder]="w.draft ? 'blocked' : w.enabled === false ? 'idle' : 'done'"
                          [label]="
                            w.draft
                              ? text.draft
                              : w.enabled === false
                                ? text.disabled
                                : text.enabled
                          "
                        />
                      </td>
                      <td>
                        <bo-provenance [actor]="w.updatedBy" [at]="w.updatedAt" />
                      </td>
                      <td nzAlign="right">
                        <div class="row-actions">
                          @if (can('run.start')) {
                            <button
                              nz-button
                              nzSize="small"
                              nzType="primary"
                              type="button"
                              (click)="openStart(w)"
                              [disabled]="w.enabled === false || w.draft"
                              i18n="@@projects.detail.workflows.startRun"
                            >
                              Start run
                            </button>
                          }
                          <button
                            nz-button
                            nzSize="small"
                            nzType="text"
                            type="button"
                            (click)="metricsFor.set(w)"
                            [attr.aria-label]="metricsLabel(w)"
                            [attr.title]="metricsLabel(w)"
                          >
                            <nz-icon nzType="line-chart" />
                          </button>
                          <a nz-button nzSize="small" [routerLink]="['/workflows', w.id, 'edit']">
                            @if (can('workflow.edit')) {
                              <span i18n="@@projects.detail.workflows.edit">Edit</span>
                            } @else {
                              <span i18n="@@projects.detail.workflows.view">View</span>
                            }
                          </a>
                          @if (can('workflow.edit')) {
                            <button
                              nz-button
                              nzSize="small"
                              nzType="text"
                              type="button"
                              nz-dropdown
                              [nzDropdownMenu]="wfMenu"
                              nzTrigger="click"
                              nzPlacement="bottomRight"
                              [attr.aria-label]="moreActionsLabel(w)"
                            >
                              <nz-icon nzType="more" />
                            </button>
                            <nz-dropdown-menu #wfMenu="nzDropdownMenu">
                              <ul nz-menu>
                                <li nz-menu-item (click)="duplicate(w)">
                                  <nz-icon nzType="copy" />
                                  <span i18n="@@projects.detail.workflows.duplicate"
                                    >Duplicate workflow</span
                                  >
                                </li>
                                @if (canManageLibrary()) {
                                  <li nz-menu-item (click)="publishing.set(w)">
                                    <nz-icon nzType="cloud-upload" />
                                    <span i18n="@@projects.detail.workflows.publish"
                                      >Publish to library</span
                                    >
                                  </li>
                                }
                                @if (w.library; as lib) {
                                  @if (behind(lib) || lib.modified) {
                                    <li nz-menu-item (click)="updateFromLibrary(w)">
                                      <nz-icon nzType="sync" />
                                      <span i18n="@@projects.detail.workflows.libraryUpdateAction"
                                        >Update from library</span
                                      >
                                    </li>
                                  }
                                  <li nz-menu-item (click)="detach(w)">
                                    <nz-icon nzType="disconnect" />
                                    <span i18n="@@projects.detail.workflows.detach"
                                      >Detach from library</span
                                    >
                                  </li>
                                }
                                <li nz-menu-item nzDanger (click)="deleteWorkflow(w)">
                                  <nz-icon nzType="delete" />
                                  <span i18n="@@projects.detail.workflows.delete"
                                    >Delete workflow</span
                                  >
                                </li>
                              </ul>
                            </nz-dropdown-menu>
                          }
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </nz-table>
            }
          </nz-tab>
          <nz-tab nzTitle="Sources" i18n-nzTitle="@@projects.detail.tab.sources">
            <ng-template nz-tab>
              <div class="two-col">
                <nz-card
                  nzTitle="Project repositories"
                  i18n-nzTitle="@@projects.detail.sources.repositories"
                >
                  <app-project-form
                    [project]="p"
                    sections="sources"
                    [saving]="saving()"
                    [cancellable]="false"
                    [readonly]="!can('project.manage')"
                    [serverError]="saveError()"
                    [specKitAllowed]="specKitAllowed()"
                    (saved)="save($event)"
                  />
                </nz-card>
                <app-project-sources-panel />
              </div>
              @defer (on immediate) {
                <app-scm-connections-panel
                  class="scm-tokens"
                  [projectId]="p.id"
                  [repositories]="repositories()"
                  [canManage]="can('project.connections.manage')"
                />
              } @placeholder {
                <bo-skeleton variant="table" [rows]="2" [columns]="4" />
              }
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Workspaces" i18n-nzTitle="@@projects.detail.tab.workspaces">
            <ng-template nz-tab>
              <app-project-workspaces-panel
                [projectId]="p.id"
                [repositories]="repositories()"
                [canManage]="can('project.manage')"
              />
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Runs" i18n-nzTitle="@@projects.detail.tab.runs">
            <ng-template nz-tab>
              <app-runs-table
                [projectId]="p.id"
                emptyText="No runs for this project yet."
                i18n-emptyText="@@projects.detail.runs.empty"
              />
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="CI">
            <ng-template nz-tab>
              <app-project-ci-panel
                [projectId]="p.id"
                [workflows]="workflows()"
                [repositories]="repositories()"
                [canManage]="can('project.manage')"
                [canManageTokens]="can('project.ci.manage')"
              />
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Members" i18n-nzTitle="@@projects.detail.tab.members">
            <ng-template nz-tab>
              @if (p.personal) {
                <p class="private-note" i18n="@@projects.detail.privateMembers">
                  A private project has only its owner (ADR 0038): its workflows and runs are yours.
                  To work with others, use a team project, created by a project manager.
                </p>
              }
              <app-project-members-panel
                [projectId]="p.id"
                [canManage]="can('project.members.manage') && !p.personal"
              />
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Connections" i18n-nzTitle="@@projects.detail.tab.connections">
            <ng-template nz-tab>
              @defer (on immediate) {
                <app-project-connections-panel
                  [projectId]="p.id"
                  [canManage]="can('project.connections.manage')"
                />
              } @placeholder {
                <bo-skeleton variant="table" [rows]="3" [columns]="5" />
              }
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Sandbox" i18n-nzTitle="@@projects.detail.tab.sandbox">
            <ng-template nz-tab>
              @defer (on immediate) {
                <app-project-sandbox-panel [projectId]="p.id" [canManage]="can('project.manage')" />
              } @placeholder {
                <bo-skeleton variant="detail" />
              }
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Budget" i18n-nzTitle="@@projects.detail.tab.budget">
            <ng-template nz-tab>
              @defer (on immediate) {
                <app-project-budget-panel [projectId]="p.id" [canManage]="can('project.manage')" />
              } @placeholder {
                <bo-skeleton variant="cards" [rows]="1" />
              }
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Settings" i18n-nzTitle="@@projects.detail.tab.settings">
            <ng-template nz-tab>
              <div class="two-col">
                <nz-card [nzTitle]="can('project.manage') ? text.settings : text.settingsReadOnly">
                  <app-project-form
                    [project]="p"
                    sections="settings"
                    [saving]="saving()"
                    [cancellable]="false"
                    [readonly]="!can('project.manage')"
                    [serverError]="saveError()"
                    [specKitAllowed]="specKitAllowed()"
                    (saved)="save($event)"
                  />
                </nz-card>
                <div class="stack">
                  <app-jira-intake-panel
                    [projectId]="p.id"
                    [workflows]="workflows()"
                    [jiraEnabled]="!!p.issueTracker"
                  />
                  <app-spec-kit-card
                    [projectId]="p.id"
                    [version]="p.updatedAt"
                    [constitution]="p.constitution ?? ''"
                    [canEdit]="can('workflow.edit')"
                    (workflowsAdded)="reloadWorkflows()"
                  />
                  @if (canDelete()) {
                    <nz-card
                      nzTitle="Delete this project"
                      i18n-nzTitle="@@projects.detail.deleteCard"
                    >
                      <p class="muted" i18n="@@projects.detail.deleteText">
                        Deleting removes the workflows, CI tokens and history of {{ p.key }}. Cancel
                        runs in flight first; only owners can delete.
                      </p>
                      <button
                        nz-button
                        nzDanger
                        type="button"
                        (click)="deleteProject()"
                        i18n="@@projects.detail.deleteButton"
                      >
                        Delete project {{ p.key }}
                      </button>
                    </nz-card>
                  }
                </div>
              </div>
            </ng-template>
          </nz-tab>
        </nz-tabs>
      }
    </div>

    <nz-modal
      [nzVisible]="!!starting()"
      [nzTitle]="startTitle()"
      nzOkText="Start run"
      i18n-nzOkText="@@projects.start.ok"
      nzCancelText="Cancel"
      i18n-nzCancelText="@@projects.start.cancel"
      [nzOkLoading]="startingBusy()"
      [nzOkDisabled]="planBlocked()"
      (nzOnOk)="startRun()"
      (nzOnCancel)="closeStart()"
    >
      <ng-container *nzModalContent>
        <label for="issue-key" class="field-label" i18n="@@projects.start.issueKey"
          >Jira issue key (optional)</label
        >
        <input
          id="issue-key"
          nz-input
          [(ngModel)]="issueKey"
          [placeholder]="(project()?.issueTracker?.jiraProjectKey || 'ABC') + '-123'"
          class="mono"
          style="margin: 4px 0 12px"
          (keydown.enter)="startRun()"
        />
        <label for="run-params" class="field-label" i18n="@@projects.start.parameters"
          >Parameters (JSON, optional)</label
        >
        <textarea
          id="run-params"
          nz-input
          class="mono-textarea"
          [(ngModel)]="paramsJson"
          [nzAutosize]="{ minRows: 3, maxRows: 10 }"
          placeholder="{ }"
        ></textarea>
        @if (paramsError()) {
          <div class="danger small">{{ paramsError() }}</div>
        }
        <div class="field-label plan-label" i18n="@@projects.start.models">Models of the steps</div>
        @if (modelPlanLoading()) {
          <div class="muted small" i18n="@@projects.start.modelsLoading">
            Checking the models...
          </div>
        } @else if (modelPlan(); as plan) {
          @if (plan.length) {
            <ul class="model-plan">
              @for (s of plan; track s.nodeId) {
                <li>
                  <div class="plan-row">
                    <span class="truncate plan-step">{{ s.nodeName }}</span>
                    <span class="truncate">{{ s.model?.displayName ?? text.noModel }}</span>
                    <span class="muted small">{{ sourceLabel(s.source) }}</span>
                    @if (s.model) {
                      <span class="plan-route" [class.own]="s.model.route === 'OWN_LOGIN'">{{
                        route(s.model.route)
                      }}</span>
                    }
                  </div>
                  @if (s.problem) {
                    <div class="danger small">{{ s.problem }}</div>
                  } @else if (s.note || s.model?.note) {
                    <div class="warning small">{{ s.note || s.model?.note }}</div>
                  }
                </li>
              }
            </ul>
          } @else {
            <div class="muted small" i18n="@@projects.start.noModelSteps">
              No step of this workflow uses a model.
            </div>
          }
          @if (planBlocked()) {
            <div class="danger small" role="alert" i18n="@@projects.start.blocked">
              Runs of this workflow cannot start until every step has a model you can use: change
              the step, the template or the project default.
            </div>
          }
        } @else {
          <div class="muted small" i18n="@@projects.start.modelsUnavailable">
            The models of the steps could not be checked; the server checks them when the run
            starts.
          </div>
        }
      </ng-container>
    </nz-modal>

    @if (project(); as p) {
      <app-adopt-workflow-dialog
        [open]="adoptOpen()"
        [fixedProjectId]="p.id"
        (adopted)="onAdopted()"
        (closed)="adoptOpen.set(false)"
      />
    }
    <app-publish-workflow-dialog
      [workflow]="publishing()"
      (published)="onPublished()"
      (closed)="publishing.set(null)"
    />
    <nz-modal
      [nzVisible]="!!metricsFor()"
      [nzTitle]="metricsTitle()"
      [nzFooter]="null"
      nzWidth="1100px"
      (nzOnCancel)="metricsFor.set(null)"
    >
      <ng-container *nzModalContent>
        <p class="metrics-scope" i18n="@@projects.detail.metrics.scope">
          Runs of this workflow started in the last 30 days, all versions.
        </p>
        <app-workflow-metrics-panel [insights]="workflowInsights()" scope="workflow" />
      </ng-container>
    </nz-modal>
  `,
  styles: [
    `
      .scm-tokens {
        display: block;
        margin-top: var(--bo-space-4);
      }
      .private {
        padding: 0 8px;
        border-radius: 999px;
        font-size: 11.5px;
        font-weight: 500;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      .plan-label {
        display: block;
        margin-top: 12px;
      }
      .model-plan {
        list-style: none;
        margin: 4px 0 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .plan-row {
        display: grid;
        grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) auto auto;
        gap: 8px;
        align-items: center;
        font-size: 13px;
      }
      .plan-step {
        font-weight: 500;
      }
      .plan-route {
        padding: 0 6px;
        border-radius: 999px;
        font-size: 11px;
        color: var(--bo-ink-2);
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      .plan-route.own {
        color: var(--bo-agent-ink);
      }
      .warning {
        color: var(--bo-you-ink);
      }
      .private-note {
        margin: 0 0 12px;
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .lib-line {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        margin-top: 4px;
      }
      .lib-line .chip {
        display: inline-flex;
        align-items: center;
        height: 20px;
        padding: 0 8px;
        border-radius: 999px;
        font-size: 11.5px;
        font-weight: 500;
        white-space: nowrap;
        color: var(--bo-ink-2);
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      .lib-line .chip.mono {
        font-family: var(--bo-font-mono);
        font-weight: 400;
      }
      .lib-line .chip.you {
        box-shadow: none;
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
      }
      .metrics-scope {
        margin: 0 0 12px;
        font-size: 12.5px;
        color: var(--bo-ink-2);
      }
      .tile {
        width: 44px;
        height: 44px;
        border-radius: 11px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--bo-ink);
        color: var(--bo-on-ink);
        font-family: var(--bo-font-mono);
        font-weight: 600;
        font-size: 15px;
      }
      .meta {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 4px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .repo {
        font-size: 11.5px;
        overflow-wrap: anywhere;
      }
      .repo-list {
        list-style: none;
        margin: 4px 0 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 2px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .repo-list li {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 6px;
      }
      .repo-key {
        color: var(--bo-ink-2);
        font-weight: 500;
      }
      .purpose,
      .primary-mark {
        font-size: 11px;
        padding: 0 6px;
        border-radius: 999px;
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        color: var(--bo-ink-2);
      }
      .primary-mark {
        background: var(--bo-agent-soft);
        border-color: transparent;
        color: var(--bo-agent-ink);
      }
      .sep {
        margin: 0 4px;
      }
      .stats {
        display: flex;
        flex-wrap: wrap;
        gap: 12px 40px;
        margin: 0;
        padding: 12px 16px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .stats dt {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .stats dd {
        margin: 2px 0 0;
        font-size: 14px;
        font-weight: 500;
        display: flex;
        gap: 12px;
        font-variant-numeric: tabular-nums;
      }
      .stats dd.you {
        color: var(--bo-you-ink);
      }
      .hold {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        font-size: 13px;
      }
      .quiet {
        color: var(--bo-ink-3);
        font-weight: 400;
      }
      .wf-name {
        font-weight: 500;
        color: var(--bo-ink);
      }
      .small {
        font-size: 12px;
      }
      .clamp {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        max-width: 60ch;
      }
      .row-actions {
        display: inline-flex;
        gap: 6px;
        align-items: center;
      }
      .field-label {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
    `,
  ],
})
export class ProjectDetailPageComponent {
  private readonly projectsApi = inject(ProjectsApi);
  private readonly workflowsApi = inject(WorkflowsApi);
  private readonly runsApi = inject(RunsApi);
  private readonly libraryApi = inject(WorkflowLibraryApi);
  private readonly insightsApi = inject(InsightsApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly identity = inject(IdentityService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly store = inject(ProjectsStore);
  private readonly crumbs = inject(BreadcrumbService);
  readonly router = inject(Router);

  readonly projectId = input.required<string>();
  /** Optional `?tab=` query param (workflows | sources | workspaces | runs | ci | members | connections | sandbox | budget | settings). */
  readonly tab = input<string | undefined>();
  /** Optional `?start=<workflowId>`: open the start dialog. */
  readonly start = input<string | undefined>();

  readonly project = signal<Project | null>(null);
  readonly workflows = signal<WorkflowDefinition[]>([]);
  readonly workflowsLoading = signal(false);
  /** ADR 0036: the platform allows Spec Kit (the form explains when it does not). */
  readonly specKitAllowed = signal(true);
  readonly inFlight = signal<WorkflowRunSummary[]>([]);
  readonly tabIndex = signal(0);
  readonly saving = signal(false);
  readonly saveError = signal<unknown>(null);
  readonly starting = signal<WorkflowDefinition | null>(null);
  readonly startingBusy = signal(false);
  readonly paramsError = signal<string | null>(null);
  /** ADR 0050: the model of every step of the workflow being started; null when it could not be loaded. */
  readonly modelPlan = signal<StepModelPlan[] | null>(null);
  readonly modelPlanLoading = signal(false);
  /** A step has no usable model: the server refuses the run, so Start is disabled. */
  readonly planBlocked = computed(() => {
    const plan = this.modelPlan();
    return !!plan && summarizeModelPlan(plan).blocked > 0;
  });
  readonly sourceLabel = planSourceLabel;
  readonly route = routeLabel;
  /** Workflow library (ADR 0037): "Add from library", the workflow being published, the workflow whose metrics are open. */
  readonly adoptOpen = signal(false);
  readonly publishing = signal<WorkflowDefinition | null>(null);
  readonly metricsFor = signal<WorkflowDefinition | null>(null);
  readonly workflowInsights = signal<WorkflowInsights | null>(null);
  readonly canReadLibrary = computed(() => this.identity.can('library.view'));
  readonly canManageLibrary = computed(() => this.identity.can('library.manage'));
  readonly metricsTitle = computed(
    () =>
      $localize`:@@projects.detail.metrics.title:Performance: ${this.metricsFor()?.name ?? ''}:workflow:`,
  );
  issueKey = '';
  paramsJson = '';

  readonly myRoles = computed(() => {
    const p = this.project();
    return p?.myRoles?.length ? p.myRoles : this.identity.projectRoles(p?.id);
  });
  /**
   * Deleting needs `project.manage`; the built-in `maintainer` holds it but may not delete, so the
   * button additionally requires `project.members.manage` (owner-level). The backend decides.
   */
  readonly canDelete = computed(
    () => this.can('project.manage') && this.can('project.members.manage'),
  );
  /** The project's repositories (ADR 0034); empty = LLM work only. */
  readonly repositories = computed(() => projectRepositories(this.project()));
  readonly repoSummary = computed(() => {
    const n = this.repositories().length;
    if (!n) return $localize`:@@projects.detail.noRepository:No repository - LLM work only`;
    return icu(
      $localize`:@@projects.detail.repositoryCount:{count, plural, one {# repository} other {# repositories}}`,
      { count: n },
    );
  });
  readonly purposeLabel = purposeLabel;
  readonly shortUrl = shortRepoUrl;
  /** Contextual help follows the open tab. */
  readonly helpKey = computed(() => helpTopicForUrl(`/projects/x?tab=${TABS[this.tabIndex()]}`));
  readonly holdings = computed(() => {
    const counts = new Map<BatonHolder, number>();
    for (const r of this.inFlight()) {
      const h = batonOf.run(r).holder;
      counts.set(h, (counts.get(h) ?? 0) + 1);
    }
    const label: Partial<Record<BatonHolder, string>> = {
      agent: $localize`:@@projects.detail.hold.agent:{count, plural, other {# with agents}}`,
      ci: $localize`:@@projects.detail.hold.ci:{count, plural, other {# waiting for CI}}`,
      you: $localize`:@@projects.detail.hold.you:{count, plural, other {# at a gate}}`,
      idle: $localize`:@@projects.detail.hold.idle:{count, plural, other {# queued}}`,
    };
    return (['agent', 'ci', 'you', 'idle'] as BatonHolder[])
      .filter((h) => counts.get(h))
      .map((h) => ({ holder: h, label: icu(label[h]!, { count: counts.get(h)! }) }));
  });
  readonly rolesText = computed(() => {
    const roles = this.myRoles();
    return icu(
      $localize`:@@projects.detail.yourRoles:{count, plural, one {Your role: {roles}} other {Your roles: {roles}}}`,
      { count: roles.length, roles: roles.map((r) => roleName(r)).join(', ') },
    );
  });
  readonly startTitle = computed(
    () => $localize`:@@projects.start.title:Start run: ${this.starting()?.name ?? ''}:workflow:`,
  );

  readonly text = {
    workflowsTab: $localize`:@@projects.detail.tab.workflows:Workflows`,
    noWorkflows: $localize`:@@projects.detail.workflows.empty:No workflows yet.`,
    noWorkflowsReadOnly: $localize`:@@projects.detail.workflows.emptyReadOnly:No workflows in this project yet.`,
    noWorkflowsDetail: $localize`:@@projects.detail.workflows.emptyDetail:Design the first one on the canvas: issue to reviewed merge request.`,
    newWorkflow: $localize`:@@projects.detail.workflows.new:New workflow`,
    disabled: $localize`:@@projects.detail.workflows.disabled:disabled`,
    draft: $localize`:@@projects.detail.workflows.draft:draft (has errors)`,
    enabled: $localize`:@@projects.detail.workflows.enabled:enabled`,
    settings: $localize`:@@projects.detail.settings:Project settings`,
    noModel: $localize`:@@projects.start.noModel:none`,
    settingsReadOnly: $localize`:@@projects.detail.settingsReadOnly:Project settings (read-only)`,
    manual: $localize`:@@projects.detail.trigger.manual:Manual`,
  };
  /** Column sorting of the workflows (URL `?workflowSort=`, apart from the tables of the other tabs). */
  readonly workflowSort = tableSort<WorkflowDefinition>(
    {
      name: (w) => w.name,
      triggers: (w) => this.triggerText(w),
      state: (w) =>
        w.draft ? this.text.draft : w.enabled === false ? this.text.disabled : this.text.enabled,
      lastChange: (w) => w.updatedAt,
    },
    { param: 'workflowSort' },
  );
  readonly workflowRows = computed(() => this.workflowSort.apply(this.workflows()));

  nodesText(w: WorkflowDefinition): string {
    return icu(
      $localize`:@@projects.detail.workflows.nodes:{count, plural, one {# node} other {# nodes}}`,
      { count: w.nodes.length },
    );
  }

  moreActionsLabel(w: WorkflowDefinition): string {
    return $localize`:@@projects.detail.workflows.more:More actions for ${w.name}:workflow:`;
  }

  constructor() {
    effect(() => {
      const id = this.projectId();
      untracked(() => this.load(id));
    });
    effect(() => {
      const w = this.metricsFor();
      untracked(() => {
        this.workflowInsights.set(null);
        if (!w) return;
        this.insightsApi
          .workflow({ workflowId: w.id })
          .pipe(catchError(() => of(null)))
          .subscribe((i) => this.workflowInsights.set(i));
      });
    });
    effect(() => {
      const t = this.tab();
      const i = t ? TABS.indexOf(t) : -1;
      if (i >= 0) this.tabIndex.set(i);
    });
    effect(() => {
      const wid = this.start();
      const list = this.workflows();
      if (!wid || !list.length) return;
      const w = list.find((x) => x.id === wid);
      untracked(() => {
        if (w && this.can('run.start')) this.openStart(w);
      });
    });
  }

  mono = monogram;

  /** Project permission of the caller: the loaded project's `myPermissions` or `/me`. */
  can(permission: string): boolean {
    return projectCan(this.identity, this.project(), permission, this.projectId());
  }

  triggerText(w: WorkflowDefinition): string {
    const t = w.triggers ?? [];
    if (!t.length) return this.text.manual;
    return t
      .map((x) =>
        x.type === 'JIRA_TRANSITION'
          ? `Jira: ${x.status || '?'}`
          : x.type === 'MANUAL'
            ? this.text.manual
            : x.type === 'API'
              ? 'API'
              : x.type === 'CI'
                ? 'CI'
                : x.type,
      )
      .join(', ');
  }

  setTab(index: number): void {
    this.tabIndex.set(index);
    void this.router.navigate([], {
      queryParams: { tab: index ? TABS[index] : null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  load(id = this.projectId()): void {
    this.store.setCurrent(id);
    this.projectsApi.get(id).subscribe((p) => {
      this.project.set(p);
      this.store.upsert(p);
      this.crumbs.set([
        { label: $localize`:@@projects.detail.crumb:Projects`, link: '/projects' },
        { label: p.key, mono: true },
      ]);
    });
    this.reloadWorkflows();
    this.projectsApi
      .specKit(id)
      .pipe(catchError(() => of(null)))
      .subscribe((s) => this.specKitAllowed.set(s?.allowedByPlatform ?? true));
    this.runsApi
      .list({ projectId: id, status: ['RUNNING', 'WAITING', 'PENDING'], size: 100 })
      .pipe(catchError(() => of({ content: [] })))
      .subscribe((p) => this.inFlight.set(p.content ?? []));
  }

  behind(link: LibraryLink): boolean {
    return updateAvailable(link);
  }

  libraryTitle(name: string): string {
    return $localize`:@@projects.detail.workflows.libraryTitle:Linked copy of the library workflow "${name}:name:"`;
  }

  metricsLabel(w: WorkflowDefinition): string {
    return $localize`:@@projects.detail.workflows.metrics:Performance of ${w.name}:name:`;
  }

  onAdopted(): void {
    this.adoptOpen.set(false);
    this.reloadWorkflows();
  }

  onPublished(): void {
    this.publishing.set(null);
    this.reloadWorkflows();
  }

  async updateFromLibrary(w: WorkflowDefinition): Promise<void> {
    const lib = w.library;
    if (!lib) return;
    if (lib.modified) {
      const ok = await this.confirm.confirm({
        title: $localize`:@@projects.detail.libraryUpdate.title:Replace local changes with library version ${lib.latestVersion}:version:?`,
        content: $localize`:@@projects.detail.libraryUpdate.content:This copy was changed in the project since v${lib.version}:version:. Updating replaces its steps and connections with the library version; step repositories, the Jira connection, named approvers, the name and triggers stay.`,
        okText: $localize`:@@projects.detail.libraryUpdate.ok:Replace and update`,
        danger: true,
      });
      if (!ok) return;
    }
    this.libraryApi
      .updateFromLibrary(w.id, { discardLocalChanges: lib.modified })
      .subscribe((updated) => {
        this.message.success(
          $localize`:@@projects.detail.libraryUpdate.done:${updated.name}:name: now follows library version ${updated.library?.version ?? ''}:version:`,
        );
        this.reloadWorkflows();
      });
  }

  async detach(w: WorkflowDefinition): Promise<void> {
    const ok = await this.confirm.confirm({
      title: $localize`:@@projects.detail.detach.title:Detach ${w.name}:name: from the library?`,
      content: $localize`:@@projects.detail.detach.content:The workflow stays as it is but no longer receives library versions, and its future runs no longer count towards the library metrics.`,
      okText: $localize`:@@projects.detail.detach.ok:Detach`,
    });
    if (!ok) return;
    this.libraryApi.detach(w.id).subscribe(() => {
      this.message.success(
        $localize`:@@projects.detail.detach.done:Workflow detached from the library`,
      );
      this.reloadWorkflows();
    });
  }

  /** Workflows of the project (after load and after "Add Spec Kit workflows"). */
  reloadWorkflows(): void {
    this.workflowsLoading.set(true);
    this.workflowsApi.listForProject(this.projectId()).subscribe({
      next: (list) => {
        this.workflows.set(list);
        this.workflowsLoading.set(false);
      },
      error: () => this.workflowsLoading.set(false),
    });
  }

  save(input: ProjectInput): void {
    this.saving.set(true);
    this.saveError.set(null);
    this.pendingSaves.track(this.projectsApi.update(this.projectId(), input)).subscribe({
      next: (p) => {
        this.project.set(p);
        this.store.upsert(p);
        this.saving.set(false);
        this.message.success($localize`:@@projects.detail.saved:Project saved`);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.saveError.set(err);
      },
    });
  }

  async deleteProject(): Promise<void> {
    const p = this.project();
    if (!p) return;
    const ok = await this.confirm.danger(
      $localize`:@@projects.detail.delete.title:Delete project ${p.key}:key:?`,
      $localize`:@@projects.detail.delete.text:This removes all workflows, runs, CI tokens and approvals of the project.`,
      $localize`:@@projects.detail.delete.ok:Delete ${p.key}:key:`,
    );
    if (!ok) return;
    this.projectsApi.delete(p.id).subscribe(() => {
      this.message.success($localize`:@@projects.detail.delete.done:Project ${p.key}:key: deleted`);
      this.store.setCurrent(null);
      this.store.refresh();
      void this.identity.refresh();
      void this.router.navigate(['/projects']);
    });
  }

  async deleteWorkflow(w: WorkflowDefinition): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@projects.detail.deleteWorkflow.title:Delete workflow "${w.name}:workflow:"?`,
      $localize`:@@projects.detail.deleteWorkflow.text:Runs already started keep their pinned definition. Deleting fails while runs are in flight or a CI entrypoint uses the workflow.`,
      $localize`:@@projects.detail.deleteWorkflow.ok:Delete workflow`,
    );
    if (!ok) return;
    this.workflowsApi.delete(w.id).subscribe(() => {
      this.message.success($localize`:@@projects.detail.deleteWorkflow.done:Workflow deleted`);
      this.load();
    });
  }

  duplicate(w: WorkflowDefinition): void {
    this.workflowsApi.duplicate(w.id).subscribe((copy) => {
      this.message.success(
        $localize`:@@projects.detail.duplicated:Copy "${copy.name}:workflow:" created`,
      );
      void this.router.navigate(['/workflows', copy.id, 'edit']);
    });
  }

  openStart(w: WorkflowDefinition): void {
    this.issueKey = '';
    this.paramsJson = '';
    this.paramsError.set(null);
    this.starting.set(w);
    this.modelPlan.set(null);
    this.modelPlanLoading.set(true);
    this.workflowsApi.modelPlan(w.id).subscribe({
      next: (plan) => {
        if (this.starting()?.id !== w.id) return;
        this.modelPlan.set(plan);
        this.modelPlanLoading.set(false);
      },
      error: () => {
        if (this.starting()?.id === w.id) this.modelPlanLoading.set(false);
      },
    });
  }

  closeStart(): void {
    this.starting.set(null);
    if (this.start())
      void this.router.navigate([], {
        queryParams: { start: null },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
  }

  startRun(): void {
    const w = this.starting();
    if (!w || this.planBlocked()) return;
    let parameters: Record<string, unknown> = {};
    if (this.paramsJson.trim()) {
      try {
        parameters = JSON.parse(this.paramsJson) as Record<string, unknown>;
      } catch (e) {
        this.paramsError.set(
          $localize`:@@projects.start.invalidJson:The parameters are not valid JSON: ${(e as Error).message}:error:`,
        );
        return;
      }
    }
    this.paramsError.set(null);
    this.startingBusy.set(true);
    this.workflowsApi
      .startRun(w.id, { issueKey: this.issueKey.trim() || null, parameters })
      .subscribe({
        next: (run) => {
          this.startingBusy.set(false);
          this.starting.set(null);
          this.message.success(
            $localize`:@@projects.start.started:Run started for ${run.issueKey || w.name}:subject:`,
          );
          void this.router.navigate(['/runs', run.id]);
        },
        error: () => this.startingBusy.set(false),
      });
  }
}
