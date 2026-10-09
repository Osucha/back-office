import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { probe, url } from './api-base';
import type {
  AuthConfig,
  Me,
  PersonalToken,
  PersonalTokenCreated,
  PersonalTokenInput,
  SessionInfo,
  UserPreferences,
} from './types';

/** `auth` and `tokens` tags: current identity, login configuration, logout, personal tokens. */
@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly http = inject(HttpClient);

  /** Bootstrap probe: 401 means "not signed in" and is handled by the caller. */
  me(): Observable<Me> {
    return this.http.get<Me>(url('me'), probe());
  }

  /** Same call as `me()` but as a normal request: used to extend the session ("Stay signed in"). */
  touch(): Observable<Me> {
    return this.http.get<Me>(url('me'));
  }

  config(): Observable<AuthConfig> {
    return this.http.get<AuthConfig>(url('auth', 'config'), probe());
  }

  logout(): Observable<{ logoutUrl?: string | null }> {
    return this.http.post<{ logoutUrl?: string | null }>(url('auth', 'logout'), {}, probe());
  }

  /** Remaining session time; does not extend the session. */
  session(): Observable<SessionInfo> {
    return this.http.get<SessionInfo>(url('auth', 'session'), probe());
  }

  updatePreferences(prefs: UserPreferences): Observable<UserPreferences> {
    return this.http.put<UserPreferences>(url('me', 'preferences'), prefs);
  }

  // ---- personal access tokens

  tokens(): Observable<PersonalToken[]> {
    return this.http.get<PersonalToken[]>(url('tokens'));
  }

  createToken(input: PersonalTokenInput): Observable<PersonalTokenCreated> {
    return this.http.post<PersonalTokenCreated>(url('tokens'), input);
  }

  revokeToken(tokenId: string): Observable<void> {
    return this.http.delete<void>(url('tokens', tokenId));
  }
}
