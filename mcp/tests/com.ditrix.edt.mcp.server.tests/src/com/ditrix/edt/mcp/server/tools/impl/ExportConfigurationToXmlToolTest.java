/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lightweight tests for {@link ExportConfigurationToXmlTool} that exercise tool
 * metadata, JSON schema, and the argument/path-validation guards that fire BEFORE
 * any EDT CLI access — so they run headless.
 * <p>
 * {@code execute()} requires {@code projectName} + {@code outputPath} up front, then
 * does a filesystem check ("outputPath exists but is not a directory") that touches
 * only {@code java.nio.file}, not EDT, then resolves the project (an unresolved name
 * yields a value-naming "Project not found" error before the CLI API is touched). The
 * real export needs a live workspace and is covered by the E2E suite.
 */
public class ExportConfigurationToXmlToolTest
{
    @Test
    public void testStaleRootDoesNotProveThisCallExported() throws Exception
    {
        assertUnchangedRootIsError(ExportMode.NOTHING);
    }

    @Test
    public void testIdenticalRewriteWithEqualTimestampCannotBeProven() throws Exception
    {
        assertUnchangedRootIsError(ExportMode.SAME_BYTES_SAME_MTIME);
    }

    @Test
    public void testStrictlyNewerTimestampProvesIdenticalRootRewrite() throws Exception
    {
        try (ExportAttempt attempt = new ExportAttempt(ExportMode.NEWER_MTIME))
        {
            Path root = seedRoot(attempt);
            FileTime before = Files.getLastModifiedTime(root);
            String result = attempt.execute();
            assertTrue(result, result.contains("status: success"));
            assertEquals("<Metadata/>", Files.readString(root));
            assertTrue(Files.getLastModifiedTime(root).compareTo(before) > 0);
            assertEquals(1, attempt.api.calls);
        }
    }

    @Test
    public void testChangedBytesProveRewriteDespiteEqualTimestampAndSize() throws Exception
    {
        try (ExportAttempt attempt = new ExportAttempt(ExportMode.CHANGED_BYTES_SAME_MTIME))
        {
            Path root = seedRoot(attempt);
            FileTime before = Files.getLastModifiedTime(root);
            long size = Files.size(root);
            String result = attempt.execute();
            assertTrue(result, result.contains("status: success"));
            assertEquals(before, Files.getLastModifiedTime(root));
            assertEquals(size, Files.size(root));
            assertEquals("<Metadatz/>", Files.readString(root));
            assertEquals(1, attempt.api.calls);
        }
    }

    private void assertUnchangedRootIsError(ExportMode mode) throws Exception
    {
        try (ExportAttempt attempt = new ExportAttempt(mode))
        {
            Path root = seedRoot(attempt);
            FileTime before = Files.getLastModifiedTime(root);
            String result = attempt.execute();
            assertTrue("A stale root must not make this call successful: " + result, result.startsWith("{"));
            JsonObject error = JsonParser.parseString(result).getAsJsonObject();
            assertFalse(error.get("success").getAsBoolean());
            String message = error.get("error").getAsString();
            assertTrue(message, message.contains(root.toString()));
            assertTrue(message, message.contains("rewritten"));
            assertTrue(message, message.contains("EDT Error Log"));
            assertTrue(message, message.contains("fresh outputPath"));
            assertEquals("The existing root is preserved", "<Metadata/>", Files.readString(root));
            assertEquals(before, Files.getLastModifiedTime(root));
            assertEquals(1, attempt.api.calls);
        }
    }

    private Path seedRoot(ExportAttempt attempt) throws IOException
    {
        Path root = attempt.output.resolve("Configuration.xml");
        Files.writeString(root, "<Metadata/>");
        Files.setLastModifiedTime(root, FileTime.fromMillis(1_700_000_000_000L));
        return root;
    }

    @Test
    public void testNormallyReturningExportWithoutRootIsError() throws Exception
    {
        assertMissingRoot(ExportMode.NOTHING);
    }

