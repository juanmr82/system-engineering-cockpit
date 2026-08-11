package com.sec.config

import io.ktor.server.config.MapApplicationConfig
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings half of `docs/JIRA_ISSUES_FEATURE_SPEC.md` §4.
 *
 * Two behaviours here are load-bearing and neither is obvious from reading the data class:
 * **an absent `jira` block must not throw** (unlike `neo4j`, which must), and **the host is
 * normalised on the way in** so that nothing downstream has to wonder whether it ends in a slash.
 */
class JiraSettingsTest {

    // -- host normalisation -----------------------------------------------------------------

    @Test
    fun `a trailing slash is stripped so path concatenation cannot double it`() {
        val settings = settings(host = "https://jira.example.com/jira/")

        assertEquals("https://jira.example.com/jira", settings.host)
        assertEquals("https://jira.example.com/jira/rest/api/2/myself", settings.url("/rest/api/2/myself"))
    }

    // A hand-edited deployment file ending `/jira//` is a typo, not a different instance.
    @Test
    fun `repeated trailing slashes are stripped, not just one`() {
        assertEquals("https://jira.example.com/jira", JiraSettings.normaliseHost("https://jira.example.com/jira///"))
    }

    @Test
    fun `surrounding whitespace is stripped`() {
        assertEquals("https://jira.example.com", JiraSettings.normaliseHost("  https://jira.example.com  "))
    }

    /**
     * The reference instance is served under a context path, so the host is legitimately not a
     * bare origin. Code that assumes it is builds `https://jira.example.com/rest/api/2/...` and
     * gets a 404 from a server that is working perfectly.
     */
    @Test
    fun `a context path in the host survives normalisation`() {
        val settings = settings(host = "https://jira.example.com/jira")

        assertEquals("https://jira.example.com/jira/rest/api/2/field", settings.url("/rest/api/2/field"))
    }

    // -- configured or not ------------------------------------------------------------------

    @Test
    fun `a blank token means not configured, however good the host is`() {
        assertFalse(settings(host = "https://jira.example.com", token = "").isConfigured)
    }

    @Test
    fun `a blank host means not configured, however good the token is`() {
        assertFalse(settings(host = "", token = "secret").isConfigured)
    }

    @Test
    fun `both present means configured`() {
        assertTrue(settings(host = "https://jira.example.com", token = "secret").isConfigured)
    }

    // -- the token does not leak ------------------------------------------------------------

    /**
     * A `data class` prints every property, and a config object reaches a log line eventually —
     * at startup, in an error, or through a debugger's `toString`. This is the one guard that
     * the override is still there after someone adds a property.
     */
    @Test
    fun `toString never contains the token`() {
        val rendered = settings(token = "s3cr3t-personal-access-token").toString()

        assertFalse(rendered.contains("s3cr3t"), rendered)
        assertTrue(rendered.contains("redacted"), rendered)
    }

    @Test
    fun `toString distinguishes an unset token from a redacted one`() {
        assertTrue(settings(token = "").toString().contains("unset"))
    }

    // -- loading ----------------------------------------------------------------------------

    /**
     * The difference from `neo4j` that matters: a deployment with no JIRA at all starts, serves
     * every other feature, and reports the absence at one endpoint. `config.property("jira.host")`
     * would have thrown here and taken the whole application down with it.
     */
    @Test
    fun `an absent jira block loads as unconfigured rather than throwing`() {
        val loaded = loadJiraSettings(MapApplicationConfig())

        assertFalse(loaded.isConfigured)
        assertEquals("", loaded.host)
    }

    @Test
    fun `an empty host and token load as unconfigured`() {
        val loaded = loadJiraSettings(
            MapApplicationConfig("jira.host" to "", "jira.token" to ""),
        )

        assertFalse(loaded.isConfigured)
    }

    @Test
    fun `values are read and the host is normalised on the way in`() {
        val loaded = loadJiraSettings(
            MapApplicationConfig(
                "jira.host" to "https://jira.example.com/jira/",
                "jira.token" to "tok",
                "jira.pageSize" to "250",
                "jira.maxRetries" to "2",
                "jira.requestTimeoutMs" to "90000",
            ),
        )

        assertTrue(loaded.isConfigured)
        assertEquals("https://jira.example.com/jira", loaded.host)
        assertEquals(250, loaded.pageSize)
        assertEquals(2, loaded.maxRetries)
        assertEquals(Duration.ofSeconds(90), loaded.requestTimeout)
    }

