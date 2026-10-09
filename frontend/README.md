# Back Office web

Angular 21 + NG-ZORRO single-page UI for Back Office. Talks to the REST API under `/api/v1`
(contract: `../docs/api/openapi.yaml`) and to the SSE stream `/api/v1/events`.

```bash
npm ci                 # Node 22.12+ (see .npmrc: legacy-peer-deps)
npm run api:types      # regenerate src/app/core/api/schema.d.ts from ../docs/api/openapi.yaml
npm start              # http://localhost:4200, proxies /api to http://localhost:8080
npm run build          # production bundle in dist/back-office-web/browser
npm run test:ci        # Vitest, single run
```

Full guide: `../docs/guides/frontend-development.md`.

Docker image (build from the repository root so the OpenAPI file is in the context):

```bash
docker build -t back-office/web:dev -f frontend/Dockerfile .
docker run --rm -p 8080:8080 -e API_UPSTREAM=http://host.docker.internal:8080 back-office/web:dev
```
