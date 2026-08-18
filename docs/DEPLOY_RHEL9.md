# Deploying the cockpit on RHEL 9

The environment contract for the **server**, the way `RUNNING.md` is the contract for the Windows
workstation that talks to DOORS. Those two machines have nothing in common and neither document
should be read as a variant of the other: that one is a locked-down laptop with no admin rights,
this one is a server you administer.

**What this assumes about the box**, because every one of these changes the instructions:

| | |
|---|---|
| RHEL **9.x**, x86-64, you have **root** | |
| **No direct internet.** A proxy, and company mirrors for dnf and the container registry | §3 |
| **No build toolchain, no source tree.** Jars and images are built elsewhere and uploaded | §4 |
| **Certificates issued by the company PKI** — you request them, you do not create them | §5 |
| One host runs everything: nginx, the backend, Neo4j, Keycloak | §2 |

## Read this bit, then decide how much of the rest you need

**There is an Ansible playbook that does all of it** (§6). A deployer edits two files and runs one
command; the playbook installs, configures, starts and then *verifies* — including the checks
nobody runs by hand, like "is anything internal reachable from outside". That is the intended
route, and if you take it you can treat §7 onwards as reference rather than instructions.

§7 is the same deployment **by hand**, for when you want to understand a step, debug one, or work
somewhere Ansible cannot reach. The two are kept honest by sharing files: the playbook templates
the same `nginx/sec.conf`, the same systemd units and the same compose file that §7 tells you to
copy.

**Two modes, and you pick one**: containers or native systemd. They are genuinely equivalent —
same ports, same configuration files, same certificates, and nginx on the host either way — so the
choice is about what your operations team would rather carry. Set `sec_deployment_mode` and the
playbook does the rest.

Everything is committed under `deploy/rhel9/`. Nothing here asks you to paste a config from a web
page.

---

## 1. Read these first

| | |
|---|---|
| `docs/KEYCLOAK_SETUP.md` | **the realm this application expects**, and the authority on it. §9 below stands the server up; that file says what goes in it and why. Where they disagree, it wins |
| `docs/adr/0017-bff-session.md` | why the browser holds no token, and why the session cookie is `Secure` unconditionally — which is what makes TLS mandatory rather than advisable |
| `docs/adr/0016-authorization-model.md` | why an object nobody has categorised is invisible to everyone, including you, on the day you first log in |
| `docs/adr/0021-production-deployment-topology.md` | the decisions this document implements |

**The one thing to know before you start.** After a clean install with a working import, the
application will be **empty for every user, including administrators**. That is correct
(ADR 0016): visibility comes from access categories, nothing has one yet, and `sec-admin` is a
capability rather than a view permit. §11.4 is the five minutes that fixes it. Do not spend an
afternoon debugging an empty screen.

---

## 2. What you are building

```
                        ┌──────────────────────────── RHEL 9 host ────────────────────────────┐
                        │                                                                     │
  browser  ──https──▶   │  nginx :443            (TLS terminates here, and only here)         │
                        │    │                                                                │
                        │    ├── /            ──▶ backend  127.0.0.1:8080   API + Angular UI  │
                        │    ├── /api/        ──▶ backend  127.0.0.1:8080                     │
                        │    └── /auth/       ──▶ keycloak 127.0.0.1:8180                     │
                        │                             │                                       │
                        │  backend ──bolt──▶ neo4j 127.0.0.1:7687                             │
                        │  keycloak ──jdbc──▶ postgresql 127.0.0.1:5432                       │
                        └─────────────────────────────────────────────────────────────────────┘
```

Four properties of that picture are load-bearing:

- **One hostname for everything.** The UI, the API and Keycloak share an origin, which keeps the
  login redirect same-site the whole way through. The session cookie is `SameSite=Lax`; splitting
  Keycloak onto its own hostname works but costs you a cross-site redirect chain to reason about.
- **Everything except nginx binds to 127.0.0.1.** Not a firewall rule — an actual loopback bind, so
  it is true even if firewalld is off. §12 says why this is what makes `server.behindProxy` safe.
- **TLS terminates once, at nginx.** It holds the certificate; the backend and Keycloak speak
  plain HTTP over loopback and are told the original scheme through `X-Forwarded-Proto`. Nothing
  else on this host has a certificate or needs one.
- **The backend serves the UI itself.** `mvn -Pui package` puts the Angular build inside the jar
  and `UiRoutes.kt` serves it, already getting right the two things a static file server gets wrong
  (`/api/**` is never answered with a page; a missing *file* is a 404, not `index.html`). Do not add
  an nginx `root` for the Angular bundle — you would be maintaining a second copy of those rules.

**PostgreSQL is not optional.** Production Keycloak refuses to start on its dev-file store, and the
realm is where every group membership lives — which is this application's entire authorization
model. Losing it loses who may see what.

---

## 3. The proxy and the mirrors

Do this first and verify it, because everything after it is a download.

### 3.1 dnf

```bash
# /etc/dnf/dnf.conf
proxy=http://proxy.company.corp:8080
# If the proxy needs credentials, they go here and this file becomes 0600 root:root.
# proxy_username=...
# proxy_password=...
```

