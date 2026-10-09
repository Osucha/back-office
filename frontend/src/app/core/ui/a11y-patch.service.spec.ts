import { TestBed } from '@angular/core/testing';
import { A11yPatchService } from './a11y-patch.service';

describe('A11yPatchService', () => {
  it('names inner inputs and switch buttons and drops invalid segmented attributes', () => {
    const root = document.createElement('div');
    root.innerHTML = `
      <label for="p-model">Model profile</label>
      <nz-select id="p-model"><input class="ant-select-selection-search-input" /></nz-select>
      <nz-select aria-label="Filter by project"><input /></nz-select>
      <nz-switch aria-label="Enabled"><button class="ant-switch"></button></nz-switch>
      <nz-segmented><label><div class="ant-segmented-item-label" aria-selected="true">Open</div></label></nz-segmented>`;
    document.body.appendChild(root);
    const service = TestBed.inject(A11yPatchService);
    service.patch(root);
    const inputs = root.querySelectorAll('input');
    expect(inputs[0].getAttribute('aria-label')).toBe('Model profile');
    expect(inputs[1].getAttribute('aria-label')).toBe('Filter by project');
    expect(root.querySelector('button.ant-switch')!.getAttribute('aria-label')).toBe('Enabled');
    expect(root.querySelector('.ant-segmented-item-label')!.hasAttribute('aria-selected')).toBe(
      false,
    );
    root.remove();
  });
});
