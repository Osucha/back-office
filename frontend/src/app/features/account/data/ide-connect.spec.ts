import { MCP_TOOLS, claudeCodeCommand, cursorConfig, mcpUrl, vscodeConfig } from './ide-connect';

describe('connect your IDE', () => {
  const url = mcpUrl('https://bo.example.com/');

  it('builds the MCP endpoint and the Claude Code command', () => {
    expect(url).toBe('https://bo.example.com/mcp');
    expect(claudeCodeCommand(url, 'bo_pat_x')).toBe(
      'claude mcp add --transport http back-office https://bo.example.com/mcp --header "Authorization: Bearer bo_pat_x"',
    );
  });

  it('builds the Cursor and VS Code configs', () => {
    expect(JSON.parse(cursorConfig(url, 'bo_pat_x'))).toEqual({
      mcpServers: {
        'back-office': { url, headers: { Authorization: 'Bearer bo_pat_x' } },
      },
    });
    const vs = JSON.parse(vscodeConfig(url));
    expect(vs.servers['back-office']).toMatchObject({ type: 'http', url });
    expect(vs.servers['back-office'].headers.Authorization).toBe(
      'Bearer ${input:back-office-token}',
    );
    expect(vs.inputs[0].password).toBe(true);
  });

  it('lists every tool of the MCP server (BackOfficeMcpTools)', () => {
    expect(MCP_TOOLS.map((t) => t.name)).toEqual([
      'list_my_turns',
      'get_run',
      'get_artifact',
      'start_workflow',
      'decide_gate',
      'search_task_library',
      'get_project_context',
      'get_delivery_insights',
      'get_agent_insights',
      'list_projects',
      'get_workflow_palette',
      'get_workflow',
      'validate_workflow',
      'create_workflow',
      'update_workflow',
      'create_project',
      'get_model_options',
      'get_model_plan',
    ]);
    expect(MCP_TOOLS.every((t) => t.does.length > 0)).toBe(true);
  });
});
