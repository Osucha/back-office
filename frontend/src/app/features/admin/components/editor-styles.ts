/**
 * Shared styles of the admin editors (group, role, grants, platform settings): one surface with
 * sections (intro left, fields right) and the sticky action bar at the bottom.
 */
export const EDITOR_STYLES = `
  .editor {
    max-width: 1040px;
    background: var(--bo-surface);
    border: 1px solid var(--bo-line);
    border-radius: var(--bo-radius-card);
  }
  .section {
    display: grid;
    grid-template-columns: minmax(200px, 300px) minmax(0, 1fr);
    gap: 24px;
    padding: 20px;
    border-bottom: 1px solid var(--bo-line);
  }
  .section.wide {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  @media (max-width: 860px) {
    .section {
      grid-template-columns: 1fr;
      gap: 8px;
    }
  }
  .section h2 {
    margin: 0 0 4px;
    font-size: 14px;
    font-weight: 600;
    display: flex;
    align-items: baseline;
    gap: 8px;
  }
  .section h2 .count {
    font-weight: 400;
    font-size: 12px;
    color: var(--bo-ink-3);
  }
  .intro p {
    margin: 0;
    font-size: 12.5px;
    color: var(--bo-ink-3);
  }
  .fields {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 0;
  }
  .fields nz-form-item {
    margin-bottom: 0;
  }
  bo-action-bar {
    border-radius: 0 0 var(--bo-radius-card) var(--bo-radius-card);
    overflow: hidden;
  }
`;
