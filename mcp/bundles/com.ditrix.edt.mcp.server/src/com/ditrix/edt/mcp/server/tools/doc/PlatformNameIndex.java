/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.doc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The names a platform lookup scanned, and the "not found" banner built from them.
 *
 * <p>It exists because the old banner listed the FIRST 30 names the provider happened to hand out
 * and called them "Available types" - and on a metadata-aware platform those first 30 are exactly
 * the names the lookup could not resolve. An answer that says "Type not found: CatalogObject" and
 * then lists {@code CatalogObject} among the available types sends a caller round a loop of
 * equivalent retries (issue #355). So the index is fed ONLY names the lookup would actually
 * resolve, it counts them all (a bare "first 30 ... more available" hid the scale), and it puts the
 * names CLOSE to what was asked first - a miss is far more often a spelling than a wrong concept.
 *
 * <p>Fed while the single scan over the provider's descriptions runs, so the banner costs no second
 * pass. Pure string logic with no platform dependency, hence unit-testable.
 */
final class PlatformNameIndex
{
    /** How many names the banner lists as a sample of what is available. */
    private static final int SAMPLE_LIMIT = 30;

    /** How many "did you mean" candidates the banner offers. */
    private static final int SUGGESTION_LIMIT = 8;

    private final String query;

    private final List<String> samples = new ArrayList<>();

    /** Candidates that START with the query - the likeliest correction, so they are offered first. */
    private final List<String> prefixHits = new ArrayList<>();

    /** Candidates the query contains, or that it qualifies with a dot ({@code CatalogObject.X}). */
    private final List<String> otherHits = new ArrayList<>();

    private int total;

    /** Set when the query DID name something the platform documents nothing for. */
    private String undocumentedLabel;

    /**
     * @param query the name that was looked up (used to rank the suggestions)
     */
    PlatformNameIndex(String query)
    {
        this.query = query == null ? "" : query.trim(); //$NON-NLS-1$
    }

    /**
     * Records one name the lookup WOULD resolve. Names that resolve to nothing must not be passed:
     * the whole point of the index is that everything it lists is a name a caller can actually ask
     * for.
     *
     * @param name the resolvable name, ignored when blank
     */
    void accept(String name)
    {
        if (name == null || name.isEmpty())
        {
            return;
        }
        total++;
        if (samples.size() < SAMPLE_LIMIT)
        {
            samples.add(name);
        }
        collectSuggestion(name);
    }

    /**
     * Records that the query DID name a known platform entry which simply carries no documentation -
     * a type SET that unions other types and declares no members of its own ({@code AnyRef} /
     * {@code ЛюбаяСсылка}). Such a name is deliberately absent from {@link #accept} (it is not a
     * name that answers), so without this the caller would be told it does not exist at all, which
     * is a different - and wrong - diagnosis.
     *
     * @param label the entry as it should be named back to the caller
     */
    void markUndocumented(String label)
    {
        this.undocumentedLabel = label;
    }

    /** @return {@code true} when the query named a known but undocumented entry */
    boolean isUndocumented()
    {
        return undocumentedLabel != null;
    }

    /** @return how many resolvable names the scan saw */
    int total()
    {
        return total;
    }

    /** @return the "did you mean" candidates, best first, capped at {@link #SUGGESTION_LIMIT} */
    List<String> suggestions()
    {
        List<String> all = new ArrayList<>();
        for (List<String> bucket : List.of(prefixHits, otherHits))
        {
            for (String hit : bucket)
            {
                if (all.size() >= SUGGESTION_LIMIT)
                {
                    return all;
                }
                all.add(hit);
            }
        }
        return all;
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
        sb.append("Error: ").append(subject).append(name).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

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

        // "N of TOTAL" rather than the old "first N ... (more available)": a caller - and an agent
        // in particular - needs to know whether it is looking at a sample of 30 or at everything.
        sb.append("Available ").append(itemsLabel).append(" (").append(samples.size()) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" of ").append(total).append(" documented names, English and Russian):\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String item : samples)
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
     * Sorts a name into the suggestion buckets when it is close to the query: it starts with the
     * query, contains it, or the query QUALIFIES it - {@code CatalogObject.Currencies} names a
     * concrete metadata type whose documentation lives on {@code CatalogObject}. The qualifying
     * direction demands the dot: a bare substring test let a two-syllable type name like
     * {@code Type} be offered for any query that happened to contain the word.
     */
    private void collectSuggestion(String name)
    {
        // Enough of the BEST kind is enough: the weaker bucket is still filled while prefix hits are
        // scarce, but a full prefix bucket already answers the question.
        if (query.isEmpty() || prefixHits.size() >= SUGGESTION_LIMIT)
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
            prefixHits.add(name);
        }
        else if ((lowerName.contains(lowerQuery) || lowerQuery.startsWith(lowerName + '.'))
            && otherHits.size() < SUGGESTION_LIMIT)
        {
            otherHits.add(name);
        }
    }
}
