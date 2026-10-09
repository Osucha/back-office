import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SessionNoticesComponent } from './core/auth/session-notices.component';
import { ThemeService } from './core/ui/theme.service';
import { NotifyService } from './core/ui/notify.service';
import { DensityService } from './core/ui/density.service';
import { A11yPatchService } from './core/ui/a11y-patch.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, SessionNoticesComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<router-outlet /><app-session-notices />`,
})
export class App {
  constructor() {
    // Eager: the theme applies on the login page too; screen notifications close on navigation.
    inject(ThemeService);
    inject(NotifyService);
    inject(DensityService);
    inject(A11yPatchService);
  }
}
