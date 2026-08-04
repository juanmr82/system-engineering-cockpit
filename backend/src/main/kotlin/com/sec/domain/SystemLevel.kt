package com.sec.domain

// The closed system-level vocabulary (docs/features/requirements-modules.md §4.1). This enum is
// this feature's slice of the R5 alias map: the label text is never stored on the
// :__Classification node (only `code` is), so it can change here without a data migration.
public enum class SystemLevel(public val code: String, public val label: String) {
    L0("L0", "L0 – Customer"),
    L1("L1", "L1 – System of Systems"),
    L2("L2", "L2 – Segment"),
    L3("L3", "L3 – Subsystem"),
    L4("L4", "L4 – Component"),
    ;

    public companion object {
        public fun fromCode(code: String): SystemLevel? = entries.firstOrNull { it.code == code }
    }
}
