import { Injectable, signal } from '@angular/core';

// Tracks pending Tier-2 annotation edits (comments, mandatory-attribute rules, review
// outcomes) across every view, so the toolbar save action and its dirty badge have one source
// of truth. Wired now even though no view produces edits yet — retrofitting a global save
// across a dozen views later is far harder than stubbing it here (CLAUDE.md §9).
@Injectable({ providedIn: 'root' })
export class AnnotationStore {
  readonly pendingCount = signal(0);
  readonly isDirty = signal(false);
}
