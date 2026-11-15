/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.git.GitCloneTarget;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code git clone} (#934) with a REAL git process: the happy path from a local {@code git daemon} (a
 * {@code git://} URL, one the production grammar accepts - {@code file://} and local paths it refuses),
 * the fast failure when the remote cannot be reached or wants credentials nobody can give, and a job that
 * is cancelled or runs out of time: its git process is killed and the partial clone taken back.
 * <p>
 * Every test is skipped when {@code git} (or {@code git daemon}, for the happy path) cannot be run.
 * Helper invocations run with a scrubbed environment; the tool's own run is left alone - it hardens its
 * environment itself, and that is part of what is under test.
 */
public class GitToolCloneProcessTest
{
    private static final int HELPER_TIMEOUT_SECONDS = 60;

    private static String gitExecutable;

    private static String gitUnavailableReason;

    private final List<Path> scratch = new ArrayList<>();

    private final List<Process> daemons = new ArrayList<>();

    private final List<ServerSocket> servers = new ArrayList<>();

    private BackgroundJobs jobs;

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
                gitUnavailableReason = "'git --version' exited with " + probe.exitCode + ": " + probe.output; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (IOException e)
        {
            gitUnavailableReason = "git is not on PATH: " + e.getMessage(); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            gitUnavailableReason = "interrupted while probing git"; //$NON-NLS-1$
        }
    }

    @Before
    public void setUp()
    {
        Assume.assumeTrue(String.valueOf(gitUnavailableReason), gitExecutable != null);
        jobs = new BackgroundJobs(10, 2);
    }

    @After
    public void tearDown() throws Exception
    {
        if (jobs != null)
        {
            jobs.close();
        }
        for (ServerSocket server : servers)
        {
            server.close();
        }
        for (Process daemon : daemons)
        {
            daemon.descendants().forEach(ProcessHandle::destroyForcibly);
            daemon.destroyForcibly();
            daemon.waitFor(10, TimeUnit.SECONDS);
        }
        for (Path folder : scratch)
        {
            deleteTree(folder);
        }
    }

    /**
     * The happy path: a clone from a {@code git://} remote lands in the new directory with the remote's
     * history and the branch asked for, and the answer names the directory and what is left to do.
     */
    @Test(timeout = 120_000)
    public void testACloneFromAGitDaemonLandsInTheNewDirectory() throws Exception
    {
        String url = serveRepositoryWithOneCommit();
        Path target = scratchFolder().resolve("clone"); //$NON-NLS-1$
        // Spelled with '/' (on Windows a different spelling of the same path): git must be handed the
        // real path that was judged, not the spelling.
        String spelled = target.toString().replace('\\', '/');

        JsonObject result = startClone("clone -b trunk " + url + " '" + spelled + "'", target, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            TimeUnit.MINUTES.toMillis(2), 90);

        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("done", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result.get("jobId")); //$NON-NLS-1$
        Path real = target.getParent().toRealPath().resolve("clone"); //$NON-NLS-1$
        assertEquals(real.toString(), result.get("directory").getAsString()); //$NON-NLS-1$
        assertTrue("git was given the real path: " + result.get("command"), //$NON-NLS-1$ //$NON-NLS-2$
            result.get("command").getAsString().endsWith(" " + real)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(GitTool.cloneNextStep(real), result.get("nextStep").getAsString()); //$NON-NLS-1$
        assertFalse("a plain SHA-1 clone carries no warning: " + result, result.has("warnings")); //$NON-NLS-1$ //$NON-NLS-2$
        try (Repository cloned = new FileRepositoryBuilder().setGitDir(real.resolve(".git").toFile()).build()) //$NON-NLS-1$
        {
            assertEquals("refs/heads/trunk", cloned.getFullBranch()); //$NON-NLS-1$
            assertNotNull("the remote's commit came along", cloned.resolve("HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The machine's core.autocrlf may turn the line ending into CRLF on checkout.
        assertEquals("first line\n", //$NON-NLS-1$
            new String(Files.readAllBytes(real.resolve("tracked.txt")), StandardCharsets.UTF_8).replace("\r", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A remote that cannot be reached fails at once, in the same call, and the directory the tool
     * created for it is gone again.
     */
    @Test(timeout = 120_000)
    public void testAnUnreachableRemoteFailsFastAndLeavesNothingBehind() throws Exception
    {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            closedPort = probe.getLocalPort();
        }
        Path target = scratchFolder().resolve("clone"); //$NON-NLS-1$
        long started = System.nanoTime();

        JsonObject result = startClone("clone git://127.0.0.1:" + closedPort + "/repo.git '" + target + "'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            target, TimeUnit.MINUTES.toMillis(2), 60);

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("failed", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an unreachable remote must fail fast, took " + seconds + " s", seconds < 30); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.toString(), result.get("cleanedUp").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("error").getAsString().contains("was removed again")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the directory the tool created is gone", Files.exists(target, LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$
    }

    /**
     * A remote that asks for credentials, with none to give: the hardened environment makes git fail at
     * once instead of prompting - the run seam is driven directly, because the production grammar takes
     * no {@code http://} URL and an https server needs a certificate; the environment is the same.
     */
    @Test(timeout = 120_000)
    public void testARemoteThatWantsCredentialsFailsFastInsteadOfPrompting() throws Exception
    {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        servers.add(server);
        Thread responder = new Thread(() -> answerUnauthorized(server), "clone-test-401"); //$NON-NLS-1$
        responder.setDaemon(true);
        responder.start();
        Path target = Files.createDirectory(scratchFolder().resolve("clone")); //$NON-NLS-1$
        List<String> argv = new ArrayList<>(List.of("git", "clone", //$NON-NLS-1$ //$NON-NLS-2$
            "http://127.0.0.1:" + server.getLocalPort() + "/repo.git", target.toString())); //$NON-NLS-1$ //$NON-NLS-2$
        long started = System.nanoTime();

        GitTool.CloneRun run = new GitTool(jobs).runClone(argv, target.toRealPath(), false, TimeUnit.MINUTES.toMillis(2));

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        assertFalse(run.json(), run.success());
        assertTrue("a credential nobody can give must fail fast, took " + seconds + " s", seconds < 60); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = JsonParser.parseString(run.json()).getAsJsonObject();
        assertTrue("git's own message says why: " + result, result.get("output").getAsString() //$NON-NLS-1$ //$NON-NLS-2$
            .toLowerCase(java.util.Locale.ROOT).matches("(?s).*(authentication|username|terminal prompts|401).*")); //$NON-NLS-1$
        assertTrue("an existing empty target stays", Files.isDirectory(target)); //$NON-NLS-1$
        try (Stream<Path> left = Files.list(target))
        {
            assertEquals("and is empty again", 0, left.count()); //$NON-NLS-1$
        }
    }

    /**
     * cancel_job stops a clone that hangs on its remote: the git process is killed (the remote sees its
     * connection close), the partial clone is taken back, and the target is free again.
     */
    @Test(timeout = 120_000)
    public void testCancellingTheJobKillsGitAndRemovesThePartialClone() throws Exception
    {
        HangingRemote remote = hangingRemote();
        Path target = scratchFolder().resolve("clone"); //$NON-NLS-1$

        JsonObject started = startClone("clone " + remote.url + " '" + target + "'", target, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            TimeUnit.MINUTES.toMillis(10), 0);
        assertEquals(started.toString(), "running", started.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("git reached the remote", remote.connected.await(30, TimeUnit.SECONDS)); //$NON-NLS-1$

        BackgroundJobs.CancellationResult cancelled = jobs.cancel(started.get("jobId").getAsString()); //$NON-NLS-1$
        assertEquals(BackgroundJobs.CancellationOutcome.CANCELLED, cancelled.getOutcome());

        assertTrue("the git process must be killed: the remote's connection stays open", //$NON-NLS-1$
            remote.closed.await(30, TimeUnit.SECONDS));
        Path real = target.getParent().toRealPath().resolve("clone"); //$NON-NLS-1$
        awaitTargetFree(real);
        assertFalse("the partial clone is removed", Files.exists(real, LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$
    }

    /** A clone past its limit is killed the same way and reported as a timeout. */
    @Test(timeout = 120_000)
    public void testACloneOverItsLimitIsKilledAndReported() throws Exception
    {
        HangingRemote remote = hangingRemote();
        Path target = scratchFolder().resolve("clone"); //$NON-NLS-1$

        JsonObject result = startClone("clone " + remote.url + " '" + target + "'", target, 2_000, 60); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("failed", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.toString(), result.get("error").getAsString().contains("timed out after")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the git process is killed", remote.closed.await(30, TimeUnit.SECONDS)); //$NON-NLS-1$
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
    }

    // ---- helpers ----

    /** Parses {@code command}, checks its target and starts the clone on this test's registry. */
    private JsonObject startClone(String command, Path target, long timeoutMillis, int waitSeconds) throws Exception
    {
        List<String> argv = GitTool.parseCommand(command);
        GitTool.CloneCommand clone = GitTool.checkCloneGrammar(argv.subList(2, argv.size()));
        GitCloneTarget.Checked checked = GitCloneTarget.check(target, List.of());
        assertTrue(String.valueOf(checked.refusal()), checked.ok());
        return JsonParser.parseString(new GitTool(jobs).startClone(argv, clone, checked, timeoutMillis, waitSeconds))
            .getAsJsonObject();
    }

    /** Waits until the cleanup after a cancel has run: the claim on the target is released. */
    private static void awaitTargetFree(Path real) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline)
        {
            GitCloneTarget.Claim claim = GitCloneTarget.claim(real);
            if (claim != null)
            {
                claim.close();
                return;
            }
            Thread.sleep(100);
        }
        fail("the cancelled clone never released its target"); //$NON-NLS-1$
    }

    /** A bare repository with one commit on 'trunk', served by a local git daemon; returns its URL. */
    private String serveRepositoryWithOneCommit() throws Exception
    {
        Path root = scratchFolder();
        Path work = Files.createDirectory(root.resolve("work")); //$NON-NLS-1$
        helper(work, "init", "-q", "-b", "trunk"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        helper(work, "config", "user.email", "tests@example.com"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        helper(work, "config", "user.name", "EDT MCP tests"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.write(work.resolve("tracked.txt"), "first line\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        helper(work, "add", "tracked.txt"); //$NON-NLS-1$ //$NON-NLS-2$
        helper(work, "commit", "-q", "-m", "initial commit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Path served = Files.createDirectory(root.resolve("served")); //$NON-NLS-1$
        helper(root, "clone", "-q", "--bare", work.toString(), served.resolve("repo.git").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            port = probe.getLocalPort();
        }
        ProcessBuilder daemon = new ProcessBuilder(gitExecutable, "daemon", "--reuseaddr", "--export-all", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "--base-path=" + served, "--listen=127.0.0.1", "--port=" + port, served.toString()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        scrubGitEnvironment(daemon.environment());
        Process process = daemon.start();
        daemons.add(process);
        String url = "git://127.0.0.1:" + port + "/repo.git"; //$NON-NLS-1$ //$NON-NLS-2$
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline)
        {
            if (runHelper(root, Arrays.asList(gitExecutable, "ls-remote", url)).exitCode == 0) //$NON-NLS-1$
            {
                return url;
            }
            Assume.assumeTrue("git daemon could not be started here", process.isAlive()); //$NON-NLS-1$
            Thread.sleep(200);
        }
        Assume.assumeTrue("git daemon did not answer within 20 s", false); //$NON-NLS-1$
        return null;
    }

    /** A {@code git://} remote that accepts the connection and never answers. */
    private HangingRemote hangingRemote() throws IOException
    {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        servers.add(server);
        HangingRemote remote = new HangingRemote("git://127.0.0.1:" + server.getLocalPort() + "/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        Thread holder = new Thread(() -> {
            try (Socket socket = server.accept())
            {
                remote.connected.countDown();
                InputStream in = socket.getInputStream();
                while (in.read() != -1)
                {
                    // read the request and say nothing back
                }
                remote.closed.countDown();
            }
            catch (IOException e)
            {
                // a reset connection is a closed one too
                remote.closed.countDown();
            }
        }, "clone-test-hang"); //$NON-NLS-1$
        holder.setDaemon(true);
        holder.start();
        return remote;
    }

    private static final class HangingRemote
    {
        final String url;
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        HangingRemote(String url)
        {
            this.url = url;
        }
    }

    /** Answers every HTTP request with 401 and a Basic challenge. */
    private static void answerUnauthorized(ServerSocket server)
    {
        while (!server.isClosed())
        {
            try (Socket socket = server.accept())
            {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1)
                {
                    head.write(b);
                    if (head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) //$NON-NLS-1$
                    {
                        break;
                    }
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"test\"\r\n" //$NON-NLS-1$
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); //$NON-NLS-1$
                out.flush();
            }
            catch (IOException e)
            {
                // closed by tearDown, or the client went away
            }
        }
    }

    private Path scratchFolder() throws IOException
    {
        Path folder = Files.createTempDirectory("git-clone-exec").toRealPath(); //$NON-NLS-1$
        scratch.add(folder);
        Assume.assumeTrue("the temporary directory lies inside a git repository on this machine", //$NON-NLS-1$
            GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "scratch") == null); //$NON-NLS-1$
        return folder;
    }

    private static void helper(Path directory, String... args) throws IOException, InterruptedException
    {
        List<String> argv = new ArrayList<>();
        argv.add(gitExecutable);
        argv.add("-c"); //$NON-NLS-1$
        argv.add("core.hooksPath="); //$NON-NLS-1$
        argv.add("-c"); //$NON-NLS-1$
        argv.add("init.templateDir="); //$NON-NLS-1$
        argv.addAll(Arrays.asList(args));
        HelperResult result = runHelper(directory, argv);
        assertEquals("helper git failed: " + argv + " -> " + result.output, 0, result.exitCode); //$NON-NLS-1$ //$NON-NLS-2$
    }

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
        if (!process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            fail("helper git hung: " + argv); //$NON-NLS-1$
        }
        drain.join(TimeUnit.SECONDS.toMillis(5));
        return new HelperResult(process.exitValue(), captured.get());
    }

    private static void scrubGitEnvironment(Map<String, String> environment)
    {
        environment.keySet().removeIf(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_")); //$NON-NLS-1$
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null/no-such-gitconfig"); //$NON-NLS-1$ //$NON-NLS-2$
        environment.put("GIT_CONFIG_SYSTEM", "/dev/null/no-such-gitconfig"); //$NON-NLS-1$ //$NON-NLS-2$
        environment.put("GIT_CONFIG_NOSYSTEM", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        environment.put("GIT_TERMINAL_PROMPT", "0"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String readAllQuietly(Process process)
    {
        try (InputStream in = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream())
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

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }
        try (Stream<Path> paths = Files.walk(root))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true);
                if (!path.toFile().delete())
                {
                    path.toFile().deleteOnExit();
                }
            });
        }
    }

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
