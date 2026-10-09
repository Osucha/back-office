import type { Router } from '@angular/router';
import { safeReturnTo } from '../../../core/auth/browser-navigation';
import type { IdentityService } from '../../../core/auth/identity.service';
import type { LoginDestination } from './login-flow';

/**
 * After a successful sign-in step: load `/me` (the session now exists, restricted or not) and go to
 * the app, or to the screen that lifts the session restriction (the target keeps `returnTo`).
 */
export async function goAfterSignIn(
  destination: LoginDestination,
  identity: IdentityService,
  router: Router,
): Promise<void> {
  await identity.load();
  const returnTo = safeReturnTo(destination.returnTo);
  const keep = returnTo === '/' ? null : returnTo;
  if (destination.kind === 'change-password') {
    await router.navigate(['/change-password'], { queryParams: { forced: 1, returnTo: keep } });
  } else if (destination.kind === 'setup-mfa') {
    await router.navigate(['/setup-mfa'], { queryParams: { forced: 1, returnTo: keep } });
  } else {
    await router.navigateByUrl(returnTo);
  }
}
