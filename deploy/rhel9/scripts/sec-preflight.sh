#!/usr/bin/env bash
#
# One line per prerequisite. Changes nothing.
#
#     ./sec-preflight.sh                    # native path
#     ./sec-preflight.sh --compose          # container path
#     ./sec-preflight.sh --host sec.example.corp
#
# The Linux counterpart of scripts/win/sec-doctor.ps1, and the same discipline: read-only, one
# line per fact, and it says what to do rather than only what is wrong.
#
# If you deployed with Ansible, the sec_verify role already checked all of this and more, from
# OFF the host, and the playbook failed rather than finishing if anything was wrong. This script
# is for a by-hand deployment (DEPLOY_RHEL9.md §7), and for looking at a server from the inside
# when something has gone wrong later.
set -uo pipefail   # deliberately not -e: every check runs, a failure is reported not fatal

MODE=native
FQDN=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --compose) MODE=compose; shift ;;
    --host)    FQDN="$2"; shift 2 ;;
    -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)         echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

pass=0; warn=0; fail=0
ok()   { printf '  \033[32mok  \033[0m %s\n' "$1"; pass=$((pass+1)); }
no()   { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fail=$((fail+1)); }
hmm()  { printf '  \033[33mwarn\033[0m %s\n' "$1"; warn=$((warn+1)); }
note() { printf '       %s\n' "$1"; }

echo
echo "  SEC preflight — ${MODE} path"
echo

# --- the platform ---------------------------------------------------------------------------
if [[ -r /etc/redhat-release ]]; then
  ok "$(cat /etc/redhat-release)"
else
  hmm "not a Red Hat family host; the guide assumes RHEL 9"
fi

# SELinux is the single most common cause of "nginx returns 502 and the config is obviously
# right": enforcing mode blocks outbound connections from nginx unless httpd_can_network_connect
# is on. Reported here so it is known before, not diagnosed after.
if command -v getenforce >/dev/null 2>&1; then
  se="$(getenforce)"
  if [[ "$se" == "Enforcing" ]]; then
    if command -v getsebool >/dev/null 2>&1 && getsebool httpd_can_network_connect 2>/dev/null | grep -q ' on$'; then
      ok "SELinux Enforcing, httpd_can_network_connect is on"
    else
      no "SELinux is Enforcing and httpd_can_network_connect is off — nginx will 502"
      note "setsebool -P httpd_can_network_connect on"
    fi
  else
    ok "SELinux $se"
  fi
fi

# --- the toolchain --------------------------------------------------------------------------
if [[ "$MODE" == native ]]; then
  if command -v java >/dev/null 2>&1; then
    v="$(java -version 2>&1 | head -1)"
    major="$(sed -E 's/.*"([0-9]+).*/\1/' <<<"$v")"
    if [[ "${major:-0}" -ge 21 ]]; then ok "java: $v"; else no "java is $major, need 21+"; fi
  else
    no "no java on PATH"
    note "dnf install java-21-openjdk-headless"
  fi

  systemctl list-unit-files 2>/dev/null | grep -q '^neo4j\.service' \
    && ok "neo4j.service is installed" \
    || hmm "neo4j.service not installed yet (DEPLOY_RHEL9.md §7.2)"

  systemctl list-unit-files 2>/dev/null | grep -q '^postgresql\.service' \
    && ok "postgresql.service is installed (Keycloak's database)" \
    || hmm "postgresql.service not installed yet (DEPLOY_RHEL9.md §7.2)"
else
  if command -v docker >/dev/null 2>&1; then
    ok "docker: $(docker --version)"
    docker compose version >/dev/null 2>&1 \
      && ok "docker compose: $(docker compose version --short 2>/dev/null)" \
      || no "the compose plugin is missing — dnf install docker-compose-plugin"
    docker info >/dev/null 2>&1 \
      && ok "the docker daemon is reachable" \
      || no "the docker daemon is not reachable (systemctl start docker; check group membership)"
  else
    no "no docker on PATH"
  fi
fi

