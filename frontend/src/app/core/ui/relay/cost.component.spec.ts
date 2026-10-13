import { TestBed } from '@angular/core/testing';
import { CostComponent } from './cost.component';

describe('bo-cost', () => {
  function render(value: unknown, apiValue: unknown): HTMLElement {
    const fixture = TestBed.createComponent(CostComponent);
    fixture.componentRef.setInput('value', value);
    fixture.componentRef.setInput('apiValue', apiValue);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('adds the cost at API prices only when it differs from the spent cost (ADR 0042)', () => {
    const subscription = render({ amount: 0, currency: 'USD' }, { amount: 0.046579, currency: 'USD' });
    expect(subscription.textContent).toContain('API');
    expect(subscription.querySelectorAll('.tick')).toHaveLength(2);

    const gateway = render({ amount: 0.5, currency: 'USD' }, { amount: 0.5, currency: 'USD' });
    expect(gateway.textContent).not.toContain('API');

    const legacy = render({ amount: 0.5, currency: 'USD' }, null);
    expect(legacy.querySelectorAll('.tick')).toHaveLength(1);
  });
});
