/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Map;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;

/**
 * Shared helpers for resolving the 1C synonym language CODE and for reading a
 * synonym value out of the language-code-keyed synonym map.
 * <p>
 * 1C synonyms are stored in a map keyed by the configuration language CODE (e.g.
 * {@code "ru"} / {@code "en"}), NOT by the {@link Language} object's NAME (e.g.
 * {@code "Russian"} / {@code "Русский"}). Resolving the code via
 * {@link Language#getName()} or hardcoding {@code "ru"} are both bugs: the former
 * uses a key EDT never looks up (blank synonym in the editor), the latter is
 * wrong for non-Russian configurations.
 * <p>
 * This class centralises logic previously duplicated across
 * {@code CreateMetadataObjectTool}, {@code GetMetadataObjectsTool},
 * {@code GetMetadataDetailsTool}, {@code SubsystemUtils} and
 * {@code AbstractMetadataFormatter}.
 */
public final class MetadataLanguageUtils
{
    private MetadataLanguageUtils()
    {
        // Utility class
    }

    /**
     * Resolves the synonym language CODE for the given configuration.
     * <p>
     * Precedence (mirrors the verified {@code CreateMetadataObjectTool.resolveLanguage}):
     * <ol>
     * <li>If {@code explicit} is non-empty, it is returned as-is.</li>
     * <li>Otherwise the default language's {@link Language#getLanguageCode()} if non-empty.</li>
     * <li>Otherwise the first configured language's {@link Language#getLanguageCode()} if non-empty.</li>
     * <li>Otherwise {@code null} (the caller decides the error message / fallback).</li>
     * </ol>
     *
     * @param config the configuration (may be {@code null})
     * @param explicit an explicitly requested language code, or {@code null}/empty
     * @return the resolved language CODE, or {@code null} if none can be determined
     */
    public static String resolveLanguageCode(Configuration config, String explicit)
    {
        if (explicit != null && !explicit.isEmpty())
        {
            return explicit;
        }
        if (config == null)
        {
            return null;
        }
        // The synonym map is keyed by the language CODE (e.g. "en", "ru"), not by
        // the Language object's name (e.g. "English"). Using the name would store
        // the synonym under a key EDT never looks up, leaving the synonym blank in
        // the editor.
        Language defaultLanguage = config.getDefaultLanguage();
        if (defaultLanguage != null
            && defaultLanguage.getLanguageCode() != null
            && !defaultLanguage.getLanguageCode().isEmpty())
        {
            return defaultLanguage.getLanguageCode();
        }
        // No default language: use the first configured language code instead of a
        // hardcoded "ru", which would be wrong for non-Russian configurations.
        for (Language lang : config.getLanguages())
        {
            if (lang != null && lang.getLanguageCode() != null && !lang.getLanguageCode().isEmpty())
            {
                return lang.getLanguageCode();
            }
        }
        return null;
    }

    /**
     * Reads a synonym value from a language-code-keyed synonym map.
     * <p>
     * Lookup order (mirrors the previously-triplicated helper bodies):
     * <ol>
     * <li>The value keyed by {@code code} if non-empty.</li>
     * <li>Otherwise the first non-empty value in the map.</li>
     * <li>Otherwise an empty string {@code ""} (never {@code null}).</li>
     * </ol>
     * <p>
     * Accepts a plain {@link Map} so callers holding an EMF {@code EMap} pass
     * {@code emap.map()} and callers holding a {@link java.util.HashMap} pass it
     * directly.
     *
     * @param synonym the synonym map keyed by language CODE (may be {@code null}/empty)
     * @param code the preferred language CODE (may be {@code null}/empty)
     * @return the resolved synonym, or {@code ""} when none is available
     */
    public static String getSynonymForLanguage(Map<String, String> synonym, String code)
    {
        if (synonym == null || synonym.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        if (code != null && !code.isEmpty())
        {
            String preferred = synonym.get(code);
            if (preferred != null && !preferred.isEmpty())
            {
                return preferred;
            }
        }
        for (String value : synonym.values())
        {
            if (value != null && !value.isEmpty())
            {
                return value;
            }
        }
        return ""; //$NON-NLS-1$
    }
}
