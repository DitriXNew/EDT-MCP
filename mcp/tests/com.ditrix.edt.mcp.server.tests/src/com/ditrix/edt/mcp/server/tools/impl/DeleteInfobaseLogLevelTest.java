/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.Activator;

/** Regression tests for database-directory deletion diagnostics (#735). */
public class DeleteInfobaseLogLevelTest
{
    private final List<IStatus> recorded = Collections.synchronizedList(new ArrayList<>());
    private ILog log;
    private Path temporary;
    private volatile String directoryFilter;
    private final ILogListener listener = (status, plugin) -> {
        String filter = directoryFilter;
        if (filter != null && status.getMessage().startsWith("delete_infobase:") //$NON-NLS-1$
            && status.getMessage().contains(filter))
        {
            recorded.add(status);
        }
    };

    @Before
    public void captureBundleLog() throws IOException
    {
        Bundle bundle = FrameworkUtil.getBundle(DeleteInfobaseTool.class);
        assertNotNull("the regression must observe the real OSGi bundle log", bundle); //$NON-NLS-1$
        log = Platform.getLog(bundle);
        temporary = Files.createTempDirectory("delete-infobase-log-"); //$NON-NLS-1$
        directoryFilter = temporary.toString();
        log.addLogListener(listener);
    }

    @After
    public void releaseBundleLog() throws IOException
    {
        if (log != null)
        {
            log.removeLogListener(listener);
        }
        if (temporary != null)
        {
            List<Path> paths;
            try (Stream<Path> tree = Files.walk(temporary))
            {
                paths = tree.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths)
            {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void filesystemRootRefusalIsOneWarningWithoutStack() throws Exception
    {
        Path root = temporary.toAbsolutePath().getRoot();
        assertNotNull("the test must address an actual filesystem root", root); //$NON-NLS-1$

        // Include both quotes so a same-drive directory cannot match the root filter.
        directoryFilter = "'" + root + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        Activator.logWarning("delete_infobase: unrelated directory '" + temporary + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(deleteDirectory(root));

        assertWarning("delete_infobase: refusing to delete the filesystem root '" + root + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refused root must remain", Files.isDirectory(root)); //$NON-NLS-1$
    }

    @Test
    public void nonInfobaseRefusalIsOneWarningWithoutStackAndKeepsFiles() throws Exception
    {
        Path dir = Files.createDirectory(temporary.resolve("not-an-infobase")); //$NON-NLS-1$
        Path sentinel = Files.writeString(dir.resolve("keep.txt"), "unrelated user data"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(deleteDirectory(dir));

        assertWarning("delete_infobase: '" + dir + "' is not a 1C infobase directory " //$NON-NLS-1$ //$NON-NLS-2$
            + "(no 1Cv8.1CD) — NOT deleting it"); //$NON-NLS-1$
        assertEquals("unrelated user data", Files.readString(sentinel)); //$NON-NLS-1$
    }

    @Test
    public void ioFailureIsOneWarningWithItsMessageAndWithoutStack() throws Exception
    {
        Path dir = infobaseDirectory();
        IOException failure = new FileSystemException(dir.toString(), null,
            "The database file is locked by another process"); //$NON-NLS-1$

        assertFalse(deleteDirectory(failingPath(dir, failure)));

        assertWarning(failureMessage(dir, failure));
        assertEquals("database contents", Files.readString(dir.resolve("1Cv8.1CD"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void accessDeniedFailureIsOneWarningWithItsTypeAndWithoutStack() throws Exception
    {
        Path dir = infobaseDirectory();
        IOException failure = new AccessDeniedException(dir.toString());

        assertFalse(deleteDirectory(failingPath(dir, failure)));

        assertWarning(failureMessage(dir, failure));
        assertTrue("a path-only exception must retain its type", //$NON-NLS-1$
            recorded.get(0).getMessage().contains("AccessDeniedException: " + dir)); //$NON-NLS-1$
        assertEquals("database contents", Files.readString(dir.resolve("1Cv8.1CD"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void runtimeFailureIsOneErrorWithItsOriginalException() throws Exception
    {
        Path dir = infobaseDirectory();
        RuntimeException failure = new IllegalStateException("Unexpected deletion visitor state"); //$NON-NLS-1$

        assertFalse(deleteDirectory(failingPath(dir, failure)));

        assertEquals("exactly one directory-deletion diagnostic: " + recorded, 1, recorded.size()); //$NON-NLS-1$
        IStatus status = recorded.get(0);
        assertEquals("an unexpected runtime defect must remain ERROR", IStatus.ERROR, status.getSeverity()); //$NON-NLS-1$
        assertSame("the original exception must retain its stack", failure, status.getException()); //$NON-NLS-1$
        assertEquals("delete_infobase: could not delete the database directory '" + dir //$NON-NLS-1$
            + "' (non-fatal — the registration is already removed; delete it manually)", status.getMessage()); //$NON-NLS-1$
        assertEquals("database contents", Files.readString(dir.resolve("1Cv8.1CD"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unrelatedDirectoryDiagnosticsAreIgnored() throws Exception
    {
        Path dir = Files.createDirectory(temporary.resolve("not-an-infobase")); //$NON-NLS-1$
        Path unrelated = temporary.resolveSibling("unrelated-" + temporary.getFileName()); //$NON-NLS-1$
        Activator.logWarning("delete_infobase: unrelated directory '" + unrelated + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(deleteDirectory(dir));

        assertWarning("delete_infobase: '" + dir + "' is not a 1C infobase directory " //$NON-NLS-1$ //$NON-NLS-2$
            + "(no 1Cv8.1CD) — NOT deleting it"); //$NON-NLS-1$
    }

    @Test
    public void successfulDeletionStillRemovesTheDirectoryWithoutWarnings() throws Exception
    {
        Path dir = infobaseDirectory();

        assertTrue(deleteDirectory(dir));

        assertFalse("the real EDT deleter must remove the database directory", Files.exists(dir)); //$NON-NLS-1$
        assertTrue("successful deletion must not log a refusal", recorded.isEmpty()); //$NON-NLS-1$
    }

    private Path infobaseDirectory() throws IOException
    {
        Path dir = Files.createDirectory(temporary.resolve("infobase")); //$NON-NLS-1$
        Files.writeString(dir.resolve("1Cv8.1CD"), "database contents"); //$NON-NLS-1$ //$NON-NLS-2$
        return dir;
    }

    /**
     * Fail the real EDT deleter's Path.toFile() call after the safety guards have read a real
     * marker. Mockito's Answer can throw either checked or unchecked failures here, independently
     * of OS file-lock semantics, without adding a production seam absent from the base revision.
     */
    private static Path failingPath(Path dir, Exception failure)
    {
        Path path = mock(Path.class);
        when(path.toAbsolutePath()).thenReturn(path);
        when(path.normalize()).thenReturn(path);
        when(path.getNameCount()).thenReturn(dir.getNameCount());
        when(path.getParent()).thenReturn(dir.getParent());
        when(path.resolve("1Cv8.1CD")).thenReturn(dir.resolve("1Cv8.1CD")); //$NON-NLS-1$ //$NON-NLS-2$
        when(path.toString()).thenReturn(dir.toString());
        when(path.toFile()).thenAnswer(invocation -> {
            throw failure;
        });
        return path;
    }

    private static boolean deleteDirectory(Path dir) throws Exception
    {
        Method method = DeleteInfobaseTool.class.getDeclaredMethod("deleteDatabaseDirBestEffort", Path.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (Boolean)method.invoke(null, dir);
    }

    private static String failureMessage(Path dir, IOException failure)
    {
        return "delete_infobase: could not delete the database directory '" + dir //$NON-NLS-1$
            + "' (non-fatal — the registration is already removed; delete it manually): " //$NON-NLS-1$
            + failure.getClass().getSimpleName() + ": " + failure.getMessage(); //$NON-NLS-1$
    }

    private void assertWarning(String expectedMessage)
    {
        assertEquals("exactly one directory-deletion diagnostic: " + recorded, 1, recorded.size()); //$NON-NLS-1$
        IStatus status = recorded.get(0);
        assertEquals("a refusal or kept directory is WARNING, never ERROR", IStatus.WARNING, status.getSeverity()); //$NON-NLS-1$
        assertNull("a non-fatal diagnostic must not attach a stack trace", status.getException()); //$NON-NLS-1$
        assertEquals(expectedMessage, status.getMessage());
    }
}