If your site publishes its own RHEL mirror rather than proxying to the CDN, point the repos at it
instead and skip the proxy for dnf entirely — one mechanism, not both.

```bash
dnf clean all && dnf makecache          # this is the test; it either works or nothing else will
```

### 3.2 The container registry (path A only)

```bash
# /etc/containers/registries.conf
[[registry]]
prefix   = "quay.io"
location = "registry.company.corp/quay-remote"

[[registry]]
prefix   = "docker.io"
location = "registry.company.corp/dockerhub-remote"
```

Then `systemctl restart docker`. If your site has no registry mirror, images have to be carried in
as tarballs — §7.1 covers that, and it is not much worse.

### 3.3 Maven and npm — **not on this machine**

The server builds nothing (§4), so it needs neither. They belong on the build machine, and
`scripts/win/maven-settings.xml.example` is the mirror/proxy shape to copy. Mentioned here only so
nobody installs a JDK-with-Maven on the server out of habit: the fewer things on it, the fewer
things to patch.

### 3.4 Neo4j's repository (path B only)

```bash
rpm --import https://debian.neo4j.com/neotechnology.gpg.key   # through the proxy, or from the mirror

cat > /etc/yum.repos.d/neo4j.repo <<'EOF'
[neo4j]
name=Neo4j RPM Repository
baseurl=https://yum.neo4j.com/stable/2026.1
enabled=1
gpgcheck=1
EOF
```

Ask your mirror administrator to sync `yum.neo4j.com/stable/<series>` and point `baseurl` at the
internal copy. **Pin the series to the one the project pins** — `neo4j-image.version` in the root
`pom.xml`. The container tests run against that image, and testing against a different major than
production defeats the point of testing against Community at all (CLAUDE.md §7).

---

## 4. Build the artifact, elsewhere, and bring it over

The server has no toolchain and no source tree, so the artifact is built on a machine that has both
and copied in. That is item 6 of the deployment brief and it is also just good practice: what you
test is then bit-for-bit what you run.

**On the build machine:**

```bash
scripts/linux/sec-package.sh            # Angular build, then the jar around it
# → backend/target/backend-0.1.0-all.jar, and its sha256 printed at the end
```

Windows build machine: `scripts\win\sec-package.ps1`, identical output.

The script refuses to finish if the jar does not contain `static/index.html`, because a jar missing
its UI is indistinguishable from a working one until somebody opens a browser.

**Copy it over, and check it arrived intact:**

```bash
scp backend/target/backend-0.1.0-all.jar sec-server:/tmp/
ssh sec-server 'sha256sum /tmp/backend-0.1.0-all.jar'    # compare with what the build printed
```

That comparison is not ceremony. Until §13 puts these in a repository with checksums of its own, a
hand-copied file is the *only* artifact in this system with no provenance at all.

---

## 5. Certificates and trust

**You do not create these.** You request a server certificate for the host's FQDN from the company
PKI, and they issue you three things:

| | |
|---|---|
| the **server certificate** | for `sec.example.corp`, PEM |
| its **private key** | PEM, unencrypted — nginx cannot prompt for a passphrase at boot |
| the **CA chain** | the issuing CA and any intermediates, root included |

Put them where Ansible can read them:

```
deploy/rhel9/ansible/files/certs/
    sec.example.corp.crt
    sec.example.corp.key
    company-ca-chain.crt
```

That directory is git-ignored except for its `.gitkeep`, so a key cannot be committed by accident.

### 5.1 Check the bundle before you install it

```bash
deploy/rhel9/scripts/sec-check-certs.sh sec.example.corp \
    --cert  files/certs/sec.example.corp.crt \
    --key   files/certs/sec.example.corp.key \
    --chain files/certs/company-ca-chain.crt
```

The Ansible role runs the same checks itself and refuses to install a bundle that fails them, so
this script is for the conversation with the PKI team — it names the defect precisely enough to
quote in a ticket.

**The mistakes with a supplied bundle are not typos.** They are the ones that look fine:

| | |
|---|---|
| the key is from a **different CSR** | nginx refuses to start — "key values mismatch" — but only after you have deployed |
| there is **no subjectAltName** | every browser since 2017 rejects it, however correct the CN looks in the subject line |
| the SAN covers a **different name** | same, and the certificate looks perfectly valid on inspection |
| the chain is **missing an intermediate** | works for a browser that has it cached, fails for a fresh one. This is why "it works on my machine" is such a common report |
| it is **DER, not PEM** | a Windows CA hands these out routinely. OpenSSL 3 reads it happily; nginx does not |

### 5.2 Distribute the CA to clients — usually already done

If the company PKI issued it, domain-joined workstations almost certainly trust the chain already
and there is nothing to do. Check before you plan any rollout.

Where it is not: **Firefox keeps its own trust store** regardless of the machine's, and is the
usual cause of "it works for everyone but me".

### 5.3 The Java trust store — the one that bites

The backend fetches Keycloak's discovery document over `https://sec.example.corp/auth/...`, through
nginx, with this certificate. If the JVM does not trust it, **OIDC discovery fails at startup and
nobody can log in**, with a `PKIX path building failed` in the log and nothing else looking wrong.

