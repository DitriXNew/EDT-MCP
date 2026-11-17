// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

/** Identifier and search folding must not depend on the host's default locale. */
public class LocaleCaseConversionRatchetTest
{
    private static final Pattern NON_CODE = Pattern.compile(
        "/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*|\"\"\"[\\s\\S]*?\"\"\"|\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern CALL = Pattern.compile("\\.\\s*to(?:Lower|Upper)Case\\s*\\(\\s*\\)");

    static long countCalls(String source)
    {
        return CALL.matcher(NON_CODE.matcher(source).replaceAll(" ")).results().count();
    }

    @Test public void noLocaleLessCallsInBundleSources() throws Exception
    {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("CLAUDE.md"))) root = root.getParent();
        assertNotNull("Cannot locate source tree", root);
        Path bundles = root.resolve("mcp/bundles");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(bundles))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")
                && bundles.relativize(p).toString().replace('\\', '/').contains("/src/")).sorted().toList())
            {
                long count = countCalls(Files.readString(file));
                if (count > 0) violations.add(bundles.relativize(file) + ": " + count);
            }
        }
        assertEquals("Use Locale.ROOT for case conversion", List.of(), violations);
    }

    @Test public void detectsBothCasesAcrossWhitespaceAndComments()
    {
        assertEquals(2, countCalls("a.toLowerCase(); b. toUpperCase /* reason */ (\n);"));
        assertEquals(0, countCalls("a.toLowerCase(Locale.ROOT); // b.toUpperCase()\n"
            + "/* c.toLowerCase() */ String s = \"d.toUpperCase()\"; char c = '\"';"));
    }
}
