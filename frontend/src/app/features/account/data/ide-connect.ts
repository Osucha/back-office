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

/** Every `@McpTool` of `BackOfficeMcpTools` (governance-adapters), in the order the server declares them. */
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
    does: $localize`:@@account.ide.toolStartWorkflow:Start a workflow run, optionally for an issue key and with run parameters`,
  },
  {
    name: 'decide_gate',
    does: $localize`:@@account.ide.toolDecideGate:Approve, revise or reject with a comment (approving needs the checklist ids; token with gate decisions via MCP)`,
  },
  {
    name: 'search_task_library',
    does: $localize`:@@account.ide.toolSearchTaskLibrary:Find templates and skills`,
  },
  {
    name: 'get_project_context',
    does: $localize`:@@account.ide.toolGetProjectContext:Constitution, repository, CI entrypoints, workflows and recent runs of a project`,
  },
  {
    name: 'get_delivery_insights',
    does: $localize`:@@account.ide.toolGetDeliveryInsights:Delivery insights of one project or of all your projects`,
  },
  {
    name: 'get_agent_insights',
    does: $localize`:@@account.ide.toolGetAgentInsights:Agent insights: accepted results and cost per agent, model and template`,
  },
  {
    name: 'list_projects',
    does: $localize`:@@account.ide.toolListProjects:Projects you can see`,
  },
  {
    name: 'get_workflow_palette',
    does: $localize`:@@account.ide.toolGetWorkflowPalette:What a workflow of a project can be built from: nodes, actions and task templates`,
  },
  {
    name: 'get_workflow',
    does: $localize`:@@account.ide.toolGetWorkflow:One workflow with its full definition`,
  },
  {
    name: 'validate_workflow',
    does: $localize`:@@account.ide.toolValidateWorkflow:Check a workflow definition without saving it`,
  },
  {
    name: 'create_workflow',
    does: $localize`:@@account.ide.toolCreateWorkflow:Create a workflow in a project (token with workflow edits via MCP)`,
  },
  {
    name: 'update_workflow',
    does: $localize`:@@account.ide.toolUpdateWorkflow:Save a new version of a workflow (token with workflow edits via MCP)`,
  },
  {
    name: 'create_project',
    does: $localize`:@@account.ide.toolCreateProject:Create a project with at most one repository, optionally personal (token with workflow edits via MCP)`,
  },
  {
    name: 'get_model_options',
    does: $localize`:@@account.ide.toolGetModelOptions:Models a step of a project may use, usable ones first`,
  },
  {
    name: 'get_model_plan',
    does: $localize`:@@account.ide.toolGetModelPlan:The model of every step of a workflow and whether you can use it`,
  },
];
