import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { CodeBlockComponent } from '../../../core/ui/code-block.component';
import {
  MCP_TOOLS,
  claudeCodeCommand,
  cursorConfig,
  mcpUrl,
  vscodeConfig,
} from '../data/ide-connect';

type Client = 'claude' | 'cursor' | 'vscode';

/**
 * Account → Connect your IDE: the MCP endpoint, how to authenticate (a personal access token from
 * the section above) and copyable config for Claude Code, Cursor and VS Code, plus the tools.
 */
@Component({
  selector: 'app-connect-ide-section',
  imports: [RouterLink, CodeBlockComponent, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="intro" i18n="@@account.ide.intro">
      Back Office is an MCP server: your coding agent can list your turns, read specs and reviews,
      start workflows and decide gates from the editor, with your permissions. Nothing new to
      deploy.
    </p>
    <ol class="steps">
      <li i18n="@@account.ide.stepEndpoint">
        <strong>Endpoint.</strong> Streamable HTTP at
        <code class="url">{{ url() }}</code>
      </li>
      <li>
        <strong i18n="@@account.ide.stepToken">Token.</strong>{{ ' ' }}
        <span i18n="@@account.ide.stepTokenText"
          ><a [routerLink]="[]" [queryParams]="{ section: 'tokens' }" queryParamsHandling="merge"
            >Create a personal access token</a
          >
          (above) and use it as <code>Authorization: Bearer bo_pat_…</code>. Limit its permissions
          and expiry; revoking it disconnects the IDE.</span
        >
        <bo-help-link topic="account.tokens" variant="text" i18n="@@account.ide.tokenHelp"
          >How to create and use a token</bo-help-link
        >
      </li>
      <li i18n="@@account.ide.stepAddServer"><strong>Add the server</strong> to your client:</li>
    </ol>
    <div class="tabs" role="tablist" aria-label="IDE" i18n-aria-label="@@account.ide.tabs">
      @for (c of clients; track c.id) {
        <button
          type="button"
          role="tab"
          class="tab"
          [id]="'ide-tab-' + c.id"
          [attr.aria-selected]="client() === c.id"
          [attr.aria-controls]="'ide-panel'"
          [class.on]="client() === c.id"
          (click)="client.set(c.id)"
        >
          {{ c.label }}
        </button>
      }
    </div>
    <div id="ide-panel" role="tabpanel" [attr.aria-labelledby]="'ide-tab-' + client()">
      @switch (client()) {
        @case ('claude') {
          <app-code-block label="Terminal" i18n-label="@@account.ide.terminal" [code]="claude()" />
          <p class="note" i18n="@@account.ide.claudeNote">
            Replace the placeholder with your token. <code>claude mcp list</code> shows the
            connection.
          </p>
        }
        @case ('cursor') {
          <app-code-block [label]="files.cursor" [code]="cursor()" />
          <p class="note" i18n="@@account.ide.cursorNote">
            Or in Cursor Settings → MCP → Add new server. Keep the file out of version control.
          </p>
        }
        @case ('vscode') {
          <app-code-block [label]="files.vscode" [code]="vscode()" />
          <p class="note" i18n="@@account.ide.vscodeNote">
            VS Code asks for the token on first use and stores it in its secret storage.
          </p>
        }
      }
    </div>
    <h3 class="sub" i18n="@@account.ide.tools">Tools</h3>
    <table class="tools">
      <caption class="sr-only" i18n="@@account.ide.toolsCaption">
        Tools of the Back Office MCP server
      </caption>
      <tbody>
        @for (t of tools; track t.name) {
          <tr>
            <th scope="row">
              <code>{{ t.name }}</code>
            </th>
            <td>{{ t.does }}</td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: [
    `
      .intro {
        margin: 0 0 12px;
        font-size: 13px;
        color: var(--bo-ink-2);
        max-width: 80ch;
      }
      .steps {
        margin: 0 0 12px;
        padding-left: 20px;
        display: grid;
        gap: 6px;
        font-size: 13px;
      }
      .steps a {
        text-decoration: underline;
        text-underline-offset: 2px;
      }
      code {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .url {
        padding: 1px 6px;
        border-radius: 4px;
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
      }
      .tabs {
        display: inline-flex;
        gap: 2px;
        padding: 3px;
        margin-bottom: 8px;
        border-radius: 8px;
        background: var(--bo-surface-3);
        border: 1px solid var(--bo-line);
      }
      .tab {
        height: 26px;
        padding: 0 12px;
        border: 0;
        border-radius: 6px;
        background: transparent;
        color: var(--bo-ink-2);
        font-size: 13px;
        font-weight: 500;
        cursor: pointer;
      }
      .tab.on {
        background: var(--bo-surface);
        color: var(--bo-ink);
        box-shadow: 0 0 0 1px var(--bo-line);
      }
      .tab:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 1px;
      }
      .note {
        margin: 6px 0 0;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .sub {
        margin: 20px 0 8px;
        font-size: var(--bo-fs-dense);
        font-weight: 600;
      }
      .tools {
        width: 100%;
        border-collapse: collapse;
        font-size: 13px;
      }
      .tools th,
      .tools td {
        text-align: left;
        padding: 6px 0;
        border-top: 1px solid var(--bo-line);
        vertical-align: baseline;
      }
      .tools th {
        width: 220px;
        font-weight: 400;
      }
      .tools td {
        color: var(--bo-ink-2);
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
      }
    `,
  ],
})
export class ConnectIdeSectionComponent {
  readonly clients: { id: Client; label: string }[] = [
    { id: 'claude', label: 'Claude Code' },
    { id: 'cursor', label: 'Cursor' },
    { id: 'vscode', label: 'VS Code' },
  ];
  /** Config file names (code-block labels), the same in every language. */
  readonly files = {
    cursor: '.cursor/mcp.json', // i18n-ignore: file name
    vscode: '.vscode/mcp.json', // i18n-ignore: file name
  };
  readonly tools = MCP_TOOLS;
  readonly client = signal<Client>('claude');
  readonly url = computed(() => mcpUrl(location.origin));
  readonly claude = computed(() => claudeCodeCommand(this.url()));
  readonly cursor = computed(() => cursorConfig(this.url()));
  readonly vscode = computed(() => vscodeConfig(this.url()));
}
