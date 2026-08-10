/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

/**
 * Covers {@link GitFailureLog}: the EDT-log rendering shared by the two git paths that can end in
 * JGit's configuration parser - the stored-remote pre-flight ({@code GitTool}) and opening the
 * repository ({@link GitRepositoryResolver}).
 * <p>
 * The exception under test is a REAL one, produced by loading a malformed configuration file through
 * JGit, not a hand-built stand-in: the whole point is that JGit's own message quotes the offending
 * line, credential included, and a fabricated exception could not prove that.
 */
public class GitFailureLogTest
{
    /** The fake credential the fixture hides in a configuration value. */
    private static final String SECRET = "s3cr3t-token"; //$NON-NLS-1$

    /** The fake host beside it; a log line must not carry that either. */
    private static final String HOST = "example.com"; //$NON-NLS-1$

    /**
     * Shortest exception message worth looking for inside the rendered line. A message of one or two
     * characters could occur there by coincidence and turn the assertion into noise.
     */
    private static final int MIN_TELLTALE_MESSAGE_CHARS = 8;

    @Test
    public void testAConfigurationValueInTheCauseChainNeverReachesTheLog() throws Exception
    {
        Throwable thrown = configLoadFailure();

        // Positive control: with no credential inside JGit's own exception there would be nothing to
        // withhold, and this case would pass on an empty premise.
        String reported = causeChainMessages(thrown);
        assertTrue("fixture: JGit's exception must really quote the credential: " + reported, //$NON-NLS-1$
            reported.contains(SECRET));

        String logged = GitFailureLog.typesOnly("git: opening the repository failed", thrown); //$NON-NLS-1$

        assertFalse("the log line must not carry the credential: " + logged, logged.contains(SECRET)); //$NON-NLS-1$
        assertFalse("nor the host, nor any other configuration content: " + logged, //$NON-NLS-1$
            logged.contains(HOST));
        // And not by luck of WHICH link happens to quote it: no message from the chain may be
        // embedded at all. Asserting the credential alone would stay green on a line that rendered
        // the outermost exception, whose own message names the file rather than the value.
        for (Throwable link = thrown; link != null; link = link.getCause())
        {
            String message = link.getMessage();
            if (message != null && message.length() >= MIN_TELLTALE_MESSAGE_CHARS)
            {
                assertFalse("no exception message may reach the log line: " + logged, //$NON-NLS-1$
                    logged.contains(message));
            }
        }
        // ...and it must still be a usable report: what failed, and the TYPES behind it - a class
        // name can carry no configuration.
        assertTrue("the log line must say what failed: " + logged, //$NON-NLS-1$
            logged.contains("git: opening the repository failed")); //$NON-NLS-1$
        assertTrue("...and name the exception type: " + logged, //$NON-NLS-1$
            logged.contains(thrown.getClass().getName()));
        assertTrue("...and the type of its cause, or a wrapped failure would be unidentifiable: " //$NON-NLS-1$
            + logged, logged.contains(thrown.getCause().getClass().getName()));
    }

    @Test
    public void testNothingToReportStillRendersAReadableLine()
    {
        String logged = GitFailureLog.typesOnly("git: opening the repository failed", null); //$NON-NLS-1$

        assertTrue("a missing throwable must not produce 'null' text: " + logged, //$NON-NLS-1$
            logged.contains("git: opening the repository failed")); //$NON-NLS-1$
        assertFalse("...nor a literal 'null': " + logged, logged.contains("null")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Loads a malformed configuration file through JGit and returns what it threw - the genuine
     * chain, whose innermost link quotes the offending line.
     *
     * @return the exception JGit threw
     * @throws Exception when the fixture cannot be written
     */
    private static Throwable configLoadFailure() throws Exception
    {
        File directory = Files.createTempDirectory("git-failure-log").toFile(); //$NON-NLS-1$
        try
        {
            File file = new File(directory, "gitconfig"); //$NON-NLS-1$
            // An '[include]' entry whose key is not 'path': JGit reports it as "Invalid line in
            // config file: <ConfigLine>", and ConfigLine renders 'section.name=VALUE' - the value
            // included, which here is the credential.
            Files.write(file.toPath(),
                ("[include]\n\tnotpath = https://user:" + SECRET + "@" + HOST + "/r.git\n") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .getBytes(StandardCharsets.UTF_8));
            try
            {
                new FileBasedConfig(null, file, FS.DETECTED).load();
            }
            catch (ConfigInvalidException expected)
            {
                return expected;
            }
            fail("fixture: this configuration loaded fine, so nothing below is under test"); //$NON-NLS-1$
            return null;
        }
        finally
        {
            deleteRecursively(directory);
        }
    }

    /**
     * Every message in a throwable's cause chain, joined. Bounded: a cause chain can be cyclic.
     *
     * @param failure the exception to walk
     * @return the messages, one per line
     */
    private static String causeChainMessages(Throwable failure)
    {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++)
        {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }

    /** Recursively deletes a temp directory tree (best-effort test cleanup). */
    private static void deleteRecursively(File file)
    {
        if (file == null)
        {
            return;
        }
        File[] children = file.listFiles();
        if (children != null)
        {
            for (File child : children)
            {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
