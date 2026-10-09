import {
  EnvironmentInjector,
  Injectable,
  createEnvironmentInjector,
  importProvidersFrom,
  inject,
} from '@angular/core';
import type { NzModalService } from 'ng-zorro-antd/modal';

export interface ConfirmOptions {
  title: string;
  content?: string;
  okText?: string;
  danger?: boolean;
}

/**
 * Promise-based wrapper over NzModalService.confirm for destructive or irreversible actions.
 * `ng-zorro-antd/modal` (with `@angular/cdk/drag-drop`) is imported on the first confirmation, so
 * it stays out of the initial bundle although the unsaved-changes guard uses this service.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly env = inject(EnvironmentInjector);
  private modal: Promise<NzModalService> | null = null;

  private modalService(): Promise<NzModalService> {
    this.modal ??= import('ng-zorro-antd/modal').then(({ NzModalModule, NzModalService }) =>
      createEnvironmentInjector([importProvidersFrom(NzModalModule)], this.env).get(NzModalService),
    );
    return this.modal;
  }

  async confirm(options: ConfirmOptions): Promise<boolean> {
    const modal = await this.modalService();
    return new Promise((resolve) => {
      modal.confirm({
        nzTitle: options.title,
        nzContent: options.content,
        nzOkText: options.okText ?? $localize`:@@core.confirm.ok:Confirm`,
        nzOkDanger: options.danger ?? false,
        nzCancelText: $localize`:@@core.confirm.cancel:Cancel`,
        nzOnOk: () => resolve(true),
        nzOnCancel: () => resolve(false),
        nzAutofocus: 'ok',
      });
    });
  }

  danger(
    title: string,
    content?: string,
    okText = $localize`:@@core.confirm.delete:Delete`,
  ): Promise<boolean> {
    return this.confirm({ title, content, okText, danger: true });
  }
}
