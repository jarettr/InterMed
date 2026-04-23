package org.intermed.core.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full SemVer constraint parser and evaluator (ТЗ 3.2.1, Requirement 2).
 *
 * <h3>Supported constraint syntax</h3>
 * <pre>
 *   *  /  any              → always matches (wildcard)
 *   1.2.3  /  =1.2.3       → exact version
 *   >=1.2.3                → greater than or equal
 *   >1.2.3                 → strictly greater than
 *   <=1.2.3                → less than or equal
 *   &lt;1.2.3                 → strictly less than
 *   !=1.2.3                → not equal
 *   ^1.2.3                 → compatible release: >=1.2.3 &lt;2.0.0
 *   ~1.2.3  /  ~=1.2.3     → approx equal:     >=1.2.3 &lt;1.3.0
 *   1.2.*  /  1.*          → wildcard range
 *   1.0.0 - 2.0.0          → inclusive range:   >=1.0.0 &lt;=2.0.0
 *   >=1.0.0 &lt;2.0.0          → space-separated conjunction (AND)
 * </pre>
 *
 * <p>Version strings are parsed with best-effort SemVer:
 * build metadata ({@code +...}) is stripped, pre-release identifiers
 * ({@code -alpha}, {@code -rc.1}) are preserved and compared numerically
 * where possible.  Non-parseable version strings default to {@code 0.0.0}.
 *
 * <p>Instances are immutable; obtain one via {@link #parse(String)}.
 */
public final class SemVerConstraint {

    private static final Pattern RANGE_PATTERN =
        Pattern.compile("^(.+?)\\s+-\\s+(.+)$");

    // -------------------------------------------------------------------------
    // Parsed representation
    // -------------------------------------------------------------------------

    /** Ordered list of atomic predicates combined with AND semantics. */
    private final List<Predicate> predicates;
    private final String raw;

    private SemVerConstraint(List<Predicate> predicates, String raw) {
        this.predicates = Collections.unmodifiableList(predicates);
        this.raw = raw;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses the constraint string and returns an evaluable {@code SemVerConstraint}.
     * Never throws — unrecognised tokens are treated as exact-version comparisons.
     *
     * @param constraint The constraint string (may be {@code null} or blank).
     * @return A non-null constraint object.
     */
    public static SemVerConstraint parse(String constraint) {
        if (constraint == null || constraint.isBlank()
                || constraint.equals("*") || constraint.equalsIgnoreCase("any")) {
            return new SemVerConstraint(List.of(), constraint != null ? constraint : "*");
        }

        String trimmed = constraint.trim();

        // ── Maven/Forge interval: [1.0,2.0), [47,), (,2.0], [1.0] ───────────
        SemVerConstraint mavenRange = parseMavenRange(trimmed, constraint);
        if (mavenRange != null) {
            return mavenRange;
        }

        // ── Range: "1.0.0 - 2.0.0" ──────────────────────────────────────────
        Matcher rangeMatcher = RANGE_PATTERN.matcher(trimmed);
        if (rangeMatcher.matches()) {
            SemVer lower = SemVer.parse(rangeMatcher.group(1).trim());
            SemVer upper = SemVer.parse(rangeMatcher.group(2).trim());
            List<Predicate> preds = new ArrayList<>();
            preds.add(new ComparisonPredicate(Op.GTE, lower));
            preds.add(new ComparisonPredicate(Op.LTE, upper));
            return new SemVerConstraint(preds, constraint);
        }

        // ── Conjunction: space-separated tokens → AND of each ────────────────
        String[] tokens = trimmed.split("\\s+");
        List<Predicate> predicates = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            predicates.add(parseToken(token));
        }
        return new SemVerConstraint(predicates, constraint);
    }

    private static SemVerConstraint parseMavenRange(String trimmed, String original) {
        if (trimmed.length() < 2) {
            return null;
        }

        char lowerBracket = trimmed.charAt(0);
        char upperBracket = trimmed.charAt(trimmed.length() - 1);
        boolean bracketed = (lowerBracket == '[' || lowerBracket == '(')
            && (upperBracket == ']' || upperBracket == ')');
        if (!bracketed) {
            return null;
        }

        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (!body.contains(",")) {
            if (body.isBlank()) {
                return new SemVerConstraint(List.of(), original);
            }
            return new SemVerConstraint(List.of(
                new ComparisonPredicate(Op.EQ, SemVer.parse(body))
            ), original);
        }

        String[] bounds = body.split(",", -1);
        if (bounds.length != 2) {
            return null;
        }

        String lower = bounds[0].trim();
        String upper = bounds[1].trim();
        List<Predicate> predicates = new ArrayList<>(2);

        if (!lower.isEmpty()) {
            predicates.add(new ComparisonPredicate(
                lowerBracket == '[' ? Op.GTE : Op.GT,
                SemVer.parse(lower)
            ));
        }
        if (!upper.isEmpty()) {
            predicates.add(new ComparisonPredicate(
                upperBracket == ']' ? Op.LTE : Op.LT,
                SemVer.parse(upper)
            ));
        }

        return new SemVerConstraint(predicates, original);
    }

    /**
     * Returns {@code true} if {@code versionStr} satisfies all predicates in
     * this constraint.
     *
     * @param versionStr A version string such as {@code "1.2.3"} or
     *                   {@code "1.20.1-alpha.1"}.
     */
    public boolean matches(String versionStr) {
        if (predicates.isEmpty()) return true;   // wildcard
        SemVer v = SemVer.parse(versionStr);
        for (Predicate p : predicates) {
            if (!p.test(v)) return false;
        }
        return true;
    }

    /** The original constraint string passed to {@link #parse(String)}. */
    public String getRaw() { return raw; }

    /**
     * Compares two version strings with the same best-effort SemVer semantics
     * used by the resolver.
     */
    public static int compareVersions(String left, String right) {
        return SemVer.parse(left).compareTo(SemVer.parse(right));
    }

    @Override
    public String toString() { return raw; }

    // -------------------------------------------------------------------------
    // Token parser
    // -------------------------------------------------------------------------

    private static Predicate parseToken(String token) {
        // Caret: ^1.2.3 → >=1.2.3 <(next-breaking).0.0
        if (token.startsWith("^")) {
            SemVer ver = SemVer.parse(token.substring(1));
            SemVer upper = caretUpper(ver);
            return new RangePredicate(ver, upper, true, false); // [ver, upper)
        }
        // Tilde: ~1.2.3 or ~=1.2.3 → >=1.2.3 <1.3.0
        if (token.startsWith("~=")) {
            SemVer ver = SemVer.parse(token.substring(2));
            return new RangePredicate(ver, new SemVer(ver.major, ver.minor + 1, 0), true, false);
        }
        if (token.startsWith("~")) {
            SemVer ver = SemVer.parse(token.substring(1));
            return new RangePredicate(ver, new SemVer(ver.major, ver.minor + 1, 0), true, false);
        }
        // Wildcard: 1.2.* or 1.*
        if (token.endsWith(".*") || token.endsWith(".x")) {
            return parseWildcardToken(token);
        }
        // Comparison operators (ordered longest-match first)
        if (token.startsWith(">=")) return new ComparisonPredicate(Op.GTE, SemVer.parse(token.substring(2)));
        if (token.startsWith("<=")) return new ComparisonPredicate(Op.LTE, SemVer.parse(token.substring(2)));
        if (token.startsWith("!=")) return new ComparisonPredicate(Op.NEQ, SemVer.parse(token.substring(2)));
        if (token.startsWith(">"))  return new ComparisonPredicate(Op.GT,  SemVer.parse(token.substring(1)));
        if (token.startsWith("<"))  return new ComparisonPredicate(Op.LT,  SemVer.parse(token.substring(1)));
        if (token.startsWith("="))  return new ComparisonPredicate(Op.EQ,  SemVer.parse(token.substring(1)));

        // Bare version string — exact match
        return new ComparisonPredicate(Op.EQ, SemVer.parse(token));
    }

    private static Predicate parseWildcardToken(String token) {
        // Strip trailing .*  or .x
        String base = token.replaceAll("[.*x]+$", "").replaceAll("\\.$", "");
        String[] parts = base.split("\\.");
        int major = parts.length > 0 ? parseIntSafe(parts[0]) : 0;
        int minor = parts.length > 1 ? parseIntSafe(parts[1]) : 0;

        SemVer lower, upper;
        if (parts.length == 1) {
            // "1.*" → [1.0.0, 2.0.0)
            lower = new SemVer(major, 0, 0);
            upper = new SemVer(major + 1, 0, 0);
        } else {
            // "1.2.*" → [1.2.0, 1.3.0)
            lower = new SemVer(major, minor, 0);
            upper = new SemVer(major, minor + 1, 0);
        }
        return new RangePredicate(lower, upper, true, false); // [lower, upper)
    }

    /** Computes the exclusive upper bound for a caret (^) constraint. */
    private static SemVer caretUpper(SemVer ver) {
        if (ver.major > 0)        return new SemVer(ver.major + 1, 0, 0);
        else if (ver.minor > 0)   return new SemVer(0, ver.minor + 1, 0);
        else                      return new SemVer(0, 0, ver.patch + 1);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // =========================================================================
    // Internal predicate types
    // =========================================================================

    private interface Predicate {
        boolean test(SemVer v);
    }

    private enum Op { EQ, NEQ, LT, LTE, GT, GTE }

    private record ComparisonPredicate(Op op, SemVer version) implements Predicate {
        @Override
        public boolean test(SemVer v) {
            int c = v.compareTo(version);
            return switch (op) {
                case EQ  -> c == 0;
                case NEQ -> c != 0;
                case LT  -> c < 0;
                case LTE -> c <= 0;
                case GT  -> c > 0;
                case GTE -> c >= 0;
            };
        }
    }

    /**
     * Half-open or closed range predicate.
     * {@code lowerInclusive}: true  → lower bound is >=, false → >
     * {@code upperInclusive}: true  → upper bound is <=, false → <
     */
    private record RangePredicate(SemVer lower, SemVer upper,
                                  boolean lowerInclusive,
                                  boolean upperInclusive) implements Predicate {
        @Override
        public boolean test(SemVer v) {
            int lo = v.compareTo(lower);
            int hi = v.compareTo(upper);
            boolean passLower = lowerInclusive ? lo >= 0 : lo > 0;
            boolean passUpper = upperInclusive ? hi <= 0 : hi < 0;
            return passLower && passUpper;
        }
    }

    // =========================================================================
    // SemVer value type
    // =========================================================================

    /**
     * A parsed semantic version with major, minor, patch, and optional
     * pre-release identifier.  Build metadata ({@code +...}) is ignored.
     */
    static final class SemVer implements Comparable<SemVer> {

        final int    major;
        final int    minor;
        final int    patch;
        /** Pre-release string (e.g. "alpha.1"), or {@code null} for a release. */
        final String preRelease;

        SemVer(int major, int minor, int patch) {
            this(major, minor, patch, null);
        }

        SemVer(int major, int minor, int patch, String preRelease) {
            this.major      = major;
            this.minor      = minor;
            this.patch      = patch;
            this.preRelease = preRelease;
        }

        /**
         * Parses a version string with best-effort SemVer semantics.
         * Build metadata ({@code +...}) is stripped.
         * Returns {@code 0.0.0} for blank or completely non-parseable input.
         */
        static SemVer parse(String raw) {
            if (raw == null || raw.isBlank()) return new SemVer(0, 0, 0);
            // Strip build metadata
            String s = raw.trim().split("\\+")[0];
            // Split pre-release
            String[] preSplit = s.split("-", 2);
            String numeric    = preSplit[0];
            String pre        = preSplit.length > 1 ? preSplit[1] : null;
            // Parse numeric parts
            String[] parts = numeric.split("\\.");
            int major = parts.length > 0 ? parseIntSafe(parts[0]) : 0;
            int minor = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
            int patch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
            return new SemVer(major, minor, patch, pre);
        }

        private static int parseIntSafe(String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }

        @Override
        public int compareTo(SemVer other) {
            int c = Integer.compare(major, other.major);
            if (c != 0) return c;
            c = Integer.compare(minor, other.minor);
            if (c != 0) return c;
            c = Integer.compare(patch, other.patch);
            if (c != 0) return c;
            // Release > pre-release (null preRelease = release)
            if (preRelease == null && other.preRelease == null) return 0;
            if (preRelease == null) return  1;   // this is release, other is pre
            if (other.preRelease == null) return -1; // this is pre, other is release
            // Both have pre-release: compare identifier by identifier
            return comparePreRelease(preRelease, other.preRelease);
        }

        private static int comparePreRelease(String a, String b) {
            String[] ap = a.split("\\.");
            String[] bp = b.split("\\.");
            int len = Math.max(ap.length, bp.length);
            for (int i = 0; i < len; i++) {
                String ai = i < ap.length ? ap[i] : "";
                String bi = i < bp.length ? bp[i] : "";
                // Numeric identifiers < string identifiers when compared cross-type
                boolean aNum = ai.matches("\\d+");
                boolean bNum = bi.matches("\\d+");
                int cmp;
                if (aNum && bNum) {
                    cmp = Integer.compare(Integer.parseInt(ai), Integer.parseInt(bi));
                } else if (aNum) {
                    cmp = -1; // numeric < string
                } else if (bNum) {
                    cmp = 1;
                } else {
                    cmp = ai.compareTo(bi);
                }
                if (cmp != 0) return cmp;
            }
            return Integer.compare(ap.length, bp.length); // more identifiers = higher pre-release
        }

        @Override
        public String toString() {
            String base = major + "." + minor + "." + patch;
            return preRelease != null ? base + "-" + preRelease : base;
        }
    }
}
