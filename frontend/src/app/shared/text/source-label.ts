// A source's own display name, for the "Source" column every Access screen shows. `sourceId` is
// an internal identifier (`AccessContainment.sourceId`, matching `ImportJob.importerId` on the
// backend) — lower-case, chosen to be a stable key, never meant to be read as a label. The Access
// views print it raw today because there is only ever a handful of values; this is the one place
// that stops meaning "someone will fix the casing later."
//
// Named the way the rest of the app already spells these sources — JIRA's own sidenav group is
// "JIRA", Windchill's is "Windchill" (docs/features/access-control.md's own R5 table). A source
// this map does not know yet falls back to capitalising the first letter rather than throwing, so
// a new source lands readable before anyone remembers to add it here.
const SOURCE_LABELS: Readonly<Record<string, string>> = {
  doors: 'DOORS',
  jira: 'JIRA',
  windchill: 'Windchill',
};

export function sourceLabel(sourceId: string): string {
  return SOURCE_LABELS[sourceId] ?? (sourceId.charAt(0).toUpperCase() + sourceId.slice(1));
}
