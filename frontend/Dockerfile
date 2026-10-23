# syntax=docker/dockerfile:1.7
#
# Back Office web UI: build the Angular app, serve it with unprivileged nginx.
#
#   docker build -t back-office/web:<git-sha> -f frontend/Dockerfile .
#   docker run -p 8080:8080 -e API_UPSTREAM=http://back-office-api:8080 back-office/web:<git-sha>
#
# Build context is the REPOSITORY ROOT so that docs/api/openapi.yaml is available for `npm run api:types`.

# ---- build --------------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /workspace

# Install dependencies first so the layer is cached while sources change.
# Behind a TLS-inspecting proxy pass its root certificate as the BuildKit secret `extra_ca` (compose: BO_EXTRA_CA_FILE);
# it is used only while npm downloads and never stored in the image.
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./frontend/
RUN --mount=type=cache,target=/root/.npm \
    --mount=type=secret,id=extra_ca,required=false \
    cd frontend \
 && if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi \
 && npm ci --no-audit --no-fund

# The OpenAPI contract is the single source of the API types.
COPY docs/api/openapi.yaml ./docs/api/openapi.yaml
# The user manual is served by the UI at /manual/ (in-app help, angular.json assets).
COPY docs/manual/ ./docs/manual/
COPY frontend/ ./frontend/
RUN cd frontend \
 && npm run api:types \
 && npm run build

# ---- runtime ------------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime

# Defaults; override at run time. nginx renders /etc/nginx/templates/*.template with envsubst.
ENV API_UPSTREAM=http://back-office-api:8080 \
    NGINX_PORT=8080

COPY frontend/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/frontend/dist/back-office-web/browser/ /usr/share/nginx/html/

EXPOSE 8080
USER 101

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
