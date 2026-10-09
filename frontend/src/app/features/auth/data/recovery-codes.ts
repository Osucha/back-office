/** Content of the recovery-codes .txt download (one code per line, with what they are and how to use them). */
export function recoveryCodesText(
  codes: readonly string[],
  email: string | null | undefined,
  issuedAt: Date = new Date(),
): string {
  const lines = [
    $localize`:@@auth.recoveryFile.title:Back Office recovery codes`,
    email ? $localize`:@@auth.recoveryFile.account:Account: ${email}:email:` : null,
    $localize`:@@auth.recoveryFile.created:Created: ${issuedAt.toISOString()}:date:`,
    '',
    $localize`:@@auth.recoveryFile.howTo:Each code signs you in once when you cannot use your authenticator app.`,
    $localize`:@@auth.recoveryFile.keep:Keep them somewhere safe; creating new codes makes these stop working.`,
    '',
    ...codes,
    '',
  ].filter((l): l is string => l !== null);
  return lines.join('\n');
}

/** `backoffice-recovery-codes-anna.txt` (the e-mail name, file-name safe). */
export function recoveryCodesFileName(email: string | null | undefined): string {
  const name = (email ?? '')
    .split('@')[0]
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-');
  return name ? `backoffice-recovery-codes-${name}.txt` : 'backoffice-recovery-codes.txt';
}

/** Saves `text` as a file through a temporary object URL. */
export function downloadText(fileName: string, text: string, doc: Document = document): void {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const href = URL.createObjectURL(blob);
  const a = doc.createElement('a');
  a.href = href;
  a.download = fileName;
  a.rel = 'noopener';
  doc.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 0);
}
