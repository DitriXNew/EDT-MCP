/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Pins every event-publishing ext-info pairing to the platform's exact EClass map. */
public class FormPairingMapRatchetTest
{
    private static final String WRITER_SOURCE =
        "mcp/bundles/com.ditrix.edt.mcp.server/src/" //$NON-NLS-1$
            + "com/ditrix/edt/mcp/server/utils/FormElementWriter.java"; //$NON-NLS-1$

    private static final Pattern STRING_CONSTANT = Pattern.compile(
        "(?m)^\\s*(?:public|protected|private)?\\s*static\\s+final\\s+String\\s+" //$NON-NLS-1$
            + "([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"\\s*;"); //$NON-NLS-1$

    private static final Pattern MAP_ENTRY = Pattern.compile(
        "\\bm\\.put\\(\\s*([^,]+?)\\s*,\\s*([^)]+?)\\);", Pattern.DOTALL); //$NON-NLS-1$

    private static final Pattern RETURN_VALUE =
        Pattern.compile("\\breturn\\s+([^;]+);"); //$NON-NLS-1$

    private static final Pattern DIRECT_REQUIREMENT = Pattern.compile(
        "ExtInfoRequirement\\.of\\(\\s*(\"[^\"]*\"|[A-Za-z_$][\\w$]*)\\s*\\)"); //$NON-NLS-1$

    private static final Pattern NON_NLS_MARKER =
        Pattern.compile("//\\$NON-NLS-\\d+\\$"); //$NON-NLS-1$

    @Test
    public void testEveryPublishingPairingHasADecidedPlatformType()
    {
        String source = writerSource();
        Map<String, String> constants = stringConstants(source);
        Set<String> platformTypes = new TreeSet<>();
        collectMapArgument(methodBody(source, "buildPlatformTypeMap"), 1, constants, //$NON-NLS-1$
            platformTypes);

        Set<String> pairedClassifiers = new TreeSet<>();
        for (String method : List.of("buildFieldExtInfoMap", "decorationExtInfoMap", //$NON-NLS-1$ //$NON-NLS-2$
            "additionExtInfoMap", "buildFormExtInfoMap")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            collectMapArgument(methodBody(source, method), 2, constants, pairedClassifiers);
        }
        collectReturnValues(methodBody(source, "groupExtInfoClassifierFor"), constants, //$NON-NLS-1$
            pairedClassifiers);
        collectDirectRequirements(methodBody(source, "extInfoRequirement", 2), constants, //$NON-NLS-1$
            pairedClassifiers);

        assertTrue("the source scan did not find the platform map", platformTypes.size() >= 50); //$NON-NLS-1$
        assertTrue("the source scan did not find the item and form-root pairings", //$NON-NLS-1$
            pairedClassifiers.size() >= 30);
        assertTrue("the direct Table pairing must be part of the ratchet", //$NON-NLS-1$
            pairedClassifiers.contains("DynamicListTableExtInfo")); //$NON-NLS-1$

        Set<String> missing = new TreeSet<>(pairedClassifiers);
        missing.removeAll(platformTypes);
        // The platform's own exact map also omits these two, so both publish no events.
        Set<String> expected = Set.of("ButtonGroupExtInfo", "DynamicListFormExtInfo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a new pairing needs an explicit platform-type decision", expected, missing); //$NON-NLS-1$
    }

    private static void collectMapArgument(String body, int group,
        Map<String, String> constants, Set<String> target)
    {
        Matcher entries = MAP_ENTRY.matcher(body);
        while (entries.find())
        {
            addResolved(entries.group(group), constants, target);
        }
    }

    private static void collectReturnValues(String body, Map<String, String> constants,
        Set<String> target)
    {
        Matcher returns = RETURN_VALUE.matcher(body);
        while (returns.find())
        {
            addResolved(returns.group(1), constants, target);
        }
    }

    private static void collectDirectRequirements(String body, Map<String, String> constants,
        Set<String> target)
    {
        Matcher requirements = DIRECT_REQUIREMENT.matcher(body);
        while (requirements.find())
        {
            addResolved(requirements.group(1), constants, target);
        }
    }

    private static void addResolved(String expression, Map<String, String> constants,
        Set<String> target)
    {
        String value = NON_NLS_MARKER.matcher(expression).replaceAll("").trim(); //$NON-NLS-1$
        if ("null".equals(value)) //$NON-NLS-1$
        {
            return;
        }
        if (value.startsWith("\"") && value.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            target.add(value.substring(1, value.length() - 1));
            return;
        }
        String resolved = constants.get(value);
        if (resolved == null)
        {
            fail("the source ratchet cannot resolve pairing expression: " + value); //$NON-NLS-1$
            return;
        }
        target.add(resolved);
    }

    private static Map<String, String> stringConstants(String source)
    {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher declarations = STRING_CONSTANT.matcher(source);
        while (declarations.find())
        {
            constants.put(declarations.group(1), declarations.group(2));
        }
        return constants;
    }

    private static String methodBody(String source, String methodName)
    {
        return methodBody(source, methodName, -1);
    }

    /** Locates an overload by parameter count; a negative count accepts the first declaration. */
    private static String methodBody(String source, String methodName, int parameterCount)
    {
        Pattern declaration = Pattern.compile(
            "(?m)^\\s*(?:public|protected|private)\\s+static\\s+[^;{]+\\b" //$NON-NLS-1$
                + Pattern.quote(methodName) + "\\s*\\(([^)]*)\\)\\s*\\{"); //$NON-NLS-1$
        Matcher method = declaration.matcher(source);
        while (method.find())
        {
            String parameters = method.group(1).trim();
            int actualCount = parameters.isEmpty() ? 0 : parameters.split(",").length; //$NON-NLS-1$
            if (parameterCount >= 0 && parameterCount != actualCount)
            {
                continue;
            }
            int open = source.indexOf('{', method.start());
            int depth = 1;
            for (int i = open + 1; i < source.length(); i++)
            {
                char ch = source.charAt(i);
                if (ch == '{')
                {
                    depth++;
                }
                else if (ch == '}' && --depth == 0)
                {
                    return source.substring(open + 1, i);
                }
            }
            fail("unterminated source method " + methodName); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
        fail("could not locate source method " + methodName); //$NON-NLS-1$
        return ""; //$NON-NLS-1$
    }

    private static String writerSource()
    {
        File source = locate(WRITER_SOURCE);
        assertNotNull("could not locate " + WRITER_SOURCE + " from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir"), source); //$NON-NLS-1$
        try
        {
            return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static File locate(String relative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, relative);
            if (candidate.exists())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
