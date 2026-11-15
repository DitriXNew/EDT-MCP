/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.git.GitCloneTarget;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What a failed or cancelled {@code git clone} takes back, through the job entry point
 * ({@link GitTool#startClone}) and a real git process (Codex review of #944): a file another program put
 * into an existing target while the clone ran is never deleted, and a cancel's rollback report reaches
 * whoever polls the job.
 */
public class GitToolCloneRollbackTest
{
    private final List<Path> scratch = new ArrayList<>();

    private final List<ServerSocket> servers = new ArrayList<>();

    private BackgroundJobs jobs;

    @Before
    public void setUp() throws Exception
    {
        boolean git;
        try
        {
            Process probe = new ProcessBuilder("git", "--version").redirectErrorStream(true).start(); //$NON-NLS-1$ //$NON-NLS-2$
            probe.getInputStream().readAllBytes();
            git = probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0;
        }
        catch (IOException e)
        {
            git = false;
        }
        Assume.assumeTrue("git is not available", git); //$NON-NLS-1$
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
        for (Path folder : scratch)
        {
            try (Stream<Path> paths = Files.walk(folder))
            {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    path.toFile().setWritable(true);
                    path.toFile().delete();
                });
            }
        }
    }

    /**
     * Codex P1 on #944: a pre-existing empty target into which another program wrote while the clone ran
     * keeps that file when the clone fails - the rollback deletes only what this run provably created.
     */
    @Test(timeout = 120_000)
    public void testAFailedCloneKeepsAFileThatAppearedInAnExistingTarget() throws Exception
    {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            closedPort = probe.getLocalPort();
        }
        Path target = Files.createDirectory(scratchFolder().resolve("clone")); //$NON-NLS-1$
        List<String> argv = GitTool.parseCommand("clone git://127.0.0.1:" + closedPort + "/repo.git '" + target + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GitCloneTarget.Checked checked = GitCloneTarget.check(target, List.of());
        assertTrue(String.valueOf(checked.refusal()), checked.ok());
        // Written after the last check, as another program would.
        Path theirs = Files.write(target.resolve("theirs.txt"), "theirs".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject result = JsonParser.parseString(new GitTool(jobs).startClone(argv,
            GitTool.checkCloneGrammar(argv.subList(2, argv.size())), checked, TimeUnit.MINUTES.toMillis(2), 60))
            .getAsJsonObject();

        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("a file the clone did not create is kept", Files.exists(theirs)); //$NON-NLS-1$
        assertEquals("theirs", new String(Files.readAllBytes(theirs), StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertTrue(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * Codex P2 on #944: cancelling a running clone publishes its rollback report as the job's result, so
     * get_job_status says what was removed (or what is left to remove) instead of a bare "cancelled".
     */
    @Test(timeout = 120_000)
    public void testACancelledClonePublishesItsRollbackReport() throws Exception
    {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        servers.add(server);
        CountDownLatch connected = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (Socket socket = server.accept())
            {
                connected.countDown();
                InputStream in = socket.getInputStream();
                while (in.read() != -1)
                {
                    // say nothing back
                }
            }
            catch (IOException e)
            {
                // closed
            }
        }, "clone-rollback-hang"); //$NON-NLS-1$
        holder.setDaemon(true);
        holder.start();
        Path target = scratchFolder().resolve("clone"); //$NON-NLS-1$
        List<String> argv = GitTool.parseCommand("clone git://127.0.0.1:" + server.getLocalPort() + "/repo.git '" //$NON-NLS-1$ //$NON-NLS-2$
            + target + "'"); //$NON-NLS-1$
        GitCloneTarget.Checked checked = GitCloneTarget.check(target, List.of());
        assertTrue(String.valueOf(checked.refusal()), checked.ok());

        JsonObject started = JsonParser.parseString(new GitTool(jobs).startClone(argv,
            GitTool.checkCloneGrammar(argv.subList(2, argv.size())), checked, TimeUnit.MINUTES.toMillis(10), 0))
            .getAsJsonObject();
        String jobId = started.get("jobId").getAsString(); //$NON-NLS-1$
        assertTrue("git reached the remote", connected.await(30, TimeUnit.SECONDS)); //$NON-NLS-1$

        jobs.cancel(jobId);
        BackgroundJobs.JobSnapshot snapshot = jobs.await(jobId, 30_000);

        assertEquals(BackgroundJobs.Status.CANCELLED, snapshot.getStatus());
        assertTrue("the cancellation result must be the rollback report, was " + snapshot.getResult(), //$NON-NLS-1$
            snapshot.getResult() instanceof String);
        JsonObject report = JsonParser.parseString((String)snapshot.getResult()).getAsJsonObject();
        assertTrue(report.toString(), report.has("cleanedUp")); //$NON-NLS-1$
    }

    private Path scratchFolder() throws IOException
    {
        Path folder = Files.createTempDirectory("git-clone-rollback").toRealPath(); //$NON-NLS-1$
        scratch.add(folder);
        Assume.assumeTrue("the temporary directory lies inside a git repository on this machine", //$NON-NLS-1$
            GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "scratch") == null); //$NON-NLS-1$
        return folder;
    }
}