command -v nginx >/dev/null 2>&1 && ok "nginx: $(nginx -v 2>&1)" || hmm "nginx not installed yet"

# --- ports ---------------------------------------------------------------------------------
# ss -H over both families: a listener on ::1 alone does not answer 127.0.0.1 and vice versa.
for spec in "443:nginx" "8080:backend" "8180:keycloak" "7687:neo4j bolt"; do
  port="${spec%%:*}"; who="${spec#*:}"
  if ss -Hltn "sport = :$port" 2>/dev/null | grep -q .; then
    ok "port $port is listening ($who)"
  else
    hmm "port $port is free ($who not started yet)"
  fi
done

# --- firewall --------------------------------------------------------------------------------
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  if firewall-cmd --list-services 2>/dev/null | grep -qw https; then
    ok "firewalld allows https"
  else
    no "firewalld is running and https is not allowed"
    note "firewall-cmd --permanent --add-service=https && firewall-cmd --reload"
  fi
  # The inverse check, and the more important one: these must NOT be open. The backend and Neo4j
  # are reached through nginx and loopback only.
  for p in 8080 7687 8180; do
    firewall-cmd --list-ports 2>/dev/null | grep -qw "${p}/tcp" \
      && no "port $p is open to the network; it must be loopback-only" \
      || true
  done
else
  hmm "firewalld is not running — check whatever else filters this host"
fi

# --- configuration and secrets ------------------------------------------------------------------
if [[ -f /etc/sec/sec.env ]]; then
  perms="$(stat -c '%a %U:%G' /etc/sec/sec.env)"
  if [[ "$perms" =~ ^0?6[04]0\  ]]; then ok "/etc/sec/sec.env $perms"
  else no "/etc/sec/sec.env is $perms — should be 0640 root:sec"; fi

  grep -q 'CHANGE-ME' /etc/sec/sec.env \
    && no "/etc/sec/sec.env still contains CHANGE-ME placeholders" \
    || ok "no placeholder secrets left in /etc/sec/sec.env"
else
  hmm "/etc/sec/sec.env does not exist yet (config/sec.env.example)"
fi

[[ -f /etc/sec/sec.yaml ]] && ok "/etc/sec/sec.yaml exists" \
                           || hmm "/etc/sec/sec.yaml does not exist yet (config/sec.yaml.example)"

# --- trust -----------------------------------------------------------------------------------
if [[ -n "$FQDN" ]]; then
  if [[ -f "/etc/pki/tls/certs/${FQDN}.crt" ]]; then
    ok "server certificate for ${FQDN} exists"
    openssl verify -CAfile /etc/pki/tls/certs/ca-bundle.crt "/etc/pki/tls/certs/${FQDN}.crt" >/dev/null 2>&1 \
      && ok "the system trust store accepts it" \
      || no "the system trust store rejects it — the CA chain is not installed (--tags certs, or update-ca-trust extract)"

    exp="$(openssl x509 -enddate -noout -in "/etc/pki/tls/certs/${FQDN}.crt" | cut -d= -f2)"
    note "expires: $exp"
  else
    hmm "no certificate at /etc/pki/tls/certs/${FQDN}.crt yet (DEPLOY_RHEL9.md §5)"
  fi

  # The end-to-end question, and the one that catches a JVM with its own trust store.
  if command -v curl >/dev/null 2>&1; then
    code="$(curl -s -o /dev/null -w '%{http_code}' "https://${FQDN}/api/v1/health" --max-time 5 2>/dev/null)"
    case "$code" in
      200) ok "https://${FQDN}/api/v1/health answers 200" ;;
      000) hmm "https://${FQDN}/api/v1/health did not answer (not started, or DNS/TLS)" ;;
      *)   no  "https://${FQDN}/api/v1/health answers $code" ;;
    esac
  fi
else
  note "pass --host <fqdn> to check the certificate and the health endpoint too"
fi

echo
printf '  %d ok, %d warnings, %d failures\n\n' "$pass" "$warn" "$fail"
[[ "$fail" -eq 0 ]]
