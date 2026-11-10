/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileUrlsTest
{
    /** "Program" in Russian: the \\uXXXX escapes keep the source ASCII. */
    private static final String CYRILLIC = "\u041F\u0440\u043E\u0433\u0440\u0430\u043C\u043C\u0430";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path root;

    @Before
    public void setUp()
    {
        root = temporary.getRoot().toPath();
    }

    @Test
    public void aRawUrlWithASpaceResolvesToTheSpacedDirectory() throws Exception
    {
        Path directory = directory("Program Files");

        assertEquals(directory, FileUrls.toPath(rawUrl(directory)));
    }

    @Test
    public void anEncodedUrlWithASpaceFallsBackToTheDecodedDirectory() throws Exception
    {
        Path directory = directory("Program Files");
        assertFalse(Files.exists(root.resolve("Program%20Files")));

        assertEquals(directory, FileUrls.toPath(encodedUrl(directory)));
    }

    @Test
    public void cyrillicAloneResolvesFromBothForms() throws Exception
    {
        Path directory = directoryOrSkip(CYRILLIC);

        assertEquals(directory, FileUrls.toPath(rawUrl(directory)));
        assertEquals(directory, FileUrls.toPath(encodedUrl(directory)));
    }

    @Test
    public void cyrillicWithASpaceResolvesFromBothForms() throws Exception
    {
        Path directory = directoryOrSkip(CYRILLIC + " Files");

        assertEquals(directory, FileUrls.toPath(rawUrl(directory)));
        assertEquals(directory, FileUrls.toPath(encodedUrl(directory)));
    }

    @Test
    public void aLiteralPercentDirectoryWinsOverItsDecodedSibling() throws Exception
    {
        Path literal = directory("a%20b");
        directory("a b");

        assertEquals(literal, FileUrls.toPath(rawUrl(literal)));
    }

    @Test
    public void aLiteralPercentDirectoryAloneResolvesFromBothForms() throws Exception
    {
        Path literal = directory("a%20b");

        assertEquals(literal, FileUrls.toPath(rawUrl(literal)));
        assertEquals(literal, FileUrls.toPath(encodedUrl(literal)));
    }

    @Test
    public void aBarePercentIsNotAnEscape() throws Exception
    {
        Path directory = directory("100%");

        assertEquals(directory, FileUrls.toPath(rawUrl(directory)));
    }

    @Test
    public void aHashIsPartOfThePath() throws Exception
    {
        Path directory = directory("a#b");

        assertEquals(directory, FileUrls.toPath(rawUrl(directory)));
    }

    @Test
    public void whenNeitherFormExistsTheLiteralPathIsReturned() throws Exception
    {
        Path escaped = root.resolve("x%20y");
        Path barePercent = root.resolve("100%");

        assertEquals(escaped, FileUrls.toPath(rawUrl(escaped)));
        assertEquals(barePercent, FileUrls.toPath(rawUrl(barePercent)));
    }

    @Test
    public void anEscapeThatDecodesToAnIllegalPathLeavesTheLiteralPath() throws Exception
    {
        Path escapedNul = root.resolve("a%00b");

        assertEquals(escapedNul, FileUrls.toPath(rawUrl(escapedNul)));
    }

    @Test
    public void aPathWithNothingToEncodeIsTheSameAsThePlainConversion() throws Exception
    {
        URL url = rawUrl(directory("plain"));

        assertEquals(Path.of(url.toURI()), FileUrls.toPath(url));
    }

    @Test
    public void aNullUrlIsRefused()
    {
        assertThrows(IOException.class, () -> FileUrls.toPath(null));
    }

    @Test
    public void aNonFileSchemeIsRefusedAndNamed() throws Exception
    {
        URL url = new URL("http://example.invalid/a");

        IOException refusal = assertThrows(IOException.class, () -> FileUrls.toPath(url));

        assertEquals("Not a file URL: http://example.invalid/a", refusal.getMessage());
    }

    @Test
    public void aUrlThatCannotBeAPathIsReportedWithTheUrl() throws Exception
    {
        URL url = new URL("file:/a\u0000b");

        IOException refusal = assertThrows(IOException.class, () -> FileUrls.toPath(url));

        assertTrue(refusal.getMessage(), refusal.getMessage().contains(url.toString()));
    }

    @Test
    public void aUncUrlKeepsItsServerAndShare() throws Exception
    {
        assumeTrue("UNC paths exist on Windows only", isWindows());

        assertEquals(Path.of("\\\\server\\share\\a b"), FileUrls.literalPath(new URL("file://server/share/a b")));
    }

    private Path directory(String name) throws IOException
    {
        return Files.createDirectory(root.resolve(name));
    }

    private Path directoryOrSkip(String name) throws IOException
    {
        Path path = null;
        try
        {
            path = root.resolve(name);
        }
        catch (InvalidPathException e)
        {
            assumeNoException("The file system encoding cannot represent Cyrillic", e);
        }
        return Files.createDirectory(path);
    }

    /** The form the Eclipse platform hands out: the path is not encoded. */
    @SuppressWarnings("deprecation")
    private static URL rawUrl(Path path) throws IOException
    {
        return path.toFile().toURL();
    }

    private static URL encodedUrl(Path path) throws IOException
    {
        return path.toUri().toURL();
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
