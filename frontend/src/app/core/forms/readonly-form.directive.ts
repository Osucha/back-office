import { DestroyRef, Directive, DoCheck, inject, input } from '@angular/core';
import { ControlContainer } from '@angular/forms';

/**
 * `<form nz-form [boReadonly]="!canEdit()">` disables every control of a template-driven or
 * reactive form (NG-ZORRO controls render their `nzDisabled` look through the value accessor), so an
 * editor opened without the permission becomes a read-only view instead of offering edits that can
 * never be saved. Controls that appear later (`@if` blocks) are disabled on the next check.
 */
@Directive({ selector: 'form[boReadonly]' })
export class ReadonlyFormDirective implements DoCheck {
  private readonly container = inject(ControlContainer, { self: true });
  readonly boReadonly = input(false);
  private scheduled: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      if (this.scheduled) clearTimeout(this.scheduled);
    });
  }

  ngDoCheck(): void {
    const form = this.container.control;
    if (!form || this.scheduled) return;
    const readonly = this.boReadonly();
    if (readonly === form.disabled) return;
    // Not during change detection: status changes would invalidate bindings already checked.
    this.scheduled = setTimeout(() => {
      this.scheduled = null;
      if (this.boReadonly()) form.disable({ emitEvent: false });
      else form.enable({ emitEvent: false });
    });
  }
}
