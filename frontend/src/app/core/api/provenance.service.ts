import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { url } from './api-base';
import type { AttestationSummary, AttestationVerification, DsseEnvelope } from './types';

/** Provenance attestations (tag `provenance`, ADR 0025): in-toto statements in DSSE envelopes. */
@Injectable({ providedIn: 'root' })
export class ProvenanceApi {
  private readonly http = inject(HttpClient);

  forRun(runId: string): Observable<AttestationSummary[]> {
    return this.http.get<AttestationSummary[]>(url('runs', runId, 'attestations'));
  }

  /** The DSSE envelope (`application/vnd.in-toto+json`). */
  envelope(attestationId: string): Observable<DsseEnvelope> {
    return this.http.get<DsseEnvelope>(url('attestations', attestationId), {
      headers: new HttpHeaders({ Accept: 'application/vnd.in-toto+json, application/json' }),
    });
  }

  /** Verifies the envelope against the published keys; returns the decoded statement. */
  verify(envelope: DsseEnvelope): Observable<AttestationVerification> {
    return this.http.post<AttestationVerification>(url('attestations', 'verify'), envelope);
  }
}
