/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.doc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The names a platform lookup scanned, and the "not found" banner built from them.
 *
 * <p>It exists because the old banner listed the FIRST 30 names the provider happened to hand out
 * and called them "Available types" - and on a metadata-aware platform those first 30 are exactly
 * the names the lookup could not resolve. An answer that says "Type not found: CatalogObject" and
 * then lists {@code CatalogObject} among the available types sends a caller round a loop of
 * equivalent retries (issue #355). So a name earns its place twice: it is only COLLECTED if the
 * lookup would resolve it, and only PRINTED if it still passes {@link #resolvable} - a real
 * resolution attempt, affordable because it runs on the few dozen names about to be shown rather
 * than on the whole vocabulary. The index also counts everything it saw (a bare "first 30 ... more
 * available" hid the scale) and puts the names CLOSE to what was asked first - a miss is far more
 * often a spelling than a wrong concept.
 *
 * <p>Fed while the single scan over the provider's descriptions runs, so the banner costs no second
 * pass. Pure string logic with no platform dependency, hence unit-testable.
 */
final class PlatformNameIndex
{
    /** How many names the banner lists as a sample of what is available. */
    private static final int SAMPLE_LIMIT = 30;

    /**
     * How many candidates are kept for the printed list. Far more than {@link #SAMPLE_LIMIT},
     * because a candidate that fails the resolvability check at render time is dropped and the list
     * has to stay full - and a run of failures at the head of the scan must not be able to exhaust
     * the pool while thousands of good names sit behind it. Names are short strings; holding them
     * costs nothing next to the resolution that {@link #VERIFY_ATTEMPT_LIMIT} actually bounds.
     */
    private static final int CANDIDATE_LIMIT = 2000;

    /**
     * How many candidates may be RESOLVED while filling the printed list. This, not the pool size,
     * is the real cost bound: each attempt loads a platform resource.
     */
    private static final int VERIFY_ATTEMPT_LIMIT = SAMPLE_LIMIT * 4;

    /** How many "did you mean" candidates the banner offers. */
    private static final int SUGGESTION_LIMIT = 8;

    /** How many candidates each suggestion bucket holds, so filtering still leaves a full list. */
    private static final int SUGGESTION_CANDIDATE_LIMIT = SUGGESTION_LIMIT * 3;

    /** How many single-character edits a name may be from the query and still be offered. */
    private static final int MAX_TYPO_DISTANCE = 2;

    /**
     * The shortest query eligible for typo matching. Within two edits of a four-letter query sits a
     * good part of the vocabulary, so below this the suggestions would be noise, not help.
     */
    private static final int MIN_TYPO_QUERY_LENGTH = 5;

    /**
     * Every character a consumer may read as a line break, not just CR/LF.
     *
     * <p>"Line" has to be taken as widely as the READERS take it. A Java {@code BufferedReader}
     * breaks on CR/LF only, but the banner is parsed elsewhere - the tool's own e2e checks are
     * Python, and {@code str.splitlines()} additionally breaks on VT, FF, the three information
     * separators, NEL and the Unicode line/paragraph separators. Flattening only CR/LF would leave
     * exactly those consumers a working forgery, so the whole {@code splitlines} set is covered.
     * Written as {@code \\uXXXX} escapes deliberately: a raw U+2028 in a Java source file is itself
     * a line terminator to the compiler.
     */
    private static final Pattern LINE_BREAK =
        Pattern.compile("[\\r\\n\\u000B\\f\\u001C\\u001D\\u001E\\u0085\\u2028\\u2029]+"); //$NON-NLS-1$

    private final String query;

    /**
     * The last word on whether a name really answers a lookup - applied to the handful of names the
     * banner is about to PRINT, never to the whole vocabulary.
     *
     * <p>Cheap structural checks decide what to collect ({@code isDocumentable}), but a check that
     * cannot lie has to actually resolve the name, and resolving thousands of descriptions on the
     * UI thread for one error message is not affordable. Verifying only what is printed costs a few
     * dozen resolutions and makes the printed list PROVABLY usable, which is the whole point: a
     * caller must be able to take any name off this list and query it.
     */
    private final Predicate<String> resolvable;

    private final List<String> samples = new ArrayList<>();

    /** Candidates that START with the query - the likeliest correction, so they are offered first. */
    private final List<String> prefixHits = new ArrayList<>();

    /** Candidates the query contains, or that it qualifies with a dot ({@code CatalogObject.X}). */
    private final List<String> otherHits = new ArrayList<>();

    /**
     * Candidates within {@link #MAX_TYPO_DISTANCE} edits of the query - the last resort, offered only
     * when nothing above matched, so a plain misspelling still gets an answer.
     */
    private final List<String> typoHits = new ArrayList<>();

    /**
     * The names already counted. The platform genuinely publishes the same name twice - two distinct
     * types can share one Russian name - so without this the total over-reports and one name can eat
     * two suggestion slots.
     */
    private final Set<String> seen = new HashSet<>();

    private int total;

    /** Why the query failed - see {@link MissReason}. */
    private MissReason missReason = MissReason.UNKNOWN_NAME;

    /**
     * Why a lookup came back empty. The three are NOT interchangeable: each one sends the caller
     * somewhere different, and answering with the wrong one is the class of defect this whole file
     * exists to remove.
     */
    enum MissReason
    {
        /** The platform does not publish this name at all. Offer the vocabulary. */
        UNKNOWN_NAME,
        /**
         * A type SET the platform does publish, which unions other types and declares no members of
         * its own ({@code AnyRef} / {@code ЛюбаяСсылка}). Nothing to render, ever - a retry cannot
         * help, so send the caller to the sets it unions.
         */
        DOCUMENTS_NOTHING,
        /**
         * A type SET that DOES name a documented target, registered in the provider, which still
         * could not be resolved. Nothing is known to be missing from the platform here - the model
         * may simply not be fully loaded - so a retry is a reasonable next step.
         */
        TARGET_UNRESOLVED
    }

    /**
     * An index that trusts what it is fed. For a caller that has no way to re-check a name.
     *
     * @param query the name that was looked up (used to rank the suggestions)
     */
    PlatformNameIndex(String query)
    {
        this(query, null);
    }

    /**
     * @param query the name that was looked up (used to rank the suggestions)
     * @param resolvable the final check applied to each name before it is PRINTED, or {@code null}
     *            to print what was collected
     */
    PlatformNameIndex(String query, Predicate<String> resolvable)
    {
        this.query = query == null ? "" : query.trim(); //$NON-NLS-1$
        this.resolvable = resolvable;
    }

    /**
     * Records one name the lookup WOULD resolve. Names that resolve to nothing must not be passed:
     * the whole point of the index is that everything it lists is a name a caller can actually ask
     * for.
     *
     * <p>A name carrying a line break is refused HERE, at intake, rather than filtered later. The
     * banner cannot advertise it (see {@link #verified}), so it is not a name that answers - the
     * same category as a type set that documents nothing. Filtering it downstream instead would
     * leave it holding a candidate slot, a suggestion slot, and a place in the total, and worse:
     * a poisoned name matching the query by prefix makes the strong suggestion bucket non-empty,
     * which SUPPRESSES the typo bucket that held the real answer.
     *
     * @param name the resolvable name, ignored when blank
     */
    void accept(String name)
    {
        if (name == null || name.isEmpty() || LINE_BREAK.matcher(name).find()
            || !seen.add(name.toLowerCase(Locale.ROOT)))
        {
            return;
        }
        total++;
        if (samples.size() < CANDIDATE_LIMIT)
        {
            samples.add(name);
        }
        collectSuggestion(name);
    }

    /**
     * Records that the query named a type set the platform documents NOTHING for - it unions other
     * types and declares no members of its own. Such a name is deliberately absent from
     * {@link #accept} (it is not a name that answers), so without this the caller would be told it
     * does not exist at all, which is a different - and wrong - diagnosis.
     */
    void markDocumentsNothing()
    {
        // Never downgrade TARGET_UNRESOLVED: see markTargetUnresolved.
        if (missReason == MissReason.UNKNOWN_NAME)
        {
            missReason = MissReason.DOCUMENTS_NOTHING;
        }
    }

    /**
     * Records that the query named a type set whose documented target IS registered and still could
     * not be resolved. Distinct from {@link #markDocumentsNothing()} on purpose: only one of the two
     * is entitled to tell the caller the platform has nothing to say about this name.
     *
     * <p>It also OVERRIDES a {@code DOCUMENTS_NOTHING} already recorded, in the rare case where one
     * name matched two descriptions and each failed differently. "The platform documents nothing for
     * it" is an assertion about the platform; "we could not reach it" is an admission about us. When
     * one registration demonstrably named a target, the first statement is false, and a false
     * explanation is exactly what this file exists to stop shipping.
     */
    void markTargetUnresolved()
    {
        missReason = MissReason.TARGET_UNRESOLVED;
    }

    /** @return why the lookup failed; {@link MissReason#UNKNOWN_NAME} unless something marked it */
    MissReason missReason()
    {
        return missReason;
    }

    /** @return how many resolvable names the scan saw */
    int total()
    {
        return total;
    }

    /** @return the "did you mean" candidates, best first, capped at {@link #SUGGESTION_LIMIT} */
    List<String> suggestions()
    {
        // The typo bucket is a LAST resort: a name related to the query by substring or qualification
        // is a better guess than one that merely looks similar, and mixing them would bury it.
        //
        // "Last resort" has to be decided on what the strong bucket YIELDS, not on what it contains.
        // Deciding on its raw contents let a single unresolvable name that happens to share the
        // query's prefix suppress the whole typo fallback: query ValueTabel, one broken
        // ValueTabelBroken in the provider, and the resolvable ValueTable never gets offered - the
        // banner drops its "Did you mean" exactly where a misspelling needed one. The second pass
        // costs a verification round only when the first produced nothing at all.
        List<String> strong = new ArrayList<>(prefixHits);
        strong.addAll(otherHits);
        List<String> best = verified(strong, SUGGESTION_LIMIT);
        return best.isEmpty() ? verified(typoHits, SUGGESTION_LIMIT) : best;
    }

    /**
     * The first {@code limit} names of {@code candidates} that pass {@link #resolvable}. This is the
     * one place a name becomes something the banner will show, so it is the one place the promise
     * "every name here answers a lookup" is kept.
     *
     * <p>A name carrying a line break is DROPPED rather than flattened, and unlike the echoed query
     * that is not a matter of taste. The query is echoed to show the caller what was asked, so
     * flattening it keeps it useful; a listed name is a name the caller is invited to copy and
     * query, so a flattened one would be a different string that no longer resolves - breaking the
     * exact promise the list makes. {@link #accept} already refuses such names, so this is the
     * backstop on the choke point where the promise is actually made, for any future feed.
     */
    /**
     * The sample pool reordered so that a bounded number of attempts SPANS it, instead of walking
     * its head.
     *
     * <p>{@link #VERIFY_ATTEMPT_LIMIT} is a real cost bound - each attempt loads a platform resource
     * on the UI thread - so it cannot simply be raised to "however many the pool holds": that is
     * thousands of loads for one error message. But taking the candidates in provider order spends
     * the whole budget on the first {@code VERIFY_ATTEMPT_LIMIT} entries, and unresolvable entries
     * arrive CLUSTERED - one malformed package, one version-incompatible group, all published
     * together. A single such block at the head then hides every good name behind it and the banner
     * reports that nothing checked could be resolved, while thousands of usable names sat unvisited.
     *
     * <p>Striding by {@code ceil(pool / budget)} makes the FIRST pass visit about {@code budget}
     * entries spread across the entire pool, so the search is never confined to the head and no
     * contiguous block can monopolise it. The later offsets fill in the gaps if the budget allows.
     * Exactly the same number of attempts; only their placement changes.
     *
     * <p>Order is meaningless for this list - it is a sample of what is available - so scattering it
     * costs nothing. It is applied ONLY to the sample: in {@link #suggestions()} the order IS the
     * ranking (best guess first), and scattering that would be a downgrade.
     *
     * @param pool the candidates, in the order the provider published them
     * @param budget how many of them may actually be resolved
     * @return the same names, ordered so a budget-sized prefix spans the pool
     */
    private static List<String> strided(List<String> pool, int budget)
    {
        int stride = (pool.size() + Math.max(1, budget) - 1) / Math.max(1, budget);
        if (stride <= 1)
        {
            return pool;
        }
        List<String> spread = new ArrayList<>(pool.size());
        for (int offset = 0; offset < stride; offset++)
        {
            for (int i = offset; i < pool.size(); i += stride)
            {
                spread.add(pool.get(i));
            }
        }
        return spread;
    }

    private List<String> verified(List<String> candidates, int limit)
    {
        List<String> kept = new ArrayList<>();
        int attempts = 0;
        for (String candidate : candidates)
        {
            if (kept.size() >= limit || attempts >= VERIFY_ATTEMPT_LIMIT)
            {
                break;
            }
            if (LINE_BREAK.matcher(candidate).find())
            {
                continue;
            }
            attempts++;
            if (accepts(candidate))
            {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /**
     * Builds the soft "not found" banner. It begins with the {@code "Error: "} token that
     * {@link PlatformDocumentationService#isNotFoundBanner} recognises, so the tool can turn it into
     * a real {@code ToolResult.error} while keeping the actionable body.
     *
     * @param subject the not-found phrase incl. trailing separator (e.g. {@code "Type not found: "})
     * @param name the looked-up name, appended after {@code subject}
     * @param itemsLabel the plural noun for the heading (e.g. {@code "types"})
     * @param hint a closing sentence naming the next step, or {@code null} for none
     * @return the rendered banner
     */
    String buildNotFoundBanner(String subject, String name, String itemsLabel, String hint)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Error: ").append(subject).append(oneLine(name)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> suggestions = suggestions();
        if (!suggestions.isEmpty())
        {
            sb.append("Did you mean: ").append(String.join(", ", suggestions)).append("?\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        if (total == 0)
        {
            sb.append("(no ").append(itemsLabel).append(" found - provider may be empty)\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return sb.toString();
        }

        List<String> listed = verified(strided(samples, VERIFY_ATTEMPT_LIMIT), SAMPLE_LIMIT);
        if (listed.isEmpty())
        {
            // The provider is NOT empty - it published `total` names - but not one of the ones
            // tried could be resolved. Saying "may be empty" here would be a false diagnosis, and
            // dropping the hint would leave the caller with no next step at the moment it needs one
            // most. State what actually happened and still point somewhere.
            sb.append("(").append(total).append(" candidate ").append(itemsLabel) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" were found, but none of the ones checked could be resolved - the " //$NON-NLS-1$
                    + "platform model may not be fully loaded yet)\n"); //$NON-NLS-1$
            if (hint != null && !hint.isEmpty())
            {
                sb.append('\n').append(hint).append('\n');
            }
            return sb.toString();
        }

        // "N of TOTAL" rather than the old "first N ... (more available)": a caller - and an agent
        // in particular - needs to know whether it is looking at a sample of 30 or at everything.
        // TOTAL is the count of names this lookup CONSIDERED - deduplicated, and already without
        // the ones that answer nothing (AnyRef) - not everything the provider publishes, so it is
        // not called "published". Claiming the wider number would be the same kind of small lie
        // this banner exists to remove.
        sb.append("Available ").append(itemsLabel).append(" (").append(listed.size()) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" shown of ").append(total).append(" candidate names, English and Russian; ") //$NON-NLS-1$ //$NON-NLS-2$
            .append("every name listed here resolves):\n"); //$NON-NLS-1$
        for (String item : listed)
        {
            sb.append("- ").append(item).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (hint != null && !hint.isEmpty())
        {
            sb.append('\n').append(hint).append('\n');
        }
        return sb.toString();
    }

    /**
     * The looked-up name, flattened to a single line, for echoing back into the banner.
     *
     * <p>The name comes straight from the caller, and the banner is a LINE-STRUCTURED document
     * whose {@code "- "} bullets carry a promise ("every name listed here resolves") that
     * consumers parse. A name containing newlines could therefore forge its own bullet and get a
     * non-resolving name counted under that promise - the tool's own e2e parser reads every
     * {@code "- "} line. Echoing the bad value is worth keeping; letting it choose the shape of
     * the answer is not.
     */
    private static String oneLine(String name)
    {
        return name == null ? "" : LINE_BREAK.matcher(name).replaceAll(" ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether one candidate survives the resolvability check. A candidate that THROWS is simply
     * dropped: the verifier resolves arbitrary provider entries sampled next to the query, and one
     * malformed or version-incompatible description among them must cost that name its place in the
     * list - not abort the whole answer and turn every miss into a failed request.
     */
    private boolean accepts(String candidate)
    {
        if (resolvable == null)
        {
            return true;
        }
        try
        {
            return resolvable.test(candidate);
        }
        catch (RuntimeException | LinkageError e) // NOSONAR one bad entry must not sink the banner
        {
            return false;
        }
    }

    /**
     * Sorts a name into the suggestion buckets when it is close to the query: it starts with the
     * query, contains it, or the query QUALIFIES it - {@code CatalogObject.Currencies} names a
     * concrete metadata type whose documentation lives on {@code CatalogObject}. The qualifying
     * direction demands the dot: a bare substring test let a two-syllable type name like
     * {@code Type} be offered for any query that happened to contain the word.
     */
    private void collectSuggestion(String name)
    {
        if (query.isEmpty())
        {
            return;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        if (lowerName.equals(lowerQuery))
        {
            // The caller is being told this name was NOT found; offering it back would be the very
            // loop this class exists to break.
            return;
        }
        if (lowerName.startsWith(lowerQuery))
        {
            // Each bucket is capped SEPARATELY, and the scan goes on regardless. Stopping the whole
            // collection once the prefix bucket filled was the same mistake as picking the bucket by
            // its raw contents, one step earlier: the weaker buckets are the FALLBACK for when every
            // strong candidate fails verification, and a scan that returned here left them empty for
            // good - 24 unresolvable prefix matches at the head of the provider's output and the
            // banner had no "Did you mean" to give, however many usable names came later. The work
            // this used to skip is two lowercase comparisons; the resolution attempts are what cost
            // anything, and those are bounded elsewhere.
            if (prefixHits.size() < SUGGESTION_CANDIDATE_LIMIT)
            {
                prefixHits.add(name);
            }
        }
        else if ((lowerName.contains(lowerQuery) || lowerQuery.startsWith(lowerName + '.'))
            && otherHits.size() < SUGGESTION_CANDIDATE_LIMIT)
        {
            otherHits.add(name);
        }
        else if (typoHits.size() < SUGGESTION_CANDIDATE_LIMIT && isTypoOf(lowerName, lowerQuery))
        {
            typoHits.add(name);
        }
    }

    /**
     * Whether {@code name} is within {@link #MAX_TYPO_DISTANCE} single-character edits of the query -
     * the misspelling case none of the substring rules catch ({@code ValueTabel} -> {@code ValueTable}
     * shares no useful prefix and contains nothing).
     *
     * <p>Gated on the length difference first, which is the whole reason this is affordable: a name
     * that cannot possibly be within the bound is rejected without any character work, so the scan
     * stays a few string compares per name rather than a matrix per name.
     */
    private static boolean isTypoOf(String name, String query)
    {
        if (Math.abs(name.length() - query.length()) > MAX_TYPO_DISTANCE
            || query.length() < MIN_TYPO_QUERY_LENGTH)
        {
            return false;
        }
        return editDistanceWithin(name, query, MAX_TYPO_DISTANCE);
    }

    /**
     * Bounded Levenshtein: {@code true} when {@code a} and {@code b} are at most {@code max} edits
     * apart. Abandons a row as soon as every cell in it exceeds {@code max}, so a distant pair costs
     * a fraction of the full matrix.
     */
    private static boolean editDistanceWithin(String a, String b, int max)
    {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
        {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            current[0] = i;
            int rowBest = current[0];
            for (int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowBest = Math.min(rowBest, current[j]);
            }
            if (rowBest > max)
            {
                return false;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()] <= max;
    }
}
