// Frontend architecture guardrails (npm run lint:deps). Layers, top to bottom:
//   app (app.*.ts, bootstrap) -> layout -> features -> core
// A lower layer never imports a higher one; a feature reaches another feature only through that
// feature's `public-api.ts` (shared building blocks belong in core).
/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: 'core-is-the-bottom-layer',
      comment: 'core/ must not depend on features/, layout/ or the app bootstrap.',
      severity: 'error',
      from: { path: '^src/app/core/' },
      to: { path: '^src/app/(features|layout)/|^src/app/app\\.[^/]+$' },
    },
    {
      name: 'features-do-not-import-layout-or-app',
      comment: 'Features are routed by the app; they never import the shell or the bootstrap.',
      severity: 'error',
      from: { path: '^src/app/features/' },
      to: { path: '^src/app/layout/|^src/app/app\\.[^/]+$' },
    },
    {
      name: 'feature-isolation',
      comment:
        'A feature does not import the internals of another feature; use its public-api.ts or move the code to core/.',
      severity: 'error',
      from: { path: '^src/app/features/([^/]+)/' },
      to: {
        path: '^src/app/features/([^/]+)/',
        pathNot: '^src/app/features/($1/|[^/]+/public-api\\.ts$)',
      },
    },
    {
      name: 'no-library-internals',
      comment: 'Do not deep-import private modules of packages (node_modules/**/internal).',
      severity: 'error',
      from: {},
      to: { path: 'node_modules/.+/internal(/|$)' },
    },
    {
      name: 'no-circular',
      comment: 'No import cycles between modules.',
      severity: 'error',
      from: { path: '^src/' },
      to: { circular: true },
    },
  ],
  options: {
    doNotFollow: { path: 'node_modules' },
    exclude: { path: '\\.spec\\.ts$|schema\\.d\\.ts$' },
    tsPreCompilationDeps: true,
    tsConfig: { fileName: 'tsconfig.app.json' },
    enhancedResolveOptions: { extensions: ['.ts', '.js', '.mjs', '.json'] },
  },
};
