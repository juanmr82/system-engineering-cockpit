package com.sec.config

/**
 * Makes `-config=<path>` an **overlay** on the packaged `application.yaml` rather than a
 * replacement for it.
 *
 * Ktor's `EngineMain` accepts `-config=` already, which is why this project adds no `-c` flag of
 * its own (`docs/REFACTOR_BACKEND.md` item 6). What it does *not* do is merge: given one
 * `-config=`, the packaged file is dropped entirely, so a deployment file omitting the `ktor:`
 * block dies with *"Neither port nor sslPort specified"*. That would make every operator's file
 * carry `com.sec.ApplicationKt.module` — the module's fully-qualified Kotlin function name, which
 * is an implementation detail no one deploying this should have to know, and which becomes wrong
 * the day the class is renamed.
 *
 * Ktor *does* merge when `-config=` is repeated: the paths are collected and handed to
 * `ConfigLoader.loadAll`, which merges them key by key with the **last** one winning. So the whole
 * fix is to put the packaged file first. `-config=application.yaml` resolves from the classpath —
 * and, verified, is not shadowed by a file of that name in the working directory.
 *
 * The result: a deployment file states only what its environment changes.
 *
 * ```
 * java -jar backend-all.jar -config=/etc/sec/sec.yaml
 * ```
 * ```yaml
 * # /etc/sec/sec.yaml — no ktor: block needed
 * neo4j:
 *   uri: "bolt://db.internal:7687"
 * ```
 *
 * A pure function on the argument array, so it is unit-tested without launching a server, and so
 * `EngineMain` keeps ownership of every other flag it understands — `-port=`, `-host=`, and the
 * per-key `-P:neo4j.uri=…` override that a container uses instead of a file.
 */
internal object ConfigArgs {

    /** The packaged configuration, resolved from the classpath. */
    const val PACKAGED: String = "application.yaml"

    private const val FLAG = "-config="

    /**
     * Returns [args] with the packaged configuration inserted ahead of the first `-config=`.
     *
     * Untouched when no `-config=` is present — `EngineMain` already loads the packaged file in
     * that case, and prepending would only make the default path load it twice.
     *
     * Untouched when the caller has already named it, so an operator who wants to control the
     * merge order by hand keeps that ability and never gets a duplicate.
     */
    fun withPackagedDefaults(args: Array<String>): Array<String> {
        val first = args.indexOfFirst { it.startsWith(FLAG) }
        if (first < 0 || args.any { it == FLAG + PACKAGED }) {
            return args
        }
        return args.copyOfRange(0, first) + (FLAG + PACKAGED) + args.copyOfRange(first, args.size)
    }
}
