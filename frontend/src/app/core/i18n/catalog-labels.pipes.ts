import { Pipe, type PipeTransform } from '@angular/core';
import { permissionDescription, roleDescription, roleName } from './catalog-labels';

/** `{{ p.key | permissionDescription: p.description }}`: see `permissionDescription()`. */
@Pipe({ name: 'permissionDescription' })
export class PermissionDescriptionPipe implements PipeTransform {
  transform(key: string | null | undefined, serverText?: string | null): string {
    return key ? permissionDescription(key, serverText) : (serverText ?? '');
  }
}

/** `{{ r.key | roleName: r.name }}` or `{{ 'owner' | roleName }}`: see `roleName()`. */
@Pipe({ name: 'roleName' })
export class RoleNamePipe implements PipeTransform {
  transform(key: string | null | undefined, serverName?: string | null): string {
    return key ? roleName(key, serverName) : (serverName ?? '');
  }
}

/** `{{ r.key | roleDescription: r.description }}`: see `roleDescription()`. */
@Pipe({ name: 'roleDescription' })
export class RoleDescriptionPipe implements PipeTransform {
  transform(key: string | null | undefined, serverText?: string | null): string {
    return key ? roleDescription(key, serverText) : (serverText ?? '');
  }
}
