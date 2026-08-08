/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private SubsystemUtils()
    {
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

    private static Subsystem findChild(Iterable<Subsystem> children, String name)
    {
        if (children == null || name == null)
        {
            return null;
        }
        String trimmed = name.trim();
        for (Subsystem child : children)
        {
            if (trimmed.equalsIgnoreCase(child.getName()))
            {
                return child;
            }
        }
        return null;
    }
}
