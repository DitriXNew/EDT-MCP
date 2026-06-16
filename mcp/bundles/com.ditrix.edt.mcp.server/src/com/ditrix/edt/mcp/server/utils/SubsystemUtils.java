/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

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
