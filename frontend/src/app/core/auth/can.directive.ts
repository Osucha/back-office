import {
  Directive,
  EmbeddedViewRef,
  TemplateRef,
  ViewContainerRef,
  effect,
  inject,
  input,
} from '@angular/core';
import { IdentityService } from './identity.service';

/** Renders the template or the `else` template and re-renders when the identity changes. */
abstract class ConditionalView {
  private readonly vcr = inject(ViewContainerRef);
  private readonly tpl = inject(TemplateRef<unknown>);
  private shown: 'then' | 'else' | null = null;
  private view: EmbeddedViewRef<unknown> | null = null;

  protected render(allowed: boolean, elseTpl: TemplateRef<unknown> | null): void {
    const want = allowed ? 'then' : elseTpl ? 'else' : null;
    if (want === this.shown) return;
    this.vcr.clear();
    this.view = null;
    this.shown = want;
    if (want === 'then') this.view = this.vcr.createEmbeddedView(this.tpl);
    else if (want === 'else' && elseTpl) this.view = this.vcr.createEmbeddedView(elseTpl);
  }
}

/**
 * `*boCan="'admin.users.manage'"` renders the element only with the platform permission (an array
 * means "any of"); `*boCan="'x'; else readOnly"` renders `readOnly` otherwise.
 */
@Directive({ selector: '[boCan]' })
export class CanDirective extends ConditionalView {
  private readonly identity = inject(IdentityService);
  readonly boCan = input.required<string | readonly string[]>();
  readonly boCanElse = input<TemplateRef<unknown> | null>(null);

  constructor() {
    super();
    effect(() => this.render(this.identity.can(this.boCan()), this.boCanElse()));
  }
}

export interface ProjectPermissionCheck {
  permission: string;
  /** Omitted/null = "in any project". */
  projectId?: string | null;
  /**
   * Effective permissions of the loaded project (`Project.myPermissions`); when present they are
   * used in addition to `/me`, so a project created a moment ago works before `/me` is reloaded.
   */
  projectPermissions?: readonly string[] | null;
}

/**
 * `*boCanInProject="{ permission: 'workflow.edit', projectId: id }"` renders the element only with
 * the project permission; supports `; else tpl` like `*boCan`.
 */
@Directive({ selector: '[boCanInProject]' })
export class CanInProjectDirective extends ConditionalView {
  private readonly identity = inject(IdentityService);
  readonly boCanInProject = input.required<ProjectPermissionCheck>();
  readonly boCanInProjectElse = input<TemplateRef<unknown> | null>(null);

  constructor() {
    super();
    effect(() => {
      const check = this.boCanInProject();
      const allowed =
        !!check.projectPermissions?.includes(check.permission) ||
        this.identity.canInProject(check.permission, check.projectId);
      this.render(allowed, this.boCanInProjectElse());
    });
  }
}
