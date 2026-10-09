/**
 * Form styles shared by the public auth screens and the account security dialogs (component styles
 * are encapsulated, so each component that renders these classes includes this string).
 * Relay tokens only; 40 px fields on the sign-in card (touch-friendly), 14 px text.
 */
export const AUTH_FORM_STYLES = `
  .form-title {
    margin: 0;
    font-size: var(--bo-fs-title);
    line-height: var(--bo-lh-tight);
    letter-spacing: var(--bo-tracking-title);
    font-weight: 600;
  }
  .form-lead {
    margin: var(--bo-space-1) 0 0;
    color: var(--bo-ink-2);
    font-size: var(--bo-fs-body);
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: var(--bo-space-4);
  }
  .field > label,
  .field .label {
    font-size: var(--bo-fs-dense);
    font-weight: 500;
    color: var(--bo-ink);
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--bo-space-2);
  }
  .field .label-aside {
    font-weight: 400;
    color: var(--bo-ink-3);
    font-size: var(--bo-fs-meta);
  }
  .text-input {
    height: 40px;
    width: 100%;
    box-sizing: border-box;
    padding: 0 12px;
    font: inherit;
    font-size: var(--bo-fs-body);
    color: var(--bo-ink);
    background: var(--bo-surface);
    border: 1px solid var(--bo-line-strong);
    border-radius: var(--bo-radius-control);
    outline: none;
    transition: border-color var(--bo-dur-fast) var(--bo-ease), box-shadow var(--bo-dur-fast) var(--bo-ease);
  }
  .text-input::placeholder {
    color: var(--bo-ink-3);
  }
  .text-input:hover {
    border-color: var(--bo-ink-3);
  }
  .text-input:focus {
    border-color: var(--bo-accent);
    box-shadow: 0 0 0 3px var(--bo-accent-soft);
  }
  .text-input[readonly] {
    background: var(--bo-surface-2);
    color: var(--bo-ink-2);
  }
  .text-input[aria-invalid='true'] {
    border-color: var(--bo-blocked);
  }
  .text-input.mono {
    font-family: var(--bo-font-mono);
    font-size: 13px;
    letter-spacing: 0.02em;
  }
  .field-error {
    color: var(--bo-blocked-ink);
    font-size: var(--bo-fs-meta);
    margin: 0;
  }
  .field-note {
    color: var(--bo-ink-3);
    font-size: var(--bo-fs-meta);
    margin: 0;
  }
  .submit {
    margin-top: var(--bo-space-5);
    width: 100%;
    height: 40px;
    font-weight: 500;
  }
  .banner {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    margin-top: var(--bo-space-4);
    padding: 10px 12px;
    border-radius: var(--bo-radius-control);
    font-size: var(--bo-fs-dense);
    line-height: 1.45;
    border: 1px solid transparent;
  }
  .banner bo-glyph {
    margin-top: 2px;
  }
  .banner.error {
    background: var(--bo-blocked-soft);
    color: var(--bo-blocked-ink);
    border-color: color-mix(in srgb, var(--bo-blocked) 30%, transparent);
  }
  .banner.warn {
    background: var(--bo-you-soft);
    color: var(--bo-you-ink);
    border-color: color-mix(in srgb, var(--bo-you) 30%, transparent);
  }
  .banner.info {
    background: var(--bo-surface-2);
    color: var(--bo-ink-2);
    border-color: var(--bo-line);
  }
  .banner.ok {
    background: var(--bo-done-soft);
    color: var(--bo-done-ink);
    border-color: color-mix(in srgb, var(--bo-done) 30%, transparent);
  }
  .banner strong {
    font-weight: 600;
  }
  .banner .countdown {
    font-family: var(--bo-font-mono);
    font-variant-numeric: tabular-nums;
    font-weight: 600;
  }
  .link-button {
    background: none;
    border: 0;
    padding: 0;
    font: inherit;
    font-size: var(--bo-fs-dense);
    color: var(--bo-accent);
    cursor: pointer;
    text-decoration: underline;
    text-decoration-thickness: 1px;
    text-underline-offset: 2px;
  }
  .link-button:hover {
    color: var(--bo-accent-hover);
  }
  .link-button:disabled {
    color: var(--bo-ink-3);
    cursor: default;
  }
  .row-between {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--bo-space-3);
    flex-wrap: wrap;
  }
  .who {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    max-width: 100%;
    margin-top: var(--bo-space-3);
    padding: 4px 10px 4px 4px;
    border: 1px solid var(--bo-line);
    border-radius: var(--bo-radius-chip);
    background: var(--bo-surface-2);
    font-size: var(--bo-fs-dense);
    color: var(--bo-ink-2);
  }
  .who .avatar {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: var(--bo-ink);
    color: var(--bo-on-ink);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    font-weight: 600;
    flex: none;
  }
  .who .addr {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;
