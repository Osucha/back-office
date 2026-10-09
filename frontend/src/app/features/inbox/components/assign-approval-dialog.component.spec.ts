import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NzMessageService } from 'ng-zorro-antd/message';
import type { ApprovalRequest, AssigneeCandidate } from '../../../core/api/types';
import { AssignApprovalDialogComponent } from './assign-approval-dialog.component';

const approval: ApprovalRequest = {
  id: 'a1',
  status: 'OPEN',
  question: 'Merge?',
  canAssign: true,
  assignees: [{ userId: 'gone', displayName: 'Gone' }],
  assignment: { userIds: ['gone'], assignedAt: '2026-10-07T10:00:00Z' },
};
const candidates: AssigneeCandidate[] = [
  { userId: 'u1', displayName: 'Ann', projectRoles: ['approver'], assigned: false },
  { userId: 'u2', displayName: 'Bob', projectRoles: [], assigned: true },
];

describe('AssignApprovalDialogComponent', () => {
  let http: HttpTestingController;
  const success = vi.fn();

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AssignApprovalDialogComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NzMessageService, useValue: { success } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function open() {
    const fixture = TestBed.createComponent(AssignApprovalDialogComponent);
    fixture.componentRef.setInput('approval', approval);
    fixture.detectChanges();
    http.expectOne('/api/v1/approvals/a1/assignee-candidates').flush(candidates);
    fixture.detectChanges();
    return fixture;
  }

  it('offers the candidates labelled with their roles and preselects the current assignees', () => {
    const c = open().componentInstance;
    expect(c.candidates().map((x) => c.optionLabel(x))).toEqual(['Ann (Approver)', 'Bob']);
    expect(c.selected()).toEqual(['u2']);
    expect(c.reassigned()).toBe(true);
  });

  it('assigns the picked users with the comment and emits the updated approval', () => {
    const fixture = open();
    const c = fixture.componentInstance;
    const emitted: ApprovalRequest[] = [];
    c.assigned.subscribe((a) => emitted.push(a));
    c.comment.set('  Ann is back  ');
    c.save(['u1']);
    const req = http.expectOne('/api/v1/approvals/a1/assignees');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ userIds: ['u1'], comment: 'Ann is back' });
    req.flush({ ...approval, assignees: [{ userId: 'u1', displayName: 'Ann' }] });
    expect(emitted[0].assignees?.[0].displayName).toBe('Ann');
    expect(success).toHaveBeenCalledWith('Gate assigned');
  });

  it('clears the assignment with an empty list and shows a refused assignment inline', () => {
    const c = open().componentInstance;
    c.save([]);
    const req = http.expectOne('/api/v1/approvals/a1/assignees');
    expect(req.request.body).toEqual({ userIds: [], comment: null });
    req.flush(
      { title: 'Bad Request', status: 400, detail: 'Bob cannot decide this gate' },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(c.error()).toContain('Bob cannot decide this gate');
    expect(c.busy()).toBe(false);
  });
});
