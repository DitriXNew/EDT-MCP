/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Assume;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ObjectHelpReader.HelpPage;

/**
 * Tests for {@link ObjectHelpReader#readFromFolder(IProject, String)} - the disk-based help lookup for
 * an EXPLICIT object folder, relative to the project root. This is the path a caller must use when the
 * generic {@link ObjectHelpReader#read} resource-lookup hop would resolve to the WRONG folder: an owned
 * form's {@code BasicForm} resolves, via {@code IResourceLookup.getPlatformResource}, to its OWNER's
 * {@code .mdo} (the catalog/document/etc.), not the form's own {@code Forms/<FormName>/} folder - see
 * {@code GetMetadataDetailsTool.formatFormHelp}, which resolves the form's own folder via
 * {@code MetadataPathResolver.resolveFormFolderPath} and reads it through this method.
 * <p>
 * A real temporary directory stands in for the project root (the same pattern
 * {@code EventLogLocatorTest} / {@code ResyncToDiskToolTest} use for filesystem-backed logic); only
 * {@link IProject#getLocation()} is mocked, needing no live workspace.
 */
public class ObjectHelpReaderTest
{
    private static IProject projectAt(Path root)
    {
        IProject project = mock(IProject.class);
        // IPath is OS-agnostic internally but the constructor expects '/'-separated segments - a
        // Windows absolute path (backslashes) is normalized before handing it to Path, the same way a
        // relative folder path (e.g. "src/Catalogs/Foo/Forms/Bar") is always '/'-separated in this
        // codebase (MetadataPathResolver never emits a backslash).
        String normalized = root.toAbsolutePath().toString().replace('\\', '/');
        when(project.getLocation()).thenReturn(new org.eclipse.core.runtime.Path(normalized));
        return project;
    }