**Packaged OpenJDK** (`dnf install java-21-openjdk-headless`) — nothing to do. RHEL's shared trust
store regenerates `/etc/pki/java/cacerts`, and the packaged JDK uses it, so installing the chain
into `/etc/pki/ca-trust/source/anchors/` and running `update-ca-trust extract` — which the Ansible
role does — covers Java too. Verify:

```bash
keytool -list -keystore /etc/pki/java/cacerts -storepass changeit | grep -i company
```

**A JDK from a tarball does not use that file.** Import into that JDK's own store, and remember a
JDK upgrade replaces it — which is the argument for the packaged one:

```bash
"$JAVA_HOME/bin/keytool" -importcert -trustcacerts -noprompt \
  -alias company-ca -file /etc/pki/ca-trust/source/anchors/company-ca-chain.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
```

**Containers** get the chain bind-mounted and run `update-ca-trust` in the image; the UBI OpenJDK
base links its cacerts to the system store, so the same mechanism applies inside.

### 5.4 Renewal

Drop the new files in the same place and re-run:

```bash
ansible-playbook -i inventory/hosts.yml site.yml --ask-vault-pass --tags certs
```

The role validates, installs and reloads nginx. Nothing else changes, and clients need no action
because the CA has not changed. The playbook warns when a certificate is within 30 days of expiry,
so a routine run is also the reminder.


## 6. Deploy it — Ansible

This is the intended route. Two files to edit, one command to run.

### 6.1 On the machine you run Ansible from

Not the server. Your workstation, or a jump host — anywhere with ssh to the target and Ansible
installed. Nothing is installed on the server that the playbook does not put there.

```bash
dnf install -y ansible-core          # or: pip install --user ansible-core
cd deploy/rhel9/ansible
ansible-galaxy collection install -r requirements.yml
```

Air-gapped: install the three collections from a company Galaxy mirror, or carry their tarballs
and `ansible-galaxy collection install ./community-crypto-*.tar.gz`.

### 6.2 The two files

```bash
cp inventory/hosts.yml.example  inventory/hosts.yml
cp group_vars/all.yml.example   group_vars/all.yml
cp group_vars/vault.yml.example group_vars/vault.yml
```

**`inventory/hosts.yml`** — the server and the account you ssh in as. It needs sudo.

**`group_vars/all.yml`** — every non-secret decision, and it is commented section by section. The
three that matter: `sec_hostname` (the real FQDN, the playbook refuses to run while it is still the
example), `sec_deployment_mode` (`native` or `compose`), `sec_jar_src` (the jar from §4).

**`group_vars/vault.yml`** — the secrets, then encrypt it:

```bash
openssl rand -base64 32          # once per secret; paste into the file
ansible-vault encrypt group_vars/vault.yml
ansible-vault edit    group_vars/vault.yml     # from now on, always this
```

**This is the answer to "where do secrets live" for a team.** The encrypted file is safe to commit
— that is the point: the values are versioned, reviewed and rolled back like everything else, and
there is one copy of the truth rather than one per engineer's shell profile. What each person needs
is the *vault passphrase*, not the secrets; keep it wherever the company already keeps shared
credentials.

> Both `vault.yml` and `all.yml` are git-ignored, deliberately. Git cannot tell an encrypted vault
> from a forgotten plaintext one, so neither is tracked and the `.example` files are the templates.
> If your team decides to commit the encrypted vault, remove that line knowingly.

### 6.3 Run it

```bash
ansible-playbook -i inventory/hosts.yml site.yml --ask-vault-pass
```

Or, with the passphrase in a `0600` file outside the repository:

```bash
ansible-playbook -i inventory/hosts.yml site.yml --vault-password-file ~/.sec-vault-pass
```

What it does, in order: refuses to start unless the host is RHEL 9 and every secret is real and the
jar exists → accounts, directories, SELinux boolean, firewall → **validates the certificate bundle
and installs it** → Neo4j → PostgreSQL and Keycloak, realm and theme → the jar, its config, its
unit → nginx → **verifies the result end to end**.

It ends by telling you the two things left to do in Keycloak (§9.2) and reminding you the
application will look empty until §11.4.

### 6.4 What you get for free

The last role is the part worth having. It checks, from outside, over the real hostname:

- liveness, readiness, and that the UI is actually in the jar
- that an Angular route survives a reload, **and** that a missing asset is still a 404 — the two
  rules a static file server gets wrong, verified rather than assumed
- that `/api/**` answers an RFC 9457 problem detail rather than a page
- that Keycloak's advertised `issuer` **matches what the backend is configured with** — if these
  differ every login fails token validation, and the cause is `KC_HOSTNAME`, not SEC
- that ports 8080, 8180 and 7687 are **not** reachable from off-host

That last one is the check nobody runs by hand, and it is the one that catches a firewall rule
somebody added during an incident and never removed.

### 6.5 Re-running it

Idempotent, so re-running is the normal way to change anything:

