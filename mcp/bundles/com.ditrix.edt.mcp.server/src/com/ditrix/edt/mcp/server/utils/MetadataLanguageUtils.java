/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
 * {@code CreateMetadataTool}, {@code GetMetadataObjectsTool},
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
     * Precedence (the established synonym language-resolution order, used by create_metadata):
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
     * Resolves the language code for an OPTIONAL localized value (a synonym / form title /
     * localized property), or fails with the shared actionable message.
     * <ul>
     * <li>{@code value} absent/empty &rarr; {@code null} (nothing to localize, no error);</li>
     * <li>an EXPLICIT code the configuration DECLARES &rarr; that language's own spelling (a
     * differently-cased request is canonicalized rather than stored as a second, never-displayed
     * key);</li>
     * <li>an EXPLICIT code the configuration does NOT declare &rarr; throws (issue #298) - unless the
     * configuration declares no code at all, which leaves nothing to validate against;</li>
     * <li>no explicit code &rarr; the {@link #resolveLanguageCode} fallback, which comes from the
     * configuration itself and is therefore declared by construction;</li>
     * <li>no code determinable at all &rarr; throws.</li>
     * </ul>
     * Every thrown message is ready for {@code ToolResult.error} (the caller wraps it).
     * Extracted because the identical resolve-or-error block existed at four call sites
     * (create_metadata synonym x2, the create form-member title, modify_metadata's
     * localized-string branch) and their error texts had started to drift.
     *
     * @param config the configuration (may be {@code null})
     * @param value the localized value being set (may be {@code null}/empty)
     * @param explicitLanguage an explicitly requested language code, or {@code null}/empty
     * @param subject what is being localized, for the error message (e.g. {@code "the synonym"})
     * @return the resolved language code, or {@code null} when {@code value} is absent
     * @throws IllegalArgumentException when a code is needed but cannot be determined
     */
    public static String resolveSynonymLanguage(Configuration config, String value,
        String explicitLanguage, String subject)
    {
        return resolveSynonymLanguage(config, value, explicitLanguage, subject, (Collection<String>)null);
    }

    /**
     * The {@link #resolveSynonymLanguage(Configuration, String, String, String)} variant that
     * validates against the codes the configuration will declare AFTER the current call.
     * <p>
     * One {@code modify_metadata} batch can set a {@code Language} object's {@code languageCode} AND
     * a localized value in the same breath. Validating against the model alone would reject the
     * second half of an edit whose first half declares the code; validating against the UNION of old
     * and new would do the opposite and accept a code the batch REMOVES (renaming a language's code
     * from {@code en} to {@code fr} leaves no {@code en} behind). So the caller computes the
     * post-batch set and passes it here, and it REPLACES the model-derived one.
     * <p>
     * This cannot let a value slip in under a code that never materializes: the whole batch is
     * prepared before anything is written, so a rejected {@code languageCode} fails the call before
     * the locale it would have declared can be used.
     *
     * @param config the configuration (may be {@code null})
     * @param value the localized value being set (may be {@code null}/empty)
     * @param explicitLanguage an explicitly requested language code, or {@code null}/empty
     * @param subject what is being localized, for the error message
     * @param declaredOverride the codes declared AFTER this call, or {@code null}/empty to use the
     *            configuration's current ones
     * @return the resolved language code, or {@code null} when {@code value} is absent
     * @throws IllegalArgumentException when the code is undeclared or cannot be determined
     */
    public static String resolveSynonymLanguage(Configuration config, String value,
        String explicitLanguage, String subject, Collection<String> declaredOverride)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        // An UNDECLARED code must be refused, not stored: the platform has no fallback between
        // locale codes, so a value written under a code the configuration does not declare is simply
        // never displayed - the label comes out blank and the mistake is invisible until a human
        // opens the form. Only an EXPLICIT code can be wrong; the fallbacks below resolve to a
        // declared language by construction. Issue #298.
        if (explicitLanguage != null && !explicitLanguage.isEmpty())
        {
            List<String> declared = declaredOrOverride(config, declaredOverride);
            if (!declared.isEmpty())
            {
                String canonical = canonicalOf(declared, explicitLanguage);
                if (canonical == null)
                {
                    throw new IllegalArgumentException("Unknown language '" + explicitLanguage //$NON-NLS-1$
                        + "' for " + subject + ". This configuration declares: " //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", declared) //$NON-NLS-1$
                        + ". A value stored under an undeclared code is never displayed. Use one of " //$NON-NLS-1$
                        + "the declared codes, or omit 'language' to use the default one."); //$NON-NLS-1$
                }
                return canonical;
            }
            // The configuration declares no language code at all (a brand-new or unresolved
            // configuration): nothing to validate against, so take the caller's code as before
            // rather than rejecting every localized write.
        }
        String code = resolveLanguageCode(config, explicitLanguage);
        if (code == null)
        {
            throw new IllegalArgumentException("Cannot determine a language code for " + subject //$NON-NLS-1$
                + " in this configuration. Specify a 'language' code (e.g. 'en' or 'ru')."); //$NON-NLS-1$
        }
        // The fallback comes from the model, so it is declared - BEFORE this call. A batch that
        // RENAMES the default language's code removes it, and the value would land under the code
        // the same call deleted: invisible, which is the whole point of this guard. With exactly one
        // code left afterwards the intent is unambiguous, so use it; with several, refuse rather
        // than guess which one the caller meant.
        if (declaredOverride != null && !declaredOverride.isEmpty() && !declaredOverride.contains(code))
        {
            List<String> after = declaredOrOverride(config, declaredOverride);
            if (after.size() == 1)
            {
                return after.get(0);
            }
            throw new IllegalArgumentException("This call changes the language codes, so the default "  //$NON-NLS-1$
                + "code ('" + code + "') for " + subject + " no longer exists after it. Name the " //$NON-NLS-1$ //$NON-NLS-2$
                + "'language' explicitly - one of: " + String.join(", ", after) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return code;
    }

    /**
     * The language CODES the configuration declares, in declaration order, without blanks or
     * duplicates.
     * <p>
     * An EMPTY result means "this configuration declares no language code", which callers must treat
     * as <em>cannot validate</em> - never as "no code is allowed". A configuration being created, or
     * one whose languages have not resolved yet, legitimately lands here.
     *
     * @param config the configuration (may be {@code null})
     * @return the declared codes, never {@code null}
     */
    public static List<String> declaredLanguageCodes(Configuration config)
    {
        List<String> codes = new ArrayList<>();
        collectDeclared(config, codes);
        return codes;
    }

    /**
     * {@code declaredOverride} when it has content, else the configuration's current codes. An empty
     * or {@code null} override means "the caller has nothing better to say".
     *
     * @param config the configuration (may be {@code null})
     * @param declaredOverride the codes declared after the current call (may be {@code null}/empty)
     * @return the codes to validate against, never {@code null}
     */
    public static List<String> declaredOrOverride(Configuration config,
        Collection<String> declaredOverride)
    {
        if (declaredOverride != null && !declaredOverride.isEmpty())
        {
            List<String> codes = new ArrayList<>();
            for (String code : declaredOverride)
            {
                if (code != null && !code.isEmpty() && !codes.contains(code))
                {
                    codes.add(code);
                }
            }
            if (!codes.isEmpty())
            {
                return codes;
            }
        }
        return declaredLanguageCodes(config);
    }

    private static void collectDeclared(Configuration config, List<String> codes)
    {
        if (config == null)
        {
            return;
        }
        for (Language lang : config.getLanguages())
        {
            if (lang == null)
            {
                continue;
            }
            String code = lang.getLanguageCode();
            if (code != null && !code.isEmpty() && !codes.contains(code))
            {
                codes.add(code);
            }
        }
    }

    /**
     * The DECLARED spelling of a language code, or {@code null} when the configuration does not
     * declare it.
     * <p>
     * An exact match wins; otherwise the comparison is case-insensitive and the DECLARED spelling is
     * returned, so {@code "EN_ca"} is stored under the configuration's own {@code "en_CA"} key
     * instead of creating a second, never-displayed entry.
     *
     * @param config the configuration (may be {@code null})
     * @param code the requested code (may be {@code null}/empty)
     * @return the declared spelling, or {@code null} when the code is not declared
     */
    public static String canonicalLanguageCode(Configuration config, String code)
    {
        return canonicalOf(declaredLanguageCodes(config), code);
    }

    /** The declared spelling of {@code code} within {@code declared}, or {@code null}. */
    private static String canonicalOf(List<String> declared, String code)
    {
        if (code == null || code.isEmpty())
        {
            return null;
        }
        if (declared.contains(code))
        {
            return code;
        }
        for (String declaredCode : declared)
        {
            if (declaredCode.equalsIgnoreCase(code))
            {
                return declaredCode;
            }
        }
        return null;
    }

    /**
     * The declared language codes that a localized property does NOT yet have a value for - what the
     * caller still owes a translation for. Issue #298.
     *
     * @param config the configuration (may be {@code null})
     * @param present the codes that DO have a value (may be {@code null}/empty)
     * @return the missing codes in declaration order, never {@code null}
     */
    public static List<String> localesMissing(Configuration config, Collection<String> present)
    {
        List<String> missing = new ArrayList<>();
        for (String declared : declaredLanguageCodes(config))
        {
            if (present == null || !present.contains(declared))
            {
                missing.add(declared);
            }
        }
        return missing;
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

    /**
     * Builds a string from BMP code points. The canonical home of the helper used for the Russian
     * (bilingual) tokens across the resolvers / writers ({@code MetadataNodeResolver},
     * {@code FormElementWriter}, {@code ModifyMetadataTool}), so those sources stay pure ASCII
     * (encoding-independent under the non-UTF-8 Tycho build) instead of carrying raw Cyrillic
     * literals.
     *
     * @param codePoints the BMP code points of the token characters
     * @return the assembled token string
     */
    public static String cp(int... codePoints)
    {
        StringBuilder sb = new StringBuilder(codePoints.length);
        for (int c : codePoints)
        {
            sb.append((char)c);
        }
        return sb.toString();
    }
}
