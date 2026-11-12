import {
  DEFAULT_BRANCH_TEMPLATE,
  deliverChangesSchema,
  deliverFormConfig,
  deliverRepositories,
  deliverySummary,
  deliverySummaryTitle,
  mergeRequestLabel,
  opensMergeRequest,
  withDeliverRepositories,
  withFormConfig,
} from './deliver-changes';
import { fieldsFromSchema } from './schema-form';

describe('Deliver changes action (ADR 0063)', () => {
  it('renders a translated form even without a server schema', () => {
    const fields = fieldsFromSchema(deliverChangesSchema(null, {}));
    expect(fields.map((f) => f.key)).toEqual([
      'branchTemplate',
      'commitSubjectTemplate',
      'mergeRequest',
      'mergeRequestTitleTemplate',
      'mergeRequestDescriptionTemplate',
      'mergeRequestDraft',
    ]);
    expect(fields[0]).toMatchObject({ label: 'Branch', kind: 'text' });
    expect(fields[1]).toMatchObject({ label: 'Commit subject', kind: 'text' });
    expect(fields[2]).toMatchObject({
      label: 'Open a merge request',
      kind: 'switch',
      default: true,
    });
    expect(fields[4].kind).toBe('textarea');
    expect(fields[0].description).toContain(DEFAULT_BRANCH_TEMPLATE);
  });

  it('takes defaults and unknown keys from the server, never the repositories', () => {
    const schema = deliverChangesSchema(
      {
        type: 'object',
        properties: {
          mergeRequestDraft: { type: 'boolean', default: true, title: 'Draft MR' },
          repositories: { type: 'array', items: { type: 'string' } },
          labels: { type: 'array', items: { type: 'string' } },
        },
      },
      {},
    );
    expect(schema.properties?.['mergeRequestDraft']).toMatchObject({
      title: 'Draft',
      default: true,
    });
    expect(schema.properties?.['repositories']).toBeUndefined();
    expect(fieldsFromSchema(schema).at(-1)?.key).toBe('labels');
  });

  it('hides the merge request fields while no merge request is opened and keeps their values', () => {
    const config = {
      mergeRequest: false,
      mergeRequestTitleTemplate: 'T',
      repositories: ['app'],
      branchTemplate: 'b',
    };
    expect(opensMergeRequest(config)).toBe(false);
    expect(opensMergeRequest({})).toBe(true);
    expect(Object.keys(deliverChangesSchema(null, config).properties ?? {})).toEqual([
      'branchTemplate',
      'commitSubjectTemplate',
      'mergeRequest',
    ]);
    expect(deliverFormConfig(config)).toEqual({ mergeRequest: false, branchTemplate: 'b' });
    // the form changes the branch: the hidden title and the repositories stay
    expect(withFormConfig(config, { mergeRequest: false, branchTemplate: 'c' })).toEqual({
      mergeRequest: false,
      branchTemplate: 'c',
      repositories: ['app'],
      mergeRequestTitleTemplate: 'T',
    });
    // turning the merge request back on shows (and keeps) what the form sends
    expect(withFormConfig(config, { mergeRequest: true })).toEqual({
      mergeRequest: true,
      repositories: ['app'],
    });
  });

  it('reads and writes the repositories to deliver', () => {
    expect(deliverRepositories({ repositories: ['app', { key: 'web' }, '', 3] })).toEqual([
      'app',
      'web',
    ]);
    expect(deliverRepositories({})).toEqual([]);
    expect(withDeliverRepositories({ a: 1 }, ['app'])).toEqual({ a: 1, repositories: ['app'] });
    expect(withDeliverRepositories({ a: 1, repositories: ['app'] }, [])).toEqual({ a: 1 });
  });

  it('summarises the branch and the merge request for the node card', () => {
    expect(deliverySummary({})).toEqual({
      branch: DEFAULT_BRANCH_TEMPLATE,
      mergeRequest: 'ON',
      repositories: [],
    });
    expect(
      deliverySummary({ branchTemplate: ' fix/{{issue.key}} ', mergeRequestDraft: true }),
    ).toEqual({
      branch: 'fix/{{issue.key}}',
      mergeRequest: 'DRAFT',
      repositories: [],
    });
    expect(deliverySummary({ mergeRequest: false, mergeRequestDraft: true }).mergeRequest).toBe(
      'OFF',
    );
    expect(mergeRequestLabel('ON')).toBe('MR');
    expect(mergeRequestLabel('DRAFT')).toBe('MR (draft)');
    expect(mergeRequestLabel('OFF')).toBe('no MR');
    expect(
      deliverySummaryTitle({ branch: 'b', mergeRequest: 'ON', repositories: ['app', 'web'] }),
    ).toBe('Branch b, MR, app, web');
    expect(deliverySummaryTitle({ branch: 'b', mergeRequest: 'OFF', repositories: [] })).toBe(
      'Branch b, no MR, every changed repository',
    );
  });
});
