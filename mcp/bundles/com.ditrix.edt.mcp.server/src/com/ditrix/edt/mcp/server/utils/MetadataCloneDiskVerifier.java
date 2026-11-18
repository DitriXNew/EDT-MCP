/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.InputStream;

import org.eclipse.core.resources.IFile;

/**
 * Checks the clone's files after EDT exported it. The model itself is EDT's: modeled files are
 * not re-parsed and compared here, since a detached load skips EDT's own load-time normalisation.
 */
public final class MetadataCloneDiskVerifier
{
    private MetadataCloneDiskVerifier()
    {
    }

    /** Called after export has drained; pending destination files never use source-readiness checks. */
    public static byte[] verifyCopiedFile(IFile file, String expectedHash)
    {
        if (!file.exists())
        {
            throw new IllegalStateException("Missing clone file '" + file.getProjectRelativePath()
                + "' after export. Inspect the clone and use resync_to_disk to export its committed model.");
        }
        try (InputStream input = file.getContents(true))
        {
            byte[] bytes = input.readAllBytes();
            if (expectedHash != null && !expectedHash.equals(MetadataClonePlan.bytesHash(bytes)))
            {
                throw new IllegalStateException("Copied bytes differ in '" + file.getProjectRelativePath() + "'");
            }
            return bytes;
        }
        catch (org.eclipse.core.runtime.CoreException | java.io.IOException e)
        {
            throw new IllegalStateException("Cannot read clone file '" + file.getProjectRelativePath()
                + "' after export. Inspect its file permissions and the EDT log before recovery.", e);
        }
    }
}
