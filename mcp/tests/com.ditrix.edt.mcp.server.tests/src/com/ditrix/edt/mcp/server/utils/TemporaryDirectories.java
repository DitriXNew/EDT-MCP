/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Deletes the temporary directory trees tests create, waiting out a Windows removal that lags.
 * <p>
 * On Windows, removing a directory right after deleting the files in it can fail with
 * {@code DirectoryNotEmptyException} although every one of those deletes returned normally: a
 * file that something still holds open can keep its entry in the directory, delete-pending,
 * until that handle closes - usually within milliseconds. A test that writes files just before
 * its teardown removes them meets that at random, and a suite that fails in TEARDOWN over it
 * reports a defect nobody has.
 * <p>
 * In the merge-rules tests this was written for, the handle is not one the tests or the code
 * under test leave open: they close every one of theirs before a test returns. Nor would one of
 * theirs left open fail this way on Windows 11 with NTFS: a file held open through NIO leaves no
 * entry once deleted, and one held through {@code java.io} ({@code ZipFile} included) cannot be
 * deleted at all, which fails on that file rather than on its directory. The handle is held
 * outside the JVM - plausibly by a scanner or an indexer looking at the freshly written files -
 * and which holder it is does not matter: the retry waits for whichever it is.
 * <p>
 * Bounded, so it hides no leak: a handle that keeps blocking the removal fails the teardown just
 * the same, about two seconds later. And it masks nothing: a test that cares whether a file is
 * left behind asserts that inside the test - as {@code MergeRulesCodecTest}'s leftover tests do -
 * never in a teardown.
 */
public final class TemporaryDirectories
{
    /** How many times the removal is tried before its last failure is rethrown. */
    private static final int ATTEMPTS = 20;

    /** The pause after a failed attempt, in milliseconds. */
    private static final long PAUSE_MS = 100L;

    private TemporaryDirectories()
    {
        // Utility class
    }

    /**
     * Deletes {@code directory} and everything in it, retrying briefly - see the class javadoc.
     *
     * @param directory the tree to delete; {@code null} or an absent path is left alone
     * @throws IOException the last failure, when every attempt failed
     * @throws InterruptedException when interrupted in a pause between attempts
     */
    public static void deleteRecursively(Path directory) throws IOException, InterruptedException
    {
        IOException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++)
        {
            if (directory == null || !Files.exists(directory))
            {
                return;
            }
            try
            {
                try (Stream<Path> walk = Files.walk(directory))
                {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                    {
                        Files.deleteIfExists(path);
                    }
                }
                return;
            }
            catch (IOException e)
            {
                last = e;
                Thread.sleep(PAUSE_MS);
            }
        }
        if (last != null)
        {
            throw last;
        }
    }
}
