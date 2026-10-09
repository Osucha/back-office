import { recoveryCodesFileName, recoveryCodesText } from './recovery-codes';

describe('recovery codes download', () => {
  const codes = ['abcd-efgh', 'ijkl-mnop', 'qrst-uvwx'];

  it('lists every code on its own line after an explanation', () => {
    const text = recoveryCodesText(codes, 'anna@example.com', new Date('2026-09-27T10:00:00Z'));
    const lines = text.split('\n');
    expect(lines[0]).toBe('Back Office recovery codes');
    expect(lines).toContain('Account: anna@example.com');
    expect(lines).toContain('Created: 2026-09-27T10:00:00.000Z');
    expect(text).toContain('Each code signs you in once');
    const first = lines.indexOf('abcd-efgh');
    expect(lines.slice(first, first + 3)).toEqual(codes);
    expect(text.endsWith('\n')).toBe(true);
  });

  it('leaves out the account line without an e-mail', () => {
    expect(recoveryCodesText(codes, null)).not.toContain('Account:');
  });

  it('names the file after the e-mail name', () => {
    expect(recoveryCodesFileName('Anna.K+bo@example.com')).toBe(
      'backoffice-recovery-codes-anna.k-bo.txt',
    );
    expect(recoveryCodesFileName(undefined)).toBe('backoffice-recovery-codes.txt');
  });
});
