# syntax=docker/dockerfile:1.7
# Back Office egress proxy: Stripe Smokescreen + per-job roles (see main.go, docs/architecture/10-agent-execution.md).
#
# main.go is compiled inside the Smokescreen source tree at a pinned commit, using Smokescreen's own
# vendor/ directory: the build needs git access to github.com only (no Go module proxy) and uses exactly
# the dependency versions Smokescreen is tested with.
#
# Build: docker build -t back-office/egress-proxy:dev backend/runner-controller/egress-proxy
FROM golang:1.26-alpine AS build
RUN apk add --no-cache git
ARG SMOKESCREEN_REPO=https://github.com/stripe/smokescreen.git
ARG SMOKESCREEN_REF=c8bfe28c88f50107fe25578fcf549999c7003070
WORKDIR /src
RUN git init -q . && git remote add origin "${SMOKESCREEN_REPO}" \
    && git fetch -q --depth 1 origin "${SMOKESCREEN_REF}" && git checkout -q FETCH_HEAD
COPY main.go /src/cmd/bo-egress-proxy/main.go
RUN CGO_ENABLED=0 go vet -mod=vendor ./cmd/bo-egress-proxy \
    && CGO_ENABLED=0 go build -mod=vendor -trimpath -ldflags "-s -w" -o /out/egress-proxy ./cmd/bo-egress-proxy

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/egress-proxy /egress-proxy
USER 65532:65532
EXPOSE 4750
ENTRYPOINT ["/egress-proxy"]
