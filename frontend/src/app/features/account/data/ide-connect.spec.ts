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

  it('lists the tools of doc 11', () => {
    expect(MCP_TOOLS.map((t) => t.name)).toContain('decide_gate');
    expect(MCP_TOOLS).toHaveLength(8);
  });
});
