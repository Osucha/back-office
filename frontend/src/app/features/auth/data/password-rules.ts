import { icu } from '../../../core/i18n/icu';
import type { PasswordPolicy } from '../../../core/api/types';
import { COMMON_PASSWORDS } from './common-passwords';

/** The platform default (backend `PasswordPolicy`), used until `/auth/config` answers. */
export const DEFAULT_POLICY: PasswordPolicy = {
  minLength: 12,
  maxLength: 128,
  rejectCommon: true,
  rejectContainingEmail: true,
};

export type PasswordCheckId = 'length' | 'common' | 'email';

export interface PasswordCheck {
  id: PasswordCheckId;
  /** The rule, phrased positively ("At least 12 characters"). */
  label: string;
  ok: boolean;
  /** Not evaluated yet (empty password): shown neutral, not as a failure. */
  pending: boolean;
}

const LEET: Readonly<Record<string, string>> = {
  '0': 'o',
  '1': 'i',
  '3': 'e',
  '4': 'a',
  '5': 's',
  '7': 't',
  '@': 'a',
  $: 's',
  '!': 'i',
};
const MIN_CORE = 4;
const MIN_LOCAL_PART = 3;
const isLetter = (c: string) => /\p{L}/u.test(c);

function trimNonLetters(s: string): string {
  const chars = [...s];
  let a = 0;
  let b = chars.length;
  while (a < b && !isLetter(chars[a])) a++;
  while (b > a && !isLetter(chars[b - 1])) b--;
  return chars.slice(a, b).join('');
}

/**
 * Mirror of the backend's `PasswordPolicy.commonVariants`: the password, lower-cased, the "core"
 * with leading/trailing digits and symbols stripped, the core with leetspeak undone, and the unit of
 * a repeated core (`dragondragon` -> `dragon`). `Password2026!`, `p4ssw0rd123456` and `!!!QWERTY2026`
 * all reduce to a common word.
 */
export function commonVariants(password: string): Set<string> {
  const lower = password.toLowerCase();
  const core = trimNonLetters(lower);
  const deLeet = trimNonLetters([...core].map((c) => LEET[c] ?? c).join(''));
  const variants = new Set([password, lower]);
  for (const c of [core, deLeet]) {
    if (c.length < MIN_CORE) continue;
    variants.add(c);
    for (let unit = 1; unit <= c.length / 2; unit++) {
      if (c.length % unit !== 0) continue;
      const first = c.slice(0, unit);
      if (first.repeat(c.length / unit) === c) {
        variants.add(first);
        break;
      }
    }
  }
  return variants;
}

export function isCommonPassword(
  password: string,
  isCommon: (s: string) => boolean = (s) => COMMON_PASSWORDS.has(s),
): boolean {
  return password.length > 0 && [...commonVariants(password)].some(isCommon);
}

/** The local part of the e-mail when the policy checks it (at least 3 characters), else null. */
export function emailName(email: string | null | undefined): string | null {
  const local = (email ?? '').split('@')[0].trim().toLowerCase();
  return local.length >= MIN_LOCAL_PART ? local : null;
}

/** Code points, like the backend (`codePointCount`): an emoji counts as one character. */
export function passwordLength(password: string): number {
  return [...password].length;
}

/**
 * The live hints under a new-password field, in the order of the policy. Only the enabled rules are
 * listed. The backend validates again on submit (its common list is larger).
 */
export function passwordChecks(
  password: string,
  email: string | null | undefined,
  policy: PasswordPolicy = DEFAULT_POLICY,
  isCommon?: (s: string) => boolean,
): PasswordCheck[] {
  const empty = password.length === 0;
  const len = passwordLength(password);
  const checks: PasswordCheck[] = [
    {
      id: 'length',
      label:
        len > policy.maxLength
          ? icu(
              $localize`:@@auth.rules.atMost:{count, plural, one {At most # character} other {At most # characters}}`,
              { count: policy.maxLength },
            )
          : icu(
              $localize`:@@auth.rules.atLeast:{count, plural, one {At least # character} other {At least # characters}}`,
              { count: policy.minLength },
            ),
      ok: len >= policy.minLength && len <= policy.maxLength && password.trim().length > 0,
      pending: empty,
    },
  ];
  if (policy.rejectCommon)
    checks.push({
      id: 'common',
      label: $localize`:@@auth.rules.notCommon:Not a common password`,
      ok: !empty && !isCommonPassword(password, isCommon),
      pending: empty,
    });
  const name = policy.rejectContainingEmail ? emailName(email) : null;
  if (policy.rejectContainingEmail)
    checks.push({
      id: 'email',
      label: name
        ? $localize`:@@auth.rules.noEmailName:Does not contain your e-mail name (${name}:name:)`
        : $localize`:@@auth.rules.noEmail:Does not contain your e-mail name`,
      ok: !empty && !(name && password.toLowerCase().includes(name)),
      pending: empty,
    });
  return checks;
}

export function passwordAcceptable(checks: readonly PasswordCheck[]): boolean {
  return checks.every((c) => c.ok);
}

/**
 * Length meter: 0..1 of the way from nothing to twice the minimum (length is what makes a password
 * strong under this policy), with a word for the screen reader.
 */
export function lengthMeter(
  password: string,
  policy: PasswordPolicy = DEFAULT_POLICY,
): { ratio: number; level: 'empty' | 'short' | 'ok' | 'strong'; label: string } {
  const len = passwordLength(password);
  const target = policy.minLength * 2;
  const ratio = Math.min(1, len / target);
  if (len === 0)
    return { ratio: 0, level: 'empty', label: $localize`:@@auth.meter.empty:No password yet` };
  if (len < policy.minLength)
    return {
      ratio,
      level: 'short',
      label: icu(
        $localize`:@@auth.meter.short:{count, plural, one {# more character needed} other {# more characters needed}}`,
        { count: policy.minLength - len },
      ),
    };
  if (len < target) return { ratio, level: 'ok', label: $localize`:@@auth.meter.ok:Long enough` };
  return { ratio, level: 'strong', label: $localize`:@@auth.meter.strong:Long and strong` };
}
