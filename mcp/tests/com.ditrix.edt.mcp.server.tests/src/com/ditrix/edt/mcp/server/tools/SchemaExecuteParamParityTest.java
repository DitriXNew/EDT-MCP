/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Enforces CLAUDE.md rule #6 (schema/execute parameter parity) automatically — the
 * direction {@code ToolContractConsistencyTest} explicitly leaves "not asserted":
 * every parameter key that {@code execute()} pulls out of {@code params} MUST be a
 * declared {@code inputSchema} property, and every declared property must actually be
 * read. A renamed/typo'd key (schema {@code fqn} vs code {@code objectFqn}) or an
 * orphaned schema property then fails the build instead of slipping through review.
 * <p>
 * Mechanism: a source scan of each {@code tools/impl} tool's {@code .java} for the
 * project's parameter-accessor idioms ({@code JsonUtils.extract*Argument} /
 * {@code requireArgument(s)} / direct {@code params.get(...)}), cross-checked against
 * the property names parsed from the live {@code getInputSchema()}.
 * <p>
 * Convention assumption: params are read with a STRING-LITERAL key (verified: every
 * {@code tools/impl} accessor today passes a literal — no constant-key, {@code
 * getOrDefault}, key-iteration, or {@code readParam(...)} helper). If a future tool
 * reads a declared param through a non-literal key, the {@code declared -> read}
 * direction will flag it as "unread" — that is a signal to make the read explicit (a
 * literal key) or model the new idiom here, NOT to loosen the test.
 */
public class SchemaExecuteParamParityTest
{
    /** Single-key accessors: extract*Argument(params, "key" ...) and requireArgument(params, "key" ...). */
    private static final Pattern SINGLE_KEY = Pattern.compile(
        "(?:extractStringArgument|extractArrayArgument|extractObjectArray|extractBooleanArgument|extractLongArgument|" //$NON-NLS-1$
            + "extractIntArgument|extractDoubleArgument|requireArgument)\\s*\\(\\s*params\\s*,\\s*\"([a-zA-Z][a-zA-Z0-9]*)\""); //$NON-NLS-1$

    /** Varargs: requireArguments(params, "a", "b", ...). Captures the whole quoted-list tail. */
    private static final Pattern MULTI_KEY = Pattern.compile(
        "requireArguments\\s*\\(\\s*params\\s*,\\s*((?:\"[a-zA-Z][a-zA-Z0-9]*\"\\s*,?\\s*)+)\\)"); //$NON-NLS-1$

    /** Direct map access: params.get("key") / params.containsKey("key"). */
    private static final Pattern DIRECT_KEY = Pattern.compile(
        "params\\s*\\.\\s*(?:get|containsKey)\\s*\\(\\s*\"([a-zA-Z][a-zA-Z0-9]*)\""); //$NON-NLS-1$

    private static final Pattern QUOTED = Pattern.compile("\"([a-zA-Z][a-zA-Z0-9]*)\""); //$NON-NLS-1$

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void everyKeyReadInExecuteIsDeclaredInTheSchema()
    {
        File implDir = locateToolsImplSourceDir();
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            Class<?> cls = tool.getClass();
            if (!cls.getName().contains(".tools.impl.")) //$NON-NLS-1$
            {
                continue;
            }
            String source = readSource(implDir, cls);
            if (source == null)
            {
                continue; // tools whose key handling lives elsewhere are out of scope
            }
            Set<String> declared = declaredProperties(tool);
            Set<String> read = readKeys(source);
            for (String key : read)
            {
                if (!declared.contains(key))
                {
                    problems.add(cls.getSimpleName() + ": execute() reads param '" + key //$NON-NLS-1$
                        + "' that is NOT a declared inputSchema property (rule #6)"); //$NON-NLS-1$
                }
            }
        }
        assertTrue("schema/execute parity (read-but-undeclared) violations:\n  " //$NON-NLS-1$
            + String.join("\n  ", problems), problems.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void everyDeclaredPropertyIsReadInExecute()
    {
        File implDir = locateToolsImplSourceDir();
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            Class<?> cls = tool.getClass();
            if (!cls.getName().contains(".tools.impl.")) //$NON-NLS-1$
            {
                continue;
            }
            String source = readSource(implDir, cls);
            if (source == null)
            {
                continue;
            }
            Set<String> declared = declaredProperties(tool);
            Set<String> read = readKeys(source);
            for (String prop : declared)
            {
                // An orphaned/typo'd schema property: declared but the code never reads
                // it by that name (a renamed key, or a dead property).
                if (!read.contains(prop))
                {
                    problems.add(cls.getSimpleName() + ": inputSchema declares '" + prop //$NON-NLS-1$
                        + "' but execute() never reads it by that name (orphan / typo - rule #6)"); //$NON-NLS-1$
                }
            }
        }
        assertTrue("schema/execute parity (declared-but-unread) violations:\n  " //$NON-NLS-1$
            + String.join("\n  ", problems), problems.isEmpty()); //$NON-NLS-1$
    }

