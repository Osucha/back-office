import {
  axisMoneyDecimals,
  benchmarkDuration,
  benchmarkMoney,
  columnDecimals,
  moneyDecimals,
  moneyWith,
  niceAxis,
  scoreAxis,
} from './benchmark-format';

describe('benchmark money', () => {
  it('uses two decimals, three under $0.10', () => {
    expect(benchmarkMoney(22.714)).toBe('$22.71');
    expect(benchmarkMoney({ amount: 0.42, currency: 'USD' })).toBe('$0.42');
    expect(benchmarkMoney(0.0423)).toBe('$0.042');
    expect(benchmarkMoney(0)).toBe('$0.00');
    expect(benchmarkMoney(1234.5)).toBe('$1,234.50');
    expect(moneyDecimals(0.1)).toBe(2);
    expect(moneyDecimals(0.099)).toBe(3);
  });

  it('marks positive amounts too small to show', () => {
    expect(benchmarkMoney(0.0002)).toBe('<$0.001');
  });

  it('takes a fixed precision for axes', () => {
    expect(benchmarkMoney(0.2, 3)).toBe('$0.200');
    expect(benchmarkMoney(1, 2)).toBe('$1.00');
  });

  it('shows a dash without a value and keeps other currencies', () => {
    expect(benchmarkMoney(null)).toBe('-');
    expect(benchmarkMoney(undefined)).toBe('-');
    expect(benchmarkMoney({ amount: 2, currency: 'EUR' })).toBe('€2.00');
    expect(benchmarkMoney(-1.5)).toBe('-$1.50');
  });
});

describe('niceAxis', () => {
  it('rounds the top to steps of 1, 2 or 5 with room above the maximum', () => {
    expect(niceAxis(0.6)).toEqual({ max: 0.8, interval: 0.2 });
    expect(niceAxis(4.3)).toEqual({ max: 5, interval: 1 });
    expect(niceAxis(0.04)).toEqual({ max: 0.05, interval: 0.01 });
    expect(niceAxis(23)).toEqual({ max: 30, interval: 10 });
    expect(niceAxis(0).max).toBeGreaterThan(0);
  });

  it('picks three decimals for axes below $0.10', () => {
    expect(axisMoneyDecimals(0.05)).toBe(3);
    expect(axisMoneyDecimals(0.8)).toBe(2);
  });
});

describe('benchmark duration', () => {
  it('is the one compact duration format', () => {
    expect(benchmarkDuration(411000)).toBe('6m 51s');
    expect(benchmarkDuration(850)).toBe('850ms');
    expect(benchmarkDuration(null)).toBe('-');
  });
});

describe('column money and score axis', () => {
  it('uses three decimals in a column with any amount below $1', () => {
    expect(columnDecimals([2.5, 0.4, null])).toBe(3);
    expect(columnDecimals([2.5, 0, null, 12])).toBe(2);
    expect(columnDecimals([])).toBe(2);
    expect(moneyWith(3)(2.5)).toBe('$2.500');
    expect(moneyWith(2)(0)).toBe('$0.00');
  });

  it('fits the score axis to the data with round steps', () => {
    expect(scoreAxis([62, 84.6, 71])).toEqual({ min: 50, max: 90, interval: 10 });
    expect(scoreAxis([88, 92])).toEqual({ min: 80, max: 100, interval: 5 });
    expect(scoreAxis([10, 95])).toEqual({ min: 0, max: 100, interval: 20 });
    expect(scoreAxis([])).toEqual({ min: 0, max: 100, interval: 20 });
  });
});
