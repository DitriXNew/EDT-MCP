/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Shared pure parser for every localized DCS title or presentation. */
public final class DcsPresentationParser
{
    private static final Set<String> PRESENTATION_MEMBERS = presentationMembers();

    private DcsPresentationParser()
    {
        // Utility class
    }

    /**
     * Recursively validates all {@code title} and {@code presentation} members in a DCS body. This
     * is the common entry point for schema, settings, and dynamic-list writers, so a nested branch
     * cannot bypass language-code validation.
     *
     * @param root body subtree to inspect
     * @param languages configured language-code context
     * @return {@code null} on success, or an actionable error
     */
    public static String validateRecursively(JsonElement root, LanguageContext languages)
    {
        return validateRecursively(root, languages, "body"); //$NON-NLS-1$
    }

    private static String validateRecursively(JsonElement value, LanguageContext languages,
        String path)
    {
        if (value == null || value.isJsonNull())
        {
            return null;
        }
        if (value.isJsonArray())
        {
            for (int i = 0; i < value.getAsJsonArray().size(); i++)
            {
                String error = validateRecursively(value.getAsJsonArray().get(i), languages,
                    path + "[" + i + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                if (error != null)
                {
                    return error;
                }
            }
            return null;
        }
        if (!value.isJsonObject())
        {
            return null;
        }
        for (Map.Entry<String, JsonElement> member : value.getAsJsonObject().entrySet())
        {
            String memberPath = path + "." + member.getKey(); //$NON-NLS-1$
            if (PRESENTATION_MEMBERS.contains(member.getKey()))
            {
                ParseResult parsed = parse(member.getValue(), languages, memberPath);
                if (!parsed.isSuccess())
                {
                    return parsed.error();
                }
            }
            String error = validateRecursively(member.getValue(), languages, memberPath);
            if (error != null)
            {
                return error;
            }
        }
        return null;
    }

    /** Parses one optional presentation without touching the model. */
    public static ParseResult parse(JsonElement element, LanguageContext languages, String path)
    {
        if (element == null || element.isJsonNull())
        {
            return ParseResult.success(null);
        }
        if (element.isJsonPrimitive())
        {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isString())
            {
                return ParseResult.failure("Presentation '" + path //$NON-NLS-1$
                    + "' must be a string or a {languageCode:text} object."); //$NON-NLS-1$
            }
            String text = primitive.getAsString();
            if (text.isEmpty())
            {
                return ParseResult.success(null);
            }
            // A plain string is stored under the language the call is working in: the language
            // parameter when one is given, and the CONFIGURATION's default language when it is not.
            // It is not stored as Presentation.value. EDT never writes the neutral form - 41940 of
            // 41940 presentation and title elements in ERP 2.5.16 are LocalStrings - and shipped
            // consumers rely on that: the platform's DataCompositionNameVariantDefaultCheck
            // dereferences getLocalValue().getContent() with no guard, so a neutral value makes the
            // whole check throw and stop validating the schema. Our own localized parameter value
            // refused it outright for the same reason.
            String code = languages == null ? "en" : languages.writeLanguageCode(); //$NON-NLS-1$
            Map<String, String> single = new LinkedHashMap<>();
            single.put(code, text);
            if (languages != null)
            {
                languages.record(code);
            }
            return ParseResult.success(Plan.localized(single));
        }
        if (!element.isJsonObject())
        {
            return ParseResult.failure("Presentation '" + path //$NON-NLS-1$
                + "' must be a string or a {languageCode:text} object."); //$NON-NLS-1$
        }

        JsonObject object = element.getAsJsonObject();
        if (object.entrySet().isEmpty())
        {
            return ParseResult.success(null);
        }
        if (languages == null)
        {
            Map<String, String> localized = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> member : object.entrySet())
            {
                JsonElement text = member.getValue();
                if (text == null || !text.isJsonPrimitive()
                    || !text.getAsJsonPrimitive().isString())
                {
                    return ParseResult.failure("Localized presentation '" + path + "' value for '" //$NON-NLS-1$ //$NON-NLS-2$
                        + member.getKey() + "' must be a string."); //$NON-NLS-1$
                }
                localized.put(member.getKey(), text.getAsString());
            }
            return ParseResult.success(Plan.localized(localized));
        }
        if (languages.declaredCodes().isEmpty())
        {
            String first = object.keySet().iterator().next();
            return ParseResult.failure("This configuration declares no language codes, so '" //$NON-NLS-1$
                + first + "' in presentation '" + path + "' cannot be stored where anything " //$NON-NLS-1$ //$NON-NLS-2$
                + "would display it. Add a Language object with a 'languageCode', then retry."); //$NON-NLS-1$
        }

