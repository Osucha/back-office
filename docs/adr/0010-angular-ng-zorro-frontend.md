# 0010 - Angular 21 with NG-ZORRO, standalone components and signals

Status: accepted - 2026-09-26

## Context
Required by the owner. The UI is data-dense (tables, timelines, a designer, charts).

## Decision
Angular 21 (compatible with the installed Node 22 LTS), NG-ZORRO 21, standalone components, signals for
state, `HttpClient` services typed from the OpenAPI contract, `EventSource` for live updates, ECharts
(via `ngx-echarts`) for the score-vs-cost scatter and cost charts. Served by nginx which proxies `/api`.

## Consequences
- The OpenAPI file in `docs/api` is the single contract; `npm run api:types` regenerates the TypeScript types.
- The pipeline designer is a form-driven ordered step editor with a JSON view, not a free-form canvas,
  to keep validation simple and the definition readable in git.
