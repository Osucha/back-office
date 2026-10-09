import { HttpErrorResponse } from '@angular/common/http';

/**
 * The message for a failed password submission: the backend's policy violations ("password is too
 * common") as a sentence, a wrong current password, or the problem detail.
 */
export function passwordErrorOf(err: unknown): {
  field: 'new' | 'current' | 'form';
  message: string;
} {
  if (!(err instanceof HttpErrorResponse))
    return {
      field: 'form',
      message: $localize`:@@auth.password.notSaved:The password could not be saved. Try again.`,
    };
  if (err.status === 401)
    return {
      field: 'current',
      message: $localize`:@@auth.password.currentWrong:The current password is not correct.`,
    };
  if (err.status === 0)
    return {
      field: 'form',
      message: $localize`:@@auth.flow.unreachable:Back Office cannot reach the server. Check your connection and try again.`,
    };
  const body = (err.error && typeof err.error === 'object' ? err.error : {}) as {
    detail?: string;
    errors?: { field?: string; message?: string }[];
  };
  const messages = (body.errors ?? [])
    .filter((e) => !e.field || /password/i.test(e.field))
    .map((e) => sentence(e.message ?? ''))
    .filter(Boolean);
  if (err.status === 400 && messages.length) return { field: 'new', message: messages.join(' ') };
  return {
    field: 'form',
    message:
      body.detail ||
      $localize`:@@auth.password.notSaved:The password could not be saved. Try again.`,
  };
}

function sentence(s: string): string {
  const t = s.trim();
  if (!t) return '';
  const cap = t[0].toUpperCase() + t.slice(1);
  return /[.!?]$/.test(cap) ? cap : cap + '.';
}
