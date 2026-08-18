#!/usr/bin/env bash
#
# Checks a certificate bundle issued by the company PKI *before* you install it, and again after.
#
#     ./sec-check-certs.sh sec.example.corp \
#         --cert  sec.example.corp.crt \
#         --key   sec.example.corp.key \
#         --chain company-ca-chain.crt
#
#     ./sec-check-certs.sh sec.example.corp --installed      # re-check what nginx is serving
#
# Read-only. It installs nothing — the Ansible role does that (`deploy/rhel9/ansible`), and this
# is what tells you the files it is about to install are the right ones.
#
# WHY THIS EXISTS. You did not create these files, so the usual mistakes are not typos — they are
# a certificate issued for the wrong name, a key from a different request, a chain missing its
# intermediate, or a bundle in the wrong order. nginx accepts most of those at `nginx -t` and then
# fails at handshake time for *some* clients and not others, which is a genuinely horrible thing
# to debug. Every check below is one of those failures, caught while it is still cheap.
set -uo pipefail

pass=0; fail=0
ok()  { printf '  \033[32mok  \033[0m %s\n' "$1"; pass=$((pass+1)); }
no()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fail=$((fail+1)); }
hmm() { printf '  \033[33mwarn\033[0m %s\n' "$1"; }
note(){ printf '       %s\n' "$1"; }

usage() { sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

[[ $# -ge 1 ]] || { usage; exit 2; }
FQDN="$1"; shift

CERT=""; KEY=""; CHAIN=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cert)      CERT="$2"; shift 2 ;;
    --key)       KEY="$2"; shift 2 ;;
    --chain)     CHAIN="$2"; shift 2 ;;
    --installed) CERT="/etc/pki/tls/certs/${FQDN}.crt"
                 KEY="/etc/pki/tls/private/${FQDN}.key"
                 CHAIN="/etc/pki/ca-trust/source/anchors/company-ca-chain.crt"
                 shift ;;
    -h|--help)   usage; exit 0 ;;
    *)           echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$CERT" && -n "$KEY" ]] || { echo "need --cert and --key (or --installed)" >&2; exit 2; }

echo
echo "  Certificate bundle for ${FQDN}"
echo

# --- the files are what they claim to be ------------------------------------------------------
# A PEM/DER mix-up is common when a certificate arrives from a Windows CA: openssl reads DER only
# when told to, so a .crt that is really DER fails here with a confusing parse error rather than
# later inside nginx.
if ! openssl x509 -in "$CERT" -noout 2>/dev/null; then
  no "the certificate is not readable as a certificate at all"
  echo; printf '  %d ok, %d failures\n\n' "$pass" "$fail"; exit 1
fi

# Readable is not the same as PEM. OpenSSL 3 sniffs the input format, so `x509 -in` happily reads
# a DER file — but nginx's ssl_certificate does NOT, and fails at start with a parse error. So the
# check is on the encoding itself, not on whether openssl coped. A Windows CA hands out DER (.cer)
# routinely, which is exactly how this arrives.
if head -c 11 "$CERT" | grep -q -- "-----BEGIN"; then
  ok "the certificate is PEM"
else
  no "the certificate is DER, not PEM — nginx will not load it"
  note "openssl x509 -in '$CERT' -out '${CERT%.*}.pem'   # openssl 3 detects the input format"
fi

if openssl pkey -in "$KEY" -noout 2>/dev/null; then
  ok "the private key is readable"
else
  no "the private key is not readable (encrypted? nginx cannot use a passphrase-protected key unattended)"
  note "openssl rsa -in '$KEY' -out '${KEY}.nopass'   # you will be asked for the passphrase"
fi

# --- the key belongs to the certificate ----------------------------------------------------------
# THE check. A cert and a key from two different CSRs look perfectly valid on their own; nginx
# refuses to start with "key values mismatch", which at least is honest, but only at start time.
cert_pub="$(openssl x509 -in "$CERT" -noout -pubkey 2>/dev/null | openssl md5)"
key_pub="$(openssl pkey -in "$KEY" -pubout 2>/dev/null | openssl md5)"
if [[ -n "$cert_pub" && "$cert_pub" == "$key_pub" ]]; then
  ok "the private key matches this certificate"
else
  no "the private key does NOT match this certificate — they are from different requests"
fi

# --- it is for this name --------------------------------------------------------------------
# Browsers have ignored CN for host matching since 2017. A certificate whose CN is right and whose
# SAN does not list the name fails with ERR_CERT_COMMON_NAME_INVALID, and the CN in the subject
# line makes it look correct at a glance.
san="$(openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | tr -d ' ')"
if [[ -z "$san" ]]; then
  no "the certificate has NO subjectAltName — every current browser will reject it"
  note "go back to the PKI team: the CSR needs a SAN, the CN is not enough"
