import type { LeaderboardRow } from '../../../../core/api/types';
import {
  boardCostPerAccepted,
  boardMeanCost,
  boardScorePerDollar,
  comparesOnApi,
} from './board-cost';

const usd = (amount: number) => ({ amount, currency: 'USD' });

const API_ROW: LeaderboardRow = {
  modelProfileId: 'a',
  subscription: false,
  meanCost: usd(0.4),
  meanApiCost: usd(0.4),
  totalCost: usd(4),
  totalApiCost: usd(4),
  costPerAccepted: usd(0.5),
  apiCostPerAccepted: usd(0.5),
  scorePerDollar: 200,
  scorePerApiDollar: 200,
};

const SUBSCRIPTION_ROW: LeaderboardRow = {
  modelProfileId: 'b',
  subscription: true,
  meanCost: usd(0),
  meanApiCost: usd(1.14),
  totalCost: usd(0),
  totalApiCost: usd(11.4),
  costPerAccepted: null,
  apiCostPerAccepted: usd(1.9),
  scorePerDollar: null,
  scorePerApiDollar: 70,
};

describe('comparesOnApi', () => {
  it('compares subscription candidates on the API figures', () => {
    expect(comparesOnApi(SUBSCRIPTION_ROW)).toBe(true);
    expect(comparesOnApi(API_ROW)).toBe(false);
  });

  it('also when nothing was spent but the API amount is not zero', () => {
    expect(comparesOnApi({ ...SUBSCRIPTION_ROW, subscription: false })).toBe(true);
    expect(comparesOnApi({ subscription: false, meanApiCost: usd(0.2) })).toBe(true);
  });

  it('not for free work or missing rows', () => {
    expect(comparesOnApi({ totalCost: usd(0), totalApiCost: usd(0) })).toBe(false);
    expect(comparesOnApi({})).toBe(false);
    expect(comparesOnApi(null)).toBe(false);
  });
});

describe('board figures', () => {
  it('use the spent cost for API candidates', () => {
    expect(boardMeanCost(API_ROW)).toBe(0.4);
    expect(boardCostPerAccepted(API_ROW)).toEqual(usd(0.5));
    expect(boardScorePerDollar(API_ROW)).toBe(200);
  });

  it('use the API cost for subscription candidates', () => {
    expect(boardMeanCost(SUBSCRIPTION_ROW)).toBe(1.14);
    expect(boardCostPerAccepted(SUBSCRIPTION_ROW)).toEqual(usd(1.9));
    expect(boardScorePerDollar(SUBSCRIPTION_ROW)).toBe(70);
  });

  it('fall back to 0 and null', () => {
    expect(boardMeanCost({})).toBe(0);
    expect(boardCostPerAccepted({})).toBeNull();
    expect(boardScorePerDollar({ subscription: true })).toBeNull();
  });
});
