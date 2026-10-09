import { firstName, formatCost, formatUsage, sumMoney } from './format';

describe('relay formats', () => {
  it('formats costs like the spec', () => {
    expect(formatCost({ amount: 0.042, currency: 'USD' })).toBe('$0.042');
    expect(formatCost(0.31)).toBe('$0.31');
    expect(formatCost(0.0123)).toBe('$0.0123');
    expect(formatCost(0.5)).toBe('$0.50');
    expect(formatCost(0)).toBe('$0.00');
    expect(formatCost(0.00001)).toBe('$<0.0001');
    expect(formatCost(1234.5)).toBe('$1,234.50');
    expect(formatCost({ amount: 2, currency: 'EUR' })).toBe('€2.00');
    expect(formatCost(null)).toBe('-');
  });

  it('describes token usage', () => {
    expect(formatUsage({ inputTokens: 12000, outputTokens: 1500 })).toBe(
      'in 12k · out 1.5k tokens',
    );
    expect(formatUsage({})).toBe('');
  });

  it('sums money', () => {
    expect(
      sumMoney([{ amount: 1, currency: 'USD' }, null, { amount: 0.5, currency: 'USD' }]),
    ).toEqual({
      amount: 1.5,
      currency: 'USD',
    });
    expect(sumMoney([])).toBeNull();
  });

  it('extracts a first name', () => {
    expect(firstName('Ann Admin')).toBe('Ann');
    expect(firstName('', 'bob.builder@example.com')).toBe('Bob');
    expect(firstName(null)).toBe('');
  });
});