    @Test
    public void testUnrelatedXmlDoesNotProveExportSuccess() throws Exception
    {
        assertMissingRoot(ExportMode.CHILD_XML);
    }

    @Test
    public void testDirectoryNamedConfigurationXmlIsNotAnExportedRoot() throws Exception
    {
        assertMissingRoot(ExportMode.ROOT_DIRECTORY);
    }

    @Test
    public void testNestedRootDoesNotProveRequestedDestinationWasExported() throws Exception
    {
        assertMissingRoot(ExportMode.NESTED_ROOT);
    }

    @Test
    public void testRootFileKeepsExistingSuccessResponse() throws Exception
    {
        try (ExportAttempt attempt = new ExportAttempt(ExportMode.ROOT_FILE))
        {
            String result = attempt.execute();
            assertTrue(result, result.contains("status: success"));
            assertTrue(result, result.contains("# Configuration exported to XML files"));
            assertTrue(result, result.contains(attempt.project.getName()));
            assertTrue(result, result.contains(attempt.output.toString()));
            assertTrue(Files.isRegularFile(attempt.output.resolve("Configuration.xml")));
            assertEquals(1, attempt.api.calls);
        }
    }

    private void assertMissingRoot(ExportMode mode) throws Exception
    {
        try (ExportAttempt attempt = new ExportAttempt(mode))
        {
            String result = attempt.execute();
            assertTrue("An API return without a root must be a ToolResult error: " + result,
                result.startsWith("{"));
            JsonObject error = JsonParser.parseString(result).getAsJsonObject();
            assertFalse(error.get("success").getAsBoolean());
            String message = error.get("error").getAsString();
            assertTrue(message, message.contains(attempt.output.resolve("Configuration.xml").toString()));
            assertTrue(message, message.contains("EDT Error Log"));
            assertTrue(message, message.contains("preparation"));
            assertTrue(message, message.contains("retry"));
            assertEquals(1, attempt.api.calls);
        }
    }

    private enum ExportMode
    {
        NOTHING, CHILD_XML, ROOT_DIRECTORY, NESTED_ROOT, ROOT_FILE,
        SAME_BYTES_SAME_MTIME, CHANGED_BYTES_SAME_MTIME, NEWER_MTIME
    }

    /** A normally returning CLI boundary with real output files, including its silent no-op case. */
    public static final class ExportApiProbe
    {
        private final ExportMode mode;
        int calls;

        ExportApiProbe(ExportMode mode)
        {
            this.mode = mode;
        }

        public void exportProject(String projectName, Path output) throws IOException
        {
            calls++;
            switch (mode)
            {
            case CHILD_XML:
                Files.writeString(output.resolve("Catalog.xml"), "<Metadata/>");
                break;
            case ROOT_DIRECTORY:
                Files.createDirectory(output.resolve("Configuration.xml"));
                break;
            case NESTED_ROOT:
                Files.createDirectories(output.resolve("elsewhere"));
                Files.writeString(output.resolve("elsewhere/Configuration.xml"), "<Metadata/>");
                break;
            case ROOT_FILE:
                Files.writeString(output.resolve("Configuration.xml"), "<Metadata/>");
                break;
            case SAME_BYTES_SAME_MTIME:
            case CHANGED_BYTES_SAME_MTIME:
            case NEWER_MTIME:
                Path root = output.resolve("Configuration.xml");
                FileTime before = Files.getLastModifiedTime(root);
                Files.writeString(root, mode == ExportMode.CHANGED_BYTES_SAME_MTIME ? "<Metadatz/>" : "<Metadata/>");
                Files.setLastModifiedTime(root, mode == ExportMode.NEWER_MTIME
                    ? FileTime.fromMillis(before.toMillis() + 10_000L) : before);
                break;
            default:
                break;
            }
        }
    }

    private static final class ExportAttempt implements AutoCloseable
    {
        final IProject project;
        final Path output;
        final ExportApiProbe api;
        final Field plugin;
        final Activator saved;

