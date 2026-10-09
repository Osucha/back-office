import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { WorkflowsApi } from '../../../core/api/workflows.service';
import type { StateField } from '../../../core/api/types';

/**
 * One SpEL expression with live validation (`POST /workflows/expressions/validate`, debounced)
 * and a helper menu that inserts a state field path.
 */
@Component({
  selector: 'app-expression-editor',
  imports: [
    FormsModule,
    NzInputModule,
    NzIconModule,
    NzButtonModule,
    NzDropDownModule,
    NzTooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="row">
      <nz-input-group [nzSuffix]="statusTpl" nzSize="small" class="grow">
        <input
          #box
          nz-input
          class="mono"
          [ngModel]="value()"
          (ngModelChange)="onChange($event)"
          [placeholder]="placeholder()"
          [attr.aria-label]="ariaLabel()"
          spellcheck="false"
        />
      </nz-input-group>
      <ng-template #statusTpl>
        @switch (state()) {
          @case ('checking') {
            <nz-icon nzType="loading" nzSpin />
          }
          @case ('valid') {
            <nz-icon
              nzType="check-circle"
              class="ok"
              nz-tooltip
              nzTooltipTitle="Expression parses"
              i18n-nzTooltipTitle="@@workflows.expression.parses"
            />
          }
          @case ('invalid') {
            <nz-icon nzType="close-circle" class="bad" nz-tooltip [nzTooltipTitle]="message()" />
          }
          @case ('offline') {
            <nz-icon
              nzType="question-circle"
              class="muted"
              nz-tooltip
              nzTooltipTitle="Server validation unavailable"
              i18n-nzTooltipTitle="@@workflows.expression.offline"
            />
          }
        }
      </ng-template>
      @if (fields().length) {
        <button
          nz-button
          nzSize="small"
          type="button"
          nz-dropdown
          [nzDropdownMenu]="menu"
          nzTrigger="click"
          aria-label="Insert state field"
          i18n-aria-label="@@workflows.expression.insertField"
        >
          <nz-icon nzType="function" />
        </button>
        <nz-dropdown-menu #menu="nzDropdownMenu">
          <ul nz-menu class="fields">
            @for (f of fields(); track f.path) {
              <li nz-menu-item (click)="insert(f.path ?? '', box)">
                <span class="mono">{{ f.path }}</span>
                @if (f.description) {
                  <span class="desc">{{ f.description }}</span>
                }
              </li>
            }
          </ul>
        </nz-dropdown-menu>
      }
    </div>
    @if (state() === 'invalid' && message()) {
      <div class="danger msg">{{ message() }}</div>
    }
  `,
  styles: [
    `
      .row {
        display: flex;
        gap: 4px;
        align-items: center;
      }
      .grow {
        flex: 1;
      }
      .ok {
        color: var(--bo-done-ink);
      }
      .bad {
        color: var(--bo-blocked-ink);
      }
      .muted {
        color: var(--bo-ink-3);
      }
      .msg {
        font-size: 11px;
        margin-top: 2px;
      }
      .fields {
        max-height: 320px;
        overflow: auto;
      }
      .desc {
        margin-left: 10px;
        color: var(--bo-ink-3);
        font-size: 11px;
      }
    `,
  ],
})
export class ExpressionEditorComponent {
  private readonly api = inject(WorkflowsApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly value = input('');
  readonly fields = input<StateField[]>([]);
  readonly placeholder = input('confidence >= 0.8');
  readonly ariaLabel = input('Expression');
  readonly valueChange = output<string>();
  readonly validity = output<boolean | null>();

  readonly state = signal<'idle' | 'checking' | 'valid' | 'invalid' | 'offline'>('idle');
  readonly message = signal<string | null>(null);
  private readonly input$ = new Subject<string>();

  constructor() {
    this.input$
      .pipe(
        debounceTime(400),
        distinctUntilChanged(),
        switchMap((expr) => {
          if (!expr.trim()) {
            this.state.set('idle');
            return of(null);
          }
          this.state.set('checking');
          return this.api.validateExpression(expr).pipe(catchError(() => of({ offline: true })));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        if (!res) {
          this.validity.emit(null);
          return;
        }
        if ('offline' in res) {
          this.state.set('offline');
          this.validity.emit(null);
          return;
        }
        this.state.set(res.valid ? 'valid' : 'invalid');
        this.message.set(
          res.valid
            ? null
            : (res.message ?? $localize`:@@workflows.expression.invalid:Expression does not parse`),
        );
        this.validity.emit(!!res.valid);
      });
    effect(() => this.input$.next(this.value()));
  }

  onChange(text: string): void {
    this.valueChange.emit(text);
    this.input$.next(text);
  }

  insert(path: string, box: HTMLInputElement): void {
    const current = this.value();
    const start = box.selectionStart ?? current.length;
    const end = box.selectionEnd ?? current.length;
    const next = `${current.slice(0, start)}${path}${current.slice(end)}`;
    this.onChange(next);
    queueMicrotask(() => {
      box.focus();
      box.setSelectionRange(start + path.length, start + path.length);
    });
  }
}
