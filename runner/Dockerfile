# syntax=docker/dockerfile:1.7
# Back Office agent runner image (ADR 0006, sandboxed execution ADR 0024).
#
# One image for every SPEC_KIT / IMPLEMENT step and coding benchmark attempt:
#   git, curl, jq, Python 3 + uv, Node 22, Temurin JDK 21, OpenCode, Claude Code, Spec Kit.
# Runs as non-root `runner` (uid 10001) with /workspace as the only writable location (emptyDir in
# Kubernetes). entrypoint.py implements the runner protocol (docs/architecture/03-pipelines-and-agents.md).
#
# Build:  docker build -t back-office/runner:dev runner/
# Pins:   docker build --build-arg OPENCODE_VERSION=1.18.32 --build-arg CLAUDE_CODE_VERSION=2.1.283 ...

FROM ubuntu:24.04

ARG NODE_MAJOR=22
ARG OPENCODE_VERSION=1.18.32
ARG CLAUDE_CODE_VERSION=2.1.283
# Git ref of https://github.com/github/spec-kit (tag with leading v, branch or SHA)
ARG SPEC_KIT_REF=v1.0.12
ARG UV_VERSION=0.12.19
ARG TEMURIN_PACKAGE=temurin-21-jdk

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PIP_BREAK_SYSTEM_PACKAGES=1

# Base tooling ------------------------------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg git git-lfs openssh-client jq \
      glab \
      python3 python3-pip python3-venv \
      unzip zip xz-utils tar wget less procps tini \
      build-essential pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Temurin JDK 21 (Adoptium apt repo) + Node 22 (NodeSource) ---------------------------------------
RUN install -d -m 0755 /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" > /etc/apt/sources.list.d/adoptium.list \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update && apt-get install -y --no-install-recommends ${TEMURIN_PACKAGE} nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" /usr/lib/jvm/default-jdk \
    && java -version && node --version && npm --version

ENV JAVA_HOME=/usr/lib/jvm/default-jdk

# uv (Astral) at a fixed, root-owned path -----------------------------------------------------------
RUN curl -LsSf "https://astral.sh/uv/${UV_VERSION}/install.sh" | env UV_UNMANAGED_INSTALL=/usr/local/bin sh \
    && uv --version

# Coding agents ------------------------------------------------------------------------------------
RUN npm install -g --no-fund --no-audit "opencode-ai@${OPENCODE_VERSION}" "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && npm cache clean --force \
    && opencode --version && claude --version

# Spec Kit CLI (specify) ---------------------------------------------------------------------------
ENV UV_TOOL_DIR=/opt/uv/tools \
    UV_TOOL_BIN_DIR=/usr/local/bin
RUN uv tool install "specify-cli" --from "git+https://github.com/github/spec-kit.git@${SPEC_KIT_REF}" \
    && rm -rf /root/.cache/uv \
    && specify --help >/dev/null

# Non-root user and workspace ------------------------------------------------------------------------
RUN useradd --uid 10001 --user-group --create-home --shell /bin/bash runner \
    && install -d -o runner -g runner -m 0755 /workspace

COPY --chmod=0755 entrypoint.py /usr/local/bin/entrypoint.py

USER runner
WORKDIR /workspace
ENV HOME=/home/runner \
    RUNNER_WORKSPACE=/workspace \
    RUNNER_JOB_DIR=/var/run/backoffice/job \
    RUNNER_GIT_TOKEN_FILE=/var/run/backoffice/git/token \
    RUNNER_SANDBOX=1 \
    GIT_TERMINAL_PROMPT=0 \
    NPM_CONFIG_UPDATE_NOTIFIER=false \
    CI=true

# Smoke check that the tools work as the runtime user.
RUN git --version && python3 --version && uv --version && node --version && java -version 2>&1 | head -1 \
    && opencode --version && claude --version && specify --help >/dev/null

ENTRYPOINT ["/usr/bin/tini", "--", "python3", "/usr/local/bin/entrypoint.py"]