else
  ok "subjectAltName: $san"
  if grep -qE "(^|,)DNS:${FQDN//./\\.}(,|$)" <<<"$san"; then
    ok "the SAN covers ${FQDN}"
  elif grep -qE "(^|,)DNS:\*\." <<<"$san"; then
    hmm "the SAN has a wildcard but not ${FQDN} literally — check it covers exactly one label"
  else
    no "the SAN does NOT cover ${FQDN}; this certificate is for something else"
  fi
fi

# --- it is usable as a server certificate --------------------------------------------------------
eku="$(openssl x509 -in "$CERT" -noout -ext extendedKeyUsage 2>/dev/null | tail -n +2 | tr -d ' ')"
if [[ -z "$eku" ]] || grep -qi "TLSWebServerAuthentication\|serverAuth" <<<"$eku"; then
  ok "extended key usage permits server authentication"
else
  no "extendedKeyUsage does not include serverAuth: $eku"
  note "this is a client or code-signing certificate, not a web server one"
fi

# --- dates ------------------------------------------------------------------------------------
not_after="$(openssl x509 -in "$CERT" -noout -enddate | cut -d= -f2)"
if openssl x509 -in "$CERT" -noout -checkend 0 >/dev/null 2>&1; then
  if openssl x509 -in "$CERT" -noout -checkend 2592000 >/dev/null 2>&1; then
    ok "valid until ${not_after}"
  else
    hmm "expires within 30 days: ${not_after} — request the renewal now"
  fi
else
  no "the certificate has EXPIRED: ${not_after}"
fi

if ! openssl x509 -in "$CERT" -noout -checkend -1 >/dev/null 2>&1; then :; fi
start="$(openssl x509 -in "$CERT" -noout -startdate | cut -d= -f2)"
if [[ "$(date -d "$start" +%s 2>/dev/null || echo 0)" -gt "$(date +%s)" ]]; then
  no "not valid yet — starts ${start}. Check this host's clock as well as the certificate"
fi

# --- the chain ----------------------------------------------------------------------------------
# The most common real-world defect. A server must send its intermediates; a browser that already
# has them cached succeeds while a fresh one fails, which is why "it works on my machine" is such
# a common report for a chain problem.
echo
echo "  Chain"
subject="$(openssl x509 -in "$CERT" -noout -subject | sed 's/^subject= *//')"
issuer="$(openssl x509 -in "$CERT" -noout -issuer | sed 's/^issuer= *//')"
note "subject: $subject"
note "issuer:  $issuer"

if [[ "$subject" == "$issuer" ]]; then
  hmm "self-signed (subject == issuer). Expected from the company PKI? Probably not"
fi

if [[ -n "$CHAIN" && -f "$CHAIN" ]]; then
  count="$(grep -c 'BEGIN CERTIFICATE' "$CHAIN" 2>/dev/null || echo 0)"
  ok "the supplied chain file holds ${count} certificate(s)"
  if openssl verify -CAfile "$CHAIN" "$CERT" >/dev/null 2>&1; then
    ok "the certificate verifies against the supplied chain"
  else
    no "the certificate does NOT verify against the supplied chain"
    note "$(openssl verify -CAfile "$CHAIN" "$CERT" 2>&1 | tail -1)"
    note "usually a missing intermediate — ask the PKI team for the full chain, root included"
  fi
else
  hmm "no --chain given; only checking against this host's trust store"
fi

# The question that actually matters for the JVM: does THIS HOST trust it? The backend fetches
# Keycloak's discovery document through nginx, and a PKIX failure there means nobody can log in.
if openssl verify "$CERT" >/dev/null 2>&1; then
  ok "this host's trust store already accepts the certificate"
  note "the company CA is in the system store — the JVM inherits it via /etc/pki/java/cacerts"
else
  hmm "this host does not trust it yet — install the CA chain:"
  note "cp <chain>.crt /etc/pki/ca-trust/source/anchors/ && update-ca-trust extract"
  note "(the Ansible role does this; see DEPLOY_RHEL9.md §5)"
fi

# --- permissions, when checking what is installed -------------------------------------------------
if [[ "$KEY" == /etc/pki/tls/private/* && -f "$KEY" ]]; then
  perms="$(stat -c '%a %U:%G' "$KEY")"
  [[ "$perms" =~ ^0?600\  ]] && ok "the installed key is $perms" \
                             || no "the installed key is $perms — should be 0600 root:root"
fi

echo
printf '  %d ok, %d failures\n\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
