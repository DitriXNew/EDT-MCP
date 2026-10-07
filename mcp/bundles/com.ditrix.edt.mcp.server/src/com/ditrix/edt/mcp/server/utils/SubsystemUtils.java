/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.eclipse.emf.common.util.EMap;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Helpers shared between subsystem tools (list_subsystems, get_subsystem_content).
 */
public final class SubsystemUtils
{
    /**
     * The CANONICAL English type token of a subsystem segment - the single spelling EDT itself
     * stores in a nested subsystem's FQN ({@code Subsystem.Sales.Subsystem.Orders}, as serialized
     * into {@code parentSubsystem}). Every ACCEPTED spelling still comes from the shared bilingual
     * catalogue through {@link #isSubsystemTypeToken}; this constant is only how the canonical form
     * is WRITTEN back.
     */
    private static final String SUBSYSTEM_TOKEN = "Subsystem"; //$NON-NLS-1$

    /** The sentence {@link #addressingHint()} returns. */
    private static final String ADDRESSING_HINT = "A top-level subsystem is addressed as 'Subsystem.<Name>' and " //$NON-NLS-1$
        + "a nested one by its whole chain from a top-level subsystem, with a type token before every name " //$NON-NLS-1$
        + "('Subsystem.<Parent>.Subsystem.<Child>', any depth; the tokens may be English or Russian) - " //$NON-NLS-1$
        + "list_subsystems lists the existing ones in exactly that form"; //$NON-NLS-1$

    private SubsystemUtils()
    {
    }

    /**
     * How a subsystem is addressed: the one sentence the subsystem refusals listed here share, so they
     * cannot drift into teaching different forms (a command-interface refusal keeps its own text: it
     * teaches a section address, not a subsystem). It is appended by
     * {@code adopt_metadata_object}'s "not found", by {@code get_metadata_details}' rows for a missing
     * and for a malformed chain, and by {@link #notFoundMessage} - the "subsystem not found" of
     * {@code modify_metadata} and {@code get_subsystem_content}. It has no final period: each refusal
     * ends it the way the rest of its own text ends.
     *
     * <p>Every clause holds wherever it is appended: each of those tools resolves a chain through
     * {@link #parseSubsystemPath}, which takes a type token before every name in either language,
     * and {@code list_subsystems} prints every chain in the canonical English form. It does NOT
     * point at {@code get_metadata_objects}: that tool lists top-level subsystems only.</p>
     *
     * <p>A method, not a public constant, on purpose: a String constant is compiled INTO every class
     * that reads it, so a consumer compiled before the sentence changed would go on refusing with the
     * old text - one more copy, only in bytecode.</p>
     *
     * @return the sentence, without a final period
     */
    public static String addressingHint()
    {
        return ADDRESSING_HINT;
    }

