/** Public API of the workflows feature for other features (dependency-cruiser: no deep imports). */
export * from './components/workflow-canvas.component';
export * from './data/graph-model';
export * from './data/auto-layout';
/** ADR 0054: the template editor renders a template's config with the designer's schema form. */
export { SchemaFormComponent } from './components/schema-form.component';
export type { JsonSchema } from './data/schema-form';
/** ADR 0063: the config keys a template still sets, with translated labels. */
export { REMOVED_STEP_KEYS, templateFormSchema } from './data/step-config-keys';
/** ADR 0063: the "Deliver changes" action (run detail facts). */
export { DELIVER_CHANGES, deliverySummary, mergeRequestLabel } from './data/deliver-changes';
