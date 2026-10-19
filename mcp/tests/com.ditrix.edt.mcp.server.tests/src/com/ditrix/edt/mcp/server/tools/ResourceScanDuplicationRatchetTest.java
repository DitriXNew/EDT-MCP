/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Ratchet: no tool under {@code tools/impl} walks the workspace with its own resource visitor.
 * The project-wide line scan exists once, in {@code utils/ResourceTextSearch}, and both
 * full-text search tools ({@code search_in_code}, {@code search_in_dcs}) call it. The first
 * attempt at {@code search_in_dcs} (#326) copied the scanner into a second engine instead, and the
 * two drifted at once; this keeps that copy from coming back.
 */
public class ResourceScanDuplicationRatchetTest
{
    private static final String SERVER = "bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server"; //$NON-NLS-1$

    @Test
    public void noToolImplementsItsOwnResourceVisitor() throws IOException
    {
        File server = locateServerSourceDir();
        File shared = new File(server, "utils/ResourceTextSearch.java"); //$NON-NLS-1$
        assertTrue("positive control: the shared scanner must be found and be a resource visitor", //$NON-NLS-1$
            shared.isFile() && read(shared).contains("implements IResourceVisitor")); //$NON-NLS-1$

        File[] tools = new File(server, "tools/impl").listFiles((dir, name) -> name.endsWith(".java")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("positive control: tools/impl must hold tool sources", tools != null && tools.length > 50); //$NON-NLS-1$

        List<String> offenders = new ArrayList<>();
        for (File tool : tools)
        {
            String source = read(tool);
            if (source.contains("IResourceVisitor") || source.contains("IResourceProxyVisitor")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                offenders.add(tool.getName());
            }
        }
        assertTrue("these tools walk the workspace with their own visitor - use utils/ResourceTextSearch: " //$NON-NLS-1$
            + offenders, offenders.isEmpty());
    }

    private static String read(File file) throws IOException
    {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File locateServerSourceDir()
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File direct = new File(dir, SERVER);
            if (direct.isDirectory())
            {
                return direct;
            }
            File underMcp = new File(dir, "mcp/" + SERVER); //$NON-NLS-1$
            if (underMcp.isDirectory())
            {
                return underMcp;
            }
            dir = dir.getParentFile();
        }
        fail("could not locate the server source dir by walking up from user.dir=" //$NON-NLS-1$
            + System.getProperty("user.dir")); //$NON-NLS-1$
        return null;
    }
}
