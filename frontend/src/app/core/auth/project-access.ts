/**
 * Project permission of the caller for a loaded entity. `Project.myPermissions` (returned with the
 * project) wins because it is fresher than `/me` (a project created a moment ago); otherwise the
 * permissions from `/me` for `projectId` are used.
 */
export interface ProjectPermissionSource {
  canInProject(permission: string, projectId?: string | null): boolean;
}

export function projectCan(
  identity: ProjectPermissionSource,
  project: { id?: string; myPermissions?: string[] } | null | undefined,
  permission: string,
  projectId?: string | null,
): boolean {
  if (project?.myPermissions?.includes(permission)) return true;
  const id = project?.id ?? projectId;
  return id ? identity.canInProject(permission, id) : false;
}
