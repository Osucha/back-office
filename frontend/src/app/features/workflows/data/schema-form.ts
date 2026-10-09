/**
 * Turns a `configSchema` (JSON Schema, as returned in the workflow palette for task templates
 * and control nodes) into a flat list of field specs that `SchemaFormComponent` renders with
 * NG-ZORRO controls.
 *
 * Mapping: string -> input (textarea for long/template-like fields), enum -> select,
 * boolean -> switch, integer/number -> input-number, array of strings -> tags,
 * anything else (objects, arrays of objects, mixed types) -> JSON textarea.
 */
export type JsonSchema = {
  type?: string | string[];
  title?: string;
  description?: string;
  properties?: Record<string, JsonSchema>;
  required?: string[];
  enum?: Array<string | number | boolean | null>;
  default?: unknown;
  format?: string;
  items?: JsonSchema;
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  additionalProperties?: boolean | JsonSchema;
  'x-multiline'?: boolean;
  'x-order'?: number;
  [key: string]: unknown;
};

export type FieldKind = 'text' | 'textarea' | 'select' | 'switch' | 'number' | 'tags' | 'json';

export interface FieldSpec {
  key: string;
  label: string;
  kind: FieldKind;
  required: boolean;
  description?: string;
  options?: Array<{ value: string | number | boolean | null; label: string }>;
  integer?: boolean;
  min?: number;
  max?: number;
  pattern?: string;
  placeholder?: string;
  default?: unknown;
}

/** Keys that are almost always multi-line text even when the schema does not say so. */
const MULTILINE_KEY =
  /(template|prompt|schema|instructions|constitution|rubric|body|message|question|description|context)/i;

export function fieldsFromSchema(schema: JsonSchema | null | undefined): FieldSpec[] {
  if (!schema || !schema.properties) return [];
  const required = new Set(schema.required ?? []);
  return Object.entries(schema.properties)
    .map(([key, prop], index) => ({
      spec: toField(key, prop, required.has(key)),
      order: prop['x-order'] ?? index,
    }))
    .sort((a, b) => a.order - b.order)
    .map((x) => x.spec);
}

function toField(key: string, prop: JsonSchema, required: boolean): FieldSpec {
  const base: FieldSpec = {
    key,
    label: prop.title ?? humanizeKey(key),
    kind: 'json',
    required,
    description: prop.description,
    default: prop.default,
  };
  const type = primaryType(prop);

  if (prop.enum && prop.enum.length) {
    return {
      ...base,
      kind: 'select',
      options: prop.enum.map((v) => ({ value: v, label: v === null ? 'null' : String(v) })),
    };
  }
  switch (type) {
    case 'boolean':
      return { ...base, kind: 'switch' };
    case 'integer':
    case 'number':
      return {
        ...base,
        kind: 'number',
        integer: type === 'integer',
        min: prop.minimum,
        max: prop.maximum,
      };
    case 'string': {
      const multiline =
        prop['x-multiline'] === true ||
        ['markdown', 'multiline', 'textarea', 'template', 'json', 'json-schema'].includes(
          prop.format ?? '',
        ) ||
        (prop.maxLength ?? 0) > 200 ||
        MULTILINE_KEY.test(key);
      return {
        ...base,
        kind: multiline ? 'textarea' : 'text',
        pattern: prop.pattern,
        placeholder: prop.format,
      };
    }
    case 'array': {
      const itemType = prop.items ? primaryType(prop.items) : undefined;
      if (itemType === 'string' && !prop.items?.enum) return { ...base, kind: 'tags' };
      if (itemType === 'string' && prop.items?.enum) {
        return {
          ...base,
          kind: 'tags',
          options: prop.items.enum!.map((v) => ({ value: v, label: String(v) })),
        };
      }
      return { ...base, kind: 'json' };
    }
    default:
      return { ...base, kind: 'json' };
  }
}

function primaryType(prop: JsonSchema): string | undefined {
  if (Array.isArray(prop.type)) return prop.type.find((t) => t !== 'null');
  return prop.type;
}

/** camelCase / snake_case / kebab-case -> "Sentence case". */
export function humanizeKey(key: string): string {
  const spaced = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase();
  return spaced.replace(/^\w/, (c) => c.toUpperCase());
}

/** Config populated with the schema defaults (only for properties that declare one). */
export function defaultConfig(schema: JsonSchema | null | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, prop] of Object.entries(schema?.properties ?? {})) {
    if (prop.default !== undefined) out[key] = prop.default;
  }
  return out;
}

/** Keys present in the config but not described by the schema (edited as raw JSON). */
export function extraKeys(
  config: Record<string, unknown> | undefined,
  schema: JsonSchema | null | undefined,
): string[] {
  const known = new Set(Object.keys(schema?.properties ?? {}));
  return Object.keys(config ?? {}).filter((k) => !known.has(k));
}

/** Coerces a raw control value to what the schema expects; empty strings become undefined. */
export function coerceValue(spec: FieldSpec, raw: unknown): unknown {
  if (raw === '' || raw === null || raw === undefined) return undefined;
  switch (spec.kind) {
    case 'number': {
      const n = typeof raw === 'number' ? raw : Number(raw);
      if (Number.isNaN(n)) return undefined;
      return spec.integer ? Math.round(n) : n;
    }
    case 'switch':
      return Boolean(raw);
    case 'tags':
      return Array.isArray(raw)
        ? raw.filter((v) => v !== '' && v !== null && v !== undefined)
        : raw;
    case 'json':
      if (typeof raw === 'string') {
        try {
          return JSON.parse(raw);
        } catch {
          return raw;
        }
      }
      return raw;
    default:
      return raw;
  }
}

/** Validates required fields and simple constraints client-side; returns messages keyed by field. */
export function validateConfig(
  config: Record<string, unknown> | undefined,
  fields: FieldSpec[],
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const f of fields) {
    const error = fieldError(f, config?.[f.key]);
    if (error) errors[f.key] = error;
  }
  return errors;
}

/** The message for one field value, null when it is valid. */
function fieldError(f: FieldSpec, v: unknown): string | null {
  const empty = v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);
  if (empty) return f.required ? $localize`:@@workflows.schemaForm.required:Required` : null;
  if (f.kind === 'number' && typeof v === 'number') return numberError(f, v);
  if (f.kind === 'text' && f.pattern && typeof v === 'string' && !new RegExp(f.pattern).test(v))
    return $localize`:@@workflows.schemaForm.pattern:Must match ${f.pattern}:pattern:`;
  if (f.kind === 'select' && f.options && !f.options.some((o) => o.value === v))
    return $localize`:@@workflows.schemaForm.oneOf:Must be one of ${f.options.map((o) => o.label).join(', ')}:options:`;
  return null;
}

/** Integer before maximum before minimum (the most specific message wins). */
function numberError(f: FieldSpec, v: number): string | null {
  if (f.integer && !Number.isInteger(v))
    return $localize`:@@workflows.schemaForm.integer:Must be an integer`;
  if (f.max !== undefined && v > f.max)
    return $localize`:@@workflows.schemaForm.max:Must be at most ${f.max}:max:`;
  if (f.min !== undefined && v < f.min)
    return $localize`:@@workflows.schemaForm.min:Must be at least ${f.min}:min:`;
  return null;
}
