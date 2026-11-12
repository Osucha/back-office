import { TestBed } from '@angular/core/testing';
import { HelpTipComponent } from './help-tip.component';

describe('HelpTipComponent (ADR 0061)', () => {
  it('is a focusable "?" whose tooltip opens on focus and closes on blur or Escape', () => {
    const fixture = TestBed.createComponent(HelpTipComponent);
    fixture.componentRef.setInput('text', 'Groups the template in the library.');
    fixture.detectChanges();
    const tip = fixture.nativeElement.querySelector('.tip') as HTMLElement;
    expect(tip.getAttribute('tabindex')).toBe('0');
    expect(tip.getAttribute('aria-label')).toBe('Help');

    tip.dispatchEvent(new Event('focus'));
    expect(fixture.componentInstance.visible()).toBe(true);
    tip.dispatchEvent(new Event('blur'));
    expect(fixture.componentInstance.visible()).toBe(false);
    tip.dispatchEvent(new Event('focus'));
    tip.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(fixture.componentInstance.visible()).toBe(false);
  });
});