    /**
     * The refusal of a subsystem address that resolves to no subsystem: the address as the caller
     * wrote it, then how subsystems are addressed ({@link #addressingHint()}). The one text of
     * {@code modify_metadata}'s and {@code get_subsystem_content}'s "subsystem not found".
     *
     * @param fqn the requested address, as written
     * @return the refusal message
     */
    public static String notFoundMessage(String fqn)
    {
        return "Subsystem not found: " + fqn + ". " + addressingHint() + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Resolves the language code for synonyms using the explicit value if provided,
     * otherwise the configuration default language. Returns {@code null} when no
     * language is determined — callers pass the result to
     * {@link #getSynonymForLanguage} which already falls back to any non-empty
     * synonym entry.
     */
    public static String resolveLanguage(String explicit, Configuration config)
    {
        // Delegate to the shared resolver (note the swapped argument order). This
        // also fixes the former getName() bug: the synonym map is keyed by the
        // language CODE, not the Language object's name.
        return MetadataLanguageUtils.resolveLanguageCode(config, explicit);
    }

    /**
     * Returns the synonym for the requested language with fallback to any available
     * non-empty entry. A {@code null} or empty {@code language} skips the preferred
     * lookup and goes straight to the fallback. Returns empty string when nothing
     * is set.
     */
    public static String getSynonymForLanguage(EMap<String, String> synonyms, String language)
    {
        return MetadataLanguageUtils.getSynonymForLanguage(synonyms == null ? null : synonyms.map(), language);
    }

    /**
     * Resolves a subsystem by FQN of the form
     * <code>Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog</code>.
     * Returns null if any segment cannot be resolved.
     *
     * <p>The type token is recognized via {@link MetadataTypeUtils} so any
     * registered form is accepted: English ("Subsystem"/"Subsystems") or Russian
     * ("Подсистема"/"Подсистемы"), case-insensitive. Segments may be mixed
     * (e.g. <code>Подсистема.Продажи.Subsystem.Orders</code>). Subsystem name
     * matching is case-insensitive.</p>
     */
    public static Subsystem resolveByFqn(Configuration config, String fqn)
    {
        String[] names = parseSubsystemPath(fqn);
        if (names == null)
        {
            return null;
        }
        return resolveByPath(config, names, names.length);
    }

    /**
     * The parsed chain of an address that names a NESTED subsystem - a subsystem chain of depth 2 or
     * more, e.g. {@code Subsystem.Sales.Subsystem.Orders} - or {@code null} for anything else
     * (including a plain top-level {@code Subsystem.Sales}).
     *
     * <p>This is the gate {@code create_metadata} dispatches on, kept here rather than in the tool so
     * the answer comes from the ONE bilingual token catalogue every subsystem consumer already
     * shares: EVERY position of the chain is judged by {@link #isSubsystemTypeToken}, so the tokens
     * may be English or Russian independently at each level
     * ({@code Подсистема.Продажи.Subsystem.Orders}).</p>
     *
     * <p>This decides the SHAPE only. Whether the address is well-FORMED is a separate question
     * answered by {@link #malformedSegmentError}, deliberately kept apart: a sloppy subsystem
     * address must still reach the subsystem branch so it can be refused by NAMING what is wrong
     * with it, instead of falling through to the generic "cannot resolve a create target" whose
     * list of kinds does not even mention subsystems.</p>
     *
     * @param fqn the requested address (may be {@code null})
     * @return the parsed chain of subsystem names (length &gt;= 2), or {@code null} when the address
     *     is not a nested-subsystem chain
     */
    public static String[] nestedChain(String fqn)
    {
        String[] names = parseSubsystemPath(fqn);
        if (names == null || names.length < 2)
        {
            return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
        }
        return names;
    }

    /**
     * An actionable refusal when {@code fqn} carries a MALFORMED segment - one that is empty, or
     * padded with whitespace - or {@code null} when every segment is clean.
     *
     * <p>ONE rule for both, because they are one question: an address that reads differently from a
     * well-formed one must not be silently accepted AS a well-formed one. {@link #parseSubsystemPath}
     * tolerates both, and rightly so - it answers LOOKUPS, where a padded name has only one reading -
     * but a CREATE stores the leaf and navigates by the ancestors, so the difference is the
     * difference between two nodes.</p>
     *
     * <ul>
     *   <li><b>Padded</b> ({@code Subsystem. Sales .Subsystem. Child }): the ordinary create path
     *       refuses this on both counts - {@code MetadataTypeUtils.findObject} matches an owner name
     *       verbatim, and the identifier check rejects a leading space - so accepting it here would
     *       create {@code Child} for {@code ' Child '}: a different node from the one requested.</li>
     *   <li><b>Empty</b> ({@code Subsystem.Sales.Subsystem.Child.}, {@code ...Child..}): a stray or
     *       doubled separator. It has no single reading - the child, or a deeper node whose name the
     *       caller failed to type - which is exactly the verdict {@code get_project_errors} already
     *       gives an empty segment.</li>
     * </ul>
     *
     * <p>The split takes an explicit {@code -1} limit ON PURPOSE: the default drops TRAILING empty
     * strings, so {@code Subsystem.Sales.Subsystem.Child.} splits into the same four segments as the
     * clean address and the stray separator becomes invisible. A leading or mid-string empty segment
     * survives either limit and is already refused upstream by the arity / empty-name checks in
     * {@link #parseSubsystemPath}; the trailing one is the only spelling that needs {@code -1}.</p>
     *
     * @param fqn the requested address (may be {@code null})
     * @return the refusal message naming what is wrong, or {@code null} when the address is clean
     */
    public static String malformedSegmentError(String fqn)
    {
        if (fqn == null)
        {
            return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
        }
        // No separate check for whitespace around the WHOLE address: it can only ever land in the
        // first or the last segment, so the per-segment loop already catches it - and catches it with
        // the better message, one that QUOTES the segment at fault instead of the whole address.
        String[] segments = fqn.split("\\.", -1); //$NON-NLS-1$
        for (String segment : segments)
        {
            if (segment.isEmpty())
            {
                return "The address '" + fqn + "' has an EMPTY segment - a stray or doubled '.'. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "It has no single reading (the node named here, or a deeper one whose name " //$NON-NLS-1$
                    + "was not typed), so it is refused rather than guessed: address the node as " //$NON-NLS-1$
                    + "'Subsystem.<Parent>.Subsystem.<Child>' with exactly one '.' between " //$NON-NLS-1$
                    + "segments."; //$NON-NLS-1$
            }
            if (!segment.equals(segment.trim()))
            {
                return "The address '" + fqn + "' has a padded segment '" + segment + "'. A Name " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "is stored and matched exactly as written, so the surrounding whitespace " //$NON-NLS-1$
                    + "would address or create a different node - remove it."; //$NON-NLS-1$
            }
        }
        return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
    }

    /**
     * Resolves the subsystem addressed by the FIRST {@code depth} names of a parsed chain.
     *
     * <p>The PREFIX overload exists so a caller that has to address the PARENT of a chain - a
     * nested-subsystem create, whose leaf does not exist yet - walks the very same descent as
     * {@link #resolveByFqn} instead of re-splitting the FQN and re-implementing the walk. Name
     * matching is case-insensitive at every level, exactly as for a whole chain.</p>
     *
     * @param config the configuration to resolve against
     * @param names the parsed chain of subsystem names (see {@link #parseSubsystemPath})
     * @param depth how many leading names to follow; {@code 0} addresses the configuration itself
     *     and therefore resolves to nothing
     * @return the resolved subsystem, or {@code null} when any segment does not resolve
     */
    public static Subsystem resolveByPath(Configuration config, String[] names, int depth)
    {
        if (config == null || names == null || depth <= 0 || depth > names.length)
        {
            return null;
        }
        Subsystem current = findChild(config.getSubsystems(), names[0]);
        for (int i = 1; i < depth && current != null; i++)
        {
            current = findChild(current.getSubsystems(), names[i]);
        }
        return current;
    }

    /**
     * Renders the first {@code depth} names of a parsed chain back as a canonical FQN
     * ({@code Subsystem.Sales.Subsystem.Orders}) - the inverse of {@link #parseSubsystemPath},
     * with every type token written in the canonical English spelling regardless of how the
     * caller spelled it.
     *
     * @param names the parsed chain of subsystem names
     * @param depth how many leading names to render
     * @return the canonical FQN, or {@code null} when the request is out of range
     */
    public static String chainFqn(String[] names, int depth)
    {
        if (names == null || depth <= 0 || depth > names.length)
        {
            return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++)
        {
            if (i > 0)
            {
                sb.append('.');
            }
            sb.append(SUBSYSTEM_TOKEN).append('.').append(names[i]);
        }
        return sb.toString();
    }

    /**
     * The subsystem a parsed chain addresses in a project's resolution root: the subsystem at the
     * chain's LAST level, or {@code null} when any level is missing - never one of its parents. A
     * single name addresses a top-level subsystem only: same-named children can live under different
     * parents, so a bare child name is not guessed.
     *
     * <p>Asked of the SCOPE because an external-objects project holds no subsystems: the
     * configuration its scope carries is the linked BASE one, which is never a resolution root there
     * (issue #309), so a chain resolves to nothing rather than to a subsystem of another project.</p>
     *
     * @param scope the resolution root (may be {@code null})
     * @param names the parsed chain of subsystem names (see {@link #parseSubsystemPath})
     * @return the subsystem, or {@code null} when the chain does not resolve in this root
     */
    public static Subsystem resolveInScope(MetadataScope scope, String[] names)
    {
        if (scope == null || scope.isExternalObjects() || names == null)
        {
            return null;
        }
        return resolveByPath(scope.configuration(), names, names.length);
    }

    /**
     * Walks up from a subsystem along its own {@code parentSubsystem} references - the persisted
     * back-reference EDT keeps on a nested subsystem (a top-level one has none) - and returns what a
     * caller reads off that one walk: the subsystems above it, the canonical chain that names it, and
     * whether an address is that chain ({@link Lineage#spells}).
     *
     * <p>The walk stops at an unresolved proxy (it is no attached object to ask anything) and at a
     * repeat, so a broken, cyclic model cannot keep it going. Only a walk that ran out of parents
     * reached a top-level subsystem: one cut short has seen part of the chain, and the levels it did
     * see would spell some other, shorter address - so it names nothing.</p>
     *
     * <p>Even a walk that reached the top reads the back-references, not the parents' own
     * {@code subsystems} lists an address is resolved DOWN by, and in a broken model the two can
     * disagree. A caller that names a subsystem it resolved by an address therefore uses the chain
     * only when the lineage spells that address ({@link Lineage#spells}).</p>
     *
     * @param leaf the subsystem (may be {@code null})
     * @return the lineage, never {@code null}
     */
    public static Lineage lineage(Subsystem leaf)
    {
        if (leaf == null)
        {
            return new Lineage(Collections.emptyList(), null, null);
        }
        List<Subsystem> ancestors = new ArrayList<>();
        Set<Subsystem> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(leaf);
        Subsystem parent = leaf.getParentSubsystem();
        while (parent != null && !parent.eIsProxy() && seen.add(parent))
        {
            ancestors.add(parent);
            parent = parent.getParentSubsystem();
        }
        String[] names = null;
        String chain = null;
        if (parent == null)
        {
            names = new String[ancestors.size() + 1];
            for (int i = 0; i < ancestors.size(); i++)
            {
                names[ancestors.size() - 1 - i] = ancestors.get(i).getName();
            }
            names[ancestors.size()] = leaf.getName();
            chain = chainFqn(names, names.length);
        }
        return new Lineage(Collections.unmodifiableList(ancestors), names, chain);
    }

    /**
     * What {@link #lineage} read off a subsystem: the subsystems above it and, when the walk reached
     * a top-level subsystem, the STORED name of every level, top first.
     */
    public static final class Lineage
    {
        private final List<Subsystem> ancestors;
        /** The stored names of the levels, top first; {@code null} when the walk was cut short. */
        private final String[] storedNames;
        private final String chainFqn;

        private Lineage(List<Subsystem> ancestors, String[] storedNames, String chainFqn)
        {
            this.ancestors = ancestors;
            this.storedNames = storedNames;
            this.chainFqn = chainFqn;
        }

        /**
         * The subsystems above the leaf, nearest first; empty for a top-level subsystem, and short
         * of the top when the walk was cut short (see {@link SubsystemUtils#lineage}).
         *
         * @return the ancestors, unmodifiable; never {@code null}
         */
        public List<Subsystem> ancestors()
        {
            return ancestors;
        }

        /**
         * The leaf's canonical address - {@code Subsystem.<Top>...Subsystem.<Leaf>}, every level by
         * its STORED name and the English token, exactly what {@code list_subsystems} prints - or
         * {@code null} when the walk did not reach a top-level subsystem. It is the leaf's address
         * only as far as the back-references are right: a caller that resolved the leaf by an
         * address asks {@link #spells} before it names the leaf by this chain.
         *
         * @return the chain FQN, or {@code null}
         */
        public String chainFqn()
        {
            return chainFqn;
        }

        /**
         * Whether the walk spells the address {@code names}: it reached a top-level subsystem, it has
         * as many levels as {@code names}, and the STORED name of every level - the top one and the
         * leaf included - matches the addressed name at the same depth by the one rule the descent
         * ({@link SubsystemUtils#resolveByPath}) matches a level with: surrounding whitespace of the
         * addressed name ignored, letter case ignored. A level that stores no name matches no
         * addressed name.
         *
         * <p>An address resolves DOWN by name, along each parent's {@code subsystems} list; this walk
         * read the levels back UP, along the {@code parentSubsystem} back-references. In a sound model
         * the two are one path. In a broken one a back-reference can name another subsystem of the
         * same depth - {@code Sales} lists {@code Stray}, whose parent link is {@code Planning} - and
         * the chain then spells another address, which names a different subsystem or none. The
         * level count cannot tell the two apart; the names it walked through can.</p>
         *
         * <p>It compares names, not subsystems: whether the walk met the very objects the descent did
         * is not asked. Under matching names the walk can still have passed through another object -
         * a sibling whose name differs in letter case alone (a clash 1C does not allow), or a
         * same-named subsystem the parent's {@code subsystems} list does not hold - and the chain
         * still resolves back to this subsystem: it is spelled by names, at every level the descent
         * takes the first listed sibling that matches, and a stored name that matches the addressed
         * one matches the same siblings it does.</p>
         *
         * @param names the addressed names, top first (see {@link SubsystemUtils#parseSubsystemPath});
         *     may be {@code null}
         * @return {@code true} when {@link #chainFqn()} is the canonical spelling of that very
         *     address; {@code false} otherwise - always for a walk cut short and for {@code null}
         */
        public boolean spells(String[] names)
        {
            if (storedNames == null || names == null || names.length != storedNames.length)
            {
                return false;
            }
            for (int i = 0; i < storedNames.length; i++)
            {
                if (!nameMatches(names[i], storedNames[i]))
                {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Resolves a subsystem chain SEGMENT BY SEGMENT, tolerating the yo (U+0451) spelling at each
     * level independently, and returns the chain of STORED names.
     *
     * <p>{@code create_metadata} normalizes yo to ye per NAME by default, so a five-level chain can
     * legitimately mix spellings level by level. Trying whole-address spellings cannot express that:
     * the address as typed and its fully normalized twin are two points in a space of 2^depth, and
     * enumerating that space is not an option either. Walking the tree is: each level is matched
     * among the ACTUAL children of the level already resolved, so the cost is linear in depth and no
     * combination is ever built.</p>
     *
     * <p>The STORED names are returned rather than the requested ones because the caller scopes a
     * marker scan with them - a marker carries what EDT stored, not what the caller typed.</p>
     *
     * @param config the configuration to resolve against
     * @param fqn the subsystem chain FQN
     * @return the resolved chain's stored names, or {@code null} when it resolves to nothing
     */
    public static List<String[]> resolveStoredChain(Configuration config, String fqn)
    {
        if (config == null)
        {
            return Collections.emptyList();
        }
        String[] names = parseSubsystemPath(fqn);
        if (names == null)
        {
            return Collections.emptyList();
        }
        // EXACT-FIRST for the WHOLE chain: the address exactly as typed wins outright, exactly as
        // MetadataNodeResolver.resolveExistingWithYoFallback treats a single name.
        List<String[]> exact = new ArrayList<>(1);
        descend(config.getSubsystems(), names, 0, false, exact);
        if (!exact.isEmpty())
        {
            return exact;
        }
        // ...and only on its COMPLETE failure do the yo readings apply - ALL of them. More than one
        // real chain can match: 'Subsystem.M[yo]d.Subsystem.V[yo]s' fits both 'M[yo]d -> V[ye]s' and
        // 'M[ye]d -> V[yo]s'. Returning whichever the walk met first scoped the scan to one and hid
        // the markers under the other, which is the same false clean this branch exists to remove.
        List<String[]> fallback = new ArrayList<>();
        descend(config.getSubsystems(), names, 0, true, fallback);
        return fallback;
    }

    /**
     * Depth-first descent with BACKTRACKING, matching {@code names[index]} against the subsystems
     * that actually exist at this level.
     *
     * <p>The previous walk committed to the first child that matched, so a chain whose typed parent
     * exists but is a DEAD END never got to try the parent's yo twin: with {@code Subsystem.M[yo]d}
     * childless and {@code Subsystem.M[ye]d} holding {@code V[ye]s}, the address
     * {@code Subsystem.M[yo]d.Subsystem.V[yo]s} stopped at the parent and came back missing.</p>
     *
     * <p>Backtracking here is NOT the subset enumeration this replaced. That built 2^n strings from
     * the ADDRESS before touching the model; this walks the model, and a level offers at most the
     * one or two children that really carry the name - a branch that matches nothing is cut on the
     * spot. The work is therefore bounded by the configuration's own subsystem tree, not by the
     * length of the address.</p>
     *
     * @param level the subsystems available at this depth
     * @param names the requested chain
     * @param index the depth being matched
     * @param allowYo whether a yo reading of the name may be tried in addition to the exact one
     * @return the STORED names along a complete matching path, or {@code null} when none exists
     */
    private static void descend(Iterable<Subsystem> level, String[] names, int index,
        boolean allowYo, List<String[]> out)
    {
        if (out.size() >= MAX_MATCHING_CHAINS)
        {
            return;
        }
        for (Subsystem candidate : candidatesAt(level, names[index], allowYo))
        {
            if (index == names.length - 1)
            {
                String[] chain = new String[names.length];
                chain[index] = candidate.getName();
                out.add(chain);
                continue;
            }
            int before = out.size();
            descend(candidate.getSubsystems(), names, index + 1, allowYo, out);
            for (int i = before; i < out.size(); i++)
            {
                out.get(i)[index] = candidate.getName();
            }
        }
    }

    /**
     * How many complete matching chains the yo pass will collect.
     *
     * <p>Not a limit on the ADDRESS: the walk is bounded by the tree, and a level offers at most the
     * one or two subsystems that really carry the name, so reaching this would need a configuration
     * with e/yo twin subsystems at half a dozen nested levels. It exists only so that a pathological
     * model cannot turn one request into unbounded work; the scope it produces is still a superset
     * of one real chain, so nothing is ever reported as absent that exists.</p>
     */
    private static final int MAX_MATCHING_CHAINS = 64;

    /**
     * The subsystems at this level that {@code name} can mean: the exact match first, then - only
     * when {@code allowYo} - the yo-normalized one, and only if it really exists and is a different
     * object. At most two, and never a name the model does not carry.
     */
    private static List<Subsystem> candidatesAt(Iterable<Subsystem> level, String name,
        boolean allowYo)
    {
        List<Subsystem> candidates = new ArrayList<>(2);
        Subsystem exact = findChild(level, name);
        if (exact != null)
        {
            candidates.add(exact);
        }
        if (allowYo)
        {
            String retry = MetadataNodeResolver.yoRetryFqn(name);
            Subsystem viaYo = retry == null ? null : findChild(level, retry);
            if (viaYo != null && viaYo != exact)
            {
                candidates.add(viaYo);
            }
        }
        return candidates;
    }


    /**
     * Parses a subsystem FQN into the ordered list of subsystem names along the
     * containment path. Returns {@code null} when the FQN is malformed (wrong
     * arity, unknown type token).
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>"Subsystem.Sales" → ["Sales"]</li>
     *   <li>"Subsystem.Sales.Subsystem.Orders" → ["Sales", "Orders"]</li>
     *   <li>"Подсистема.Продажи.Subsystem.Orders" → ["Продажи", "Orders"]</li>
     *   <li>"Catalog.Products" → null (wrong type token)</li>
     *   <li>"Subsystem" → null (missing name)</li>
     * </ul>
     */
    public static String[] parseSubsystemPath(String fqn)
    {
        if (fqn == null)
        {
            return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
        }
        String trimmed = fqn.trim();
        if (trimmed.isEmpty())
        {
            return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
        }
        String[] parts = trimmed.split("\\."); //$NON-NLS-1$
        if (parts.length < 2 || (parts.length % 2) != 0)
        {
            return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
        }

        String[] names = new String[parts.length / 2];
        for (int i = 0; i < parts.length; i += 2)
        {
            if (!isSubsystemTypeToken(parts[i]))
            {
                return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
            }
            String name = parts[i + 1] != null ? parts[i + 1].trim() : ""; //$NON-NLS-1$
            if (name.isEmpty())
            {
                return null; // NOSONAR null is a deliberate signal (omit/sentinel), not an empty collection
            }
            names[i / 2] = name;
        }
        return names;
    }

    public static boolean isSubsystemTypeToken(String token)
    {
        if (token == null)
        {
            return false;
        }
        return SUBSYSTEM_TOKEN.equals(MetadataTypeUtils.toEnglishSingular(token.trim()));
    }

    /**
     * EVERY spelling {@link #isSubsystemTypeToken} accepts, lowercase.
     *
     * <p>Published so a regression check can compare this set with the NESTED-kind catalogue the
     * object filters advertise a {@code Subsystem} segment through, in BOTH directions. The two are
     * genuinely independent lists - the predicate answers from the TOP-LEVEL type catalogue
     * ({@code MetadataTypeUtils.toEnglishSingular}), while a nested {@code Subsystem} segment is
     * translated through the nested-kind catalogue - so either one can gain a spelling the other
     * does not have. An alias added to the nested catalogue alone is an address the filter
     * documents and this predicate then refuses; an alias added to the type catalogue alone is an
     * address this predicate accepts and the filter cannot translate. Asking whether the sets
     * OVERLAP would see neither.</p>
     *
     * <p>Derived from the type catalogue, i.e. from the very map the predicate reads, never from
     * the nested catalogue it is compared against: a set copied from the other side of a comparison
     * makes the comparison vacuous.</p>
     *
     * @return the accepted tokens, lowercase (never {@code null})
     */
    public static Set<String> acceptedTypeTokens()
    {
        return MetadataTypeUtils.typeAliases(SUBSYSTEM_TOKEN);
    }

    private static Subsystem findChild(Iterable<Subsystem> children, String name)
    {
        if (children == null || name == null)
        {
            return null;
        }
        for (Subsystem child : children)
        {
            if (nameMatches(name, child.getName()))
            {
                return child;
            }
        }
        return null;
    }

    /**
     * The ONE rule by which a subsystem's stored Name matches a level of an address: the addressed
     * name without its surrounding whitespace, letter case ignored. The descent ({@link #findChild})
     * and the check of the walk back up ({@link Lineage#spells}) both match a level by it, so they
     * cannot drift apart: a level the descent walked through is a level the check accepts.
     *
     * @param addressed the name as the address carries it (may be {@code null})
     * @param stored the Name the subsystem stores (may be {@code null})
     * @return whether they name the same level; {@code false} when either is {@code null}
     */
    private static boolean nameMatches(String addressed, String stored)
    {
        return addressed != null && addressed.trim().equalsIgnoreCase(stored);
    }
}
