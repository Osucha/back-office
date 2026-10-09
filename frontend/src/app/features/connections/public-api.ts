/** Public API of the connections feature for other features (dependency-cruiser: no deep imports). */
export { ProjectConnectionsPanelComponent } from './components/project-connections-panel.component';
export { NodeConnectionSectionComponent } from './components/node-connection-section.component';
export { PersonalConnectionsPanelComponent } from './components/personal-connections-panel.component';
export {
  JIRA_CONNECTION_KEY,
  capabilityLabel,
  capabilityLabels,
  pinnedConnection,
  withPinnedConnection,
} from './data/connection-model';
