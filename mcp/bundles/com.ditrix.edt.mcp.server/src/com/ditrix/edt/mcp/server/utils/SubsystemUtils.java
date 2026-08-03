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
        if (config == null)
        {
            return null;
        }
        String[] names = parseSubsystemPath(fqn);
        if (names == null)
        {
            return null;
        }

        Subsystem current = findChild(config.getSubsystems(), names[0]);
        for (int i = 1; i < names.length && current != null; i++)
        {
            current = findChild(current.getSubsystems(), names[i]);
        }
        return current;
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
        return "Subsystem".equals(MetadataTypeUtils.toEnglishSingular(token.trim())); //$NON-NLS-1$
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
