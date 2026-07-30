# The Sidequest backend, for a self-hosted PaaS: Dokploy, Coolify, or plain Compose.
#
# Two stages. The first builds the distribution with Gradle; the second holds only a JRE and that
# distribution, so what runs carries no Gradle cache and no Kotlin compiler.

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /src

# The build definition before the sources, so editing a source file does not invalidate the dependency
# download. That is the layer that takes minutes; everything after it takes seconds.
COPY gradlew gradle.properties settings.gradle.kts stonecutter.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY protocol/build.gradle.kts ./protocol/
COPY platform-api/build.gradle.kts ./platform-api/
COPY platform-core/build.gradle.kts ./platform-core/
COPY platform-testkit/build.gradle.kts ./platform-testkit/
COPY backend/build.gradle.kts ./backend/

# Gradle insists that every `include`d project's directory exists, even for one it will never configure.
# The UI framework has nothing to do with the backend, so it is present as empty directories rather than
# copied — which keeps a change to a UI component from invalidating this image's build cache.
RUN mkdir -p ui-api ui-core ui-components ui-testkit

# `--configure-on-demand` is the load-bearing flag here.
#
# This repository is a Minecraft mod. Configuring its Stonecutter version nodes makes Fabric Loom
# provision Minecraft — several hundred megabytes and a remap — to build a server that has nothing to do
# with the game. On demand, Gradle configures only `:backend` and what it depends on, and Loom is never
# applied at all.
#
# Resolved as its own step so the dependencies land in a cached layer of their own.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --configure-on-demand --no-daemon \
    :backend:dependencies --configuration runtimeClasspath --quiet

COPY protocol/src ./protocol/src
COPY platform-api/src ./platform-api/src
COPY platform-core/src ./platform-core/src
COPY backend/src ./backend/src

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --configure-on-demand --no-daemon :backend:installDist

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# Not root. The server writes one file and needs no privileges to do it; a container that runs as root is
# a container whose escape is somebody else's whole host.
RUN useradd --system --create-home --uid 10001 sidequest \
    && mkdir -p /data && chown sidequest:sidequest /data

# The state file lives here and nowhere else, so this is the only path that needs to persist. Everything
# in the image is disposable; everything the group has ever done is in this directory.
VOLUME ["/data"]

COPY --from=build --chown=sidequest:sidequest /src/backend/build/install/backend /opt/sidequest

USER sidequest
WORKDIR /data

ENV SIDEQUEST_STATE=/data/state.json
ENV SIDEQUEST_HOST=0.0.0.0
ENV SIDEQUEST_PORT=8710
# The container's memory limit should bound the heap, not a number guessed at build time. Serial GC
# because this is a handful of users on one small server, where a concurrent collector's threads cost
# more than they save.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

EXPOSE 8710

# Asked of the application itself rather than with `curl`, which a JRE image does not have — installing
# one to ask a question the JVM can already answer would be another package to keep patched.
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["/opt/sidequest/bin/backend", "--health-check"]

ENTRYPOINT ["/opt/sidequest/bin/backend"]
