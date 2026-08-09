/// <reference lib="webworker" />

// The ELK layout worker (docs/REQ_BREAKDOWN_GRAPH_VIEW §4.2).
//
// One import and nothing else, deliberately. `elk-worker.min.js` **is** the worker: it detects a
// worker context — `document` undefined, `self` defined — and installs its own `onmessage` handler
// on import. This file exists only so the bundler emits it as a worker chunk with a URL we can
// hand to `elk-api`'s `workerFactory`.
//
// The obvious-looking alternative does not work, and fails in a way worth recording: importing
// `elk.bundled.js` here and calling `new ELK()` looks like it should run ELK in-thread on the
// worker, which is exactly what we want. It cannot. `elk.bundled.js`'s in-thread path constructs
// `require('./elk-worker.min.js').Worker` — and in a worker context that module took the
// self-install branch instead of the export branch, so there is no `Worker` on it. The result is
// `TypeError: _Worker is not a constructor`, thrown inside the worker, surfacing as a layout that
// never completes.
//
// So the split is: the pure functions (`buildElkGraph`, `readElkResult`, `compressBands`) run on
// the main thread, where they cost microseconds, and the layered layout itself — the part that is
// actually expensive at 300 nodes — runs here.
import 'elkjs/lib/elk-worker.min.js';
