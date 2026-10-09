import {
  commonVariants,
  emailName,
  isCommonPassword,
  lengthMeter,
  passwordAcceptable,
  passwordChecks,
} from './password-rules';

// The backend test list (CommonPasswordVariantsTest) so both sides are checked on the same cases.
const BACKEND_LIST = new Set(['password', 'dragon', 'letmein', 'qwerty']);
const inList = (s: string) => BACKEND_LIST.has(s);

describe('password rules (mirror of the backend policy)', () => {
  it.each([
    'Password2026!',
    'p4ssw0rd123456',
    '123dragon!!!!',
    'dragondragon',
    'LetMeIn!!2026',
    '!!!QWERTY2026',
  ])('rejects the decorated common password %s', (pw) => {
    expect(isCommonPassword(pw, inList)).toBe(true);
    const check = passwordChecks(pw, 'alice@example.com', undefined, inList).find(
      (c) => c.id === 'common',
    );
    expect(check?.ok).toBe(false);
  });

  it.each(['correct horse battery', 'Relay-kx81Qp2vZ', 'dragonfly-mountain-9'])(
    'accepts %s, which only contains a common word among other material',
    (pw) => expect(isCommonPassword(pw, inList)).toBe(false),
  );

  it('undoes every leetspeak substitution and strips surrounding digits and symbols', () => {
    expect(commonVariants('P@$$w0rd!!')).toContain('password');
    expect(commonVariants('T3$71ng')).toContain('testing');
    // decoration is stripped before leetspeak is undone (as in the backend): 7357!ng keeps "ng"
    expect(commonVariants('7357!ng')).not.toContain('testing');
    expect(commonVariants('Adm1n2026')).toContain('admin');
    expect(commonVariants('--Qwerty--')).toContain('qwerty');
    // repetition of a unit
    expect(commonVariants('abcabcabcabc')).toContain('abc');
    // short cores are not reduced further
    expect(commonVariants('ab12')).toEqual(new Set(['ab12']));
  });

  it('uses the bundled list by default', () => {
    expect(isCommonPassword('Welcome2026!!')).toBe(true);
    expect(isCommonPassword('violet-anchor-47-lantern')).toBe(false);
  });

  it('checks the length in characters (code points) within the policy', () => {
    const policy = {
      minLength: 12,
      maxLength: 64,
      rejectCommon: false,
      rejectContainingEmail: false,
    };
    expect(passwordChecks('short', null, policy)).toEqual([
      { id: 'length', label: 'At least 12 characters', ok: false, pending: false },
    ]);
    expect(passwordChecks('a'.repeat(65), null, policy)[0]).toMatchObject({
      label: 'At most 64 characters',
      ok: false,
    });
    expect(passwordChecks('🙂'.repeat(12), null, policy)[0].ok).toBe(true);
    expect(passwordChecks(' '.repeat(12), null, policy)[0].ok).toBe(false);
  });

  it('rejects passwords containing the e-mail name (3 or more characters)', () => {
    const checks = passwordChecks('my-anna.kowalska-rules', 'Anna.Kowalska@example.com');
    const email = checks.find((c) => c.id === 'email')!;
    expect(email.ok).toBe(false);
    expect(email.label).toContain('anna.kowalska');
    expect(emailName('al@example.com')).toBeNull();
    expect(
      passwordChecks('al-is-here-forever', 'al@example.com').find((c) => c.id === 'email')?.ok,
    ).toBe(true);
  });

  it('lists only the enabled rules and marks an empty password as pending', () => {
    const checks = passwordChecks('', 'a@b.c');
    expect(checks.map((c) => c.id)).toEqual(['length', 'common', 'email']);
    expect(checks.every((c) => c.pending && !c.ok)).toBe(true);
    expect(passwordAcceptable(passwordChecks('violet-anchor-47-lantern', 'anna@example.com'))).toBe(
      true,
    );
  });

  it('meters the length against twice the minimum', () => {
    expect(lengthMeter('')).toMatchObject({ level: 'empty', ratio: 0 });
    expect(lengthMeter('abcdef')).toMatchObject({
      level: 'short',
      label: '6 more characters needed',
    });
    expect(lengthMeter('a'.repeat(12))).toMatchObject({ level: 'ok', ratio: 0.5 });
    expect(lengthMeter('a'.repeat(30))).toMatchObject({ level: 'strong', ratio: 1 });
  });
});
