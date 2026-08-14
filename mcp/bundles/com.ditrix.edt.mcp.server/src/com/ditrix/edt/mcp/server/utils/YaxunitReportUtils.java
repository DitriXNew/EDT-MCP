/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Shared discovery and rendering for complete or partial YAXUnit JUnit reports. */
public final class YaxunitReportUtils
{
    private static final String JUNIT_XML = "junit.xml"; //$NON-NLS-1$

    private YaxunitReportUtils()
    {
        // Utility class
    }

    /** Finds the report name used by YAXUnit, its known fallbacks, or the first XML file. */
    public static File findJunitXml(Path reportDir)
    {
        if (reportDir == null || !Files.exists(reportDir))
        {
            return null;
        }

        String[] candidates = {JUNIT_XML, "report.xml", "test-report.xml"}; //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : candidates)
        {
            File file = reportDir.resolve(name).toFile();
            if (file.exists() && file.length() > 0)
            {
                return file;
            }
        }

        File[] xmlFiles = reportDir.toFile().listFiles((dir, name) -> name.endsWith(".xml")); //$NON-NLS-1$
        return xmlFiles != null && xmlFiles.length > 0 ? xmlFiles[0] : null;
    }

    /** Parses, formats, and best-effort saves a normal completed-run report. */
    public static String renderAndSave(File junitXml)
    {
        try
        {
            String markdown = render(junitXml);
            Path reportFile = junitXml.toPath().resolveSibling("report.md"); //$NON-NLS-1$
            if (writeReportFile(reportFile, markdown))
            {
                return markdown + "\n---\n*Full report saved to:* `" + reportFile + "`\n"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return markdown;
        }
        catch (Exception e)
        {
            Activator.logError("Error parsing JUnit XML: " + junitXml, e); //$NON-NLS-1$
            return ToolResult.error("Failed to parse test results: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Renders report content only when the XML written before termination is usable.
     *
     * @return Markdown report, or {@code null} for absent/incomplete/unparseable content
     */
    public static String renderIfUsable(Path reportDir)
    {
        File junitXml = findJunitXml(reportDir);
        if (junitXml == null)
        {
            return null;
        }
        try
        {
            return render(junitXml);
        }
        catch (Exception e) // NOSONAR incomplete XML is an expected cancellation outcome
        {
            return null;
        }
    }

    private static String render(File junitXml) throws Exception
    {
        JUnitTestResults results = JUnitXmlParser.parse(junitXml);
        return JUnitMarkdownFormatter.format(results);
    }

    private static boolean writeReportFile(Path reportFile, String markdown)
    {
        try
        {
            Files.write(reportFile, markdown.getBytes(StandardCharsets.UTF_8));
            return Files.exists(reportFile);
        }
        catch (IOException e)
        {
            Activator.logError("Failed to write Markdown report to " + reportFile, e); //$NON-NLS-1$
            return false;
        }
    }
}
