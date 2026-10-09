/**
 * Who may change what in the Task Library: GLOBAL items need the platform permission
 * `library.manage`, PROJECT items `library.project.manage` on their project.
 */
import type { Scope } from '../../../core/api/types';

export interface LibraryPermissionSource {
  can(permission: string | readonly string[]): boolean;
  canInProject(permission: string, projectId?: string | null): boolean;
}

export function canEditScope(
  identity: LibraryPermissionSource,
  scope: Pick<Scope, 'type' | 'projectId'> | null | undefined,
): boolean {
  if (!scope || scope.type === 'GLOBAL') return identity.can('library.manage');
  return !!scope.projectId && identity.canInProject('library.project.manage', scope.projectId);
}

/** Can create at least somewhere (globally or in one project). */
export function canCreateInLibrary(identity: LibraryPermissionSource): boolean {
  return identity.can('library.manage') || identity.canInProject('library.project.manage');
}

/** Can create project-scoped items (shadow/extend a template). */
export function canCreateProjectItems(identity: LibraryPermissionSource): boolean {
  return identity.canInProject('library.project.manage');
}

/** Projects the caller may put new PROJECT-scoped items into. */
export function editableProjects<T extends { id: string }>(
  identity: LibraryPermissionSource,
  projects: readonly T[],
): T[] {
  return projects.filter((p) => identity.canInProject('library.project.manage', p.id));
}
