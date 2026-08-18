# Documentation map

Start here. Three questions cover almost everything:

| I want to… | Read |
|---|---|
| **Deploy it to a server** | [`DEPLOY_RHEL9.md`](DEPLOY_RHEL9.md) — RHEL 9, behind a proxy, company PKI, no toolchain. There is an Ansible playbook that does all of it |
| **Develop on it** | the root [`CLAUDE.md`](../CLAUDE.md) — the rules, the layout, the pinned versions. Then `backend/CLAUDE.md` or `frontend/CLAUDE.md` for the half you are in |
| **Run it locally** | [`RUNNING.md`](RUNNING.md) for the locked-down Windows workstation; on Linux or a normal machine, the IDE configurations in `.run/` (IntelliJ) and `.vscode/` (VS Code) |

Everything else below is reference: read it when you touch that subject, not before.

---

## Deployment and operations

| | |
|---|---|
| [`DEPLOY_RHEL9.md`](DEPLOY_RHEL9.md) | **The server's environment contract.** Proxy and mirrors, building and uploading the artifact, certificates from the company PKI, the Ansible playbook, both modes (containers / native systemd), nginx, first run, day-two operations, and a symptom→cause table |
| [`KEYCLOAK_SETUP.md`](KEYCLOAK_SETUP.md) | **The realm this application expects**, and the authority on it. `DEPLOY_RHEL9` stands the server up; this says what goes in it and why. Where they disagree, this one wins |
| [`RUNNING.md`](RUNNING.md) | The **other** machine: a Windows 11 workstation with no admin rights, proxy-only internet and no Docker — the only one that can talk to DOORS. Not a variant of `DEPLOY_RHEL9`; nothing in common with it |

The deployment files themselves are in [`../deploy/rhel9/`](../deploy/rhel9/), which has its own README.

## Architecture and decisions

| | |
|---|---|
| [`adr/`](adr/) | 22 records, one per non-obvious decision. **Read the ADR before "fixing" something that looks inconsistent** — several of them exist because the obvious thing was wrong |
| [`SE_ITEM_SCHEMA.md`](SE_ITEM_SCHEMA.md) | The graph schema: `SEItem`, identity, the `__` namespace in practice |
| [`CYPHER_API_DESIGN.md`](CYPHER_API_DESIGN.md) | The four layers guarding the ad-hoc Cypher console. Implement exactly these; do not simplify |

The ADRs worth knowing before anything else:

- **0016** the authorization model — why a freshly imported object is invisible to everyone
- **0017** the session — why the browser holds no token, and why TLS is mandatory rather than advisable
- **0010** graph names as constants — why no Kotlin code addresses the graph with a string literal
- **0021** the production deployment topology

## Sources

One per system the graph pulls from. Each says how its importer works and what it writes.

| | |
|---|---|
| [`DOORS_TO_NEO4J_IMPORTER_SPEC.md`](DOORS_TO_NEO4J_IMPORTER_SPEC.md) | The DOORS importer. See also ADR 0019 (upload) and ADR 0020 (machine push) |
| [`DOORS_TABLES.md`](DOORS_TABLES.md) | How a DOORS table is reconstructed from flat objects — the hardest part of that importer |
| [`JIRA_ISSUES_FEATURE_SPEC.md`](JIRA_ISSUES_FEATURE_SPEC.md) | The JIRA importer and the Issues view. **Read ADR 0014 beside it** — it lists twenty-four places the implementation departs from this spec deliberately |

Windchill has no spec file: the whole design is ADR 0015, and it is short.

## Views and features

These describe **shipped behaviour** and are cited from the code as the reason it is the way it is.
They are specifications, not development notes — when the code and one of these disagree, that is a
bug in one of them, and the document is how you tell which.

| | |
|---|---|
| [`features/access-control.md`](features/access-control.md) | **The most load-bearing document here.** The specification for R8: one visibility predicate, categories, grants, the reconciler. `backend/CLAUDE.md` says to read it before touching any read path |
| [`REQ_REVIEW.md`](REQ_REVIEW.md) | The requirements review table — columns, the Description rule, issues, ordering |
| [`req-review-comment-threads.md`](req-review-comment-threads.md) | Comment threads in that table |
| [`requirement-breakdown-tree.md`](requirement-breakdown-tree.md) | The Breakdown tab: `refersTo` read as *refines* |
| [`REQ_BREAKDOWN_GRAPH_VIEW.md`](REQ_BREAKDOWN_GRAPH_VIEW.md) | The dependency graph. Amended by ADR 0011 |
| [`features/requirements-modules.md`](features/requirements-modules.md) | The Modules view and its settings dialog |
| [`features/requirements-statistics.md`](features/requirements-statistics.md) | The Statistics view |
| [`features/attribute-policy-checks.md`](features/attribute-policy-checks.md) | Mandatory-attribute policies and how they are checked |

---

## What is deliberately not here

- **No `docs/history/`.** Completed review and refactor documents were deleted once their items
  were done and the decisions had moved into ADRs. Git has them.
- **No design images.** They were baselines for features that have since shipped; the rules they
  illustrated now live in the code comments that used to point at them.
- **No API reference.** The OpenAPI spec is a build artifact, and `backend/CLAUDE.md` carries the
  route table.
- **No secrets, no certificates, no environment-specific values**, in any file here or under
  `deploy/`. The `.example` files are templates; real values live in an `ansible-vault` file and in
  `/etc/sec/` on the server, and both are git-ignored.
