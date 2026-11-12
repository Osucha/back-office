import { REMOVED_STEP_KEYS, localizedConfigSchema, templateFormSchema } from './step-config-keys';
import { fieldsFromSchema } from './schema-form';

describe('step config keys (ADR 0063)', () => {
  it('lists the keys that left the step', () => {
    for (const k of [
      'push',
      'phase',
      'repositories',
      'setupCommand',
      'collectArtifacts',
      'agentTools',
    ])
      expect(REMOVED_STEP_KEYS).toContain(k);
    expect(REMOVED_STEP_KEYS).not.toContain('verifyCommand');
  });

  it('translates the known keys, keeps the server type and drops the given keys', () => {
    const schema = localizedConfigSchema(
      {
        type: 'object',
        properties: {
          verifyCommand: { type: 'string', title: 'server title', maxLength: 500 },
          push: { type: 'boolean' },
          maxInputChars: { type: 'integer', title: 'Max input' },
        },
      },
      ['push'],
    );
    expect(Object.keys(schema?.properties ?? {})).toEqual(['verifyCommand', 'maxInputChars']);
    expect(schema?.properties?.['verifyCommand']).toMatchObject({
      title: 'Verify command',
      maxLength: 500,
      'x-multiline': false,
    });
    expect(schema?.properties?.['maxInputChars']?.title).toBe('Max input');
    // a long command stays one line
    expect(fieldsFromSchema(schema)[0].kind).toBe('text');
    expect(localizedConfigSchema({ properties: { push: {} } }, ['push'])).toBeNull();
    expect(localizedConfigSchema(null)).toBeNull();
  });

  it('builds the template form: codingAgent first, as a checkbox', () => {
    const schema = templateFormSchema(
      { type: 'object', properties: { allowQuestions: { type: 'boolean', default: true } } },
      {
        type: 'object',
        required: ['phase'],
        properties: { phase: { type: 'string' }, codingAgent: { type: 'boolean' } },
      },
    );
    const fields = fieldsFromSchema(schema);
    expect(fields.map((f) => f.key)).toEqual(['codingAgent', 'allowQuestions']);
    expect(fields[0]).toMatchObject({
      kind: 'checkbox',
      label: 'Works in the repository (needs a coding agent)',
    });
    expect(fields[1]).toMatchObject({ label: 'Agent may ask questions', default: true });
    expect(schema?.required).toBeUndefined();
    expect(templateFormSchema(null, null)).toBeNull();
  });
});
