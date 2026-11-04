// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;

public class PrivatePathsTemporaryDirectoryTest
{
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void temporaryDirectoriesAreUniquePrivateAndGiveChildrenPrivateAccess() throws Exception
    {
        Path parent = PrivatePaths.directory(folder.newFolder("staging").toPath());
        Path first = PrivatePaths.temporaryDirectory(parent, "edt-mcp-private-test-");
        Path second = null;
        Path file = null;
        try
        {
            second = PrivatePaths.temporaryDirectory(parent, "edt-mcp-private-test-");
            assertNotEquals(first, second);
            assertTrue(first.isAbsolute());
            assertEquals(first.normalize(), first);
            assertEquals(parent, first.getParent());
            PrivatePaths.verifyDirectory(first);
            PrivatePaths.verifyDirectory(second);
            file = Files.createTempFile(first, "artifact-", ".cfe");
            Files.writeString(file, "fixture");
            PrivatePaths.verifyFile(file);
            assertEquals("fixture", Files.readString(file));
        }
        finally
        {
            if (file != null) Files.deleteIfExists(file);
            if (second != null) Files.deleteIfExists(second);
            Files.deleteIfExists(first);
        }
    }
}