```bash
--tags certs      # a renewed certificate, validated and installed, nginx reloaded
--tags backend    # a new jar and a restart — this is the upgrade path
--tags nginx      # a vhost change
--tags verify     # just re-run the end-to-end checks, change nothing
--check --diff    # show what would change, change nothing
```

`--check --diff` before a production run is worth the ten seconds. It shows the templated diff of
every configuration file.

---

## 7. Doing it by hand

Everything §6 automates, as steps. Read this when you want to understand something, debug a failed
task, or work on a host Ansible cannot reach. The playbook installs the **same files** this section
tells you to copy, so nothing here is a second implementation.

### 7.1 Mode: containers

```bash
dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable --now docker
```

RHEL 9 ships `podman`, and `podman-compose` can run this file, but the healthchecks and
`depends_on: condition:` gating are what compose implements best. If your site mandates podman,
expect to replace that gating with explicit ordering.

**No registry mirror?** Pull on a connected machine and carry them:

```bash
docker save neo4j:2026.01-community quay.io/keycloak/keycloak:26.0 \
            postgres:16-alpine registry.access.redhat.com/ubi9/openjdk-21-runtime:latest \
  | gzip > sec-images.tar.gz
gunzip -c sec-images.tar.gz | docker load          # on the server
```

Lay out the files:

```bash
install -d -m 0755 /opt/sec/compose
cp -r deploy/rhel9/keycloak /opt/sec/
cp deploy/rhel9/compose/docker-compose.yml /opt/sec/compose/
install -o root -g root -m 0600 deploy/rhel9/compose/.env.example /opt/sec/compose/.env

install -d -o root -g root -m 0750 /etc/sec
install -o root -g root -m 0640 deploy/rhel9/config/sec.yaml.example /etc/sec/sec.yaml
install -o root -g root -m 0600 deploy/rhel9/config/sec.env.example  /etc/sec/sec.env
```

Two files hold values and the split is deliberate: `/opt/sec/compose/.env` is read by the docker
CLI and holds topology and image tags; `/etc/sec/sec.env` is handed to one container and holds the
application's secrets. Different readers, different lifetimes. Edit both — every `CHANGE-ME` must
go, and `SEC_NEO4J_PASSWORD` must be **identical** in the two.

```bash
deploy/rhel9/scripts/sec-build-image.sh /tmp/backend-0.1.0-all.jar --tag sec/backend:0.1.0
cd /opt/sec/compose && docker compose up -d && docker compose ps
```

Then §8.

### 7.2 Mode: native

**Java and Neo4j** (§3.4 has the repository):

```bash
dnf install -y java-21-openjdk-headless neo4j cypher-shell
```

`/etc/neo4j/neo4j.conf` — three things matter, the rest is fine as shipped:

```properties
# Loopback only. Community has exactly one credential (CLAUDE.md §7), so there is no second line
# of defence if this port is reachable.
server.default_listen_address=127.0.0.1
server.bolt.listen_address=127.0.0.1:7687
server.http.listen_address=127.0.0.1:7474

server.memory.heap.initial_size=2G
server.memory.heap.max_size=2G
server.memory.pagecache.size=2G
```

```bash
neo4j-admin dbms set-initial-password 'the password you generated'   # BEFORE the first start
systemctl enable --now neo4j
```

That command works on an empty store **only**, and silently does nothing afterwards. If Neo4j has
already started once, change the password with `cypher-shell` instead. This catches people out.

**PostgreSQL:**

```bash
dnf install -y postgresql-server postgresql
postgresql-setup --initdb
systemctl enable --now postgresql
sudo -u postgres psql <<'SQL'
CREATE USER keycloak WITH PASSWORD 'the password you generated';
CREATE DATABASE keycloak OWNER keycloak;
SQL
```

**Keycloak** — upload `keycloak-26.x.zip`, then:

```bash
useradd --system --no-create-home --shell /sbin/nologin keycloak
unzip keycloak-26.0.0.zip -d /opt && ln -sfn /opt/keycloak-26.0.0 /opt/keycloak
chown -R keycloak:keycloak /opt/keycloak-26.0.0
install -d -o root -g keycloak -m 0750 /etc/keycloak
```

`/etc/keycloak/keycloak.env`, `0640 root:keycloak` — the template is
`deploy/rhel9/ansible/roles/sec_keycloak/templates/keycloak.env.j2`:

```properties
KC_DB=postgres
KC_DB_URL=jdbc:postgresql://127.0.0.1:5432/keycloak
KC_DB_USERNAME=keycloak
KC_DB_PASSWORD=CHANGE-ME
KC_HTTP_ENABLED=true
KC_HTTP_PORT=8180
KC_HTTP_HOST=127.0.0.1
KC_PROXY_HEADERS=xforwarded
KC_PROXY_TRUSTED_ADDRESSES=127.0.0.1
KC_HOSTNAME=https://sec.example.corp
KC_HTTP_RELATIVE_PATH=/auth
KC_HOSTNAME_STRICT=true
KC_HEALTH_ENABLED=true
# First start only — delete both once §9.2 has created a named admin.
KC_BOOTSTRAP_ADMIN_USERNAME=admin
KC_BOOTSTRAP_ADMIN_PASSWORD=CHANGE-ME
```

