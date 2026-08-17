// Turns a display name into a small avatar chip's initials and a stable colour, for the Comment
// column's compact chip and the thread panel's note headers (docs/req-review-comment-threads.md
// §5 redesign). Pure and presentation-agnostic on purpose: `AuthorAvatar` is the only consumer of
// the colour today, but the derivation itself carries no Angular dependency.

// An outlined chip, never filled (CLAUDE.md §8 rule 3: colour is a rail or a rule here, not a
// background) — so the palette only ever supplies a border/text colour. Kept inside the Airbus
// blue family rather than a hash into arbitrary hues, since these chips sit beside the Tier-2
// accent (`--sec-highlight-meta`) that already owns "this is application data" for the column.
const AVATAR_PALETTE = [
  'var(--sec-blue-mid)',
  'var(--sec-blue-deep)',
  'var(--sec-blue-light)',
  'var(--sec-grey-blue)',
  'var(--sec-blue)',
] as const;

/** First letter of the first two words; the first two letters of a one-word name. */
export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return '';
  }
  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase();
  }
  return (words[0][0] + words[1][0]).toUpperCase();
}

/** Stable per name — the same reviewer gets the same colour everywhere, in this session and the
 *  next, without storing anything (R2: nothing derivable belongs in the graph). */
export function avatarColorOf(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i += 1) {
    hash = (hash * 31 + name.charCodeAt(i)) | 0;
  }
  return AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length];
}
