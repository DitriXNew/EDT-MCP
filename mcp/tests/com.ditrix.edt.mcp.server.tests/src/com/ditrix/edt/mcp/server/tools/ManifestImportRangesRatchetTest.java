/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Keeps package imports aligned with EDT's Guice injector and supported Gson versions (#640).
 * Bundle versions and exported package versions are different; Guice 7 exports version 1.4.
 *
 * <table>
 * <caption>EDT exporters recorded for this compatibility fix</caption>
 * <tr><th>EDT</th><th>Guice bundle - package</th><th>Gson bundle - package</th></tr>
 * <tr><td>2026.1.2</td><td>7.0.0 - 1.4</td><td>2.10.1 - 2.10.1</td></tr>
 * <tr><td>2026.2.0</td><td>7.0.2 - 1.4</td><td>2.13.2 - 2.13.2</td></tr>
 * <tr><td>2026.2.1</td><td>7.0.2 - 1.4; 5.0.1 - 5.0.1</td>
 *     <td>2.13.2 - 2.13.2; 2.8.9 - 2.8.9</td></tr>
 * </table>
 *
 * EDT's wiring bundle requires Guice [7.0.0,8.0.0). Importing its historical package range
 * [1.4,2.0) excludes the competing Guice 5 exporter. Gson [2.10,3) keeps 2026.1.2 compatible
 * while excluding 2.8.9. An unversioned import can resolve to an incompatible exporter.
 */
public class ManifestImportRangesRatchetTest
{
    private static final String BUNDLE_MANIFEST =
        "mcp/bundles/com.ditrix.edt.mcp.server/META-INF/MANIFEST.MF"; //$NON-NLS-1$

    @Test
    public void guiceImportUsesTheHistoricalPackageVersion() throws IOException
    {
        assertImportRange("com.google.inject", "[1.4,2.0)"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void gsonImportExcludesOlderAndFutureMajorVersions() throws IOException
    {
        assertImportRange("com.google.gson", "[2.10,3)"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertImportRange(String packageName, String range) throws IOException
    {
        String imports = manifestImportPackage();
        assertNotNull("could not read Import-Package from " + BUNDLE_MANIFEST, imports); //$NON-NLS-1$
        String clause = packageName + ";version=\"" + range + '"'; //$NON-NLS-1$
        Pattern exactClause = Pattern.compile("(?:^|,)\\s*" + Pattern.quote(clause) + "(?=,|$)"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Import-Package must contain exactly " + clause, exactClause.matcher(imports).find()); //$NON-NLS-1$
    }

    private static String manifestImportPackage() throws IOException
    {
        File manifest = locate(BUNDLE_MANIFEST);
        assertNotNull("could not locate " + BUNDLE_MANIFEST + " from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir"), manifest); //$NON-NLS-1$
        String text = Files.readString(manifest.toPath(), StandardCharsets.UTF_8)
            .replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder unfolded = new StringBuilder();
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            if (line.startsWith(" ") && unfolded.length() > 0) //$NON-NLS-1$
            {
                unfolded.append(line, 1, line.length());
            }
            else
            {
                unfolded.append('\n').append(line);
            }
        }
        String prefix = "Import-Package:"; //$NON-NLS-1$
        for (String header : unfolded.toString().split("\n", -1)) //$NON-NLS-1$
        {
            if (header.startsWith(prefix))
            {
                return header.substring(prefix.length());
            }
        }
        return null;
    }

    private static File locate(String relative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, relative);
            if (candidate.isFile())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
