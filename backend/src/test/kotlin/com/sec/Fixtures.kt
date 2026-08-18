package com.sec

import kotlin.test.assertNotNull

/**
 * The committed sample exports, and the one declaration of where they live.
 *
 * They used to sit in `docs/`, beside the specs that describe them, and every test that wanted one
 * reached for it with `Path.of("..", "docs", name)` — which only resolves when the working
 * directory happens to be `backend/`, and which quietly spread the same relative path across five
 * test classes. They are test data, so they live on the test classpath: `mvn` puts them there from
 * any working directory, and an IDE that runs one test class does too.
 *
 * The existence check is not defensive noise. Every assertion these files feed is *about* the
 * fixture, so a missing one would turn whole test classes into tests of nothing — and it would do
 * so silently, since a fixture is exactly the kind of file a repository tidy-up moves.
 */
internal object Fixtures {

    /** One page of JIRA `/search`, from a real Data Center instance. */
    const val JIRA_SEARCH: String = "JIRA.json"

    /** JIRA `/field` — all 1 171 field definitions of that instance. */
    const val JIRA_FIELDS: String = "JIRA_FIELDS.json"

    /** JIRA `/issuetype`. */
    const val JIRA_ISSUE_TYPES: String = "JIRA_ISSUE_TYPES.json"

    /** One Windchill OData response, a real one with the company's names replaced. */
    const val WINDCHILL_EXPORT: String = "WindchillExportExample.json"

    /**
     * Three real DOORS module exports of one decomposition — a Customer module at L0, a System
     * module at L1 and a SubSystem module at L2 — sanitised, and linked to each other by
     * `refersTo`. Together they are the only fixture with real attribute names in it: spaces,
     * hyphens, parentheses and umlauts, which is what the hand-written smoke module cannot cover.
     */
    val DOORS_EXPORTS: List<String> = listOf(
        "doors/Something-Something_0009630f_current.json",
        "doors/SRD_000969a2_current.json",
        "doors/Something_0009f361_current.json",
    )

    /** The bytes of [name], read from `src/test/resources/fixtures/`. */
    fun bytes(name: String): ByteArray {
        val stream = Fixtures::class.java.getResourceAsStream("/fixtures/$name")
        assertNotNull(
            stream,
            "the committed fixture $name is missing from src/test/resources/fixtures/; " +
                "this test would otherwise pass vacuously.",
        )
        return stream.use { it.readBytes() }
    }

    /** [name] as UTF-8 text. Every one of these files is UTF-8 and several need it (CLAUDE.md §3). */
    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)
}
