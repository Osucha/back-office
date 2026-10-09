import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CanDirective, CanInProjectDirective } from './can.directive';
import { IdentityService } from './identity.service';
import { meFixture } from './testing';

@Component({
  imports: [CanDirective, CanInProjectDirective],
  template: `
    <button *boCan="'projects.create'" id="create">New project</button>
    <button *boCan="'admin.users.manage'; else ro" id="manage">Invite</button>
    <ng-template #ro><span id="ro">read-only</span></ng-template>
    <button *boCanInProject="{ permission: 'workflow.edit', projectId: projectId() }" id="edit">
      Edit
    </button>
    <button
      *boCanInProject="{
        permission: 'project.manage',
        projectId: 'new',
        projectPermissions: fresh,
      }"
      id="fresh"
    >
      Settings
    </button>
  `,
})
class HostComponent {
  readonly projectId = signal('p-1');
  fresh = ['project.manage'];
}

describe('*boCan / *boCanInProject', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const q = (id: string) => fixture.nativeElement.querySelector('#' + id) as HTMLElement | null;
    return { fixture, q };
  }

  it('renders only what the permissions allow, with else templates', async () => {
    TestBed.inject(IdentityService).setMe(meFixture());
    const { fixture, q } = render();
    await fixture.whenStable();
    expect(q('create')).not.toBeNull();
    expect(q('manage')).toBeNull();
    expect(q('ro')).not.toBeNull();
    expect(q('edit')).not.toBeNull();
    expect(q('fresh')).not.toBeNull();
  });

  it('re-renders when the project or the identity changes', async () => {
    const identity = TestBed.inject(IdentityService);
    identity.setMe(meFixture());
    const { fixture, q } = render();
    fixture.componentInstance.projectId.set('p-2');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(q('edit')).toBeNull();

    identity.setMe(meFixture({ platformPermissions: ['admin.users.manage'] }));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(q('manage')).not.toBeNull();
    expect(q('ro')).toBeNull();
    expect(q('create')).toBeNull();
  });
});