        Map<String, String> localized = new LinkedHashMap<>();
        Map<String, String> originalSpellings = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> member : object.entrySet())
        {
            JsonElement text = member.getValue();
            if (text == null || !text.isJsonPrimitive()
                || !text.getAsJsonPrimitive().isString())
            {
                return ParseResult.failure("Localized presentation '" + path + "' value for '" //$NON-NLS-1$ //$NON-NLS-2$
                    + member.getKey() + "' must be a string."); //$NON-NLS-1$
            }
            String canonical = languages.canonical(member.getKey());
            if (canonical == null)
            {
                return ParseResult.failure("Unknown language '" + member.getKey() //$NON-NLS-1$
                    + "' in presentation '" + path + "'. This configuration declares: " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join(", ", languages.declaredCodes()) //$NON-NLS-1$
                    + ". Use one of those codes; undeclared codes are never displayed."); //$NON-NLS-1$
            }
            if (localized.containsKey(canonical))
            {
                return ParseResult.failure("Presentation '" + path + "' names language '" //$NON-NLS-1$ //$NON-NLS-2$
                    + canonical + "' twice (as '" + originalSpellings.get(canonical) + "' and '" //$NON-NLS-1$ //$NON-NLS-2$
                    + member.getKey() + "'). Give that language once."); //$NON-NLS-1$
            }
            localized.put(canonical, text.getAsString());
            originalSpellings.put(canonical, member.getKey());
            languages.record(canonical);
        }
        return ParseResult.success(Plan.localized(localized));
    }

    /** Builds the typed DCS presentation after validation has completed. */
    public static Presentation build(Plan plan)
    {
        if (plan == null)
        {
            return null;
        }
        Presentation presentation =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createPresentation();
        LocalString local =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString();
        plan.localized.forEach(local.getContent()::put);
        presentation.setLocalValue(local);
        return presentation;
    }

    /**
     * Configured language-code spellings, the resolved presentation code, whether a language was
     * selected for this call, the configuration code used for serialized catalogue names, and
     * codes used by the parsed body.
     */
    public static final class LanguageContext
    {
        private final List<String> declaredCodes;
        private final String resolvedCode;
        private final String configurationCode;
        private final boolean languageSelected;
        private final Set<String> usedCodes = new LinkedHashSet<>();

        public LanguageContext(List<String> declaredCodes)
        {
            this(declaredCodes, null, null, false);
        }

        public LanguageContext(List<String> declaredCodes, String resolvedCode)
        {
            this(declaredCodes, resolvedCode, resolvedCode,
                resolvedCode != null && !resolvedCode.isEmpty());
        }

        public LanguageContext(List<String> declaredCodes, String resolvedCode,
            String configurationCode)
        {
            this(declaredCodes, resolvedCode, configurationCode,
                resolvedCode != null && !resolvedCode.isEmpty());
        }

        public LanguageContext(List<String> declaredCodes, String resolvedCode,
            String configurationCode, boolean languageSelected)
        {
            List<String> copy = new ArrayList<>();
            if (declaredCodes != null)
            {
                for (String code : declaredCodes)
                {
                    if (code != null && !code.isEmpty() && !copy.contains(code))
                    {
                        copy.add(code);
                    }
                }
            }
            this.declaredCodes = Collections.unmodifiableList(copy);
            if (resolvedCode == null || resolvedCode.isEmpty())
            {
                this.resolvedCode = copy.isEmpty() ? "en" : copy.get(0); //$NON-NLS-1$
            }
            else
            {
                this.resolvedCode = resolvedCode;
            }
            this.configurationCode = configurationCode == null || configurationCode.isEmpty()
                ? this.resolvedCode : configurationCode;
            this.languageSelected = languageSelected
                && resolvedCode != null && !resolvedCode.isEmpty();
        }

        public List<String> declaredCodes()
        {
            return declaredCodes;
        }

        public String resolvedCode()
        {
            return resolvedCode;
        }

        /** The configuration's own language CODE, independent of a per-call presentation code. */
        public String configurationCode()
        {
            return configurationCode;
        }

        /**
         * The language code for a plain-string write: the selected call language when present,
         * otherwise the configuration's default code.
         */
        public String writeLanguageCode()
        {
            return languageSelected ? resolvedCode : configurationCode;
        }

        public Set<String> usedCodes()
        {
            return Collections.unmodifiableSet(usedCodes);
        }

        private String canonical(String requested)
        {
            for (String code : declaredCodes)
            {
                if (code.equals(requested) || code.equalsIgnoreCase(requested))
                {
                    return code;
                }
            }
            return null;
        }

        private void record(String code)
        {
            usedCodes.add(code);
        }
    }

    /** Validated presentation data; detached from EMF until {@link #build(Plan)}. */
    public static final class Plan
    {
        private final Map<String, String> localized;

        private Plan(Map<String, String> localized)
        {
            this.localized = localized == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(localized));
        }

        private static Plan localized(Map<String, String> value)
        {
            return new Plan(value);
        }
    }

    /** Parse outcome for one presentation. */
    public static final class ParseResult
    {
        private final Plan plan;
        private final String error;

        private ParseResult(Plan plan, String error)
        {
            this.plan = plan;
            this.error = error;
        }

        private static ParseResult success(Plan plan)
        {
            return new ParseResult(plan, null);
        }

        private static ParseResult failure(String error)
        {
            return new ParseResult(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public Plan plan()
        {
            return plan;
        }

        public String error()
        {
            return error;
        }
    }

    private static Set<String> presentationMembers()
    {
        Set<String> result = new LinkedHashSet<>();
        result.add("title"); //$NON-NLS-1$
        result.add("presentation"); //$NON-NLS-1$
        result.add("userSettingPresentation"); //$NON-NLS-1$
        result.add("itemsUserSettingPresentation"); //$NON-NLS-1$
        return Collections.unmodifiableSet(result);
    }
}
