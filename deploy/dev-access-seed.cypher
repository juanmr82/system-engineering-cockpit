// Local dev only — the access-control fixture the Access views will eventually replace.
//
// docs/features/access-control.md §15 phase 4's acceptance question is "read it on screen as two
// different users". Nothing in the product can answer it yet: categories and grants are written by
// `AccessAdminService`, which is phase 6. Until then the graph side has to be seeded by hand, and
// this file is that hand — committed rather than retyped, so the check is repeatable and so phases
// 6 and 7 develop against the same shape.
//
// It pairs with `deploy/keycloak/sec-realm.json`, whose three users exist to be exactly the three
// callers `VisibilityMatrixTest` asserts over:
//
//   sec-dev-user     /SEC/Thermal    one category  — the scoped view
//   sec-dev-admin    /SEC/All-Read   seesAll       — the contrast
//   sec-dev-nogroup  (no group)      nothing       — the empty application
//
// ## Running it
//
//   docker compose -f deploy/docker-compose.dev.yml exec -T neo4j \
//     cypher-shell -u neo4j -p "$SEC_NEO4J_PASSWORD" < deploy/dev-access-seed.cypher
//
//   # PowerShell
//   Get-Content deploy\dev-access-seed.cypher | `
//     docker compose -f deploy\docker-compose.dev.yml exec -T neo4j `
//     cypher-shell -u neo4j -p $env:SEC_NEO4J_PASSWORD
//
// ## Then restart the backend, and it is not optional
//
// Two separate reasons, both of which look like "the seed did not work" if skipped:
//
//  - **Propagation.** This file tags *containers* — a module, a project — which is the only thing a
//    human is ever meant to tag (§8.1). Getting those tags onto the 984 objects inside is the
//    reconciler's job, and its startup pass (`Application.module()`) is what runs it. There is no
//    other trigger you can reach: `POST /api/v1/access/reconcile` sits behind the session guard and
//    a script has no cookie to present, which is a known, named gap.
//  - **The resolver cache.** It is keyed on the sorted group-key set with no invalidation until
//    phase 6 wires `AccessAdminService` to it (§5). A grant added under a group this process has
//    already resolved does not take effect, and signing out and back in does not help — the session
//    is not the key. Restarting is the whole of the workaround, and there is no TTL to wait out.
//
// Idempotent: every write is a MERGE and re-running changes nothing. Safe against a graph that has
// no DOORS, JIRA or Windchill data yet — each block simply tags nothing.
//
// **Not a fixture for tests.** `VisibilityMatrixTest` builds its own, deliberately, so a filtering
// bug cannot be masked by a seeding bug.

// -- 1. The two categories -------------------------------------------------------------------
//
// `__metaId` is a stable string rather than the UUID v7 the R2 contract asks of the real write
// path, and that is the one deliberate departure here: idempotency needs a fixed key to MERGE on,
// and a dev seed that created a second category on every run would be worse than a wrong id shape.
// Recognisable on sight as `dev-*` so nobody mistakes one for something a user made.

MERGE (c:__Meta:__AccessCategory {key: 'dev-thermal'})
  ON CREATE SET c.__metaId        = 'dev-category-thermal',
                c.__metaKind      = 'accessCategory',
                c.__schemaVersion = 1,
                c.__createdBy     = 'dev-seed',
                c.__createdAt     = toString(datetime())
SET c.name        = 'Thermal (dev)',
    c.description = 'Local development fixture. Granted to /SEC/Thermal.',
    c.everyGroup  = false,
    c.__updatedBy = 'dev-seed',
    c.__updatedAt = toString(datetime());

MERGE (c:__Meta:__AccessCategory {key: 'dev-avionics'})
  ON CREATE SET c.__metaId        = 'dev-category-avionics',
                c.__metaKind      = 'accessCategory',
                c.__schemaVersion = 1,
                c.__createdBy     = 'dev-seed',
                c.__createdAt     = toString(datetime())
SET c.name        = 'Avionics (dev)',
    c.description = 'Local development fixture. Granted to nobody, on purpose: it is what makes the scoped view visibly narrower.',
    c.everyGroup  = false,
    c.__updatedBy = 'dev-seed',
    c.__updatedAt = toString(datetime());

// -- 2. The groups, and what each may read ----------------------------------------------------
//
// The keys are the full paths the token's `groups` claim carries (§16 question 5), so they must
// match `sec-realm.json` exactly. A group the resolver meets first would be created the same way,
// with no grants; seeding it here only means the Access views have something to show before anyone
// has signed in.

