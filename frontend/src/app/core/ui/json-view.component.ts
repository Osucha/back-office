import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Pretty-prints any value (or a JSON string) as a code block. */
export function prettyJson(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

@Component({
  selector: 'app-json-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<pre class="code-block" [style.max-height]="maxHeight()" tabindex="0">{{
    text()
  }}</pre>`,
})
export class JsonViewComponent {
  readonly value = input<unknown>(null);
  readonly maxHeight = input('480px');
  readonly text = computed(() => prettyJson(this.value()));
}
