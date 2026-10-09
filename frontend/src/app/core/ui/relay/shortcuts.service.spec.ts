import { TestBed } from '@angular/core/testing';
import {
  formatKeys,
  isTypingTarget,
  matches,
  parseKeys,
  ShortcutsService,
} from './shortcuts.service';

const key = (k: string, init: KeyboardEventInit = {}) =>
  new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init });

describe('ShortcutsService', () => {
  let service: ShortcutsService;
  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ShortcutsService);
  });

  it('fires on the document and ignores typing in inputs', () => {
    const handler = vi.fn();
    service.register({ keys: 'j', description: 'Next', handler });
    document.body.dispatchEvent(key('j'));
    expect(handler).toHaveBeenCalledTimes(1);

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.dispatchEvent(key('j'));
    const area = document.createElement('textarea');
    document.body.appendChild(area);
    area.dispatchEvent(key('j'));
    expect(handler).toHaveBeenCalledTimes(1);
    input.remove();
    area.remove();
  });

  it('fires in inputs when the shortcut opts in (mod+s)', () => {
    const save = vi.fn();
    service.register({ keys: 'mod+s', description: 'Save', handler: save, allowInInputs: true });
    const input = document.createElement('input');
    document.body.appendChild(input);
    const e = key('s', { ctrlKey: true });
    input.dispatchEvent(e);
    expect(save).toHaveBeenCalledTimes(1);
    expect(e.defaultPrevented).toBe(true);
    input.remove();
  });

  it('later registrations win and unregistering restores the earlier one', () => {
    const shell = vi.fn();
    const page = vi.fn();
    service.register({ keys: 'a', description: 'Shell', handler: shell });
    const off = service.register({ keys: 'a', description: 'Page', handler: page });
    document.body.dispatchEvent(key('a'));
    expect(page).toHaveBeenCalledTimes(1);
    expect(shell).not.toHaveBeenCalled();
    off();
    document.body.dispatchEvent(key('a'));
    expect(shell).toHaveBeenCalledTimes(1);
  });

  it('skips shortcuts whose condition is false and lists the visible ones grouped', () => {
    const handler = vi.fn();
    service.register([
      { keys: 'x', description: 'Reject', group: 'Gate', handler, when: () => false },
      { keys: '?', description: 'Shortcuts', group: 'Global', handler: () => undefined },
    ]);
    document.body.dispatchEvent(key('x'));
    expect(handler).not.toHaveBeenCalled();
    expect(service.visible().map((g) => g.group)).toEqual(['Global']);
  });

  it('parses, matches and formats key combinations', () => {
    expect(parseKeys('mod+k')).toEqual({ key: 'k', mod: true, shift: false, alt: false });
    expect(matches(parseKeys('mod+k'), key('k', { metaKey: true }))).toBe(true);
    expect(matches(parseKeys('k'), key('k', { ctrlKey: true }))).toBe(false);
    expect(matches(parseKeys('?'), key('?', { shiftKey: true }))).toBe(true);
    expect(matches(parseKeys('a'), key('A', { shiftKey: true }))).toBe(false);
    expect(formatKeys('mod+k', true)).toEqual(['⌘', 'K']);
    expect(formatKeys('mod+k', false)).toEqual(['Ctrl', 'K']);
    expect(formatKeys('Escape', false)).toEqual(['Esc']);
    expect(isTypingTarget(document.createElement('select'))).toBe(true);
    expect(isTypingTarget(document.createElement('button'))).toBe(false);
  });
});
