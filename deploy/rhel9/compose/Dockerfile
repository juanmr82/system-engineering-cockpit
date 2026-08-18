# The backend image: a base JRE and the jar. Nothing else.
#
#   docker build -f Dockerfile --build-arg JAR=backend-0.1.0-all.jar -t sec/backend:0.1.0 .
#
# There is no Maven stage and no npm stage, deliberately. The server has no toolchain, no internet
# and no source tree (CLAUDE.md, "our development environment is not on this server"), so a
# multi-stage build could not run there — and building on a developer machine while deploying a
# *different* artifact is how a deployment stops being reproducible. The jar this copies is the
# same file the native path installs to /opt/sec, produced by scripts/linux/sec-package.sh and
# verified by its sha256.
#
# Pinned by digest in a hardened environment. The tag is here for readability; substitute the
# company mirror's own base image if there is one.
ARG BASE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
FROM ${BASE_IMAGE}

ARG JAR
# Fails the build rather than producing an image with no application in it, which is otherwise a
# perfectly valid COPY of nothing.
RUN test -n "${JAR}" || (echo "build with --build-arg JAR=<file>" && false)

USER root

# The company CA chain, so the JVM trusts Keycloak through nginx. The UBI OpenJDK images link the JDK's
# cacerts to the system store, so update-ca-trust is enough — the same mechanism as the host
# (DEPLOY_RHEL9.md §5.3).
#
# sec-build-image.sh always puts a sec-ca.crt in the build context (copied from the host's
# /etc/pki/ca-trust/source/anchors/company-ca-chain.crt), empty when there is none yet, so this
# works under both BuildKit and the legacy builder. An empty one is skipped rather than installed:
# an empty file in the anchors directory makes update-ca-trust complain, and compose bind-mounts
# the real chain at runtime anyway, which covers a chain installed after the image was built.
COPY sec-ca.crt /tmp/sec-ca.crt
RUN if [ -s /tmp/sec-ca.crt ]; then \
        cp /tmp/sec-ca.crt /etc/pki/ca-trust/source/anchors/company-ca-chain.crt && \
        update-ca-trust extract; \
    fi; \
    rm -f /tmp/sec-ca.crt

WORKDIR /opt/sec
COPY ${JAR} /opt/sec/sec.jar

# 185 is the non-root user the UBI OpenJDK images ship with. Named by number because the account
# name has changed between image generations and the number has not.
USER 185

EXPOSE 8080

# Liveness only — it touches no database, so a slow Neo4j cannot trigger a restart loop. Compose
# declares its own healthcheck against the same endpoint; this one is for `docker run` without it.
HEALTHCHECK --interval=20s --timeout=5s --start-period=45s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1"]

# Exec form, so the JVM is pid 1 and receives SIGTERM directly — the ApplicationStopping hooks
# that close the Neo4j driver and the HTTP clients depend on it.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Dfile.encoding=UTF-8", \
    "-Dlogback.configurationFile=logback-production.xml", \
    "-jar", "/opt/sec/sec.jar", \
    "-config=/etc/sec/sec.yaml"]