MERGE (g:__Group {key: '/SEC/Thermal'})
  ON CREATE SET g.firstSeenAt = toString(datetime())
SET g.name = 'Thermal', g.seesAll = false;

MERGE (g:__Group {key: '/SEC/All-Read'})
  ON CREATE SET g.firstSeenAt = toString(datetime())
SET g.name = 'All read', g.seesAll = true;

// /SEC/Thermal reads one of the two categories. That it reads *one* is the entire point: the
// second category is what the scoped user cannot see, and a seed granting both would make every
// screen agree and prove nothing.
MATCH (g:__Group {key: '/SEC/Thermal'}), (c:__AccessCategory {key: 'dev-thermal'})
MERGE (g)-[r:__mayRead]->(c)
  ON CREATE SET r.__createdBy = 'dev-seed', r.__createdAt = toString(datetime());

// /SEC/All-Read gets `seesAll` rather than a grant on both categories, and the difference is not
// cosmetic (§5 step 3). `seesAll` short-circuits the predicate, so it also covers objects carrying
// *no* category — every freshly imported object, until someone assigns it. A group granted both
// categories instead would under-count silently after each import, which is precisely the
// confusing failure this user exists to rule out.

// -- 3. Containers, alternating between the two categories ------------------------------------
//
// Alternating by name rather than by a hardcoded module id, because what has been imported into a
// given developer's graph is unknowable from here. The effect is what the on-screen check needs:
// the scoped user sees about half of everything, and the difference between the two accounts is
// visible on every view rather than only on a module someone remembered to pick.

MATCH (m:DOORSModule)
WITH m ORDER BY m.__name
WITH collect(m) AS containers
UNWIND range(0, size(containers) - 1) AS i
WITH containers[i] AS container,
     CASE i % 2 WHEN 0 THEN 'dev-thermal' ELSE 'dev-avionics' END AS categoryKey
MATCH (c:__AccessCategory {key: categoryKey})
MERGE (container)-[r:__inAccessCategory {origin: 'direct'}]->(c)
  ON CREATE SET r.__createdBy = 'dev-seed', r.__createdAt = toString(datetime());

MATCH (p:JiraProject)
WITH p ORDER BY p.__name
WITH collect(p) AS containers
UNWIND range(0, size(containers) - 1) AS i
WITH containers[i] AS container,
     CASE i % 2 WHEN 0 THEN 'dev-thermal' ELSE 'dev-avionics' END AS categoryKey
MATCH (c:__AccessCategory {key: categoryKey})
MERGE (container)-[r:__inAccessCategory {origin: 'direct'}]->(c)
  ON CREATE SET r.__createdBy = 'dev-seed', r.__createdAt = toString(datetime());

// Windchill has no container yet (§8.2, `containerless = true`), so its documents are tagged
// directly. When folder nodes arrive the folder becomes the container and this block changes to
// look like the two above; nothing else here does.
MATCH (d:WindchillDocument)
WITH d ORDER BY d.__sortKey
WITH collect(d) AS documents
UNWIND range(0, size(documents) - 1) AS i
WITH documents[i] AS document,
     CASE i % 2 WHEN 0 THEN 'dev-thermal' ELSE 'dev-avionics' END AS categoryKey
MATCH (c:__AccessCategory {key: categoryKey})
MERGE (document)-[r:__inAccessCategory {origin: 'direct'}]->(c)
  ON CREATE SET r.__createdBy = 'dev-seed', r.__createdAt = toString(datetime());

// -- 4. What it did ----------------------------------------------------------------------------
//
// `inherited` stays 0 until the backend restarts and the reconciler's startup pass runs. Seeing 0
// here is correct; seeing 0 *after* a restart means the reconciler did not run, which is the one
// failure worth chasing.

MATCH (c:__AccessCategory)
OPTIONAL MATCH (c)<-[direct:__inAccessCategory {origin: 'direct'}]-()
OPTIONAL MATCH (c)<-[inherited:__inAccessCategory {origin: 'inherited'}]-()
OPTIONAL MATCH (c)<-[:__mayRead]-(g:__Group)
RETURN c.key                        AS category,
       count(DISTINCT direct)       AS taggedDirectly,
       count(DISTINCT inherited)    AS inheritedSoFar,
       collect(DISTINCT g.key)      AS grantedTo
ORDER BY category;
