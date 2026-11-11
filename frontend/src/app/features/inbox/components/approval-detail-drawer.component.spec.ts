import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ApprovalRequest } from '../../../core/api/types';
import { ApprovalDetailDrawerComponent } from './approval-detail-drawer.component';

const approval: ApprovalRequest = {
  id: 'a1',
  runId: 'r1',
  status: 'OPEN',
  question: 'Merge?',
  options: ['approve', 'reject'],
  canDecide: true,
  checklist: [{ id: 'tests', text: 'Tests are green', required: true }],
  approvers: {
    projectRoles: ['approver'],
    accountable: 'approver',
    users: [],
    forbidSelfApproval: true,
  },
  context: { artifacts: [] },
};

describe('ApprovalDetailDrawerComponent checklist gating', () => {
  beforeEach(async () => {
    // The artefact viewer is deferred (@defer), so the component must be compiled first.
    await TestBed.configureTestingModule({
      imports: [ApprovalDetailDrawerComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('enables Approve only after the required items are ticked', () => {
    const fixture = TestBed.createComponent(ApprovalDetailDrawerComponent);
    fixture.componentRef.setInput('approval', approval);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.state('approve').enabled).toBe(false);
    c.toggleCheck('tests', true);
    expect(c.state('approve').enabled).toBe(true);
    c.toggleCheck('tests', false);
    expect(c.state('approve').enabled).toBe(false);
  });

  it('renders the Approve button disabled until the checklist is complete', async () => {
    const fixture = TestBed.createComponent(ApprovalDetailDrawerComponent);
    fixture.componentRef.setInput('approval', approval);
    fixture.detectChanges();
    await settle(fixture);
    const find = () =>
      document.querySelector('button[data-option="approve"]') as HTMLButtonElement | null;
    expect(find()?.disabled).toBe(true);
    fixture.componentInstance.toggleCheck('tests', true);
    fixture.detectChanges();
    await settle(fixture);
    expect(find()?.disabled).toBe(false);
  });

  it('says who the gate is assigned to and offers Assign only to those who may assign', async () => {
    const fixture = TestBed.createComponent(ApprovalDetailDrawerComponent);
    fixture.componentRef.setInput('approval', {
      ...approval,
      canAssign: true,
      assignees: [{ userId: 'u1', displayName: 'Ann Approver' }],
      assignment: {
        userIds: ['u1'],
        assignedAt: '2026-10-07T10:00:00Z',
        assignedBy: { kind: 'USER', userId: 'b1', displayName: 'Bea Boss' },
        comment: 'Ann covers this week',
      },
    });
    fixture.detectChanges();
    await settle(fixture);
    const text = document.body.textContent ?? '';
    expect(text).toContain('Assigned to');
    expect(text).toContain('Ann Approver');
    expect(text).toContain('Bea Boss');
    expect(text).toContain('Ann covers this week');
    const assign = document.querySelector('button.assign-btn') as HTMLButtonElement | null;
    expect(assign).not.toBeNull();
    assign!.click();
    expect(fixture.componentInstance.assigning()?.id).toBe('a1');

    fixture.componentInstance.assigning.set(null);
    fixture.componentRef.setInput('approval', { ...approval, id: 'a2', canAssign: false });
    await settle(fixture);
    expect(document.querySelector('button.assign-btn')).toBeNull();
    expect(document.body.textContent).toContain('Approver');
  });

  it('shows the agent questions and sends the answers as one numbered comment (ADR 0060)', async () => {
    const fixture = TestBed.createComponent(ApprovalDetailDrawerComponent);
    fixture.componentRef.setInput('approval', {
      ...approval,
      kind: 'AGENT_QUESTIONS',
      question: 'The agent has questions',
      options: ['answer', 'cancel'],
      checklist: [],
      questionRound: 1,
      maxQuestionRounds: 2,
      questions: [
        { question: 'Which database?', why: 'Two are configured' },
        { question: 'Keep the old API?' },
      ],
    });
    fixture.detectChanges();
    await settle(fixture);
    const c = fixture.componentInstance;
    const text = document.body.textContent ?? '';
    expect(text).toContain('Which database?');
    expect(text).toContain('Two are configured');
    expect(text).toContain('Round 1 of 2');
    expect(document.querySelectorAll('[data-answer]')).toHaveLength(2);
    expect(document.querySelector('textarea.comment')).toBeNull();
    expect(c.labelFor({ kind: 'AGENT_QUESTIONS' }, 'answer')).toBe('Answer and continue');
    expect(c.labelFor({ kind: 'AGENT_QUESTIONS' }, 'cancel')).toBe('Cancel the step');
    expect(c.state('answer')).toEqual({
      enabled: false,
      reason: 'Answer at least one question first',
    });
    expect(c.state('cancel').enabled).toBe(true);

    c.setAnswer(0, 'Postgres');
    expect(c.state('answer').enabled).toBe(true);
    const http = TestBed.inject(HttpTestingController);
    c.submit('answer');
    const req = http.expectOne((r) => r.method === 'POST' && r.url.includes('/approvals/a1'));
    expect(req.request.body).toEqual({ option: 'answer', comment: '1. Postgres\n2. -' });
  });

  it('shows the reason instead of buttons when the caller cannot decide', async () => {
    const fixture = TestBed.createComponent(ApprovalDetailDrawerComponent);
    fixture.componentRef.setInput('approval', {
      ...approval,
      canDecide: false,
      cannotDecideReason: 'Self-approval is not allowed',
    });
    fixture.detectChanges();
    await settle(fixture);
    expect(document.body.textContent).toContain('Self-approval is not allowed');
    expect(document.querySelector('button[data-option="approve"]')).toBeNull();
  });
});

/** The drawer renders through an overlay; let its timers run instead of waiting for stability. */
async function settle(fixture: { detectChanges(): void }): Promise<void> {
  for (let i = 0; i < 3; i++) {
    fixture.detectChanges();
    await new Promise((r) => setTimeout(r, 20));
  }
  fixture.detectChanges();
}