    @Test
    public void testNullProjectReturnsEmpty()
    {
        assertTrue(ObjectHelpReader.readFromFolder(null, "src/Catalogs/Foo/Forms/Bar").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testNullFolderPathReturnsEmpty() throws IOException
    {
        IProject project = projectAt(Files.createTempDirectory("help-null-folder")); //$NON-NLS-1$
        assertTrue(ObjectHelpReader.readFromFolder(project, null).isEmpty());
    }

    @Test
    public void testProjectWithoutLocationReturnsEmpty()
    {
        IProject project = mock(IProject.class);
        when(project.getLocation()).thenReturn(null);
        assertTrue(ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo/Forms/Bar").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testMissingHelpDirReturnsEmpty() throws IOException
    {
        Path root = Files.createTempDirectory("help-missing"); //$NON-NLS-1$
        Files.createDirectories(root.resolve("src/Catalogs/Foo/Forms/Bar")); //$NON-NLS-1$
        // No Help/ subdirectory under the form's own folder.
        IProject project = projectAt(root);

        assertTrue(ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo/Forms/Bar").isEmpty()); //$NON-NLS-1$
    }

    /**
     * The core regression case for the "owned form help" fix: the form's OWN {@code Forms/Bar/Help/}
     * folder (NOT the owner Catalog's own {@code Help/}) is read when given the form's own relative
     * folder path.
     */
    @Test
    public void testReadsSingleOwnedFormHelpPage() throws IOException
    {
        Path root = Files.createTempDirectory("help-form"); //$NON-NLS-1$
        Path helpDir = root.resolve("src/Catalogs/Foo/Forms/Bar/Help"); //$NON-NLS-1$
        Files.createDirectories(helpDir);
        Files.write(helpDir.resolve("en.html"), "<p>Item help</p>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        IProject project = projectAt(root);

        List<HelpPage> pages = ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo/Forms/Bar"); //$NON-NLS-1$

        assertEquals(1, pages.size());
        assertEquals("en", pages.get(0).lang); //$NON-NLS-1$
        assertTrue(pages.get(0).markdown.contains("Item help")); //$NON-NLS-1$
    }

    @Test
    public void testMultipleLanguagesOrderedByLanguageCode() throws IOException
    {
        Path root = Files.createTempDirectory("help-multi-lang"); //$NON-NLS-1$
        Path helpDir = root.resolve("src/CommonForms/Bar/Help"); //$NON-NLS-1$
        Files.createDirectories(helpDir);
        // The RU-language page's own content need not be genuine Russian prose - it only needs to be a
        // distinct, non-empty page so the ordering assertion below is meaningful.
        Files.write(helpDir.resolve("ru.html"), "<p>RU help page</p>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(helpDir.resolve("en.html"), "<p>Help</p>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        IProject project = projectAt(root);

        List<HelpPage> pages = ObjectHelpReader.readFromFolder(project, "src/CommonForms/Bar"); //$NON-NLS-1$

        assertEquals(2, pages.size());
        assertEquals("en", pages.get(0).lang); //$NON-NLS-1$
        assertEquals("ru", pages.get(1).lang); //$NON-NLS-1$
    }

    /** A page whose HTML converts to empty Markdown (e.g. an empty file) is skipped, not rendered blank. */
    @Test
    public void testEmptyHtmlPageIsSkipped() throws IOException
    {
        Path root = Files.createTempDirectory("help-empty"); //$NON-NLS-1$
        Path helpDir = root.resolve("src/Catalogs/Foo/Forms/Bar/Help"); //$NON-NLS-1$
        Files.createDirectories(helpDir);
        Files.write(helpDir.resolve("en.html"), new byte[0]); //$NON-NLS-1$

        IProject project = projectAt(root);

        assertTrue(ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo/Forms/Bar").isEmpty()); //$NON-NLS-1$
    }

    // ---- Symlink escape rejection --------------------------------------------------------------

    @Test
    public void testHelpDirectoryItselfSymlinkPointingOutsideProjectIsRejected() throws IOException
    {
        Path root = Files.createTempDirectory("help-symlink-dir"); //$NON-NLS-1$
        Path outsideHelp = Files.createTempDirectory("help-symlink-outside").resolve("Help"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(outsideHelp);
        Files.write(outsideHelp.resolve("en.html"), "<p>Secret</p>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        Path formDir = root.resolve("src/Catalogs/Foo/Forms/Bar"); //$NON-NLS-1$
        Files.createDirectories(formDir);
        try
        {
            Files.createSymbolicLink(formDir.resolve("Help"), outsideHelp); //$NON-NLS-1$
        }
        catch (IOException | UnsupportedOperationException e)
        {
            Assume.assumeNoException("symlink creation not permitted in this environment", e); //$NON-NLS-1$
            return;
        }
        IProject project = projectAt(root);

        List<HelpPage> pages = ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo/Forms/Bar"); //$NON-NLS-1$

        assertTrue("a Help directory that is a symlink escaping the project must be rejected", //$NON-NLS-1$
            pages.isEmpty());
    }

    @Test
    public void testHelpFileSymlinkEscapingTheHelpDirectoryIsSkipped() throws IOException
    {
        Path root = Files.createTempDirectory("help-symlink-file"); //$NON-NLS-1$
        Path helpDir = root.resolve("src/Catalogs/Foo/Help"); //$NON-NLS-1$
        Files.createDirectories(helpDir);
        Files.write(helpDir.resolve("en.html"), "<p>Real help</p>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        Path secret = Files.createTempFile("help-secret", ".html"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(secret, "<p>Secret content</p>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.createSymbolicLink(helpDir.resolve("ru.html"), secret); //$NON-NLS-1$
        }
        catch (IOException | UnsupportedOperationException e)
        {
            Assume.assumeNoException("symlink creation not permitted in this environment", e); //$NON-NLS-1$
            return;
        }
        IProject project = projectAt(root);

        List<HelpPage> pages = ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo"); //$NON-NLS-1$

        assertEquals("only the real, non-symlinked page must be read", 1, pages.size()); //$NON-NLS-1$
        assertEquals("en", pages.get(0).lang); //$NON-NLS-1$
    }

    // ---- Size guards: per-file cap and total-budget cap ----------------------------------------

    @Test
    public void testOversizedSingleFileIsSkippedWithPlaceholder() throws IOException
    {
        Path root = Files.createTempDirectory("help-oversized"); //$NON-NLS-1$
        Path helpDir = root.resolve("src/Catalogs/Foo/Help"); //$NON-NLS-1$
        Files.createDirectories(helpDir);
        Files.write(helpDir.resolve("en.html"), buildHtml(ObjectHelpReader.MAX_HELP_FILE_BYTES + 1)); //$NON-NLS-1$
        IProject project = projectAt(root);

        List<HelpPage> pages = ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo"); //$NON-NLS-1$

        assertEquals(1, pages.size());
        assertEquals("en", pages.get(0).lang); //$NON-NLS-1$
        assertTrue("an oversized file must not be converted - a placeholder note stands in for it: " //$NON-NLS-1$
            + pages.get(0).markdown, pages.get(0).markdown.contains("over the")); //$NON-NLS-1$
        assertFalse("must NOT contain the actual filler content - it was never read", //$NON-NLS-1$
            pages.get(0).markdown.contains("Filler")); //$NON-NLS-1$
    }

    @Test
    public void testTotalBudgetExceededSkipsLaterPagesWithPlaceholder() throws IOException
    {
        Path root = Files.createTempDirectory("help-total-budget"); //$NON-NLS-1$
        Path helpDir = root.resolve("src/Catalogs/Foo/Help"); //$NON-NLS-1$
        Files.createDirectories(helpDir);
        // Three pages each AT the per-file cap: the first two fit the total budget (2 * cap <=
        // budget), the third would push the running total over it and must be skipped, not read.
        Files.write(helpDir.resolve("en.html"), buildHtml(ObjectHelpReader.MAX_HELP_FILE_BYTES)); //$NON-NLS-1$
        Files.write(helpDir.resolve("fr.html"), buildHtml(ObjectHelpReader.MAX_HELP_FILE_BYTES)); //$NON-NLS-1$
        Files.write(helpDir.resolve("ru.html"), buildHtml(ObjectHelpReader.MAX_HELP_FILE_BYTES)); //$NON-NLS-1$
        IProject project = projectAt(root);

        List<HelpPage> pages = ObjectHelpReader.readFromFolder(project, "src/Catalogs/Foo"); //$NON-NLS-1$

        assertEquals(3, pages.size());
        assertTrue("the first language, within budget, must be genuinely converted", //$NON-NLS-1$
            pages.get(0).markdown.contains("Filler")); //$NON-NLS-1$
        assertTrue("the second language, still within budget, must be genuinely converted", //$NON-NLS-1$
            pages.get(1).markdown.contains("Filler")); //$NON-NLS-1$
        assertTrue("the third language pushes the running total past the budget and must be a " //$NON-NLS-1$
            + "placeholder: " + pages.get(2).markdown, pages.get(2).markdown.contains("budget")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A simple {@code <p>...</p>} HTML page padded with filler text to EXACTLY {@code totalBytes}. */
    private static byte[] buildHtml(long totalBytes)
    {
        int n = (int)totalBytes;
        String prefix = "<p>Filler "; //$NON-NLS-1$
        String suffix = "</p>"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(n);
        sb.append(prefix);
        while (sb.length() < n - suffix.length())
        {
            sb.append('x');
        }
        sb.setLength(n - suffix.length());
        sb.append(suffix);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
