#!/usr/bin/env bash
#
# Builds the deployable artifact: one jar carrying the API and the user interface.
#
#     scripts/linux/sec-package.sh              # Angular build, then the jar around it
#     scripts/linux/sec-package.sh --no-ui      # API only, no Angular build
#     scripts/linux/sec-package.sh --skip-tests # faster, and worth less
#
# The Linux counterpart of scripts/win/sec-package.ps1, doing the same two steps in the same
# order, because the second copies what the first produced:
#
#     1. npm run build           -> frontend/dist/frontend/browser
#     2. mvn -Pui clean package  -> backend/target/backend-<version>-all.jar
#
# Maven copies the Angular output into the jar under static/; it does not run npm. Running npm
# from Maven would mean the frontend-maven-plugin, which downloads its own Node — see the -Pui
# profile in backend/pom.xml.
#
# What comes out needs a JDK 21 and a reachable Neo4j. No Maven, no Node, no sources, no IDE:
#
#     java -jar backend-0.1.0-all.jar
#
# This is the artifact docs/DEPLOY_RHEL9.md tells you to copy to the server. Build it on a machine
# that has the toolchain; the server needs neither (CLAUDE.md, "our development environment is not
# on that server").
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dist_dir="$repo/frontend/dist/frontend/browser"

no_ui=0
skip_tests=0
for arg in "$@"; do
  case "$arg" in
    --no-ui)      no_ui=1 ;;
    --skip-tests) skip_tests=1 ;;
    -h|--help)    sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# --- the toolchain --------------------------------------------------------------------------
# Maven compiles with the JDK it runs on, so JAVA_HOME *is* the build JDK. A JDK older than 21
# fails deep inside the Kotlin plugin with a message about a class file version, which is a poor
# way to learn this.
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! command -v "${java_bin}" >/dev/null 2>&1; then
  echo "No java on PATH and JAVA_HOME is unset or wrong. A JDK 21+ is required." >&2
  exit 1
fi
java_major="$("${java_bin}" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [[ "$java_major" -lt 21 ]]; then
  echo "Java $java_major found; this project needs 21 or newer (root pom.xml, maven.compiler.release)." >&2
  exit 1
fi

# A real Maven install is preferred over the wrapper (ADR 0007); fall back to ./mvnw.
mvn="${SEC_MVN:-}"
if [[ -z "$mvn" ]]; then
  if command -v mvn >/dev/null 2>&1; then mvn="mvn"; else mvn="$repo/mvnw"; fi
fi
[[ -x "$mvn" || "$mvn" == "mvn" ]] || { echo "No Maven and no executable ./mvnw." >&2; exit 1; }

# --- 1. the user interface -------------------------------------------------------------------
if [[ "$no_ui" -eq 0 ]]; then
  echo
  echo "  [1/2] Building the user interface"
  echo
  # From frontend/, never `npm --prefix frontend` from the root: --prefix also changes where
  # npm install writes (CLAUDE.md §11).
  ( cd "$repo/frontend" && npm run build )

  # Checked rather than assumed: Maven's copy step warns and carries on when the directory is
  # missing, which would produce a jar that looks right and serves no pages.
  if [[ ! -f "$dist_dir/index.html" ]]; then
    echo "The Angular build reported success but produced no index.html in $dist_dir." >&2
    echo "Check angular.json's outputPath — this script and backend/pom.xml both expect" >&2
    echo "frontend/dist/frontend/browser." >&2
    exit 1
  fi
fi

# --- 2. the jar --------------------------------------------------------------------------------
echo
echo "  [2/2] Building the jar"
echo

mvn_args=(-B)
[[ -n "${SEC_MVN_SETTINGS:-}" ]] && mvn_args+=(-s "$SEC_MVN_SETTINGS")
[[ "$no_ui" -eq 0 ]]            && mvn_args+=(-Pui)
[[ "$skip_tests" -eq 1 ]]       && mvn_args+=(-DskipTests)
mvn_args+=(clean package)

( cd "$repo" && "$mvn" "${mvn_args[@]}" )

# --- what came out -----------------------------------------------------------------------------
jar="$(find "$repo/backend/target" -maxdepth 1 -name '*-all.jar' -printf '%T@ %p\n' 2>/dev/null |
       sort -rn | head -1 | cut -d' ' -f2-)"
if [[ -z "$jar" ]]; then
  echo "Maven reported success but produced no *-all.jar. Check the shade plugin in backend/pom.xml." >&2
  exit 1
fi

# Prove the UI really is inside, rather than trusting that the copy happened. A jar that is
# missing its pages is indistinguishable from a working one until someone opens a browser.
ui_note=", API only"
if [[ "$no_ui" -eq 0 ]]; then
  if ! unzip -l "$jar" 'static/index.html' >/dev/null 2>&1; then
    echo "The jar was built but does not contain static/index.html. The -Pui copy step did not" >&2
    echo "run — check that $dist_dir existed when Maven ran." >&2
    exit 1
  fi
  ui_note=", user interface included"
fi

size_mb="$(( $(stat -c%s "$jar") / 1024 / 1024 ))"
sha="$(sha256sum "$jar" | cut -d' ' -f1)"

cat <<EOF

  Built
    $jar
    ${size_mb} MB${ui_note}
    sha256 ${sha}

  Run it here:
    java -jar $(basename "$jar")

  Deploy it: copy the jar to the server, verify the sha256 above, and follow
  docs/DEPLOY_RHEL9.md. The server needs a JDK 21 and a reachable Neo4j — no Maven, no Node.
EOF
