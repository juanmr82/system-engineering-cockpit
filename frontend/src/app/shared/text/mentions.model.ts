// The mention syntax a comment's `text` may hold (docs/req-review-comment-threads.md §3.1):
// `@[display](kind:ref)`, where `kind` is `user` or `item`. Plain text with typed tokens, not rich
// text — no editor, no document model, just a regex parsed on read (§3.3).
//
// Display text is captured at insert time and never re-resolved: an item mention keeps reading
// what it said when written, even if the item is later renamed. The `ref` is what stays live.

export type TextSegment =
  | { readonly kind: 'text'; readonly value: string }
  | { readonly kind: 'mention'; readonly display: string; readonly target: 'user' | 'item'; readonly ref: string };

const MENTION = /@\[([^\]]+)\]\((user|item):([^)]+)\)/g;

/** Splits a note's `text` into plain runs and mention tokens, in order. */
export function parseMentions(text: string): TextSegment[] {
  const segments: TextSegment[] = [];
  let cursor = 0;

  for (const match of text.matchAll(MENTION)) {
    const start = match.index;
    if (start > cursor) {
      segments.push({ kind: 'text', value: text.slice(cursor, start) });
    }
    const [, display, target, ref] = match;
    segments.push({ kind: 'mention', display, target: target as 'user' | 'item', ref });
    cursor = start + match[0].length;
  }

  if (cursor < text.length) {
    segments.push({ kind: 'text', value: text.slice(cursor) });
  }
  return segments;
}
