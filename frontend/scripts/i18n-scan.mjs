// Source scanner of the i18n gate (scripts/i18n-check.mjs). Parses every component template with
// Angular's own template parser and every TypeScript file with the TypeScript compiler and reports
//   - where each custom id is used (id -> files, for the area check),
//   - i18n markers and $localize calls without a custom @@id,
//   - user-visible text that is not marked for translation ("untagged"): template text, text
//     attributes (placeholder, title, aria-label, nzTitle, *Label, *Text ...), string literals in
//     template expressions and, in TypeScript, UI-looking strings (`label: 'Home'`,
//     `notify.error('Could not save')`, sentence-like literals).
// The untagged detection is a heuristic. Escape hatches: `// i18n-ignore` on the line (or the line
// before) in TypeScript, `// i18n-ignore-file` for files without UI text (token tables, test
// helpers), and words that are the same in every language in
// src/locale/untranslatable.json.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import ts from 'typescript';
import { areaOfFile } from './i18n-lib.mjs';

let compiler;

/** Scans `srcDir` (frontend/src). */
export async function scanSources(srcDir, { allow = { words: [], phrases: [] } } = {}) {
  compiler ??= await import('@angular/compiler');
  const files = listFiles(join(srcDir, 'app'))
    .concat(['main.ts', 'bootstrap.ts'].map((f) => join(srcDir, f)))
    .filter((f) => f.endsWith('.ts') && !f.endsWith('.spec.ts') && !f.endsWith('.d.ts'))
    .filter((f) => !/[\\/]core[\\/]api[\\/]schema/.test(f) && !/[\\/]testing\.ts$/.test(f));
  const result = { files: {}, noId: [], untagged: [] };
  const allowWords = new Set(allow.words ?? []);
  const allowPhrases = new Set((allow.phrases ?? []).map((p) => p.trim()));
  const isAllowed = (text) => {
    const t = text.replace(/\s+/g, ' ').trim();
    if (allowPhrases.has(t)) return true;
    const words = t.match(/[\p{L}][\p{L}'’-]*/gu) ?? [];
    return words.every((w) => allowWords.has(w));
  };

  for (const file of files) {
    let code;
    try {
      code = readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    const rel = relative(srcDir, file).replace(/\\/g, '/');
    const area = areaOfFile(rel);
    const ctx = { rel, area, result, isAllowed, srcDir };
    scanTs(code, file, ctx);
  }
  return result;
}

function listFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) out.push(...listFiles(p));
    else out.push(p);
  }
  return out;
}

function addId(ctx, id, line) {
  (ctx.result.files[id] ??= []).push({ file: `${ctx.rel}:${line}`, area: ctx.area });
}

// ---------------------------------------------------------------- TypeScript

const TEXT_PROP =
  /^(label|title|subtitle|description|text|message|placeholder|hint|detail|tooltip|content|summary|reason|heading|caption|group|okText|cancelText|nzTitle|nzContent|nzOkText|nzCancelText|emptyText|actionLabel|guideLabel|confirmText|helpText|why|what)$/i;
const NOTIFY_METHODS = new Set(['success', 'error', 'info', 'warning', 'danger', 'loading', 'blank']);

function scanTs(code, file, ctx) {
  const sf = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const lines = code.split(/\r?\n/);
  const lineOf = (node) => sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
  const ignored = (line) =>
    /i18n-ignore/.test(lines[line - 1] ?? '') || /i18n-ignore/.test(lines[line - 2] ?? '');

  const ignoreFile = /i18n-ignore-file/.test(code);
  const visit = (node) => {
    // $localize`:@@id:text`
    if (ts.isTaggedTemplateExpression(node) && node.tag.getText(sf) === '$localize') {
      const head = ts.isNoSubstitutionTemplateLiteral(node.template)
        ? node.template.text
        : node.template.head.text;
      const meta = /^:([^:]*):/.exec(head);
      const id = meta && /@@([^:]+)$/.exec(meta[1])?.[1];
      if (id) addId(ctx, id, lineOf(node));
      else ctx.result.noId.push({ file: `${ctx.rel}:${lineOf(node)}`, area: ctx.area, text: head.slice(0, 80) });
      return;
    }
    // @Component({ template / templateUrl })
    if (ts.isDecorator(node)) {
      const call = node.expression;
      if (ts.isCallExpression(call) && call.expression.getText(sf) === 'Component') {
        const arg = call.arguments[0];
        if (arg && ts.isObjectLiteralExpression(arg)) {
          for (const p of arg.properties) {
            if (!ts.isPropertyAssignment(p)) continue;
            const name = p.name.getText(sf);
            if (name === 'template' && (ts.isNoSubstitutionTemplateLiteral(p.initializer) || ts.isStringLiteral(p.initializer))) {
              const startLine = sf.getLineAndCharacterOfPosition(p.initializer.getStart(sf)).line;
              scanTemplate(p.initializer.text, startLine, ctx);
            } else if (name === 'templateUrl' && ts.isStringLiteral(p.initializer)) {
              const html = resolve(dirname(file), p.initializer.text);
              try {
                scanTemplate(readFileSync(html, 'utf8'), 0, { ...ctx, rel: relative(ctx.srcDir, html).split('\\').join('/') });
              } catch {
                // missing template: the build reports it
              }
            }
          }
        }
      }
      return; // never look for UI strings inside decorators (selectors, host bindings, styles)
    }
    if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node) || ts.isLiteralTypeNode(node)) return;
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateExpression(node)) {
      if (!ignoreFile) checkTsString(node, sf, ctx, lineOf, ignored);
      if (ts.isTemplateExpression(node)) node.templateSpans.forEach((s) => visit(s.expression));
      return;
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
}

