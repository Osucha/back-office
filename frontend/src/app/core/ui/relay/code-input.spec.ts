import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  applyBackspace,
  applyInput,
  applyPaste,
  cellsOf,
  completeCode,
  firstEmpty,
} from './code-input';
import { CodeInputComponent } from './code-input.component';

const cells = (s: string) => cellsOf(s, 6);

describe('code input rules', () => {
  it('auto-advances after a digit and ignores non-digits', () => {
    expect(applyInput(cells(''), 0, '4')).toEqual({ cells: ['4', '', '', '', '', ''], focus: 1 });
    expect(applyInput(cells('4'), 1, 'x')).toEqual({ cells: ['4', '', '', '', '', ''], focus: 1 });
    // the last cell keeps focus
    expect(applyInput(cells('12345'), 5, '6').focus).toBe(5);
  });

  it('replaces a filled cell when typing over it', () => {
    expect(applyInput(cells('123456'), 2, '9').cells.join('')).toBe('129456');
  });

  it('pastes a full code into every cell, whatever the cell and the formatting', () => {
    expect(applyPaste(cells(''), 3, '123 456')).toEqual({ cells: [...'123456'], focus: 5 });
    expect(applyPaste(cells('9'), 0, ' 12-34-56 \n').cells.join('')).toBe('123456');
    // longer text: the first six digits
    expect(applyPaste(cells(''), 0, '12345678').cells.join('')).toBe('123456');
  });

  it('pastes a partial code from the current cell', () => {
    expect(applyPaste(cells('1'), 1, '23')).toEqual({
      cells: ['1', '2', '3', '', '', ''],
      focus: 3,
    });
  });

  it('autofill of the whole code into the first cell fills everything', () => {
    expect(applyInput(cells(''), 0, '654321').cells.join('')).toBe('654321');
  });

  it('backspace clears the cell, then moves back and clears the previous one', () => {
    expect(applyBackspace(cells('123'), 2)).toEqual({
      cells: ['1', '2', '', '', '', ''],
      focus: 2,
    });
    expect(applyBackspace(cells('12'), 2)).toEqual({ cells: ['1', '', '', '', '', ''], focus: 1 });
    expect(applyBackspace(cells(''), 0)).toEqual({ cells: cells(''), focus: 0 });
  });

  it('knows when the code is complete and where the first gap is', () => {
    expect(completeCode(cells('12345'))).toBeNull();
    expect(completeCode(cells('123456'))).toBe('123456');
    expect(firstEmpty(cells('12'))).toBe(2);
    expect(firstEmpty(cells('123456'))).toBe(5);
  });
});

@Component({
  imports: [CodeInputComponent],
  template: `<bo-code-input
    label="Authentication code"
    [(value)]="code"
    (completed)="done.push($event)"
  />`,
})
class HostComponent {
  readonly code = signal('');
  readonly done: string[] = [];
}

describe('CodeInputComponent', () => {
  function setup() {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const inputs = () =>
      Array.from(fixture.nativeElement.querySelectorAll('input')) as HTMLInputElement[];
    const key = (i: number, k: string) => {
      inputs()[i].dispatchEvent(
        new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
    };
    return { fixture, host: fixture.componentInstance, inputs, key };
  }

  it('renders six labelled cells in a named group with one-time-code autocomplete', () => {
    const { fixture, inputs } = setup();
    expect(inputs().length).toBe(6);
    expect(inputs()[0].getAttribute('autocomplete')).toBe('one-time-code');
    expect(inputs()[3].getAttribute('aria-label')).toBe('Digit 4 of 6');
    expect(fixture.nativeElement.querySelector('[role=group]').getAttribute('aria-label')).toBe(
      'Authentication code',
    );
  });

  it('types, advances focus, emits the completed code and supports backspace', () => {
    const { host, inputs, key } = setup();
    inputs()[0].focus();
    for (const [i, d] of [...'12345'].entries()) key(i, d);
    expect(host.code()).toBe('12345');
    expect(document.activeElement).toBe(inputs()[5]);
    key(5, '6');
    expect(host.done).toEqual(['123456']);
    key(5, 'Backspace');
    expect(host.code()).toBe('12345');
    key(5, 'Backspace');
    expect(host.code()).toBe('1234');
    expect(document.activeElement).toBe(inputs()[4]);
  });

  it('pastes a full code', () => {
    const { fixture, host, inputs } = setup();
    const paste = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => '987 654' } });
    inputs()[0].dispatchEvent(paste);
    fixture.detectChanges();
    expect(host.code()).toBe('987654');
    expect(
      inputs()
        .map((i) => i.value)
        .join(''),
    ).toBe('987654');
    expect(host.done).toEqual(['987654']);
  });

  it('reflects an external reset', () => {
    const { fixture, host, inputs } = setup();
    host.code.set('123456');
    fixture.detectChanges();
    expect(inputs()[5].value).toBe('6');
    host.code.set('');
    fixture.detectChanges();
    expect(inputs().every((i) => i.value === '')).toBe(true);
  });
});
