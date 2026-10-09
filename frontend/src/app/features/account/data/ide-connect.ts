/** Snippets for connecting an IDE agent to the Back Office MCP server (docs/architecture/11-governance.md). */

export const MCP_SERVER_NAME = 'back-office';
export const TOKEN_PLACEHOLDER = '<your personal access token>';

export function mcpUrl(origin: string): string {
  return `${origin.replace(/\/+$/, '')}/mcp`;
}

export function claudeCodeCommand(url: string, token = TOKEN_PLACEHOLDER): string {
  return `claude mcp add --transport http ${MCP_SERVER_NAME} ${url} --header "Authorization: Bearer ${token}"`;
}

/** `.cursor/mcp.json` (Cursor) — `mcpServers` with a URL and headers. */
export function cursorConfig(url: string, token = TOKEN_PLACEHOLDER): string {
  return JSON.stringify(
    // i18n-ignore: config file content
    { mcpServers: { [MCP_SERVER_NAME]: { url, headers: { Authorization: `Bearer ${token}` } } } },
    null,
    2,
  );
}

/** `.vscode/mcp.json` (VS Code) — `servers` with `type: http`; the token is prompted, not stored. */
export function vscodeConfig(url: string): string {
  return JSON.stringify(
    {
      inputs: [
        {
          type: 'promptString',
          id: 'back-office-token',
          description: 'Back Office personal access token', // i18n-ignore: config file content
          password: true,
        },
      ],
      servers: {
        [MCP_SERVER_NAME]: {
          type: 'http',
          url,
          headers: { Authorization: 'Bearer ${input:back-office-token}' }, // i18n-ignore: config
        },
      },
    },
    null,
    2,
  );
}

export const MCP_TOOLS: { name: string; does: string }[] = [
  {
    name: 'list_my_turns',
    does: $localize`:@@account.ide.toolListMyTurns:Gates and failures waiting for you`,
  },
  {
    name: 'get_run',
    does: $localize`:@@account.ide.toolGetRun:Run summary, nodes and the artefact list`,
  },
  {
    name: 'get_artifact',
    does: $localize`:@@account.ide.toolGetArtifact:Artefact content: spec, plan, tasks, review`,
  },
  {
    name: 'start_workflow',
    does: $localize`:@@account.ide.toolStartWorkflow:Start a workflow run for an issue key`,
  },
  {
    name: 'decide_gate',
    does: $localize`:@@account.ide.toolDecideGate:Approve, revise or reject with a comment (checklist ids required to approve)`,
  },
  {
    name: 'search_task_library',
    does: $localize`:@@account.ide.toolSearchTaskLibrary:Find templates and skills`,
  },
  {
    name: 'get_project_context',
    does: $localize`:@@account.ide.toolGetProjectContext:Constitution, CI entrypoints and recent runs of a project`,
  },
  {
    name: 'get_delivery_insights',
    does: $localize`:@@account.ide.toolGetDeliveryInsights:The delivery insights of a project`,
  },
];
