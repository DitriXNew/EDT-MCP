/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Integration coverage for {@link GitTool}'s EXEC path: a real {@code git} process against a real
 * temporary repository. {@link GitToolTest} covers the parser and the metadata; everything below
 * only shows up when a process actually runs — the exit-code and output plumbing, the redaction of
 * a credential that lives in the repository's own config rather than in the command, and the
 * failure shape of a command git itself rejects.
 *
 * <p>The repository is built with git itself (not JGit), so the test exercises the same binary the
 * tool will run. Every test is skipped when {@code git} cannot be run, so a machine without it does
 * not fail the build; CI has git, so the coverage is real there.
 *
 * <p><b>Isolation.</b> Helper invocations run with a scrubbed environment: every {@code GIT_*}
 * variable of the developer's shell is dropped (a stray {@code GIT_DIR}/{@code GIT_WORK_TREE} would
 * redirect the setup at another repository), the global/system config files are pointed at a
 * non-existent path, and hooks/templates are disabled per command — so a machine-wide
 * {@code core.hooksPath} can neither fail nor hang the setup. The tool's OWN run is left alone: it
 * hardens its environment itself, and that is part of what is under test.
 *
 * <p>Nothing here touches the Eclipse workspace: {@link GitTool#runGit} takes the work tree
 * directly, which is exactly the seam the project resolution feeds in production.
 */
public class GitToolProcessIntegrationTest
{
    /** Seconds a helper git invocation of this test may take before it is killed and the test fails. */
    private static final int HELPER_TIMEOUT_SECONDS = 60;

    /** Set when {@code git --version} ran successfully; {@code null} makes every test skip. */
    private static String gitExecutable;

    /** Why git was considered unusable — reported in the skip so a silent skip is never a mystery. */
    private static String gitUnavailableReason;

    private Path repository;

    @BeforeClass
    public static void resolveGit()
    {
        try
        {
            HelperResult probe = runHelper(null, Arrays.asList("git", "--version")); //$NON-NLS-1$ //$NON-NLS-2$
            if (probe.exitCode == 0)
            {
                gitExecutable = "git"; //$NON-NLS-1$
            }
            else
            {
                gitUnavailableReason = "'git --version' exited with " + probe.exitCode + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + probe.output;
            }
        }
        catch (IOException e)
        {
            gitUnavailableReason = "git is not on PATH: " + e.getMessage(); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            // Only an actual interrupt restores the flag — an absent git must not poison the runner.
            Thread.currentThread().interrupt();
            gitUnavailableReason = "interrupted while probing git"; //$NON-NLS-1$
        }
    }

    @Before
    public void createRepository() throws IOException, InterruptedException
    {
        Assume.assumeTrue(String.valueOf(gitUnavailableReason), gitExecutable != null);
        repository = Files.createTempDirectory("git-tool-exec"); //$NON-NLS-1$
        git("init", "-q"); //$NON-NLS-1$ //$NON-NLS-2$
        // A repository-local identity: the machine running the tests may have none configured.
        git("config", "user.email", "tests@example.com"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        git("config", "user.name", "EDT MCP tests"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        git("config", "commit.gpgsign", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.write(repository.resolve("tracked.txt"), //$NON-NLS-1$
            "first line\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        git("add", "tracked.txt"); //$NON-NLS-1$ //$NON-NLS-2$
        git("commit", "-q", "-m", "initial commit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @After
    public void deleteRepository() throws IOException
    {
        if (repository == null || !Files.exists(repository))
        {
            return;
        }
        List<String> undeleted = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repository))
        {
            Iterator<Path> deepestFirst = paths.sorted(Comparator.reverseOrder()).iterator();
            while (deepestFirst.hasNext())
            {
                Path path = deepestFirst.next();
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException e)
                {
                    // Windows keeps a handle open a moment after a process exits; do not fail the
                    // test over cleanup, but do not pretend it succeeded either.
                    undeleted.add(path.toString());
                }
            }
        }
        if (!undeleted.isEmpty())
        {
            // deleteOnExit runs its registrations in REVERSE order, so register shallowest-first
            // to have the JVM delete deepest-first; the other way round every directory would be
            // non-empty when its turn came.
            undeleted.stream().sorted().forEach(path -> new java.io.File(path).deleteOnExit());
            System.err.println("git integration test: could not delete " + undeleted.size() //$NON-NLS-1$
                + " temporary path(s), queued for JVM exit: " + undeleted.get(0)); //$NON-NLS-1$
        }
    }

    @Test
    public void testSucceedingCommandReportsExitCodeAndOutput() throws Exception
    {
        Files.write(repository.resolve("untracked.txt"), //$NON-NLS-1$
            "x\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        JsonObject result = run("status --short"); //$NON-NLS-1$

        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, result.get("exitCode").getAsInt()); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("output").getAsString().contains("untracked.txt")); //$NON-NLS-1$ //$NON-NLS-2$
        // The echoed command is the parsed argv, not the raw user string.
        assertEquals("git status --short", result.get("command").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCommandOutputCarriesRealGitData() throws Exception
    {
        JsonObject result = run("log -1 --format=%s"); //$NON-NLS-1$

        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("output").getAsString().contains("initial commit")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFailingCommandIsReportedAsAnErrorWithGitsOwnMessage() throws Exception
    {
        // git itself rejects this: the tool must surface the non-zero exit AND git's message,
        // rather than swallowing it into a bare failure. The message echoes the bad revision, so
        // the assertion holds without depending on git's wording (or locale).
        JsonObject result = run("show no-such-revision-xyz123"); //$NON-NLS-1$

        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("exitCode").getAsInt() != 0); //$NON-NLS-1$
        assertTrue("git's own message must reach the caller: " + result, //$NON-NLS-1$
            result.get("output").getAsString().contains("no-such-revision-xyz123")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCredentialInTheRepositoryConfigIsRedactedFromRealGitOutput() throws Exception
    {
        // The secret never passes through the command — it lives in the repository config, and git
        // prints it back. Only an output-side redactor can catch that, which is exactly why this
        // case needs a real process.
        git("remote", "add", "origin", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "https://user:s3cr3t-token@example.com/team/repo.git"); //$NON-NLS-1$

        JsonObject result = run("remote -v"); //$NON-NLS-1$
        String output = result.get("output").getAsString(); //$NON-NLS-1$

        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(output, output.contains("origin")); //$NON-NLS-1$
        assertFalse("the secret must not reach the caller: " + output, //$NON-NLS-1$
            output.contains("s3cr3t-token")); //$NON-NLS-1$
        assertTrue(output, output.contains("example.com/team/repo.git")); //$NON-NLS-1$
    }

    @Test
    public void testRunsInTheGivenWorkTreeNotTheProcessDirectory() throws Exception
    {
        // The work tree is passed in, so the result must describe THIS repository even though the
        // JVM's own working directory is somewhere else entirely.
        JsonObject result = run("rev-parse --show-toplevel"); //$NON-NLS-1$
        String output = result.get("output").getAsString().trim(); //$NON-NLS-1$

        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(repository.toRealPath().toString().replace('\\', '/'),
            output.replace('\\', '/'));
    }

    /** Parses {@code command} through the production parser and runs it in the test repository. */
    private JsonObject run(String command) throws Exception
    {
        List<String> argv = GitTool.parseCommand(command);
        String json = new GitTool().runGit(argv, repository.toFile());
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Runs a helper git command directly (test setup, not through the tool under test). */
    private void git(String... args) throws IOException, InterruptedException
    {
        List<String> argv = new ArrayList<>();
        argv.add(gitExecutable);
        // Per-command isolation from a machine-wide hooks path / init template, either of which
        // could make the setup fail or hang on someone's workstation.
        argv.add("-c"); //$NON-NLS-1$
        argv.add("core.hooksPath="); //$NON-NLS-1$
        argv.add("-c"); //$NON-NLS-1$
        argv.add("init.templateDir="); //$NON-NLS-1$
        argv.addAll(Arrays.asList(args));

        HelperResult result = runHelper(repository, argv);
        assertEquals("helper git failed: " + argv + " -> " + result.output, 0, result.exitCode); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Runs a helper process with a scrubbed environment, draining its output on a separate thread
     * so a process that never closes stdout cannot outlive {@link #HELPER_TIMEOUT_SECONDS}.
     */
    private static HelperResult runHelper(Path workingDirectory, List<String> argv)
        throws IOException, InterruptedException
    {
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workingDirectory != null)
        {
            builder.directory(workingDirectory.toFile());
        }
        scrubGitEnvironment(builder.environment());

        Process process = builder.start();
        process.getOutputStream().close();
        AtomicReference<String> captured = new AtomicReference<>(""); //$NON-NLS-1$
        Thread drain = new Thread(() -> captured.set(readAllQuietly(process)), "git-helper-drain"); //$NON-NLS-1$
        drain.setDaemon(true);
        drain.start();
        try
        {
            if (!process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                boolean died = killAndAwait(process);
                drain.join(TimeUnit.SECONDS.toMillis(5));
                fail("helper git hung for " + HELPER_TIMEOUT_SECONDS + "s and was killed" //$NON-NLS-1$
                    + (died ? "" : " (it did NOT die)") + ": " + argv + " -> " + captured.get()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            drain.join(TimeUnit.SECONDS.toMillis(5));
            return new HelperResult(process.exitValue(), captured.get());
        }
        finally
        {
            if (process.isAlive())
            {
                killAndAwait(process);
            }
        }
    }

    /**
     * Kills a helper process and waits for it to actually go away, closing its output stream so a
     * drain thread blocked on a survivor's pipe is released too.
     *
     * @param process the process to kill
     * @return {@code true} when it terminated within the grace period
     */
    private static boolean killAndAwait(Process process) throws InterruptedException
    {
        process.destroyForcibly();
        boolean died = process.waitFor(5, TimeUnit.SECONDS);
        try
        {
            process.getInputStream().close();
        }
        catch (IOException e)
        {
            // Already closed by the drain thread - nothing to release.
        }
        return died;
    }

    /**
     * Drops every {@code GIT_*} variable the developer's shell may carry ({@code GIT_DIR} and
     * {@code GIT_WORK_TREE} would silently retarget the setup at another repository, and
     * {@code GIT_CONFIG_COUNT} can inject arbitrary config) and points the global/system config at
     * a path that does not exist, so only the repository-local config applies.
     */
    private static void scrubGitEnvironment(Map<String, String> environment)
    {
        environment.keySet().removeIf(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_")); //$NON-NLS-1$
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null/no-such-gitconfig"); //$NON-NLS-1$ //$NON-NLS-2$
        environment.put("GIT_CONFIG_SYSTEM", "/dev/null/no-such-gitconfig"); //$NON-NLS-1$ //$NON-NLS-2$
        environment.put("GIT_CONFIG_NOSYSTEM", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        environment.put("GIT_TERMINAL_PROMPT", "0"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Reads a process's merged output to completion; a read failure yields what was read so far. */
    private static String readAllQuietly(Process process)
    {
        try (InputStream in = process.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream())
        {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0)
            {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return "<read failed: " + e.getMessage() + ">"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Exit code + merged output of a helper process. */
    private static final class HelperResult
    {
        final int exitCode;
        final String output;

        HelperResult(int exitCode, String output)
        {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