        ExportAttempt(ExportMode mode) throws Exception
        {
            project = ProjectContext.of("ExportRootProbe_" + System.nanoTime()).project();
            project.create(null);
            output = Files.createTempDirectory("export-root-probe-");
            api = new ExportApiProbe(mode);
            Activator activator = mock(Activator.class);
            when(activator.getExportConfigurationFilesApi()).thenReturn(api);
            plugin = Activator.class.getDeclaredField("plugin");
            plugin.setAccessible(true);
            saved = (Activator)plugin.get(null);
            plugin.set(null, activator);
        }

        String execute()
        {
            return new ExportConfigurationToXmlTool().execute(Map.of("projectName", project.getName(),
                "outputPath", output.toString()));
        }

        @Override
        public void close() throws Exception
        {
            try
            {
                project.delete(true, true, null);
                assertTrue("Remove only the scratch export", BinaryToXmlConverter.deleteQuietly(output));
            }
            finally
            {
                plugin.set(null, saved);
            }
        }
    }

    @Test
    public void testName()
    {
        assertEquals("export_configuration_to_xml", new ExportConfigurationToXmlTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        // export_configuration_to_xml is an action tool with no round-trip ID and
        // no machine-structured payload, so it returns MARKDOWN (see the
        // "Response format policy" in edt-mcp-tool-conventions).
        assertEquals(ResponseType.MARKDOWN, new ExportConfigurationToXmlTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ExportConfigurationToXmlTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsBothParameters()
    {
        String schema = new ExportConfigurationToXmlTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"outputPath\"")); //$NON-NLS-1$
    }

    @Test
    public void testBothParamsRequired()
    {
        String schema = new ExportConfigurationToXmlTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputPath must be required", tail.contains("\"outputPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new ExportConfigurationToXmlTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
    }

    // ==================== Argument & path validation (returns before any EDT access) ===========

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("outputPath", "C:/tmp/out"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportConfigurationToXmlTool().execute(params);
        assertTrue("missing projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingOutputPathIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportConfigurationToXmlTool().execute(params);
        assertTrue("missing outputPath must produce 'outputPath is required'", //$NON-NLS-1$
            result.contains("outputPath is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteOutputPathIsAFileIsError() throws IOException
    {
        // The "exists but is not a directory" guard runs purely on java.nio.file,
        // before any EDT CLI lookup — so it is reachable headless.
        Path tempFile = Files.createTempFile("export-cfg-not-a-dir", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("outputPath", tempFile.toString()); //$NON-NLS-1$
            String result = new ExportConfigurationToXmlTool().execute(params);
            assertTrue("a file outputPath must be rejected as not a directory", //$NON-NLS-1$
                result.contains("is not a directory")); //$NON-NLS-1$
            assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testExecuteUnknownProjectIsNamedErrorAndCreatesNoDirectory() throws IOException
    {
        // #683: an unknown project is refused before the CLI API (which would throw a
        // CliApiException logged at ERROR) and before outputPath is created.
        Path parent = Files.createTempDirectory("export-cfg-unknown-project"); //$NON-NLS-1$
        Path outputPath = parent.resolve("out"); //$NON-NLS-1$
        try
        {
            String badProject = "NoSuchProject_" + System.nanoTime(); //$NON-NLS-1$
            Map<String, String> params = new HashMap<>();
            params.put("projectName", badProject); //$NON-NLS-1$
            params.put("outputPath", outputPath.toString()); //$NON-NLS-1$
            String result = new ExportConfigurationToXmlTool().execute(params);
            assertTrue("an unknown project must be a 'Project not found' error: " + result, //$NON-NLS-1$
                result.contains("Project not found")); //$NON-NLS-1$
            assertTrue("the error must name the bad project", result.contains(badProject)); //$NON-NLS-1$
            assertTrue("the error must point at list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("the error must not come from the CLI API", result.contains("Export failed")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("a refused export must not create outputPath", Files.exists(outputPath)); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(outputPath);
            Files.deleteIfExists(parent);
        }
    }
}
