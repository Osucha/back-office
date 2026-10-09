import type { ApprovalRequest } from '../api/types';
import {
  KIND_SOUND,
  budgetContent,
  ciContent,
  connectionContent,
  gateContent,
  runContent,
  testContent,
} from './notification-content';
import { cleanText, joinClean } from './sanitize';

const ch = (c: number) => String.fromCharCode(c);
const RLO = ch(0x202e);
const ZWSP = ch(0x200b);
const NL = ch(10);
const TAB = ch(9);
const BEL = ch(7);

describe('notification text sanitising', () => {
  it('strips control, bidi and zero-width characters and collapses whitespace', () => {
    expect(
      cleanText('Approve' + RLO + 'txt.exe' + ZWSP + ' the' + NL + TAB + 'plan' + BEL + '?'),
    ).toBe('Approve txt.exe the plan ?');
    expect(cleanText('a' + ch(0x2066) + 'b' + ch(0x2069) + 'c' + ch(0xfeff))).toBe('a b c');
    expect(cleanText('tag' + String.fromCodePoint(0xe0041, 0xe0042) + 's')).toBe('tag s');
    expect(cleanText(42)).toBe('');
  });

  it('caps the length with an ellipsis', () => {
    const s = cleanText('x'.repeat(500), 20);
    expect(s).toHaveLength(20);
    expect(s.endsWith('…')).toBe(true);
    expect(joinClean(['A', null, '', 'B'])).toBe('A · B');
  });
});

describe('notification content', () => {
  const gate = {
    id: 'a1',
    projectKey: 'SHOP',
    issueKey: 'SHOP-7',
    workflowName: 'Feature',
    question: 'Approve the plan?' + RLO + ' lots of agent output'.repeat(40),
  } as ApprovalRequest;

  it('describes a gate with a tag and a link to the inbox item', () => {
    const c = gateContent(gate, false);
    expect(c.kind).toBe('turn');
    expect(c.title).toBe('Your turn · SHOP');
    expect(c.body.startsWith('SHOP-7 · Approve the plan?')).toBe(true);
    expect(c.body.length).toBeLessThanOrEqual(160);
    expect(c.body).not.toContain(RLO);
    expect(c.tag).toBe('bo-gate-a1');
    expect(c.link).toBe('/inbox/a1');
    expect(gateContent(gate, true).title).toBe('Assigned to you · SHOP');
  });

  it('never builds a link outside the app from a hostile id', () => {
    const c = gateContent({ ...gate, id: '//evil.example/x' } as ApprovalRequest, false);
    expect(c.link).toBe('/inbox/');
  });

  it('describes failed and landed runs without failure text', () => {
    const run = {
      id: 'r1',
      projectKey: 'SHOP',
      workflowName: 'Feature',
      issueKey: 'SHOP-7',
      currentNodeId: 'implement',
    };
    const failed = runContent(run, true);
    expect(failed).toMatchObject({
      kind: 'runFailed',
      title: 'Run failed · SHOP',
      tag: 'bo-run-r1',
      link: '/runs/r1',
    });
    expect(failed.body).toBe('SHOP-7 · Feature · stopped at implement');
    const landed = runContent(run, false);
    expect(landed).toMatchObject({
      kind: 'runLanded',
      title: 'Run landed · SHOP',
      body: 'SHOP-7 · Feature',
    });
  });

  it('describes CI failures and budgets', () => {
    expect(ciContent('c1', 'SHOP', 'review', true)).toMatchObject({
      title: 'CI failed · SHOP',
      body: 'review · the review gate failed',
      link: '/ci/c1',
    });
    const b = budgetContent({
      exceeded: true,
      platform: false,
      projectId: 'p1',
      projectKey: 'SHOP',
      period: '2026-10',
      spent: '$100.00',
      limit: '$100.00',
      hardStop: true,
    });
    expect(b.title).toBe('Budget exceeded · SHOP');
    expect(b.body).toBe('$100.00 of $100.00 spent · new agent calls are stopped');
    expect(b.tag).toBe('bo-budget-p1-2026-10-exceeded');
    expect(b.link).toBe('/projects/p1?tab=budget');
    const platform = budgetContent({
      ...{
        exceeded: false,
        platform: true,
        projectId: null,
        projectKey: null,
        period: '2026-10',
        spent: '$80.00',
        limit: '$100.00',
        hardStop: false,
      },
    });
    expect(platform.title).toBe('Budget alert · Platform budget');
    expect(platform.link).toBe('/admin/settings?section=budget');
  });

  it('gives write confirmations their own title and never the comment text', () => {
    const c = gateContent(
      {
        id: 'a-1',
        kind: 'WRITE_CONFIRMATION',
        projectKey: 'SHOP',
        issueKey: 'SHOP-42',
        question: 'Agent wants to comment on SHOP-42',
        write: {
          action: 'JIRA_COMMENT',
          issueKey: 'SHOP-42',
          comment: 'secret plan',
          contentSha256: 'x',
        },
      } as ApprovalRequest,
      false,
    );
    expect(c.kind).toBe('turn');
    expect(c.title).toBe('Confirm a Jira write · SHOP');
    expect(c.body).toBe('Agent wants to comment on SHOP-42');
    expect(c.body).not.toContain('secret plan');
    expect(c.link).toBe('/inbox/a-1');
  });

  it('reminds of expiring connection tokens and links to where they are replaced', () => {
    const platform = connectionContent({
      connectionId: 'c-1',
      projectId: null,
      projectKey: null,
      name: 'Jira reader',
      daysLeft: 3,
    });
    expect(platform.kind).toBe('connectionExpiring');
    expect(platform.title).toBe('Connection expiring');
    expect(platform.body).toBe('Jira reader · the token expires in 3 d');
    expect(platform.link).toBe('/admin/connections');
    const expired = connectionContent({
      connectionId: 'c-2',
      projectId: 'p-1',
      projectKey: 'SHOP',
      name: 'writer',
      daysLeft: -1,
    });
    expect(expired.title).toBe('Connection expired · SHOP');
    expect(expired.link).toBe('/projects/p-1?tab=connections');
    expect(expired.tag).toBe('bo-connection-c-2-expired');
  });

  it('maps every kind to a sound and has a test sample per kind', () => {
    expect(KIND_SOUND).toEqual({
      turn: 'turn',
      runFailed: 'failed',
      runLanded: 'done',
      budget: 'budget',
      ciFailed: 'failed',
      connectionExpiring: 'budget',
    });
    for (const k of [
      'turn',
      'runFailed',
      'runLanded',
      'budget',
      'ciFailed',
      'connectionExpiring',
    ] as const) {
      const c = testContent(k);
      expect(c.title.startsWith('Test: ')).toBe(true);
      expect(c.tag).toBe(`bo-test-${k}`);
    }
  });
});
