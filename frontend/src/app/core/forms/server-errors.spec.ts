import { HttpErrorResponse } from '@angular/common/http';
import { FormArray, FormControl, FormGroup } from '@angular/forms';
import { applyServerErrors, reportFormError, summarize, toControlPath } from './server-errors';

const problem = (status: number, body: unknown) =>
  new HttpErrorResponse({ status, error: body, url: '/api/v1/x' });

describe('server form errors', () => {
  const form = () =>
    new FormGroup({
      email: new FormControl(''),
      repository: new FormGroup({ url: new FormControl('') }),
      projectMemberships: new FormArray([new FormGroup({ role: new FormControl('') })]),
    });

  it('sets errors.server on matching controls and returns the rest', () => {
    const f = form();
    const r = applyServerErrors(
      f,
      problem(400, {
        detail: 'Validation failed',
        errors: [
          { field: 'email', message: 'already invited' },
          { field: 'repository.url', message: 'not a git URL' },
          { field: 'projectMemberships[0].role', message: 'unknown role' },
          { field: 'tenant', message: 'not allowed' },
        ],
      }),
    );
    expect(f.get('email')?.errors).toEqual({ server: 'already invited' });
    expect(f.get('email')?.touched).toBe(true);
    expect(f.get('repository.url')?.errors?.['server']).toBe('not a git URL');
    expect(f.get('projectMemberships.0.role')?.errors?.['server']).toBe('unknown role');
    expect(r.applied).toHaveLength(3);
    expect(r.unmatched).toEqual([{ field: 'tenant', message: 'not allowed' }]);
    expect(summarize(r)).toBe('Validation failed (tenant: not allowed)');
  });

  it('control paths', () => {
    expect(toControlPath('a[2].b')).toBe('a.2.b');
  });

  it('reportFormError notifies once; statuses the interceptor handled are not repeated', () => {
    const notify = { error: vi.fn() };
    reportFormError(notify, form(), problem(409, { detail: 'E-mail exists' }), 'Could not invite');
    expect(notify.error).toHaveBeenCalledWith('Could not invite', 'E-mail exists');
    notify.error.mockClear();
    reportFormError(notify, form(), problem(403, { detail: 'no' }));
    reportFormError(notify, form(), problem(0, null));
    expect(notify.error).not.toHaveBeenCalled();
  });
});
