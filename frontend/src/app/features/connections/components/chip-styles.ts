/** Chips of the connections screens: tone by token, the word always travels with the colour. */
export const CONNECTION_CHIP_STYLES = `
  .cchip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 11.5px;
    line-height: 18px;
    padding: 0 7px;
    border-radius: 999px;
    border: 1px solid var(--bo-line);
    background: var(--bo-surface-2);
    color: var(--bo-ink-2);
    white-space: nowrap;
  }
  .cchip.ok {
    background: var(--bo-done-soft);
    border-color: transparent;
    color: var(--bo-done-ink);
  }
  .cchip.warn {
    background: var(--bo-you-soft);
    border-color: transparent;
    color: var(--bo-you-ink);
  }
  .cchip.bad {
    background: var(--bo-blocked-soft);
    border-color: transparent;
    color: var(--bo-blocked-ink);
  }
  .cchip.cap {
    background: var(--bo-accent-soft);
    border-color: transparent;
    color: var(--bo-agent-ink);
  }
  .cchips {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }
`;
