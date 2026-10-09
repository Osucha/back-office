// Fixture for scripts/tests/i18n.test.mjs (not part of the app).
import { Component } from '@angular/core';

export const TITLE = $localize`:@@demo.page.title:Demo page`;
export const COUNT = $localize`:@@demo.page.count:{count, plural, one {# run} other {# runs}}`;
export const NO_ID = $localize`Text without an id`;
export const IGNORED = 'Only for the console'; // i18n-ignore

@Component({
  selector: 'app-demo',
  template: `
    <h1 i18n="@@demo.page.heading">Hello {{ name }}</h1>
    <p>Untagged sentence in a template</p>
    <input placeholder="Search runs" />
    <input placeholder="Tagged" i18n-placeholder="@@demo.page.search" />
    <span i18n>No custom id</span>
    <span i18n="@@demo.page.mixed">Updated {n, plural, one {# minute} other {# minutes}} ago</span>
    <span i18n="@@demo.page.icu">{n, plural, one {# minute} other {# minutes}}</span>
    <code>kubectl get pods</code>
    <span>CI</span>
    <button [title]="ok ? 'Looks good' : 'Broken'">x</button>
  `,
})
export class DemoComponent {
  name = 'x';
  n = 1;
  ok = true;
  notify = { error: (_: string) => {} };

  fail(): void {
    this.notify.error('Could not save the demo');
  }

  readonly item = { label: 'Demo label', icon: 'home' };
}
