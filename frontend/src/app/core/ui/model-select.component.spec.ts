import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import type { ModelOption } from '../api/types';
import { ModelSelectComponent } from './model-select.component';

const option = (profileId: string, changes: Partial<ModelOption> = {}): ModelOption => ({
  profileId,
  displayName: profileId,
  provider: 'anthropic',
  modelId: `${profileId}-model`,
  runtime: 'API',
  route: 'GATEWAY',
  personal: false,
  usable: true,
  ...changes,
});

@Component({
  imports: [ReactiveFormsModule, ModelSelectComponent],
  template: `<app-model-select [formControl]="control" [options]="options()" />`,
})
class HostComponent {
  readonly control = new FormControl<string | null>(null);
  readonly options = signal<ModelOption[]>([
    option('a'),
    option('b', { usable: false, problem: 'provider not connected' }),
    option('c', { note: 'runner offline' }),
  ]);
}

describe('ModelSelectComponent (ADR 0050)', () => {
  function render(value: string | null) {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.control.setValue(value);
    fixture.detectChanges();
    return fixture;
  }

  it('shows the problem of a chosen unusable model as an error', () => {
    const el = render('b').nativeElement as HTMLElement;
    expect(el.querySelector('.under.danger')?.textContent).toContain('provider not connected');
  });

  it('shows the note of the chosen model as a warning', () => {
    const el = render('c').nativeElement as HTMLElement;
    expect(el.querySelector('.under.warning')?.textContent).toContain('runner offline');
  });

  it('keeps an unknown chosen id and says it is not available', () => {
    const el = render('gone').nativeElement as HTMLElement;
    expect(el.querySelector('.under.danger')?.textContent).toContain('not available');
  });

  it('writes the form control and follows its disabled state', () => {
    const fixture = render(null);
    const select = fixture.debugElement.children[0].componentInstance as ModelSelectComponent;
    select.select('a');
    expect(fixture.componentInstance.control.value).toBe('a');
    fixture.componentInstance.control.disable();
    expect(select.disabled()).toBe(true);
  });
});
