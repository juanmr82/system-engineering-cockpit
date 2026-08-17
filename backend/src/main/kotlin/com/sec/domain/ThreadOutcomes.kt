package com.sec.domain

// Comment threads (docs/req-review-comment-threads.md). Every reply is its own one-gesture-one-
// request write (R7's ordinary rule, not the batch exception REQ_REVIEW.md §9.1 used to carve
// out) — so there is no batch outcome here the way SaveCommentsOutcome used to be one.

/** One message in a thread — root or reply, read back after a write or listed for the panel. */
public data class ThreadNote(
    public val metaId: String,
    public val text: String,
    /** The root's `__metaId` this note replies to; null for the root itself. */
    public val replyTo: String?,
    /** Root only; always null on a reply (`docs/req-review-comment-threads.md` §2.1). */
    public val resolved: Boolean?,
    public val authorName: String,
    public val createdAt: String,
    public val updatedAt: String,
)

public sealed interface PostNoteOutcome {
    /** The anchor item does not exist, or this caller may not see it (R8: both look the same). */
    public data object ItemNotFound : PostNoteOutcome

    public data class Posted(public val note: ThreadNote) : PostNoteOutcome
}

public sealed interface ResolveThreadOutcome {
    /** Covers "no such note", "not visible", and "this ref names a reply, not a root" — all three
     *  are the same "nothing to act on" a 404 already means (R8). */
    public data object NotFound : ResolveThreadOutcome

    public data class Resolved(public val note: ThreadNote) : ResolveThreadOutcome
}

public sealed interface DeleteThreadOutcome {
    public data object NotFound : DeleteThreadOutcome

    public data object Deleted : DeleteThreadOutcome
}
