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
import com._1c.g5.v8.dt.compare.model.ComparisonSide;

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

    /**
     * The two characters the padding refusal quotes as examples of whitespace an ordinary
     * {@code trim} leaves behind: EM SPACE and NO-BREAK SPACE.
     * <p>
     * Written as numbers rather than as backslash-u escapes on purpose. The Java lexer
     * translates such an escape BEFORE the code is parsed - in a comment as readily as in a
     * literal - so writing one here would put the invisible character itself into this source,
     * which is the very defect this refusal exists to name.
     */
    private static final char EM_SPACE = 0x2003;

    /** @see #EM_SPACE */
    private static final char NO_BREAK_SPACE = 0x00A0;

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
            // BEFORE the type token, and that order is the whole point. trim() cuts only code
            // points at or below U+0020, so an entry padded with U+2003 or U+00A0 arrives here
            // still padded - and if the padding sits on the LEADING segment, asking about the
            // type first answers "'Catalog' is not a metadata type" over a token that reads as
            // 'Catalog' on any screen. A message that quotes an invisible character without
            // naming it is one the caller cannot act on, so the invisible defect is named first.
            int padded = paddedNameCharacter(entry);
            if (padded >= 0)
            {
                // Reported against the string the CALLER sent, not against the trimmed one: with
                // ordinary spaces cut from the front the two disagree, and a position the caller
                // cannot count to is worse than no position.
                return refuse(paddedEntryMessage(i, entry, padded,
                    PaddedNames.trimmedFromTheFront(raw)));
            }
            // The other half of the same failure, and it needs its own question here because
            // nothing else in this class asks one. The padding check SKIPS a component that names
            // nothing - safe where a sibling predicate reports it, as it does for a merge-rule
            // key, and a hole here, where there is no sibling: 'Catalog..Products' and
            // 'Catalog.Products.' would pass every check and build a symlink matching nothing,
            // which is the very outcome the padding refusal exists to prevent.
            int empty = PaddedNames.firstEmptyComponent(entry, '.');
            if (empty > 0)
            {
                return refuse(emptySegmentMessage(i, empty));
            }
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
     * Whether a scope object is the one the engine would read as COMPARE EVERYTHING - asked
     * BEFORE the comparison session is constructed.
     *
     * <h2>The predicate is the platform's, and so is the accessor</h2>
     * Reproduced from {@code ComparisonSession.computeIsGlobalScope}: true exactly when every
     * {@link ComparisonSide}'s list is null or empty, read through {@code ComparisonScope.getScope}
     * - the same accessor the session reads. It used to ask {@code getInputScope} instead, which
     * is the same list only while {@code extendScope} has not been called: a scope built empty and
     * then extended before the session existed would be called global here and scoped by the
     * platform, and the {@code mergeObjectsContent} setting derived from it would be the opposite
     * of what the run needed. Answering through the platform's own accessor removes the
     * disagreement instead of documenting it.
     *
     * <h2>PRECONDITION: ask it before the launch, then REMEMBER the answer</h2>
     * {@code getScope} grows while the comparison runs - the engine pulls dependencies in through
     * {@code extendScope} - so this predicate is not stable across a run, and it is not the
     * question a report asks afterwards. The session computes its own answer ONCE, in its
     * constructor, and keeps it; a report must read THAT saved value
     * ({@code ComparisonView.isGlobalScope}) rather than recompute from the scope object, or a
     * whole-configuration run whose scope the engine extended would be described as a scoped one.
     * This method is therefore a PRE-LAUNCH predicate only.
     *
     * <h2>Not the same question as {@link Scoping#isGlobal()}</h2>
     * That one says the CALLER supplied no scope; this one says the ENGINE will compare
     * everything. They agree today, because a supplied scope is never built empty (see the class
     * javadoc), and they are still different facts about different objects.
     *
     * @param scope the scope about to be handed to a comparison handle; {@code null} is how this
     *     builder spells the whole-configuration case, so it answers {@code true}
     * @return {@code true} when the comparison covers the whole configuration
     */
    public static boolean isGlobalScope(ComparisonScope scope)
    {
        if (scope == null)
        {
            return true;
        }
        for (ComparisonSide side : ComparisonSide.values())
        {
            List<String> effective = scope.getScope(side);
            if (effective != null && !effective.isEmpty())
            {
                return false;
            }
        }
        return true;
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
     * Where a metadata address is PADDED with whitespace - the one defect in an address a caller
     * cannot see by looking at their own request.
     *
     * <h2>Why an address gets its own door onto the shared judgement</h2>
     * The comparison engine matches a symlink by string EQUALITY against a node's own, and no
     * symlink it produces carries whitespace. A padded address therefore matches nothing - which
     * is not an error anywhere downstream. As a SCOPE entry it is worse than a refusal: the entry
     * validates (its leading token is a real type), a non-empty {@link ComparisonScope} is built,
     * EDT's single comparison slot is taken for minutes, and the finished run reports no matching
     * rows - the caller is told the objects did not differ rather than that the name reached
     * nothing. As {@code get_comparison_node}'s address it is merely unactionable: the refusal
     * quotes an address that looks exactly right.
     * <p>
     * <b>Not trimmed for the caller</b>, for the reason the merge-rules door gives: an address
     * this tool rewrote is no longer the address that was asked about, and a scope silently
     * widened to a neighbouring object is a comparison of something nobody requested.
     * <p>
     * What counts as whitespace, why {@code trim} and {@code isBlank} disagree about it, and which
     * characters are deliberately NOT covered are all settled by {@link PaddedNames}; this method
     * supplies only the component boundary an address is made of, the {@code .} between its
     * segments. Asked per SEGMENT, so an address whose SECOND segment opens with a non-breaking
     * space is caught as surely as one padded at its very end.
     *
     * @param address a metadata full name, English or Russian (may be {@code null})
     * @return the 0-based UTF-16 offset of the first whitespace character that begins or ends a
     *         segment, or {@code -1} when no segment is padded
     */
    public static int paddedNameCharacter(String address)
    {
        return PaddedNames.firstPaddedCharacter(address, '.');
    }

    /**
     * The refusal for an entry whose segment is padded. It names the character by code point and
     * says where it sits, and it does NOT quote a "did you mean" spelling: producing one would
     * mean this class deciding what the caller meant, which is the silent rewrite the refusal
     * exists instead of.
     *
     * The position is the UTF-16 offset within the string as SENT - the trimmed offset plus
     * whatever {@code trim} cut from the front - counted from 1. It is the same unit the
     * merge-rules refusal uses, and {@link PaddedNames#codePointName(char)} says why that unit
     * rather than a code-point ordinal.
     *
     * @param index the zero-based position of the offending entry
     * @param entry the entry, as trimmed
     * @param offset the 0-based offset of the padding character within it
     * @param lead how many characters {@code trim} cut from the front of what the caller sent
     * @return the actionable message
     */
    private static String paddedEntryMessage(int index, String entry, int offset, int lead)
    {
        return "Scope entry #" + (index + 1) + " has " //$NON-NLS-1$ //$NON-NLS-2$
            + PaddedNames.codePointName(entry.charAt(offset))
            + ", a whitespace character, at character " + (lead + offset + 1) //$NON-NLS-1$
            + ", where a name begins or ends. Nothing was started. The comparison engine matches " //$NON-NLS-1$
            + "a scope entry against an object's own qualified name by exact string equality and " //$NON-NLS-1$
            + "no name it produces holds whitespace, so a padded entry matches NO object - the " //$NON-NLS-1$
            + "comparison would run to the end and report nothing for it, which reads as 'these " //$NON-NLS-1$
            + "objects did not differ'. It is not trimmed for you, because an address this tool " //$NON-NLS-1$
            + "rewrote is no longer the address you asked about. Note that '" //$NON-NLS-1$
            + PaddedNames.codePointName(EM_SPACE) + "', '" + PaddedNames.codePointName(NO_BREAK_SPACE) //$NON-NLS-1$
            + "' and their kin survive an ordinary trim, so a name pasted out of a document can " //$NON-NLS-1$
            + "carry one invisibly. Re-send it without the padding, for example " //$NON-NLS-1$
            + "'Catalog.Products' or '" + RUSSIAN_EXAMPLE + "'."; //$NON-NLS-1$
    }

    /**
     * The refusal for an entry with a segment that names nothing. It says WHICH segment, because
     * the offending part is invisible in a different way from padding - there is simply nothing
     * between two separators, and pointing at a character offset would point at a dot.
     *
     * @param index the zero-based position of the offending entry
     * @param segment the 1-based ordinal of the segment that names nothing
     * @return the actionable message
     */
    private static String emptySegmentMessage(int index, int segment)
    {
        // The wording describes the segment, not where a separator happens to sit. "between
        // two '.', or after the last one" left out the leading case entirely: '.Catalog' has its
        // empty segment BEFORE the first separator, and the sentence then described a shape the
        // entry does not have.
        return "Scope entry #" + (index + 1) + " has nothing in segment " + segment //$NON-NLS-1$ //$NON-NLS-2$
            + ": that segment of the name is empty, or holds only whitespace. Nothing was " //$NON-NLS-1$
            + "started. A scope entry is matched against an object's own qualified name by exact " //$NON-NLS-1$
            + "string equality, so an entry with an empty segment matches NO object - the " //$NON-NLS-1$
            + "comparison would run to the end and report nothing for it, which reads as 'these " //$NON-NLS-1$
            + "objects did not differ'. Send every segment, for example 'Catalog.Products' or '" //$NON-NLS-1$
            + RUSSIAN_EXAMPLE + "', or omit 'scope' to compare the whole configuration."; //$NON-NLS-1$
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