Those proxy lines go together, and the choice between them is not free-form. Keycloak's
[reverse-proxy guide](https://www.keycloak.org/server/reverseproxy) gives **three alternatives** for
serving under a path prefix — an `X-Forwarded-Prefix` header, a hostname URL carrying the path, or
`http-relative-path` — and says to use *one*. nginx here does not rewrite, so Keycloak must
genuinely serve on `/auth`: `KC_HTTP_RELATIVE_PATH` is the one, and **`KC_HOSTNAME` carries no
path**. Setting both is the mistake to avoid; the issuer becomes hostname + relative path either
way, and having two knobs for it means a future edit to one silently disagrees with the other.

Without `KC_PROXY_HEADERS` every URL Keycloak generates says `http://localhost:8180` and the login
redirect goes nowhere. `KC_PROXY_TRUSTED_ADDRESSES` is the guide's own recommendation — the
listener is loopback-only already, so this is the second lock on the same door.

```bash
cp -r deploy/rhel9/keycloak/themes/sec /opt/keycloak/themes/
sudo -u keycloak env $(grep -v '^#' /etc/keycloak/keycloak.env | xargs) /opt/keycloak/bin/kc.sh build
install -m 0644 deploy/rhel9/systemd/sec-keycloak.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now sec-keycloak
```

**Re-run `kc.sh build` whenever a `KC_DB`, `KC_FEATURES` or `KC_HEALTH_ENABLED` value changes.** The
unit starts with `--optimized`, which skips the build — so without it the old values stay in effect
and nothing says so.

**The backend:**

```bash
useradd --system --no-create-home --shell /sbin/nologin sec
install -d -o root -g sec  -m 0750 /etc/sec
install -d -o root -g root -m 0755 /opt/sec
install -d -o sec  -g sec  -m 0750 /var/log/sec

install -o root -g root -m 0644 /tmp/backend-0.1.0-all.jar /opt/sec/backend-0.1.0-all.jar
ln -sfn /opt/sec/backend-0.1.0-all.jar /opt/sec/sec.jar

install -o root -g sec -m 0640 deploy/rhel9/config/sec.yaml.example /etc/sec/sec.yaml
install -o root -g sec -m 0640 deploy/rhel9/config/sec.env.example  /etc/sec/sec.env
$EDITOR /etc/sec/sec.yaml /etc/sec/sec.env         # hostnames, then every CHANGE-ME

install -m 0644 deploy/rhel9/systemd/sec-backend.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now sec-backend
journalctl -u sec-backend -f
```

The versioned-jar-plus-symlink layout is what makes §12.1's rollback one command.

---


## 8. nginx

Common to both paths.

```bash
dnf install -y nginx
install -m 0644 deploy/rhel9/nginx/sec.conf            /etc/nginx/conf.d/
install -m 0644 deploy/rhel9/nginx/sec-ssl-params.conf /etc/nginx/conf.d/
sed -i 's/sec\.example\.corp/YOUR.FQDN/g' /etc/nginx/conf.d/sec.conf

nginx -t && systemctl enable --now nginx
```

**SELinux.** On an enforcing host nginx may not open outbound connections, and the symptom is a 502
with an nginx configuration that is obviously correct:

```bash
setsebool -P httpd_can_network_connect on
```

**firewalld** — 443 in, nothing else:

```bash
firewall-cmd --permanent --add-service=https
firewall-cmd --permanent --add-service=http     # only for the redirect
firewall-cmd --reload
```

Do **not** open 8080, 8180, 7687 or 5432. If you find yourself wanting to, the thing you actually
want is an ssh tunnel.

Three things in that vhost are not decoration, and each fails in a way that wastes an afternoon:

| | |
|---|---|
| `client_max_body_size 64m` | a DOORS export is several MB and nginx's default is **1m**. Over it, nginx rejects the upload with a 413 that never reaches the backend, so the import screen reports a failure the backend log knows nothing about |
| `proxy_buffering off` on the SSE location | without it nginx holds progress events until its buffer fills, and the import progress bar jumps from 0 to 100 at the very end |
| `X-Forwarded-For` + `server.behindProxy: true` | the pair of them is what puts the caller's address in the audit log instead of `127.0.0.1`. One without the other is either useless or unsafe — §12.3 |

---

## 9. Configure Keycloak

`docs/KEYCLOAK_SETUP.md` is the authority on **what** the realm contains and why. This is how to get
it onto this server.

### 9.1 Import the realm

`deploy/rhel9/keycloak/sec-realm-prod.json` is the dev realm with the test users and the hardcoded
secrets removed, `sslRequired: all`, brute-force protection on, a password policy, `sec-user` as the
realm's default role, and `loginTheme: sec`.

**Edit the two `sec.example.corp` occurrences first** — the redirect URI and the post-logout URI.
A mismatch here is refused by Keycloak with its own error page, which is not an SEC error page and
is the single most common first-deployment failure.

Path A:

```bash
cp deploy/rhel9/keycloak/sec-realm-prod.json /opt/sec/keycloak/
docker compose exec keycloak /opt/keycloak/bin/kc.sh import \
  --file /opt/keycloak/data/import/sec-realm-prod.json
docker compose restart keycloak
```

Path B:

```bash
sudo -u keycloak /opt/keycloak/bin/kc.sh import --file /path/to/sec-realm-prod.json
systemctl restart sec-keycloak
```

Or import it through the admin console — Realm settings → *Create realm* → *Browse* — which is
easier to undo.

### 9.2 The steps the import cannot do

0. **Decide who may reach the admin console at all.** It is at
   `https://<host>/auth/admin/` and nginx restricts it by source address — with
   `sec_admin_allow_cidrs` empty, which is the default, **nobody can reach it**, including you.
   That is the safe starting state, not a bug. Put your administration network in that list and
   re-run `--tags nginx`.

   Why by address and not simply blocked: `KC_HOSTNAME_STRICT=true` makes Keycloak build every URL
   from `KC_HOSTNAME`, so the console reached any other way — an ssh tunnel to `127.0.0.1:8180`,
   say — serves its HTML and then sends the browser to `https://<host>/auth/...` anyway. Blocking
   that path makes the console load and dead-end. Restricting it makes the same URL work for the
   people who should have it, and nobody else. See §14 if you would rather not use the console.

1. **A named admin account.** Create yourself a real user in the `master` realm with the
   `admin` role, log in as it, then **delete `KC_BOOTSTRAP_ADMIN_*`** from the env file and restart.
   A permanent bootstrap admin is a permanent shared credential.
2. **The client secret.** Clients → `sec-backend` → Credentials → *Regenerate*. Copy it into
   `SEC_OIDC_CLIENT_SECRET` in `/etc/sec/sec.env` and restart the backend. The realm file carries no
   secret on purpose.
3. **The same for `sec-doors-push`**, if you use the DOORS push importer. That secret goes on the
   machine running the exporter — *not* on this server. The backend never calls that client's token
   endpoint; it only verifies tokens the exporter already holds (ADR 0020).
4. **Your groups.** The import ships `/SEC` and `/SEC/Importers` only. Add the real ones
   (`/SEC/Thermal`, `/SEC/Avionics`, …). Names are yours; SEC treats each as an opaque key.
   **Treat a group path as permanent** — renaming one looks like a new group with no grants, and its
   members silently lose access (KEYCLOAK_SETUP.md §4).
5. **Roles to individuals.** Assign `sec-admin` and `sec-access-manager` to people, not to groups —
   a role that arrives with a data-access group spreads with it.

### 9.3 Verify the token before you debug anything else

The most common failure in this whole document is a **missing `groups` claim**: everyone
authenticates perfectly and sees an empty application, and nothing looks broken. The realm file
includes the mapper, so this is a check rather than a fix — but check it.

Log in, then `GET /api/v1/auth/me`. It returns your groups and roles as the backend sees them. If
`groups` is empty and you are in a group, the mapper is missing or *Full group path* is off; the
backend logs the claim names it *did* receive at `WARN`, and that log line is the fastest route to
the answer.

---

## 10. Customising the login and logout pages

The theme is committed at `deploy/rhel9/keycloak/themes/sec/`, and it is deliberately small: a
`theme.properties` that inherits everything from `keycloak.v2`, one stylesheet, one logo.

**Inherit, do not copy templates.** Copying the `.ftl` files in is what makes a Keycloak upgrade
painful — every copied template freezes at the version it was copied from and silently misses new
fields, like a new MFA prompt or a terms checkbox. Override structure only when you must, and write
down why.

### What to edit

| | |
|---|---|
| `login/resources/css/login.css` | colours, type, the card frame. Loaded *after* the parent stylesheet, so it only overrides. The palette is copied from `frontend/src/styles/_tokens.scss` — the one place in the repository where those hex values legitimately appear twice, because Keycloak cannot import the Angular build's tokens |
| `login/resources/img/sec-logo.svg` | replace the file, keep the name |
| `login/theme.properties` | `parent=`, `styles=`, `logo=` |

### Deploying it

Path A: already bind-mounted read-only by the compose file. `docker compose restart keycloak`.

Path B: `cp -r deploy/rhel9/keycloak/themes/sec /opt/keycloak/themes/` and restart.

Select it in **Realm settings → Themes → Login theme → `sec`** (the realm import already sets it).

**Turn off the theme cache while you iterate**, or you will edit CSS and see nothing change:

```
KC_SPI_THEME_STATIC_MAX_AGE=-1
KC_SPI_THEME_CACHE_THEMES=false
KC_SPI_THEME_CACHE_TEMPLATES=false
```

Set those, restart, iterate, then **remove them** — they are a real performance cost in production.

### The logout page

Most users never see it. SEC does RP-initiated logout with a `post_logout_redirect_uri`, so Keycloak
clears the session and sends the browser straight back to the application. The confirmation page
appears only when the `id_token_hint` is missing — a user who lands on `/auth/realms/sec/protocol/
openid-connect/logout` by hand, for example.

If you want to style it anyway it is `logout-confirm.ftl` in the base theme, and the `sec` stylesheet
already covers it, because it inherits the same card and button rules. To change its **wording**
rather than its appearance, add a message bundle instead of copying the template:

```properties
# themes/sec/login/messages/messages_en.properties
logoutConfirmTitle=Sign out of the System Engineering Cockpit
logoutConfirmHeader=Sign out?
doLogout=Sign out
```

That is the general answer for text: `messages_en.properties` overrides any string in the login
theme — page titles, field labels, error messages — with no template copying at all.

---

## 11. First run

### 11.1 Preflight

**If you deployed with §6, this already ran** — the `sec_verify` role does everything below and
more, from off-host, and the playbook failed rather than finishing if any of it was wrong. Re-run
it any time with `--tags verify`.

By hand, on the server:

```bash
deploy/rhel9/scripts/sec-preflight.sh --host sec.example.corp          # native
deploy/rhel9/scripts/sec-preflight.sh --compose --host sec.example.corp
```

One line per prerequisite, changes nothing. It checks the things that are quiet when wrong: SELinux
booleans, loopback binds, file modes on the secrets, leftover `CHANGE-ME` values, whether the system
trust store really accepts the certificate.

### 11.2 The endpoints, in order

```bash
curl -s https://sec.example.corp/api/v1/health   # the process is up; touches no database
curl -s https://sec.example.corp/api/v1/ready    # the graph is reachable; 503 if not
curl -sI https://sec.example.corp/               # 200, text/html — the UI is in the jar
curl -s  https://sec.example.corp/auth/realms/sec/.well-known/openid-configuration | head -c 200
```

Liveness and readiness are separate on purpose: an orchestrator restarts on a failed liveness probe
but only withholds traffic on a failed readiness one, so a briefly unreachable database must not be
able to trigger a restart loop. Only `/ready` opens a session.

### 11.3 Log in

Browse to `https://sec.example.corp/`. You should be redirected to the styled Keycloak page, and
back afterwards.

### 11.4 Make something visible — the step everybody misses

You are logged in and the application is empty. That is ADR 0016 working: **an object nobody has
categorised is invisible to everyone, administrators included.** No code path widens visibility on
error and there is no bypass flag, on purpose.

As a user holding `sec-access-manager`:

1. **Access → Categories** — create one, e.g. `Programme A`.
2. **Access → Grants** — grant it to a group you are in.
3. **Access → Containers** (or *Not assigned*) — assign the category to the imported module or
   project.
4. **Reconcile** — propagates the container's category to its contents. One decision, 984 objects.
5. **Access → Defaults** — set what newly imported containers get, so the next import does not
   land invisible too.

Rows appear. If they do not, re-check §9.3: a user with no `groups` claim is in no group, and a
grant to a group they are not in grants nothing.

---

## 12. Day two

### 12.1 Upgrading

Point `sec_jar_src` and `sec_jar_version` at the new build in `group_vars/all.yml`, then:

```bash
ansible-playbook -i inventory/hosts.yml site.yml --ask-vault-pass --tags backend
```

That uploads the jar, moves the `sec.jar` symlink, restarts, and re-runs the verification. Both
modes, one command — in `compose` mode it rebuilds the image from the new jar instead.

**Rollback** is the symlink, and it is deliberately something you can do without Ansible at 02:00:

```bash
ln -sfn /opt/sec/backend-0.1.0-all.jar /opt/sec/sec.jar && systemctl restart sec-backend
```

By hand, in compose mode:

```bash
deploy/rhel9/scripts/sec-build-image.sh /tmp/backend-0.2.0-all.jar --tag sec/backend:0.2.0
# SEC_BACKEND_IMAGE=sec/backend:0.2.0 in /opt/sec/compose/.env
cd /opt/sec/compose && docker compose up -d backend
```

The playbook never overwrites `/etc/sec/sec.yaml` or `sec.env` with example values — they are
templated from your own variables, so an upgrade cannot silently reset a deployment's
configuration. Run `--check --diff` first if you want to see what it would change.

**Schema changes need no step.** `MetaSchema.apply()` and the import-run store's schema run at
startup and are idempotent.

### 12.2 Backup

Two things, and only the first is obvious:

```bash
# 1. the graph — including every :__Meta node, the only data a re-import cannot reconstruct
systemctl stop sec-backend
neo4j-admin database dump neo4j --to-path=/backup/           # native
docker compose exec neo4j neo4j-admin database dump neo4j --to-path=/backup   # containers
systemctl start sec-backend

# 2. Keycloak's database — every group membership, i.e. who may see what
sudo -u postgres pg_dump keycloak | gzip > /backup/keycloak-$(date +%F).sql.gz
```

Losing the graph loses your annotations. Losing the Keycloak database loses your **authorization
model** — the imported data would still be there, and nobody could see any of it. Back both up, and
restore-test both.

`/etc/sec/` too: it is small and it is the only copy of your configuration.

### 12.3 Why loopback and `behindProxy` are one decision

`server.behindProxy: true` installs `XForwardedHeaders`, which makes the backend believe
`X-Forwarded-For`. That is what puts the real caller in the audit log — and the plugin trusts the
header from *whoever sent it*. It is safe exactly while nothing but nginx can reach port 8080.

So: **loopback bind and `behindProxy: true` travel together.** Turning the second on while the port
is exposed lets any caller write its own address into your log. Leaving the second off behind nginx
is merely useless. The packaged default is `false`, and `AppConfigTest` pins that every spelling but
`true` — including `1`, `yes` and `on` — reads as off.

### 12.4 Logs

```bash
journalctl -u sec-backend -f                       # native; JSON, one object per line
journalctl -u sec-backend -o cat | jq 'select(.level=="ERROR")'
docker compose logs -f backend                     # containers
```

Two structured fields make a log searchable rather than merely readable, and both are in the MDC
rather than in the message text:

| | |
|---|---|
| `callId` | the same id the RFC 9457 problem detail's `instance` field carries, so a user's error report maps to exactly the lines that produced it |
| `clientIp` | who called. With `server.behindProxy: true` this is the browser's address; without it, nginx's |

```bash
journalctl -u sec-backend -o cat | jq 'select(.mdc.clientIp == "10.1.2.3")'
```

If `clientIp` reads `127.0.0.1` for every request, §12.3 is what to check.

### 12.5 Rotating a secret

Edit `/etc/sec/sec.env`, restart. There is no reload — configuration is read once, at startup,
deliberately. For `SEC_OIDC_CLIENT_SECRET`, regenerate in Keycloak *first*, then the file, then
restart; logins fail in between, so do it in a window.

---

## 13. Later: a company repository

Today's artifact has no provenance beyond the sha256 you compared in §4. When you have a Nexus or
an Artifactory, three changes retire the manual copy:

1. **`distributionManagement`** in the root `pom.xml`, and `mvn deploy` publishes the shaded jar.
   Credentials go in `settings.xml` on the build agent, never in the pom.
2. **A container registry** for `sec/backend`. `sec-build-image.sh` grows a `--push`, and the compose
   file's `SEC_BACKEND_IMAGE` points at the registry — which also means `docker compose pull` starts
   working and §6.1's tarball dance goes away.
3. **Sign or checksum on publish**, so §4's comparison is done by a tool rather than by eye.

Nothing in the deployment changes shape: both paths already take a *versioned artifact from
somewhere else*, which is why they will not need rewriting.

---

## 14. When something is wrong

| Symptom | Cause, in order of likelihood |
|---|---|
| Everyone sees an empty application | Nothing is categorised yet — §11.4. Then the `groups` claim — §9.3 |
| Keycloak's own error page at login | Redirect URI mismatch. It must be `https://<host>/api/v1/auth/callback` exactly, trailing path included |
| Login loops back to the sign-in page forever | The session cookie is dropped. It is `Secure` unconditionally, so this means you reached the site over `http://`, or through a hostname the certificate does not cover |
| 502 from nginx, config obviously correct | SELinux: `setsebool -P httpd_can_network_connect on` (§8) |
| `PKIX path building failed` at backend startup | The JVM does not trust the company CA — §5.3. A tarball JDK does not use `/etc/pki/java/cacerts` |
| nginx will not start: "key values mismatch" | The key and certificate are from different CSRs. `sec-check-certs.sh` says so before you deploy (§5.1) |
| The certificate works in Chrome and not in Firefox | Firefox has its own trust store (§5.2) |
| Works for you, fails on a colleague's fresh machine | A missing intermediate in the chain — the classic. §5.1 |
| nginx will not load the certificate at all | It is DER, not PEM. A Windows CA hands these out routinely (§5.1) |
| DOORS/Windchill upload fails, nothing in the backend log | nginx `client_max_body_size` (§8). The 413 never reached the application |
| Import progress jumps 0 → 100 at the end | `proxy_buffering off` missing on the SSE location (§8) |
| Every log line says the client is 127.0.0.1 | `server.behindProxy` is not `true`, or nginx is not setting `X-Forwarded-For` (§12.3) |
| Keycloak redirects to `localhost:8180` | `KC_PROXY_HEADERS=xforwarded` and `KC_HOSTNAME` (§7.3). A user should **never** see a port — every hop is 443 on one hostname |
| 403 on `https://<host>/auth/admin/` | Working as configured. `sec_admin_allow_cidrs` is empty, or does not contain the address nginx sees you as. `tail /var/log/nginx/sec.access.log` shows that address |
| The admin console loads then dead-ends | You reached it by a tunnel rather than through nginx. `KC_HOSTNAME_STRICT` sends the browser to the public URL regardless — use the allowlist (§9.2 step 0) |
| Keycloak ignores a changed `KC_DB`/`KC_FEATURES` | `--optimized` skips the build; re-run `kc.sh build` (§7.3) |
| Theme edits have no effect | The theme cache. §10, and remember to turn it back on |
| Backend exits at startup, "Connected to…" never logged | It calls `verifyConnectivity()` and dies deliberately rather than serving 500s. Check Neo4j and the credentials in `sec.env` |
| `sec-admin` still cannot see an object | Working as designed. Capability and visibility are separate axes (R8); `sec-admin` administers, it does not see |
