package com.sec.importer

/**
 * What one execution of an importer was *asked* to do, when that is more than "run".
 *
 * ## Why this exists
 *
 * Every importer built before this one is self-driving: JIRA reads its host from configuration and
 * its project list from the graph, so `POST /import/jira/runs` needs no body and a run needs no
 * input. An importer fed by an uploaded file is not self-driving — the file *is* the input, it
 * arrives with the request that starts the run, and it cannot be fetched again afterwards.
 *
 * The alternative was a slot on the importer that the upload route filled in before calling
 * [ImportRunService.start]. That works exactly as long as nothing races it, which is the same
 * "works by coincidence" the JIRA importer's `RunState` note refuses for the same reason: the
 * per-importer mutex makes it true today and stops making it true the moment runs are scoped per
 * project or a second instance is registered. Handing the input to the run that will use it removes
 * the question.
 *
 * ## Why it is empty
 *
 * **Nothing in this package may name a source** ([ImportRun]). The framework's whole relationship
 * with a request is to carry it from `start` to [ImportContext.request] untouched; it never reads
 * one, so there is nothing for it to declare. An importer casts to its own type and throws on
 * anything else — a mismatch is a wiring error in this process, not a user's mistake, and it fails
 * the run with a message that says which importer got what.
 *
 * DOORS gets this for free when its export file moves in-process, which is the point of putting it
 * here rather than inventing a Windchill-shaped door.
 */
public interface ImportRequest
