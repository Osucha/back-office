import { Injectable } from '@angular/core';

export interface PendingMfa {
  mfaToken: string;
  email: string;
  returnTo: string;
}

/**
 * Hands an MFA step token from the set-password page to the sign-in page in memory (a token never
 * goes into the URL or storage). Taken once.
 */
@Injectable({ providedIn: 'root' })
export class PendingMfaService {
  private pending: PendingMfa | null = null;

  set(value: PendingMfa): void {
    this.pending = value;
  }

  take(): PendingMfa | null {
    const p = this.pending;
    this.pending = null;
    return p;
  }
}
