import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import type { ActorRef } from '../api/types';

const KIND_ICON: Record<string, string> = {
  USER: 'user',
  PERSONAL_TOKEN: 'key',
  CI_TOKEN: 'cloud-sync',
  SYSTEM: 'setting',
};
const KIND_LABEL = (): Record<string, string> => ({
  USER: $localize`:@@core.actor.user:User`,
  PERSONAL_TOKEN: $localize`:@@core.actor.personalToken:Personal access token`,
  CI_TOKEN: $localize`:@@core.actor.ciToken:CI token`,
  SYSTEM: $localize`:@@core.actor.system:System`,
});

/** Who did it (`ActorRef`): name with an icon for the actor kind (user, token, CI, system). */
@Component({
  selector: 'app-actor',
  imports: [NzIconModule, NzTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (actor(); as a) {
      <span class="actor" nz-tooltip [nzTooltipTitle]="kindLabel()">
        <nz-icon [nzType]="icon()" />
        <span>{{ name() }}</span>
      </span>
    } @else {
      <span class="muted">-</span>
    }
  `,
  styles: [
    `
      .actor {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }
    `,
  ],
})
export class ActorComponent {
  readonly actor = input<ActorRef | null | undefined>(null);
  readonly name = computed(() => actorName(this.actor()));
  readonly icon = computed(() => KIND_ICON[this.actor()?.kind ?? 'USER'] ?? 'user');
  readonly kindLabel = computed(() => KIND_LABEL()[this.actor()?.kind ?? 'USER'] ?? '');
}

export function actorName(actor: ActorRef | null | undefined): string {
  if (!actor) return '-';
  if (actor.displayName) return actor.displayName;
  if (actor.kind === 'SYSTEM') return $localize`:@@core.actor.system:System`;
  return actor.userId ? actor.userId.slice(0, 8) : $localize`:@@core.actor.unknown:unknown`;
}
