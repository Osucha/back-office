import manifestJson from '../../../../docs/manual/manual.json';
import type { HelpService } from '../core/help/help.service';
import type { ManualManifest } from '../core/help/manual';
import type { Router } from '@angular/router';
import { helpPaletteProvider, manualPaletteItems } from './palette-providers';

const manifest = manifestJson as ManualManifest;

describe('help palette provider', () => {
  it('has one entry per chapter and per anchor, linking into the manual', () => {
    const items = manualPaletteItems(manifest);
    const chapters = manifest.groups.flatMap((g) => g.chapters);
    const anchors = chapters.flatMap((c) => c.anchors);
    expect(items.length).toBe(chapters.length + anchors.length);
    expect(items.find((i) => i.id === 'help:integrations-jira#jira-token')).toMatchObject({
      title: 'Jira: Jira token',
      link: '/help/integrations-jira#jira-token',
    });
    expect(items.find((i) => i.id === 'help:runs')).toMatchObject({
      title: 'Manual: Runs',
      link: '/help/runs',
    });
    expect(manualPaletteItems(null)).toEqual([]);
  });

  it('offers "Open manual" without a query and the whole manual with one', async () => {
    const opened: string[] = [];
    const help = {
      manifest: () => Promise.resolve(manifest),
      openForUrl: (u: string) => opened.push(u),
    } as unknown as HelpService;
    const provider = helpPaletteProvider(help, { url: '/runs' } as Router);
    const empty = provider.items('') as ReturnType<typeof manualPaletteItems>;
    expect(empty.map((i) => i.id)).toEqual(['help:manual', 'help:page']);
    empty[1].run?.();
    expect(opened).toEqual(['/runs']);
    const all = await (provider.items('jira') as Promise<ReturnType<typeof manualPaletteItems>>);
    expect(all.length).toBe(2 + manualPaletteItems(manifest).length);
  });
});
