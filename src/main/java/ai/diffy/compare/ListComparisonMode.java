package ai.diffy.compare;

/** How list/array fields are compared between left and right. */
public enum ListComparisonMode {
    /** Multiset + ordering detection (classic Diffy). O(n×m) on large lists. */
    LEGACY,
    /** Strict index-by-index comparison. O(n). */
    INDEXED
}