function literalText(node) {
  if (ts.isTemplateExpression(node))
    return node.head.text + node.templateSpans.map((s) => '{x}' + s.literal.text).join('');
  return node.text;
}

function checkTsString(node, sf, ctx, lineOf, ignored) {
  const text = literalText(node);
  if (!/\p{L}{2,}/u.test(text)) return;
  const parent = node.parent;
  if (!parent) return;
  // keys, comparisons, element access, switch cases, enum-like members
  if (ts.isPropertyAssignment(parent) && parent.name === node) return;
  if (ts.isElementAccessExpression(parent)) return;
  if (ts.isCaseClause(parent)) return;
  if (
    ts.isBinaryExpression(parent) &&
    [
      ts.SyntaxKind.EqualsEqualsEqualsToken,
      ts.SyntaxKind.ExclamationEqualsEqualsToken,
      ts.SyntaxKind.EqualsEqualsToken,
      ts.SyntaxKind.ExclamationEqualsToken,
      ts.SyntaxKind.InKeyword,
    ].includes(parent.operatorToken.kind)
  )
    return;
  const callee = ts.isCallExpression(parent) ? parent.expression.getText(sf) : '';
  if (/^(console\..*|inject|require|import|.*\.(get|set|has|delete|getItem|setItem|removeItem|querySelector|querySelectorAll|closest|matches|startsWith|endsWith|includes|indexOf|split|replace|test|match|navigate|navigateByUrl|createElement|getAttribute|setAttribute|removeAttribute|addEventListener|removeEventListener|toggle|add|remove|contains|open|emit|register|params|append|post|put|patch|request|fetch))$/.test(callee))
    return;
  if (ts.isNewExpression(parent) && /Error|RegExp|URL|Date/.test(parent.expression.getText(sf))) return;
  if (ts.isThrowStatement(parent)) return;

  let reason = null;
  const sentence = /^[\p{Lu}][\p{Ll}'’]+/u.test(text) && /\p{L}{2,}[\s,.:;!?…]+\S/u.test(text);
  const looksMachine = /^[a-z0-9_.:/#@?=&%+*-]+$/i.test(text) && !/\s/.test(text) && !/^[A-Z][a-z]/.test(text);
  if (ts.isPropertyAssignment(parent) && TEXT_PROP.test(parent.name.getText(sf)) && !looksMachine)
    reason = 'property';
  else if (
    ts.isCallExpression(parent) &&
    ts.isPropertyAccessExpression(parent.expression) &&
    NOTIFY_METHODS.has(parent.expression.name.text) &&
    !/^console\b/.test(parent.expression.expression.getText(sf))
  )
    reason = 'notification';
  else if (ts.isCallExpression(parent) && /(^|\.)reportFormError$/.test(callee)) reason = 'notification';
  else if (sentence || /…$/.test(text)) reason = 'sentence';
  if (!reason) return;
  const line = lineOf(node);
  if (ignored(line) || ctx.isAllowed(text.replace(/\{x\}/g, ' '))) return;
  ctx.result.untagged.push({ file: `${ctx.rel}:${line}`, line: undefined, area: ctx.area, kind: `ts-${reason}`, text });
}

// ---------------------------------------------------------------- templates

const TEXT_ATTR =
  /^(placeholder|title|alt|aria-label|aria-description|aria-roledescription|aria-valuetext)$|(label|text|title|hint|description|placeholder|message|heading|caption|tooltip|content|subtitle)$/i;
const NO_TEXT_ELEMENTS = new Set(['code', 'kbd', 'pre', 'samp', 'script', 'style', 'bo-kbd']);

function scanTemplate(template, lineOffset, ctx) {
  const { parseTemplate } = compiler;
  const parsed = parseTemplate(template, ctx.rel, { preserveWhitespaces: false });
  const report = (node, kind, text) => {
    const line = (node.sourceSpan?.start?.line ?? 0) + lineOffset + 1;
    if (ctx.isAllowed(text)) return;
    ctx.result.untagged.push({ file: `${ctx.rel}:${line}`, area: ctx.area, kind, text: text.trim() });
  };
  const lineOfNode = (node) => (node.sourceSpan?.start?.line ?? 0) + lineOffset + 1;
  const message = (meta) => (meta && 'customId' in meta && 'nodes' in meta ? meta : null);

  const walk = (nodes, inI18n, inCode) => {
    for (const node of nodes ?? []) {
      const kind = node.constructor.name;
      const own = message(node.i18n);
      if (own) {
        if (own.customId) {
          if (!(kind === 'Icu' && inI18n)) addId(ctx, own.customId, lineOfNode(node));
        } else {
          ctx.result.noId.push({
            file: `${ctx.rel}:${lineOfNode(node)}`,
            area: ctx.area,
            text:
              (kind === 'Icu' ? '(an ICU must be the only content of its i18n element) ' : '') +
              (own.messageString ?? '').slice(0, 80),
          });
        }
      }
      const i18n = inI18n || (!!own && kind !== 'Icu') || (kind === 'Icu' && !!own?.customId);
      if (kind === 'Text') {
        if (!i18n && !inCode && /\p{L}{2,}/u.test(node.value)) report(node, 'text', node.value);
        continue;
      }
      if (kind === 'BoundText') {
        const ast = node.value?.ast ?? node.value;
        if (!i18n && !inCode && ast?.strings?.some((s) => /\p{L}{2,}/u.test(s)))
          report(node, 'text', ast.strings.join('{{…}}'));
        checkExpression(ast, (lit) => report(node, 'expression', lit));
        continue;
      }
      if (kind === 'Icu') continue;
      for (const a of node.attributes ?? []) {
        const meta = message(a.i18n);
        if (meta?.customId) addId(ctx, meta.customId, lineOfNode(a));
        else if (meta) ctx.result.noId.push({ file: `${ctx.rel}:${lineOfNode(a)}`, area: ctx.area, text: a.value });
        else if (TEXT_ATTR.test(a.name) && /\p{L}{2,}/u.test(a.value) && !a.name.startsWith('i18n'))
          report(a, `attribute ${a.name}`, a.value);
      }
      for (const b of [...(node.inputs ?? []), ...(node.templateAttrs ?? [])]) {
        const ast = b.value?.ast ?? b.value;
        if (ast && typeof ast === 'object') checkExpression(ast, (lit) => report(b, `binding ${b.name}`, lit));
      }
      const code = inCode || NO_TEXT_ELEMENTS.has(node.name);
      for (const key of ['children', 'branches', 'cases', 'groups']) {
        if (Array.isArray(node[key])) walk(node[key], i18n, code);
      }
      for (const key of ['empty', 'placeholder', 'loading', 'error']) {
        if (node[key]?.children) walk(node[key].children, i18n, code);
      }
      // @if (x) / @for (...) / @switch expressions
      for (const key of ['expression', 'expressionAlias']) {
        const ast = node[key]?.ast ?? null;
        if (ast) checkExpression(ast, (lit) => report(node, 'expression', lit));
      }
    }
  };
  walk(parsed.nodes, false, false);
}

/** UI-looking string literals inside a template expression (not comparison operands). */
function checkExpression(ast, onLiteral, parent = null, seen = new Set()) {
  if (!ast || typeof ast !== 'object' || seen.has(ast)) return;
  seen.add(ast);
  const kind = ast.constructor?.name;
  if (kind === 'LiteralPrimitive') {
    const v = ast.value;
    if (typeof v !== 'string' || !/\p{L}{2,}/u.test(v)) return;
    if (parent?.constructor?.name === 'Binary' && /^(==|===|!=|!==)$/.test(parent.operation)) return;
    if (parent?.constructor?.name === 'KeyedRead') return;
    const uiLike = /\s/.test(v.trim()) || /^[\p{Lu}][\p{Ll}]/u.test(v) || /…$/.test(v);
    if (uiLike) onLiteral(v);
    return;
  }
  for (const [k, v] of Object.entries(ast)) {
    if (k === 'span' || k === 'sourceSpan' || k === 'nameSpan' || k === 'keySpan' || k === 'location') continue;
    if (Array.isArray(v)) v.forEach((x) => checkExpression(x, onLiteral, ast, seen));
    else if (v && typeof v === 'object') checkExpression(v, onLiteral, ast, seen);
  }
}
