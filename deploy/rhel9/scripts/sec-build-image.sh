#!/usr/bin/env bash
#
# Builds the backend container image from an already-built jar.
#
#     ./sec-build-image.sh /path/to/backend-0.1.0-all.jar
#     ./sec-build-image.sh /path/to/backend-0.1.0-all.jar --tag sec/backend:0.2.0
#
# Called by the Ansible compose role, and runnable by hand. Run it ON THE SERVER, against the
# jar you uploaded. The image is built from the artifact
# rather than from source because the server has no toolchain and no internet — and because
# building on one machine while deploying a jar from another is how a deployment stops being
# reproducible.
#
# Air-gapped: the base image must already be in the local docker store, or on the company registry
# mirror configured in /etc/containers/registries.conf. `docker pull` reaches nothing otherwise.
set -euo pipefail

[[ $# -ge 1 ]] || { sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }

JAR="$1"; shift
TAG="sec/backend:0.1.0"
BASE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)  TAG="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    *)      echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -f "$JAR" ]] || { echo "no such file: $JAR" >&2; exit 1; }

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ctx="$(mktemp -d)"
trap 'rm -rf "$ctx"' EXIT

# A minimal build context: the Dockerfile, the jar, and the CA if there is one. Building from
# deploy/rhel9/compose directly would send the whole directory, including .env if somebody put
# one there — a build context is copied into the daemon and ends up in the image's history.
cp "$here/compose/Dockerfile" "$ctx/"
cp "$JAR" "$ctx/"

# The company CA chain if the certs role has installed it, an empty file if not. Always writing
# something keeps the build independent of which builder is in use — a glob matching nothing is
# tolerated by BuildKit and not by the legacy builder. The Dockerfile ignores a zero-byte one.
ca=/etc/pki/ca-trust/source/anchors/company-ca-chain.crt
if [[ -f "$ca" ]]; then
  cp "$ca" "$ctx/sec-ca.crt"
else
  echo "  note: no $ca — the image will not trust the company CA."
  echo "        compose bind-mounts it at runtime too, so this is only a problem for \`docker run\`."
  : > "$ctx/sec-ca.crt"
fi

if ! unzip -l "$JAR" 'static/index.html' >/dev/null 2>&1; then
  echo "  warning: this jar has no static/index.html — API only, no user interface."
fi

args=(build -f "$ctx/Dockerfile" --build-arg "JAR=$(basename "$JAR")" -t "$TAG")
[[ -n "$BASE" ]] && args+=(--build-arg "BASE_IMAGE=$BASE")
args+=("$ctx")

docker "${args[@]}"

echo
echo "  Built $TAG"
docker image inspect "$TAG" --format '    {{.Id}}  {{.Size}} bytes' 2>/dev/null || true
echo
echo "  Point compose at it:  SEC_BACKEND_IMAGE=$TAG in /opt/sec/compose/.env"
echo "  Then:                 docker compose up -d backend"
