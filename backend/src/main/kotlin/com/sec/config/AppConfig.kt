package com.sec.config

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

public data class Neo4jSettings(
    public val uri: String,
    public val database: String,
    public val user: String,
    public val password: String,
    // Community has no query governor (CLAUDE.md §7), so the application-side transaction
    // timeout is the only thing standing between one bad query and an exhausted instance.
    public val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    public val writeTimeout: Duration = DEFAULT_WRITE_TIMEOUT,
) {
    public companion object {
        public val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(10)
        public val DEFAULT_WRITE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/**
 * How to reach JIRA, and how hard to pull on it.
 *
 * **Optional, unlike [Neo4jSettings].** The application is useless without a graph, so an unset
 * `SEC_NEO4J_USER` deliberately kills startup; it is entirely usable without JIRA, so an unset
 * token must not. [isConfigured] is what the routes check, and an import attempted against an
 * unconfigured instance is a problem detail saying so — never a stack trace, and never a startup
 * failure on the developer machines and container tests that have no JIRA at all.
 */
public data class JiraSettings(
    /** No trailing slash. Empty when JIRA is not configured. */
    public val host: String,
    public val token: String,
    public val platform: JiraPlatform,
    public val authScheme: JiraAuthScheme,
    /** Only used by [JiraAuthScheme.BASIC], where the credential is `email:token`. */
    public val email: String,
    /** Issues per search request. JIRA caps this server-side; asking for more is not an error. */
    public val pageSize: Int,
    /** A ceiling on one run, so a mis-typed JQL cannot pull a quarter of a million issues. */
    public val maxIssues: Int,
    /** Rows per `UNWIND` write transaction. */
    public val batchSize: Int,
    /** Keep each issue's `fields` block verbatim alongside the flattened properties. */
    public val storeRawFields: Boolean,
    public val connectTimeout: Duration,
    public val readTimeout: Duration,
    public val maxRetries: Int,
) {
    /** Both halves are needed: a host with no token authenticates as nobody and 401s per page. */
    public val isConfigured: Boolean get() = host.isNotBlank() && token.isNotBlank()

    public companion object {
        public val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        public val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(30)
        public const val DEFAULT_PAGE_SIZE: Int = 100
        public const val DEFAULT_MAX_ISSUES: Int = 20_000
        public const val DEFAULT_BATCH_SIZE: Int = 500
        public const val DEFAULT_MAX_RETRIES: Int = 3

        /** What a backend with no `jira:` block at all runs with. */
        public val UNCONFIGURED: JiraSettings = JiraSettings(
            host = "",
            token = "",
            platform = JiraPlatform.DATACENTER,
            authScheme = JiraAuthScheme.BEARER,
            email = "",
            pageSize = DEFAULT_PAGE_SIZE,
            maxIssues = DEFAULT_MAX_ISSUES,
            batchSize = DEFAULT_BATCH_SIZE,
            storeRawFields = true,
            connectTimeout = DEFAULT_CONNECT_TIMEOUT,
            readTimeout = DEFAULT_READ_TIMEOUT,
            maxRetries = DEFAULT_MAX_RETRIES,
        )
    }
}

/**
 * Which search endpoint to use.
 *
 * Atlassian is removing the classic offset-paginated `/search` on Cloud in favour of
 * `/search/jql`, which is cursor-paginated and reports no total; Data Center has only the classic
 * one (design doc §1). The client pages by cursor either way, so this flag picks a URL and a
 * response shape, never a different algorithm.
 */
public enum class JiraPlatform { CLOUD, DATACENTER }

/** Personal access token as a bearer (Data Center) or `email:token` basic auth (Cloud). */
public enum class JiraAuthScheme { BEARER, BASIC }

public data class AppConfig(
    public val neo4j: Neo4jSettings,
    public val jira: JiraSettings,
)

// Typed config with exactly one source: the Ktor ApplicationConfig. Credentials are still absent
// from the yaml file — `user: "$SEC_NEO4J_USER"` is resolved from the environment by Ktor itself,
// which fails fast when the variable is unset. Nothing here reads System.getenv directly, so a
// test overrides credentials the same way it overrides every other setting.
public fun loadAppConfig(config: ApplicationConfig): AppConfig {
    val neo4j = Neo4jSettings(
        uri = config.property("neo4j.uri").getString(),
        database = config.property("neo4j.database").getString(),
        user = config.property("neo4j.user").getString(),
        password = config.property("neo4j.password").getString(),
        readTimeout = config.durationSeconds("neo4j.readTimeoutSeconds", Neo4jSettings.DEFAULT_READ_TIMEOUT),
        writeTimeout = config.durationSeconds("neo4j.writeTimeoutSeconds", Neo4jSettings.DEFAULT_WRITE_TIMEOUT),
    )
    return AppConfig(neo4j = neo4j, jira = loadJiraSettings(config))
}

// Every key optional, and the whole section optional: a backend with no `jira:` block starts and
// serves every other view, with the JIRA routes answering "not configured". The Neo4j block above
// is the opposite on purpose — read the KDoc on JiraSettings for why the two differ.
private fun loadJiraSettings(config: ApplicationConfig): JiraSettings {
    val defaults = JiraSettings.UNCONFIGURED
    return JiraSettings(
        // A trailing slash here and the constant API base below it would build `//rest/api/2/`,
        // which some reverse proxies answer and some redirect. Trim it once, here.
        host = config.string("jira.host", defaults.host).trimEnd('/'),
        token = config.string("jira.token", defaults.token),
        platform = config.string("jira.platform", "").toPlatform(defaults.platform),
        authScheme = config.string("jira.authScheme", "").toAuthScheme(defaults.authScheme),
        email = config.string("jira.email", defaults.email),
        pageSize = config.int("jira.pageSize", defaults.pageSize),
        maxIssues = config.int("jira.maxIssues", defaults.maxIssues),
        batchSize = config.int("jira.batchSize", defaults.batchSize),
        storeRawFields = config.string("jira.storeRawFields", "").toBooleanStrictOrNull()
            ?: defaults.storeRawFields,
        connectTimeout = config.durationSeconds("jira.connectTimeoutSeconds", defaults.connectTimeout),
        readTimeout = config.durationSeconds("jira.readTimeoutSeconds", defaults.readTimeout),
        maxRetries = config.int("jira.maxRetries", defaults.maxRetries),
    )
}

// An unrecognised value falls back rather than failing startup: the enum picks which URL to call,
// and a typo that stopped the whole application booting would be a disproportionate answer to a
// setting only the JIRA routes read.
private fun String.toPlatform(fallback: JiraPlatform): JiraPlatform =
    JiraPlatform.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: fallback

private fun String.toAuthScheme(fallback: JiraAuthScheme): JiraAuthScheme =
    JiraAuthScheme.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: fallback

private fun ApplicationConfig.string(path: String, fallback: String): String =
    propertyOrNull(path)?.getString() ?: fallback

private fun ApplicationConfig.int(path: String, fallback: Int): Int =
    propertyOrNull(path)?.getString()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

private fun ApplicationConfig.durationSeconds(path: String, fallback: Duration): Duration =
    propertyOrNull(path)?.getString()?.toLongOrNull()?.let(Duration::ofSeconds) ?: fallback