    // === helpers ===

    private static Set<String> declaredProperties(IMcpTool tool)
    {
        Set<String> names = new HashSet<>();
        try
        {
            JsonElement parsed = JsonParser.parseString(tool.getInputSchema());
            if (parsed.isJsonObject())
            {
                JsonElement props = parsed.getAsJsonObject().get("properties"); //$NON-NLS-1$
                if (props != null && props.isJsonObject())
                {
                    names.addAll(props.getAsJsonObject().keySet());
                }
            }
        }
        catch (RuntimeException e)
        {
            // schema well-formedness is asserted by ToolContractConsistencyTest
        }
        return names;
    }

    private static Set<String> readKeys(String rawSource)
    {
        // Strip line comments first: accessor calls are routinely split as
        // `extractBooleanArgument(params, //$NON-NLS-1$\n "key", default)`, so the
        // trailing //$NON-NLS-N$ marker sits between `params,` and the key and would
        // otherwise hide the read. Param keys never contain "//", so removing
        // //...EOL cannot drop or invent a key (it may truncate an unrelated string
        // literal, which is irrelevant to key extraction).
        String source = rawSource.replaceAll("//[^\\n]*", ""); //$NON-NLS-1$
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = SINGLE_KEY.matcher(source);
        while (m.find())
        {
            keys.add(m.group(1));
        }
        m = DIRECT_KEY.matcher(source);
        while (m.find())
        {
            keys.add(m.group(1));
        }
        m = MULTI_KEY.matcher(source);
        while (m.find())
        {
            Matcher q = QUOTED.matcher(m.group(1));
            while (q.find())
            {
                keys.add(q.group(1));
            }
        }
        return keys;
    }

    private static String readSource(File implDir, Class<?> cls)
    {
        File f = new File(implDir, cls.getSimpleName() + ".java"); //$NON-NLS-1$
        if (!f.isFile())
        {
            return null;
        }
        try
        {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            fail("failed reading source " + f + ": " + e.getMessage()); //$NON-NLS-1$
            return null; // unreachable
        }
    }

    /**
     * Locate {@code .../com.ditrix.edt.mcp.server/src/.../tools/impl} by walking up from
     * the working directory (Tycho surefire runs inside the repo checkout). Fails loudly
     * rather than silently passing if the source tree cannot be found.
     */
    private static File locateToolsImplSourceDir()
    {
        String rel = "bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl"; //$NON-NLS-1$
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File direct = new File(dir, rel);
            if (direct.isDirectory())
            {
                return direct;
            }
            File underMcp = new File(dir, "mcp/" + rel); //$NON-NLS-1$
            if (underMcp.isDirectory())
            {
                return underMcp;
            }
            dir = dir.getParentFile();
        }
        fail("could not locate the tools/impl source dir by walking up from user.dir=" //$NON-NLS-1$
            + System.getProperty("user.dir") //$NON-NLS-1$
            + " (looked for '" + rel + "'). Adjust the locator for this build layout."); //$NON-NLS-1$ //$NON-NLS-2$
        return null; // unreachable
    }
}
