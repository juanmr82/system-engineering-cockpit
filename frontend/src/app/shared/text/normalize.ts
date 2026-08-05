// Case- and accent-insensitive search text. One copy, used by every search box in the app: what
// the user sees is what gets searched, and a DOORS name full of umlauts must still be findable
// from a keyboard without them.
//
// The combining-mark range is written escaped on purpose. Spelled with the literal characters it
// is invisible in a diff and easy to mangle in transit — it has been mangled twice in this repo.
export function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase();
}

/** True when `term` is empty, or appears anywhere in `haystack` ignoring case and accents. */
export function matches(haystack: string, term: string): boolean {
  const needle = normalize(term.trim());
  return needle.length === 0 || normalize(haystack).includes(needle);
}
