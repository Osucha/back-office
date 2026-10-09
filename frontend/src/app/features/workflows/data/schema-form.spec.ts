import {
  coerceValue,
  defaultConfig,
  extraKeys,
  fieldsFromSchema,
  humanizeKey,
  validateConfig,
  type JsonSchema,
} from './schema-form';

const IMPLEMENT_SCHEMA: JsonSchema = {
  type: 'object',
  required: ['agent', 'promptTemplate'],
  properties: {
    agent: {
      type: 'string',
      enum: ['OPENCODE', 'CLAUDE_CODE'],
      default: 'OPENCODE',
      description: 'Coding harness',
    },
    promptTemplate: { type: 'string', description: 'Mustache template' },
    verifyCommand: { type: 'string' },
    branchTemplate: { type: 'string', default: 'feature/{{issue.key}}' },
    push: { type: 'boolean', default: true },
    maxIterations: { type: 'integer', minimum: 1, maximum: 20 },
    temperature: { type: 'number' },
    labels: { type: 'array', items: { type: 'string' } },
    retryOn: { type: 'array', items: { type: 'string', enum: ['TIMEOUT', 'VERIFY_FAILED'] } },
    outputSchema: { type: 'string', format: 'json-schema' },
    env: { type: 'object', additionalProperties: { type: 'string' } },
    nullableNote: { type: ['string', 'null'] },
  },
};

describe('fieldsFromSchema', () => {
  const fields = fieldsFromSchema(IMPLEMENT_SCHEMA);
  const byKey = Object.fromEntries(fields.map((f) => [f.key, f]));

  it('maps enums to selects with options and defaults', () => {
    expect(byKey['agent'].kind).toBe('select');
    expect(byKey['agent'].options?.map((o) => o.value)).toEqual(['OPENCODE', 'CLAUDE_CODE']);
    expect(byKey['agent'].default).toBe('OPENCODE');
    expect(byKey['agent'].required).toBe(true);
    expect(byKey['agent'].description).toBe('Coding harness');
  });

  it('maps template-like strings to textareas and short strings to inputs', () => {
    expect(byKey['promptTemplate'].kind).toBe('textarea');
    expect(byKey['branchTemplate'].kind).toBe('textarea');
    expect(byKey['outputSchema'].kind).toBe('textarea');
    expect(byKey['verifyCommand'].kind).toBe('text');
    expect(byKey['verifyCommand'].required).toBe(false);
  });

  it('maps booleans, integers and numbers', () => {
    expect(byKey['push'].kind).toBe('switch');
    expect(byKey['maxIterations']).toMatchObject({
      kind: 'number',
      integer: true,
      min: 1,
      max: 20,
    });
    expect(byKey['temperature']).toMatchObject({ kind: 'number', integer: false });
  });

  it('maps arrays of strings to tags and other arrays/objects to json', () => {
    expect(byKey['labels'].kind).toBe('tags');
    expect(byKey['labels'].options).toBeUndefined();
    expect(byKey['retryOn'].kind).toBe('tags');
    expect(byKey['retryOn'].options?.length).toBe(2);
    expect(byKey['env'].kind).toBe('json');
  });

  it('handles nullable type arrays', () => {
    expect(byKey['nullableNote'].kind).toBe('text');
  });

  it('humanizes labels and preserves declaration order', () => {
    expect(byKey['promptTemplate'].label).toBe('Prompt template');
    expect(humanizeKey('verify_command')).toBe('Verify command');
    expect(fields[0].key).toBe('agent');
    expect(fields[1].key).toBe('promptTemplate');
  });

  it('honours x-order', () => {
    const ordered = fieldsFromSchema({
      type: 'object',
      properties: { b: { type: 'string', 'x-order': 1 }, a: { type: 'string', 'x-order': 0 } },
    });
    expect(ordered.map((f) => f.key)).toEqual(['a', 'b']);
  });

  it('returns nothing for empty or missing schemas', () => {
    expect(fieldsFromSchema(undefined)).toEqual([]);
    expect(fieldsFromSchema({ type: 'object' })).toEqual([]);
  });
});

describe('defaultConfig / extraKeys', () => {
  it('collects declared defaults only', () => {
    expect(defaultConfig(IMPLEMENT_SCHEMA)).toEqual({
      agent: 'OPENCODE',
      branchTemplate: 'feature/{{issue.key}}',
      push: true,
    });
  });

  it('finds keys not described by the schema', () => {
    expect(extraKeys({ agent: 'OPENCODE', custom: 1 }, IMPLEMENT_SCHEMA)).toEqual(['custom']);
    expect(extraKeys(undefined, IMPLEMENT_SCHEMA)).toEqual([]);
  });
});

describe('coerceValue', () => {
  const fields = fieldsFromSchema(IMPLEMENT_SCHEMA);
  const spec = (key: string) => fields.find((f) => f.key === key)!;

  it('turns empty values into undefined so they are dropped from the config', () => {
    expect(coerceValue(spec('verifyCommand'), '')).toBeUndefined();
    expect(coerceValue(spec('maxIterations'), null)).toBeUndefined();
  });

  it('coerces numbers and integers', () => {
    expect(coerceValue(spec('maxIterations'), '3.6')).toBe(4);
    expect(coerceValue(spec('temperature'), '0.7')).toBe(0.7);
    expect(coerceValue(spec('maxIterations'), 'abc')).toBeUndefined();
  });

  it('parses json textareas when valid and keeps the string otherwise', () => {
    expect(coerceValue(spec('env'), '{"A":"1"}')).toEqual({ A: '1' });
    expect(coerceValue(spec('env'), '{oops')).toBe('{oops');
  });

  it('cleans tag arrays', () => {
    expect(coerceValue(spec('labels'), ['a', '', 'b'])).toEqual(['a', 'b']);
  });
});

describe('validateConfig', () => {
  const fields = fieldsFromSchema(IMPLEMENT_SCHEMA);

  it('reports missing required fields', () => {
    expect(validateConfig({}, fields)).toEqual({ agent: 'Required', promptTemplate: 'Required' });
  });

  it('checks numeric bounds, integers and enum membership', () => {
    const errors = validateConfig(
      { agent: 'CURSOR', promptTemplate: 'x', maxIterations: 25 },
      fields,
    );
    expect(errors['agent']).toContain('Must be one of');
    expect(errors['maxIterations']).toBe('Must be at most 20');
    expect(
      validateConfig({ agent: 'OPENCODE', promptTemplate: 'x', maxIterations: 2.5 }, fields)[
        'maxIterations'
      ],
    ).toBe('Must be an integer');
  });

  it('passes a valid config', () => {
    expect(
      validateConfig({ agent: 'OPENCODE', promptTemplate: 'Do it', push: true }, fields),
    ).toEqual({});
  });
});
