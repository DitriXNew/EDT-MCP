/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.osgi.service.datalocation.Location;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StdioPluginSupportLocationTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void aMissingLocationIsReportedByName()
    {
        IOException refusal = assertThrows(IOException.class, () -> StdioPluginSupport.realLocation(null, "install"));

        assertEquals("The EDT install location is not available", refusal.getMessage());
    }

    @Test
    public void aLocationWithoutAUrlIsReportedByName()
    {
        IOException refusal = assertThrows(IOException.class,
            () -> StdioPluginSupport.realLocation(locationAt(null), "configuration"));

        assertEquals("The EDT configuration location is not available", refusal.getMessage());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void aLocationWithARawSpacedUrlGivesItsRealPath() throws Exception
    {
        Path directory = Files.createDirectory(temporary.getRoot().toPath().resolve("Program Files"));

        Path real = StdioPluginSupport.realLocation(locationAt(directory.toFile().toURL()), "install");

        assertEquals(directory.toRealPath(), real);
    }

    /** A site that goes back to the strict conversion brings the "illegal character" failure back. */
    @Test
    public void everyLocationGoesThroughTheHelper() throws Exception
    {
        try (InputStream input = StdioPluginSupport.class.getResourceAsStream("StdioPluginSupport.class"))
        {
            assertNotNull(input);
            String bytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);

            assertFalse(bytes.contains("toURI"));
            assertTrue(bytes.contains("FileUrls"));
        }
    }

    private static Location locationAt(URL url)
    {
        return (Location) Proxy.newProxyInstance(Location.class.getClassLoader(), new Class<?>[] { Location.class },
            (proxy, method, arguments) -> {
                if ("getURL".equals(method.getName()))
                {
                    return url;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
