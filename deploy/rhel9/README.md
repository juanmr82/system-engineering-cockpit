# deploy/rhel9 — the production deployment kit

**Read `docs/DEPLOY_RHEL9.md`. This is the index.**

```
ansible/                 THE deployment. Everything below, done for you.
  site.yml                 one playbook, both modes, with an end-to-end verify at the end
  group_vars/
    all.yml.example        the ONE file a deployer edits — commented section by section
    vault.yml.example      every secret, encrypted with ansible-vault
  inventory/hosts.yml.example
  files/certs/             where the bundle your PKI issued goes (git-ignored)
  roles/                   sec_common, sec_certs, sec_neo4j, sec_keycloak, sec_backend,
                           sec_compose, sec_nginx, sec_verify

compose/                 mode: containers
  docker-compose.yml       neo4j, keycloak, keycloak-db, backend. nginx stays on the host
  Dockerfile               the backend image, built FROM the uploaded jar (no toolchain here)
  .env.example             topology and image tags — NOT the application's secrets

systemd/                 mode: native
  sec-backend.service      the jar, hardened, EnvironmentFile for secrets
  sec-keycloak.service     Keycloak 26 in production mode, needs postgresql

nginx/                   both modes — the readable reference copy the playbook templates
  sec.conf                 TLS, /api, /auth, the SSE stream, the upload limit
  sec-ssl-params.conf

config/                  the by-hand equivalents of what the playbook templates
  sec.yaml.example         the -config= OVERLAY on the packaged application.yaml
  sec.env.example          every secret, and why not .bashrc

keycloak/
  sec-realm-prod.json      the dev realm minus test users and hardcoded secrets
  themes/sec/              the login theme: inherits keycloak.v2, overrides a stylesheet

scripts/                 what Ansible does not replace
  sec-check-certs.sh       validate a PKI-issued bundle before installing it
  sec-preflight.sh         one line per prerequisite, on the server, changes nothing
  sec-build-image.sh       the backend image from a jar (used by the compose role too)
```

## The short version

```bash
# on the build machine (or Jenkins)
scripts/linux/sec-package.sh

# put the bundle your PKI issued in deploy/rhel9/ansible/files/certs/, then:
cd deploy/rhel9/ansible
cp inventory/hosts.yml.example inventory/hosts.yml     # your server
cp group_vars/all.yml.example  group_vars/all.yml      # hostname, mode, jar path
cp group_vars/vault.yml.example group_vars/vault.yml && ansible-vault encrypt group_vars/vault.yml

ansible-playbook -i inventory/hosts.yml site.yml --ask-vault-pass
```

Useful afterwards: `--tags certs` (renewal), `--tags backend` (upgrade), `--tags verify`
(re-check), `--check --diff` (change nothing, show what would change).

## Four things that are not obvious

- **After a clean install the application is empty for everyone, including administrators.** That
  is ADR 0016 working. §11.4 of the guide is the fix and it takes five minutes.
- **You do not create the certificates.** The company PKI issues them; the playbook's job is to
  refuse a wrong bundle — a key from a different CSR, a SAN that misses the hostname, a chain
  missing its intermediate. All three look fine on inspection and fail after deployment.
- **`server.behindProxy: true` and a loopback bind are one decision.** The flag makes the backend
  believe `X-Forwarded-For`; the bind is what makes that safe. See ADR 0021.
- **The Keycloak admin console is unreachable until you say who may reach it.** `sec_admin_allow_cidrs`
  is empty by default, so nginx allows loopback only. The application does not need the console to
  work, so this fails closed without breaking anything.
- **Nothing here holds a secret**, and nothing here should be edited to. `*.example` files are
  templates; real values live in the vault and in `/etc/sec/`, and both are git-ignored.
