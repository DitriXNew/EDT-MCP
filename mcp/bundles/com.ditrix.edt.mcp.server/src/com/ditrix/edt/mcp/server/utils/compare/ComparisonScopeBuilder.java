/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com._1c.g5.v8.dt.compare.core.ComparisonScope;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Turns the caller's list of metadata full names into the {@link ComparisonScope} the comparison
 * engine understands - or into an actionable refusal.
 * <p>
 * Two facts shape everything here, and both were measured rather than assumed.
 * <p>
 * <b>1. The engine is monolingual.</b> A scope entry is a symlink: an EDT qualified name whose
 * STRUCTURAL segments are the English literals ({@code Catalog}, {@code Form}, {@code Subsystem},
 * {@code Configuration}). Nothing in the comparison engine translates them, so a Russian address
 * arrives as a symlink that matches no object - and matching nothing is not an error there, it is a
 * perfectly legal scope. Every entry is therefore canonicalized through
 * {@link MetadataTypeUtils#toCanonicalEnglishFqn(String)}, which translates EVERY structural segment
 * and leaves the programmatic Names - Cyrillic, mixed case and all - byte-identical.
 * <p>
 * <b>2. An empty scope is not a guard, it is "compare everything".</b>
 * {@code ComparisonSession.computeIsGlobalScope()} is true exactly when every side's list is
 * null-or-empty, so handing the engine an empty {@link ComparisonScope} silently escalates to a
 * full-configuration comparison - the heaviest thing this plug-in can start, on an EDT that allows
 * one comparison at a time. That escalation must be a decision, never an accident, so this builder
 * NEVER constructs a {@link ComparisonScope} from an empty list:
 * <ul>
 * <li>a scope that was not supplied at all ({@code null} or an empty list) yields
 * {@link Scoping#isGlobal()} with a {@code null} {@link Scoping#scope()} - the caller passes no scope
 * to the engine and reports the whole configuration as the comparison's subject;</li>
 * <li>a scope that WAS supplied but carries an unusable entry is REFUSED. Reading a typo as "then
 * compare everything" would answer a narrow question with the most expensive possible run.</li>
 * </ul>
 * <p>
 * What it does NOT decide: whether a nested structural token exists. Only the LEADING token is
 * checked, against a catalogue that is complete for top-level types; the nested-kind catalogue is a
 * known subset of EDT's kinds, so refusing on it would turn a legitimate address into a false
 * refusal - the more expensive mistake of the two. An unrecognized nested token is copied verbatim
 * and stays visible in {@link Scoping#symlinks()}, which is what the report shows as the REQUESTED
 * scope.
 *
 * @see MetadataTypeUtils#toCanonicalEnglishFqn(String)
 */
public final class ComparisonScopeBuilder
{
    /**
     * The engine's own root symlink for the configuration object. It is NOT a member of the
     * metadata-type catalogue (that catalogue holds the types that own a {@code Configuration}
     * collection and an {@code src/} directory, and the configuration owns itself), so it is
     * recognized here explicitly - otherwise the one symlink the comparison engine names in its own
     * source would be refused as an unknown type.
     */
    public static final String CONFIGURATION_SYMLINK = "Configuration"; //$NON-NLS-1$

    /**
     * The Russian spelling of {@link #CONFIGURATION_SYMLINK} (ASCII: "Konfiguraciya"), accepted on
     * input and translated away on the way out. Written as code points so this source stays pure
     * ASCII, the way {@code MetadataTypeUtils} writes its own Russian tokens.
     */
    private static final String CONFIGURATION_SYMLINK_RU =
        "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f"; //$NON-NLS-1$

    /**
     * The Russian example the refusals carry (ASCII: "Spravochnik.Tovary.Forma.FormaElementa"). It is
     * deliberately a NESTED address: the whole point of the canonicalizer is that a nested Russian
     * address works, and an example that stopped at the type token would not show it.
     */
    private static final String RUSSIAN_EXAMPLE =
        "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.\u0422\u043e\u0432\u0430\u0440\u044b." //$NON-NLS-1$
            + "\u0424\u043e\u0440\u043c\u0430.\u0424\u043e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430"; //$NON-NLS-1$

    /** The Russian type token the refusals quote as an accepted form (ASCII: "Spravochnik"). */
    private static final String RUSSIAN_TYPE_EXAMPLE =
        "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"; //$NON-NLS-1$

    private ComparisonScopeBuilder()
    {
        // Utility class
    }

    /**
     * The outcome of building a scope: the engine's scope object, the explicit "whole configuration"
     * answer, or a ready {@link ToolResult} error JSON.
     * <p>
     * The three states are deliberately distinct. "No scope was asked for" and "an empty scope was
     * built" would be the same object if {@link #scope()} were allowed to hold an empty
     * {@link ComparisonScope}, and the engine reads that object as COMPARE EVERYTHING - so the
     * difference between a decision and an accident would stop being representable.
     */
    public static final class Scoping
    {
        /** The one whole-configuration outcome: no scope object, no symlinks, no error. */
        private static final Scoping GLOBAL = new Scoping(null, Collections.emptyList(), null);

        private final ComparisonScope scope;

        private final List<String> symlinks;

        private final String errorJson;

        private Scoping(ComparisonScope scope, List<String> symlinks, String errorJson)
        {
            this.scope = scope;
            this.symlinks = symlinks;
            this.errorJson = errorJson;
        }

        /** @return {@code true} when the scope was built (no refusal). */
        public boolean ok()
        {
            return errorJson == null;
        }

        /**
         * @return {@code true} when no scope was supplied and the comparison therefore covers the
         *         WHOLE configuration. {@link #scope()} is {@code null} in this state - the caller
         *         passes no scope to the engine rather than an empty one.
         */
        public boolean isGlobal()
        {
            return errorJson == null && scope == null;
        }

        /**
         * @return the engine's scope, or {@code null} for {@link #isGlobal()} and for a refusal. When
         *         it is non-{@code null} it always carries at least one symlink on every side.
         */
        public ComparisonScope scope()
        {
            return scope;
        }

        /**
         * @return the canonical symlinks that were built, in the caller's order and deduplicated;
         *         empty for {@link #isGlobal()} and for a refusal. This is what a report must show as
         *         the REQUESTED scope - what the engine later pulls in on its own is a different fact
         *         with its own accessor on {@link ComparisonScope}.
         */
        public List<String> symlinks()
        {
            return symlinks;
        }

        /** @return the error JSON to return from {@code execute}, or {@code null} on success. */
        public String errorJson()
        {
            return errorJson;
        }
    }

    /**
     * Builds the three-sided scope from the caller's full names. The same canonical list is used on
     * all three sides: the caller names OBJECTS, not sides, and an object that exists on only one
     * side is still the object being asked about - narrowing a side here would quietly drop the
     * added/deleted case, which is most of what a three-way comparison is for.
     *
     * @param fqns the caller's metadata full names, English or Russian, in any case; {@code null} or
     *     an empty list means the scope was not supplied at all
     * @return the scope, the explicit whole-configuration answer, or a refusal
     */
    public static Scoping build(List<String> fqns)
    {
        if (fqns == null || fqns.isEmpty())
        {
            return Scoping.GLOBAL;
        }

        Set<String> canonical = new LinkedHashSet<>();
        for (int i = 0; i < fqns.size(); i++)
        {
            String raw = fqns.get(i);
            if (raw == null || raw.trim().isEmpty())
            {
                return refuse(blankEntryMessage(i));
            }
            String entry = raw.trim();
            String typeToken = firstSegment(entry);
            if (!isKnownTypeToken(typeToken))
            {
                return refuse(unknownTypeMessage(entry, typeToken));
            }
            canonical.add(canonicalSymlink(entry));
        }

        // Non-empty by construction: the loop above returns on every entry it cannot use, so the only
        // way to reach this line is with at least one accepted symlink. That is what keeps an empty
        // list away from the ComparisonScope constructor, whose emptiness the engine reads as
        // "compare the whole configuration".
        List<String> symlinks = Collections.unmodifiableList(new ArrayList<>(canonical));
        ComparisonScope scope = new ComparisonScope(new ArrayList<>(symlinks), new ArrayList<>(symlinks),
            new ArrayList<>(symlinks));
        return new Scoping(scope, symlinks, null);
    }

    /**
     * Canonicalizes one address to the all-English symlink form the comparison engine matches nodes
     * by. This is the ONE entry point for that vocabulary: a comparison is scoped through it and
     * expanded through it, so an address that can scope a comparison can always address a node of
     * it.
     * <p>
     * The configuration root is handled here rather than inside the shared metadata canonicalizer
     * because it is not a metadata TYPE: it has no collection on {@code Configuration} and no
     * {@code src/} directory, so adding it to that catalogue would change what dozens of unrelated
     * tools accept. That is also exactly why it cannot be left to the metadata canonicalizer on the
     * expanding side either - it finds neither a type nor a nested kind for the Russian root token
     * and copies it through verbatim, which addresses no node at all.
     *
     * @param address a trimmed metadata full name, English or Russian, in any case; the leading
     *     token does NOT have to be known - an address this method cannot place is returned
     *     unchanged, so the caller reports "no such node" rather than a silent empty result
     * @return the canonical symlink, or {@code address} itself when nothing about it is
     *     translatable ({@code null} in, {@code null} out)
     */
    public static String canonicalSymlink(String address)
    {
        if (address == null || address.isEmpty())
        {
            return address;
        }
        String canonical = MetadataTypeUtils.toCanonicalEnglishFqn(address);
        if (canonical == null || canonical.isEmpty())
        {
            canonical = address;
        }
        int dot = canonical.indexOf('.');
        String head = dot < 0 ? canonical : canonical.substring(0, dot);
        if (isConfigurationToken(head))
        {
            return dot < 0 ? CONFIGURATION_SYMLINK : CONFIGURATION_SYMLINK + canonical.substring(dot);
        }
        return canonical;
    }

    /**
     * @param entry a non-empty entry
     * @return the leading dot-separated segment, i.e. the token that has to name a type
     */
    private static String firstSegment(String entry)
    {
        int dot = entry.indexOf('.');
        return dot < 0 ? entry : entry.substring(0, dot);
    }

    /**
     * @param token the leading segment of an entry
     * @return {@code true} when the token names a metadata type in either language, or the
     *         configuration root
     */
    private static boolean isKnownTypeToken(String token)
    {
        return MetadataTypeUtils.toEnglishSingular(token) != null || isConfigurationToken(token);
    }

    /**
     * @param token a leading segment
     * @return {@code true} when it is the configuration root token, in either language, in any case
     */
    private static boolean isConfigurationToken(String token)
    {
        return CONFIGURATION_SYMLINK.equalsIgnoreCase(token) || CONFIGURATION_SYMLINK_RU.equalsIgnoreCase(token);
    }

    /**
     * The refusal for an entry that is empty or blank. It states the whole-configuration route
     * explicitly, because the alternative reading - "an empty entry means everything" - is exactly
     * the accident this class exists to prevent.
     *
     * @param index the zero-based position of the offending entry
     * @return the actionable message
     */
    private static String blankEntryMessage(int index)
    {
        return "Scope entry #" + (index + 1) //$NON-NLS-1$
            + " is empty. Every 'scope' entry must be a metadata full name, for example " //$NON-NLS-1$
            + "'Catalog.Products' or '" + RUSSIAN_EXAMPLE //$NON-NLS-1$
            + "'. To compare the WHOLE configuration, omit 'scope' entirely - a blank entry is never " //$NON-NLS-1$
            + "read that way, because a whole-configuration comparison is the heaviest run this tool " //$NON-NLS-1$
            + "can start and has to be asked for."; //$NON-NLS-1$
    }

    /**
     * The refusal for an entry whose leading token names no type. It quotes BOTH the entry and the
     * token: the entry is what the caller wrote, the token is the part that failed, and an operator
     * who sees only one of the two has to guess which segment is wrong.
     *
     * @param entry the offending entry, as trimmed
     * @param typeToken its leading segment
     * @return the actionable message
     */
    private static String unknownTypeMessage(String entry, String typeToken)
    {
        return "Scope entry '" + entry + "' does not start with a known metadata type: '" + typeToken //$NON-NLS-1$ //$NON-NLS-2$
            + "' is neither a metadata type (English or Russian, singular or plural - Catalog, Catalogs, " //$NON-NLS-1$
            + RUSSIAN_TYPE_EXAMPLE + ") nor the configuration root token '" + CONFIGURATION_SYMLINK //$NON-NLS-1$ //$NON-NLS-2$
            + "'. Use a full name such as 'Catalog.Products' or '" + RUSSIAN_EXAMPLE //$NON-NLS-1$
            + "', call get_metadata_objects to list the names this project actually has, or omit " //$NON-NLS-1$
            + "'scope' to compare the whole configuration."; //$NON-NLS-1$
    }

    /**
     * @param message the actionable refusal text
     * @return the refusing outcome
     */
    private static Scoping refuse(String message)
    {
        return new Scoping(null, Collections.emptyList(), ToolResult.error(message).toJson());
    }
}
