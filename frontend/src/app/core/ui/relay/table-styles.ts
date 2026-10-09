/**
 * Shared component styles for Relay list tables (13 px rows, keyboard cursor, quiet row actions,
 * chips). Add to a component's `styles` next to its own rules:
 *
 *   styles: [RELAY_TABLE_STYLES, `...`]
 *
 * Rows: `<tr class="clickable-row" [class.active]="nav.cursor() === i" [attr.data-nav-index]="i"
 * tabindex="0">`; cells: `.cell-title`, `.cell-sub`, `.key`, `.chips > .chip`, `.row-actions`.
 */
export const RELAY_TABLE_STYLES = `
  td {
    font-size: 13px;
  }
  tr.active > td {
    background: var(--bo-accent-soft) !important;
  }
  /* Meta text stays AA on the cursor tint. */
  tr.active .cell-sub,
  tr.active .key,
  tr.active .quiet,
  tr.active .sub,
  tr.active .mono-cell,
  tr.active .sub-mono,
  tr.active bo-provenance {
    color: var(--bo-ink-2);
  }
  tr[data-nav-index]:focus-visible,
  tr.clickable-row:focus-visible {
    outline: 2px solid var(--bo-accent);
    outline-offset: -2px;
  }
  .cell-main {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 1px;
    line-height: 1.35;
  }
  .cell-line {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
    flex-wrap: wrap;
  }
  .cell-title {
    font-weight: 500;
    color: var(--bo-ink);
  }
  a.cell-title {
    text-decoration: none;
  }
  a.cell-title:hover {
    color: var(--bo-accent);
  }
  .cell-sub {
    font-size: 12px;
    color: var(--bo-ink-3);
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 1;
    -webkit-box-orient: vertical;
    max-width: 72ch;
  }
  .key {
    font-family: var(--bo-font-mono);
    font-size: 11.5px;
    color: var(--bo-ink-3);
  }
  .mono-cell {
    font-family: var(--bo-font-mono);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    color: var(--bo-ink-2);
    white-space: nowrap;
  }
  .quiet {
    color: var(--bo-ink-3);
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    height: 20px;
    padding: 0 8px;
    border-radius: 999px;
    background: var(--bo-surface-3);
    color: var(--bo-ink-2);
    font-size: 11.5px;
    font-weight: 500;
    white-space: nowrap;
  }
  .chip.mono {
    font-family: var(--bo-font-mono);
    font-weight: 400;
  }
  .chip.accent {
    background: var(--bo-accent-soft);
    color: var(--bo-accent);
  }
  .chip.outline {
    background: transparent;
    box-shadow: inset 0 0 0 1px var(--bo-line-strong);
  }
  .row-actions {
    display: flex;
    gap: 2px;
    justify-content: flex-end;
    white-space: nowrap;
  }
  .row-actions .ant-btn {
    color: var(--bo-ink-3);
  }
  tr:hover .row-actions .ant-btn,
  tr.active .row-actions .ant-btn,
  .row-actions .ant-btn:focus-visible {
    color: var(--bo-ink);
  }
  .row-actions .ant-btn.del:not([disabled]):hover,
  .row-actions .ant-btn.del:not([disabled]):focus-visible {
    color: var(--bo-blocked-ink);
  }
  td bo-provenance {
    flex-wrap: nowrap;
    white-space: nowrap;
  }
  .state-word {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 12px;
    color: var(--bo-ink-2);
    white-space: nowrap;
  }
`;