    @Test
    fun `absent tuning knobs fall back to the documented defaults`() {
        val loaded = loadJiraSettings(
            MapApplicationConfig("jira.host" to "https://jira.example.com", "jira.token" to "tok"),
        )

        assertEquals(JiraSettings.DEFAULT_PAGE_SIZE, loaded.pageSize)
        assertEquals(JiraSettings.DEFAULT_MAX_RETRIES, loaded.maxRetries)
        assertEquals(JiraSettings.DEFAULT_REQUEST_TIMEOUT, loaded.requestTimeout)
        assertEquals(JiraSettings.DEFAULT_SOCKET_TIMEOUT, loaded.socketTimeout)
        assertEquals(JiraSettings.DEFAULT_CONNECT_TIMEOUT, loaded.connectTimeout)
    }

    /**
     * A zero or negative page size is a typo, and honouring it would produce an import that
     * fetches nothing and reports success. Falling back is louder than failing here would be
     * useful — the value is a tuning knob, not a contract.
     */
    @Test
    fun `a nonsensical page size falls back rather than being honoured`() {
        val loaded = loadJiraSettings(
            MapApplicationConfig(
                "jira.host" to "https://jira.example.com",
                "jira.token" to "tok",
                "jira.pageSize" to "0",
            ),
        )

        assertEquals(JiraSettings.DEFAULT_PAGE_SIZE, loaded.pageSize)
    }

    // -- the two products' credentials ------------------------------------------------------------

    /**
     * Data Center's scheme, and the default. Verified against the reference instance; a Cloud host
     * answers this exact header with a 403, which is why the scheme is configuration.
     */
    @Test
    fun `bearer is the default and sends the token as a personal access token`() {
        assertEquals(JiraAuthScheme.BEARER, settings().authScheme)
        assertEquals("Bearer token", settings().authorizationHeader())
    }

    /**
     * Cloud's scheme: `base64(email:apiToken)`. Verified against `juanmr82.atlassian.net`, which
     * answers `/rest/api/2/myself` with 200 for this and 403 for a Bearer PAT.
     */
    @Test
    fun `basic sends the email and token as HTTP Basic`() {
        val header = settings(token = "api-token")
            .copy(authScheme = JiraAuthScheme.BASIC, email = "someone@example.com")
            .authorizationHeader()

        assertEquals("Basic " + base64("someone@example.com:api-token"), header)
    }

    /**
     * An API token with no account to pair it with is not a credential Cloud accepts, so "basic
     * with no email" is *not configured* rather than configured-and-broken. The distinction is the
     * whole point of `isConfigured`: one is fixed in a file, the other at the JIRA end.
     */
    @Test
    fun `basic without an email is not configured`() {
        val loaded = settings().copy(authScheme = JiraAuthScheme.BASIC, email = "")

        assertFalse(loaded.isConfigured)
        assertTrue(loaded.copy(email = "a@b.c").isConfigured)
    }

    @Test
    fun `the auth scheme is read from configuration, case-insensitively`() {
        assertEquals(JiraAuthScheme.BASIC, load("auth" to "BASIC").authScheme)
        assertEquals(JiraAuthScheme.BEARER, load("auth" to "bearer").authScheme)
    }

    /**
     * An unrecognised scheme falls back to the spec's own rather than failing startup: the cost of
     * a typo is then a 401 with a message, not a service that will not boot.
     */
    @Test
    fun `an unknown auth scheme falls back to bearer`() {
        assertEquals(JiraAuthScheme.BEARER, load("auth" to "oauth2").authScheme)
        assertEquals(JiraAuthScheme.BEARER, load().authScheme)
    }

    /** The credential is the one thing that must never be printed, whichever scheme built it. */
    @Test
    fun `toString redacts the token under both schemes`() {
        val bearer = settings(token = "s3cr3t").toString()
        val basic = settings(token = "s3cr3t")
            .copy(authScheme = JiraAuthScheme.BASIC, email = "a@b.c").toString()

        listOf(bearer, basic).forEach { rendered ->
            assertFalse(rendered.contains("s3cr3t"), rendered)
            assertFalse(rendered.contains(base64("a@b.c:s3cr3t")), rendered)
            assertTrue(rendered.contains("<redacted>"), rendered)
        }
    }

    private fun base64(raw: String): String =
        java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.ISO_8859_1))

    private fun load(vararg extra: Pair<String, String>) = loadJiraSettings(
        MapApplicationConfig(
            *(
                listOf("jira.host" to "https://jira.example.com", "jira.token" to "tok") +
                    extra.map { (k, v) -> "jira.$k" to v }
                ).toTypedArray(),
        ),
    )

    private fun settings(
        host: String = "https://jira.example.com",
        token: String = "token",
    ) = JiraSettings(host = JiraSettings.normaliseHost(host), token = token)
}
