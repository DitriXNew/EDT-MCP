/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.GitTool.CommandRejectedException;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.git.GitCloneTarget;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code git clone} in the {@link GitTool} (#934): the allow-list grammar, the URL forms, the
 * target-directory rules of {@link GitCloneTarget}, the cleanup of a failed clone, and the order of the
 * checks through {@link GitTool#execute}. The real process is covered by {@link GitToolCloneProcessTest}.
 */
public class GitToolCloneTest
{
    private static final String URL = "https://example.com/team/repo.git"; //$NON-NLS-1$

    private final List<Path> folders = new ArrayList<>();

    @After
    public void tearDown() throws IOException
    {
        for (Path folder : folders)
        {
            deleteTree(folder);
        }
    }

    // ---- the grammar: what is accepted ----

    @Test
    public void testCloneIsWhitelistedAndTakesAUrlAndAnAbsoluteDirectory() throws Exception
    {
        String dir = absolute("repo"); //$NON-NLS-1$
        assertTrue(GitTool.ALLOWED_SUBCOMMANDS.contains("clone")); //$NON-NLS-1$
        assertEquals(List.of("git", "clone", URL, dir), GitTool.parseCommand("clone " + URL + " " + quoted(dir))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GitTool.CloneCommand clone = GitTool.checkCloneGrammar(List.of(URL, dir));
        assertEquals(URL, clone.url());
        assertEquals(dir, clone.directory());
        assertEquals(1, clone.directoryIndex());
    }

    @Test
    public void testEveryAllowedOptionIsAccepted() throws Exception
    {
        String dir = absolute("repo"); //$NON-NLS-1$
        for (String options : List.of("-q", "--quiet", "-n", "--no-checkout", "--single-branch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "-b main", "-bmain", "--branch main", "--branch=main", "--branch=v1.0", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "--depth 1", "--depth=50", "--filter=blob:none", "--filter blob:limit=1m", "--filter=tree:0", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "--filter=object:type=commit", "-o upstream", "-oupstream", "--origin=upstream", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "-qn", "-qnb main", "-qbmain", "--depth=1 --single-branch -b main")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            List<String> argv = GitTool.parseCommand("clone " + options + " " + URL + " " + quoted(dir)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(options, dir, argv.get(argv.size() - 1));
        }
        // '--' ends the options; the two operands still follow it.
        GitTool.CloneCommand afterDashes = GitTool.checkCloneGrammar(List.of("-q", "--", URL, dir)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, afterDashes.directoryIndex());
    }

    @Test
    public void testEveryAcceptedUrlFormPasses() throws Exception
    {
        String dir = absolute("repo"); //$NON-NLS-1$
        for (String url : List.of(URL, "ssh://git@example.com/team/repo.git", "ssh://example.com:2222/repo.git", //$NON-NLS-1$ //$NON-NLS-2$
            "git+ssh://git@example.com/repo.git", "git://example.com/repo.git", "git@example.com:team/repo.git", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "example.com:team/repo.git", "git@example.com:/srv/repo.git", "git@[::1]:repo.git")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertEquals(url, url, GitTool.checkCloneGrammar(List.of(url, dir)).url());
        }
    }

    // ---- the grammar: what is refused ----

    @Test
    public void testEveryOptionOutsideTheAllowListIsRefused()
    {
        String dir = quoted(absolute("repo")); //$NON-NLS-1$
        for (String option : List.of("-c core.sshCommand=evil", "--bare", "--mirror", "--shared", "-s", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "--local", "-l", "--separate-git-dir=x", "--reference=/r", "--reference-if-able=/r", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "--dissociate", "--recurse-submodules", "--recursive", "-u evil", "-j 4", "--no-single-branch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "--bran=main", "--progress", "--sparse", "-qc x=y", "--server-option=x")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            String error = rejection("clone " + option + " " + URL + " " + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(option + " -> " + error, error.contains("is not accepted for 'git clone'")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The global blocked list still fires first for its own options, with its own message.
        for (String option : List.of("--config=core.sshCommand=evil", "--upload-pack=evil", "--template=/t")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String error = rejection("clone " + option + " " + URL + " " + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(option + " -> " + error, error.contains("is not allowed")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testOptionValuesAreValidated()
    {
        String dir = quoted(absolute("repo")); //$NON-NLS-1$
        Map<String, String> refused = new HashMap<>();
        refused.put("--depth 0", "positive whole number"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--depth=abc", "positive whole number"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--depth=99999999999", "positive whole number"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--depth", "positive whole number"); // the URL is read as its value //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--depth -1", "needs a value"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--filter=sparse:oid=HEAD:x", "filter specs"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--filter=combine:blob:none+tree:0", "filter specs"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--filter=blob:nothing", "filter specs"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("-b HEAD", "not a valid branch"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--branch=a..b", "not a valid branch"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("-b -x", "needs a value"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--origin=a..b", "not a valid remote name"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("-o -upload", "needs a value"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("--quiet=yes", "takes no value"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, String> entry : refused.entrySet())
        {
            String error = rejection("clone " + entry.getKey() + " " + URL + " " + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(entry.getKey() + " -> " + error, error.contains(entry.getValue())); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheOperandsMustBeAUrlAndAnAbsoluteDirectory()
    {
        String dir = absolute("repo"); //$NON-NLS-1$
        assertTrue(rejection("clone " + URL).contains("exactly two operands")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rejection("clone").contains("exactly two operands")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rejection("clone " + URL + " " + quoted(dir) + " extra").contains("exactly two operands")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(rejection("clone " + URL + " repo").contains("ABSOLUTE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String dotted = quoted(dir + java.io.File.separator + ".." + java.io.File.separator + "other"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rejection("clone " + URL + " " + dotted).contains("'..' segments")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testUrlsOutsideTheAcceptedFormsAreRefused()
    {
        String dir = quoted(absolute("repo")); //$NON-NLS-1$
        Map<String, String> refused = new HashMap<>();
        refused.put("file:///srv/repo.git", "not allowed"); // the shared scheme guard //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("http://example.com/repo.git", "scheme 'http://' is not accepted"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("ftp://example.com/repo.git", "scheme 'ftp://' is not accepted"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("HTTPS://example.com/repo.git", "not allowed"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("ext::sh -c x", "transport-helper"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("https://user:secret@example.com/repo.git", "username:password"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("https://token@example.com/repo.git", "username:password"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("https://example.com/repo.git?access_token=x", "query string"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("https://example.com/repo.git#t", "query string"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("/srv/repo.git", "local path"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("C:\\repos\\repo.git", "local path"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("C:repo.git", "local path"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("../repo.git", "local path"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("repo", "local path"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("ssh://-oProxyCommand=evil/repo.git", "host starting with '-'"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("ssh:///repo.git", "names no host"); //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("-oProxyCommand=evil:repo.git", "not a valid remote name"); // read as -o <name> //$NON-NLS-1$ //$NON-NLS-2$
        refused.put("user:secret@example.com:team/repo.git", "'@' after the ':'"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, String> entry : refused.entrySet())
        {
            String error = rejection("clone " + quoted(entry.getKey()) + " " + dir); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(entry.getKey() + " -> " + error, error.contains(entry.getValue())); //$NON-NLS-1$
        }
        // After '--' an operand may start with '-'; as a URL it is still refused - the host check.
        String dashed = rejection("clone -- " + quoted("-oProxyCommand=evil:repo.git") + " " + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(dashed, dashed.contains("local path")); //$NON-NLS-1$
        String spaced = rejection("clone \"https://example.com/team/my repo.git\" " + dir); //$NON-NLS-1$
        assertTrue(spaced, spaced.contains("whitespace")); //$NON-NLS-1$
    }

    /** A clone creates a repository too, so it runs with init's format pins (an EMPTY remote takes them). */
    @Test
    public void testACloneRunsWithTheRepositoryFormatPins() throws Exception
    {
        Path parent = freeFolder();
        ProcessBuilder clone = GitTool.processFor(
            GitTool.parseCommand("clone " + URL + " " + quoted(parent.resolve("repo").toString())), parent.toFile()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(clone.command().toString(), clone.command().contains("init.defaultObjectFormat=sha1")); //$NON-NLS-1$
        assertTrue(clone.command().toString(), clone.command().contains("core.sharedRepository=false")); //$NON-NLS-1$
        assertEquals("sha1", clone.environment().get("GIT_DEFAULT_HASH")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the subcommand still comes after the pins", "clone", //$NON-NLS-1$ //$NON-NLS-2$
            clone.command().get(clone.command().indexOf(URL) - 1));
    }

    // ---- consent ----

    @Test
    public void testCloneIsWriteCapableAndItsPreviewNamesTheUrlAndTheRealTarget() throws Exception
    {
        assertEquals("clone", GitTool.destructiveForm(List.of("git", "clone", URL, "x"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(DestructiveConsentGate.GATED_TOOLS.contains(GitTool.NAME));
        Path parent = freeFolder();
        Path target = parent.resolve("repo"); //$NON-NLS-1$
        List<String> argv = GitTool.parseCommand("clone " + URL + " " + quoted(target.toString())); //$NON-NLS-1$ //$NON-NLS-2$
        GitCloneTarget.Checked checked = GitCloneTarget.check(target, List.of());
        assertTrue(String.valueOf(checked.refusal()), checked.ok());
        ConsentPreview preview = GitTool.cloneConsentPreview(argv,
            GitTool.checkCloneGrammar(argv.subList(2, argv.size())), checked);
        String subtitle = preview.getSubtitle();
        assertTrue(subtitle, subtitle.contains(URL));
        assertTrue(subtitle, subtitle.contains(parent.toRealPath().resolve("repo").toString())); //$NON-NLS-1$
        assertTrue(subtitle, subtitle.contains("new directory")); //$NON-NLS-1$
        assertTrue("the clone's arguments are loggable: refs, paths, a URL without credentials", //$NON-NLS-1$
            preview.areNamesLoggable());
    }

    // ---- the target directory ----

    @Test
    public void testANewDirectoryUnderAnExistingParentIsAccepted() throws Exception
    {
        Path parent = freeFolder();
        GitCloneTarget.Checked checked = GitCloneTarget.check(parent.resolve("repo"), List.of()); //$NON-NLS-1$
        assertTrue(String.valueOf(checked.refusal()), checked.ok());
        assertFalse(checked.existed());
        assertEquals(parent.toRealPath().resolve("repo"), checked.real()); //$NON-NLS-1$
    }

    @Test
    public void testAnExistingEmptyDirectoryIsAcceptedAndANonEmptyOneRefused() throws Exception
    {
        Path parent = freeFolder();
        Path empty = Files.createDirectory(parent.resolve("empty")); //$NON-NLS-1$
        GitCloneTarget.Checked checked = GitCloneTarget.check(empty, List.of());
        assertTrue(String.valueOf(checked.refusal()), checked.ok());
        assertTrue(checked.existed());

        Files.write(empty.resolve("readme.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = GitCloneTarget.check(empty, List.of()).refusal();
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("exists and is not empty")); //$NON-NLS-1$

        Path file = Files.write(parent.resolve("file.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        String fileRefusal = GitCloneTarget.check(file, List.of()).refusal();
        assertTrue(String.valueOf(fileRefusal), String.valueOf(fileRefusal).contains("is not a directory")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingParentAndAFilesystemRootAreRefused() throws Exception
    {
        Path parent = freeFolder();
        String missing = GitCloneTarget.check(parent.resolve("no").resolve("repo"), List.of()).refusal(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(missing), String.valueOf(missing).contains("does not exist")); //$NON-NLS-1$
        Path root = parent.getRoot();
        String rootRefusal = GitCloneTarget.check(root, List.of()).refusal();
        assertTrue(String.valueOf(rootRefusal), String.valueOf(rootRefusal).contains("filesystem root")); //$NON-NLS-1$
    }

    @Test
    public void testATargetInsideAGitWorkTreeIsRefusedAndNamesItsRoot() throws Exception
    {
        Path outer = freeFolder();
        Files.createDirectory(outer.resolve(".git")); //$NON-NLS-1$
        Path nested = Files.createDirectories(outer.resolve("work")); //$NON-NLS-1$
        String newTarget = GitCloneTarget.check(nested.resolve("repo"), List.of()).refusal(); //$NON-NLS-1$
        assertNotNull("a new target inside a work tree must be refused", newTarget); //$NON-NLS-1$
        assertTrue(newTarget, newTarget.contains("lies inside the git repository at '" + outer.toRealPath() + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        String existingTarget = GitCloneTarget.check(nested, List.of()).refusal();
        assertTrue(String.valueOf(existingTarget), String.valueOf(existingTarget).contains("nested")); //$NON-NLS-1$
    }

    @Test
    public void testATargetInsideOrAroundAWorkspaceProjectFolderIsRefused() throws Exception
    {
        Path parent = freeFolder();
        Path projectFolder = Files.createDirectories(parent.resolve("Sales")); //$NON-NLS-1$
        List<GitCloneTarget.ProjectFolder> projects = List.of(new GitCloneTarget.ProjectFolder("Sales", projectFolder)); //$NON-NLS-1$
        String inside = GitCloneTarget.check(projectFolder.resolve("repo"), projects).refusal(); //$NON-NLS-1$
        assertTrue(String.valueOf(inside), String.valueOf(inside).contains("inside the folder of EDT project 'Sales'")); //$NON-NLS-1$

        // A project whose folder is not on disk any more but would lie inside the target.
        Path wrapper = parent.resolve("wrapper"); //$NON-NLS-1$
        List<GitCloneTarget.ProjectFolder> gone =
            List.of(new GitCloneTarget.ProjectFolder("Gone", wrapper.resolve("Gone"))); //$NON-NLS-1$ //$NON-NLS-2$
        String around = GitCloneTarget.check(wrapper, gone).refusal();
        assertTrue(String.valueOf(around), String.valueOf(around).contains("lies inside the clone target")); //$NON-NLS-1$

        assertNull("an unrelated project does not refuse", //$NON-NLS-1$
            GitCloneTarget.check(parent.resolve("repo"), projects).refusal()); //$NON-NLS-1$
    }

    // ---- the claim ----

    @Test
    public void testOneTargetIsHeldByOneCloneAtATime() throws Exception
    {
        Path target = freeFolder().resolve("repo"); //$NON-NLS-1$
        try (GitCloneTarget.Claim first = GitCloneTarget.claim(target))
        {
            assertNotNull(first);
            assertNull("the same target is held", GitCloneTarget.claim(target)); //$NON-NLS-1$
            assertNull("a directory inside it is held too", GitCloneTarget.claim(target.resolve("sub"))); //$NON-NLS-1$
            assertNull("and one around it", GitCloneTarget.claim(target.getParent())); //$NON-NLS-1$
        }
        try (GitCloneTarget.Claim again = GitCloneTarget.claim(target))
        {
            assertNotNull("released with the first clone", again); //$NON-NLS-1$
        }
    }

    @Test
    public void testAClaimWhoseJobCanNoLongerRunIsGivenUp() throws Exception
    {
        Path target = freeFolder().resolve("repo"); //$NON-NLS-1$
        GitCloneTarget.Claim stale = GitCloneTarget.claim(target);
        assertNotNull(stale);
        stale.bindTo(() -> false); // a job cancelled before it ever started
        try (GitCloneTarget.Claim next = GitCloneTarget.claim(target))
        {
            assertNotNull("a claim of a job that cannot run any more does not block the target", next); //$NON-NLS-1$
        }
        GitCloneTarget.Claim live = GitCloneTarget.claim(target);
        live.bindTo(() -> true);
        try
        {
            assertNull("a running job's claim holds", GitCloneTarget.claim(target)); //$NON-NLS-1$
        }
        finally
        {
            live.close();
        }
    }

    // ---- the staging directory: rollback and install ----

    @Test
    public void testTheStagingIsRemovedWithItsReadOnlyFilesAndNothingBesideIt() throws Exception
    {
        Path parent = freeFolder();
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        assertTrue(staging.path().getFileName().toString().startsWith(".edt-mcp-clone-")); //$NON-NLS-1$
        Path pack = Files.createDirectories(staging.repository().resolve(".git").resolve("objects").resolve("pack")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .resolve("pack-1.pack"); //$NON-NLS-1$
        Files.write(pack, "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertTrue(pack.toFile().setReadOnly());
        Path sibling = Files.write(parent.resolve("keep.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(staging.remove());
        assertFalse("the staging directory is gone", Files.exists(staging.path(), LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$
        assertTrue("nothing beside it is touched", Files.exists(sibling)); //$NON-NLS-1$
        assertFalse("the target was never created", Files.exists(parent.resolve("repo"), LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Codex P1 on #944: a staging directory REPLACED by a junction to somewhere else is not this run's
     * any more - the rollback must leave it, and above all not walk into what it points at.
     */
    @Test
    public void testAStagingReplacedByAJunctionIsLeftAloneAndNotFollowed() throws Exception
    {
        Assume.assumeTrue("junctions are a Windows feature", java.io.File.separatorChar == '\\'); //$NON-NLS-1$
        Path parent = freeFolder();
        Path outside = Files.createDirectory(parent.resolve("outside")); //$NON-NLS-1$
        Path precious = Files.write(outside.resolve("precious.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        // The junction's destination even carries this run's very marker: only "is it a plain directory"
        // tells them apart.
        Files.copy(staging.path().resolve(".edt-mcp-clone"), outside.resolve(".edt-mcp-clone")); //$NON-NLS-1$ //$NON-NLS-2$
        deleteTree(staging.path());
        junction(staging.path(), outside);

        assertFalse("a swapped-in junction is not removed as if it were the clone's", staging.remove()); //$NON-NLS-1$
        assertTrue("what the junction points at is untouched", Files.exists(precious)); //$NON-NLS-1$
        Files.delete(staging.path()); // the junction itself, for the test's own cleanup
    }

    /**
     * Codex P1 on #944, the other half: a plain directory re-created under the staging name (its
     * creation time may even be the old one's - NTFS tunnelling) is not provably this run's.
     */
    @Test
    public void testAStagingReCreatedByAnotherProgramIsLeftAlone() throws Exception
    {
        Path parent = freeFolder();
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        deleteTree(staging.path());
        Files.createDirectory(staging.path());
        Files.write(staging.path().resolve(".edt-mcp-clone"), "someone-else".getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$ //$NON-NLS-2$
        Path theirs = Files.write(staging.path().resolve("theirs.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(staging.remove());
        assertTrue("another program's directory is not deleted", Files.exists(theirs)); //$NON-NLS-1$
    }

    @Test
    public void testTheRollbackDoesNotEnterAJunctionInsideTheStaging() throws Exception
    {
        Assume.assumeTrue("junctions are a Windows feature", java.io.File.separatorChar == '\\'); //$NON-NLS-1$
        Path parent = freeFolder();
        Path outside = Files.createDirectory(parent.resolve("outside")); //$NON-NLS-1$
        Path precious = Files.write(outside.resolve("precious.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        Files.createDirectories(staging.repository());
        junction(staging.repository().resolve("junction"), outside); //$NON-NLS-1$
        assertTrue("fixture: Java reports the junction as a directory", //$NON-NLS-1$
            Files.isDirectory(staging.repository().resolve("junction"), LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$

        assertTrue(staging.remove());
        assertFalse(Files.exists(staging.path(), LinkOption.NOFOLLOW_LINKS));
        assertTrue("what the junction pointed at is untouched", Files.exists(precious)); //$NON-NLS-1$
    }

    @Test
    public void testTheRollbackDoesNotFollowALinkInsideTheStaging() throws Exception
    {
        Path parent = freeFolder();
        Path outside = Files.createDirectory(parent.resolve("outside")); //$NON-NLS-1$
        Path precious = Files.write(outside.resolve("precious.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        Files.createDirectories(staging.repository());
        try
        {
            Files.createSymbolicLink(staging.repository().resolve("link"), outside); //$NON-NLS-1$
        }
        catch (IOException | UnsupportedOperationException e)
        {
            Assume.assumeNoException("symbolic links cannot be created here", e); //$NON-NLS-1$
        }
        assertTrue(staging.remove());
        assertTrue("what the link pointed at is untouched", Files.exists(precious)); //$NON-NLS-1$
    }

    @Test
    public void testASuccessfulCloneIsMovedToANewTarget() throws Exception
    {
        Path parent = freeFolder();
        GitCloneTarget.Checked checked = checked(parent.resolve("repo")); //$NON-NLS-1$
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked);
        Files.createDirectories(staging.repository().resolve(".git")); //$NON-NLS-1$
        Files.write(staging.repository().resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(GitCloneTarget.install(staging, checked).refusal());
        assertTrue(Files.isDirectory(checked.real().resolve(".git"))); //$NON-NLS-1$
        assertTrue(Files.exists(checked.real().resolve("a.txt"))); //$NON-NLS-1$
        assertFalse("the staging directory is gone", Files.exists(staging.path(), LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$
    }

    /**
     * Codex P1 on #944: a file another program put into an existing target while the clone ran stays -
     * the clone's entries join it, nothing is deleted or replaced.
     */
    @Test
    public void testAnExistingTargetKeepsAFileThatAppearedInItWhileTheCloneRan() throws Exception
    {
        Path parent = freeFolder();
        Path target = Files.createDirectory(parent.resolve("repo")); //$NON-NLS-1$
        GitCloneTarget.Checked checked = checked(target);
        assertTrue(checked.existed());
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked);
        Files.createDirectories(staging.repository().resolve(".git")); //$NON-NLS-1$
        Files.write(staging.repository().resolve("a.txt"), "clone".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        Path theirs = Files.write(target.resolve("theirs.txt"), "theirs".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(GitCloneTarget.install(staging, checked).refusal());
        assertTrue(Files.isDirectory(target.resolve(".git"))); //$NON-NLS-1$
        assertEquals("theirs", new String(Files.readAllBytes(theirs), StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertFalse(Files.exists(staging.path(), LinkOption.NOFOLLOW_LINKS));

        // A name collision: nothing is replaced, what was moved goes back, the target stays as it was.
        Path second = Files.createDirectory(parent.resolve("second")); //$NON-NLS-1$
        GitCloneTarget.Checked secondChecked = checked(second);
        GitCloneTarget.Staging other = GitCloneTarget.createStaging(secondChecked);
        Files.createDirectories(other.repository().resolve(".git")); //$NON-NLS-1$
        Files.write(other.repository().resolve("a.txt"), "clone".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        Path clash = Files.write(second.resolve("a.txt"), "theirs".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = GitCloneTarget.install(other, secondChecked).refusal();
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("left as it was")); //$NON-NLS-1$
        assertEquals("theirs", new String(Files.readAllBytes(clash), StandardCharsets.UTF_8)); //$NON-NLS-1$
        try (Stream<Path> left = Files.list(second))
        {
            assertEquals("only their file is in the target", 1, left.count()); //$NON-NLS-1$
        }
        assertTrue("the clone is back in staging", Files.isDirectory(other.repository().resolve(".git"))); //$NON-NLS-1$
        assertTrue(other.remove());
    }

    // ---- who wins: the cancel or the worker ----

    @Test
    public void testACancelAfterTheWorkerConcludedStopsNothing()
    {
        GitTool.CloneControl control = new GitTool.CloneControl();
        assertTrue(control.conclude());
        assertFalse("a clone that already concluded is not stopped", control.requestStop()); //$NON-NLS-1$
        assertFalse(control.stopRequested());
    }

    @Test
    public void testAWorkerAfterACancelDoesNotConclude()
    {
        GitTool.CloneControl control = new GitTool.CloneControl();
        assertTrue(control.requestStop());
        assertFalse("a cancel that won first is not overridden by a later success", control.conclude()); //$NON-NLS-1$
        assertTrue(control.stopRequested());
    }

    /** Codex P2 on #944: the rollback waits for the process tree; the watch says when it is really gone. */
    @Test(timeout = 60_000)
    public void testTheProcessWatchWaitsUntilTheKilledProcessHasExited() throws Exception
    {
        boolean windows = java.io.File.separatorChar == '\\';
        Process sleeper = new ProcessBuilder(windows ? List.of("ping", "-n", "60", "127.0.0.1") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            : List.of("sleep", "60")).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            GitTool.ProcessWatch watch = new GitTool.ProcessWatch();
            watch.started(sleeper);
            assertFalse("a live process is not reported gone", watch.awaitExit(300)); //$NON-NLS-1$
            watch.killAll();
            assertTrue("a killed process is waited for until it has exited", watch.awaitExit(20_000)); //$NON-NLS-1$
            assertFalse(sleeper.isAlive());
        }
        finally
        {
            sleeper.destroyForcibly();
        }
    }

    // ---- what EDT can read ----

    @Test
    public void testAShaTwoFiftySixOrPartialCloneIsNamedAndAPlainOneIsNot() throws Exception
    {
        Path plain = Files.createDirectories(freeFolder().resolve(".git")).getParent(); //$NON-NLS-1$
        Files.write(plain.resolve(".git").resolve("config"), //$NON-NLS-1$ //$NON-NLS-2$
            "[core]\n\trepositoryformatversion = 0\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertEquals(List.of(), GitCloneTarget.formatWarnings(plain));

        Path sha256 = Files.createDirectories(freeFolder().resolve(".git")).getParent(); //$NON-NLS-1$
        Files.write(sha256.resolve(".git").resolve("config"), ("[core]\n\trepositoryformatversion = 1\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "[extensions]\n\tobjectformat = sha256\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        List<String> warnings = GitCloneTarget.formatWarnings(sha256);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("sha256 repository")); //$NON-NLS-1$

        Path partial = Files.createDirectories(freeFolder().resolve(".git")).getParent(); //$NON-NLS-1$
        Files.write(partial.resolve(".git").resolve("config"), ("[core]\n\trepositoryformatversion = 1\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "[extensions]\n\tpartialclone = origin\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        List<String> partialWarnings = GitCloneTarget.formatWarnings(partial);
        assertEquals(1, partialWarnings.size());
        assertTrue(partialWarnings.get(0), partialWarnings.get(0).contains("partial clone")); //$NON-NLS-1$
    }

    // ---- execute(): the order of the checks ----

    @Test
    public void testACloneRefusesAProjectName()
    {
        JsonObject result = execute(Map.of("projectName", "Sales", "command", "clone " + URL + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            + quoted(absolute("repo")))); //$NON-NLS-1$
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("error").getAsString().contains("takes no projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOtherSubcommandsStillNeedAProjectName()
    {
        JsonObject result = execute(Map.of("command", "status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("error").getAsString().contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testATargetRefusalComesBeforeConsentAndCreatesNothing() throws Exception
    {
        Path parent = freeFolder();
        Path occupied = Files.createDirectory(parent.resolve("occupied")); //$NON-NLS-1$
        Files.write(occupied.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = execute(Map.of("command", "clone " + URL + " " + quoted(occupied.toString()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the target refusal, not the consent one: " + result, //$NON-NLS-1$
            result.get("error").getAsString().contains("exists and is not empty")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testWithoutConsentNothingIsCreated() throws Exception
    {
        Path parent = freeFolder();
        Path target = parent.resolve("repo"); //$NON-NLS-1$
        JsonObject result = execute(Map.of("command", "clone " + URL + " " + quoted(target.toString()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("clone is gated like every write-capable subcommand", //$NON-NLS-1$
            DestructiveConsentGate.consentDeniedMessage(DestructiveConsentGate.ConsentDecision.UNATTENDED,
                GitTool.NAME),
            result.get("error").getAsString()); //$NON-NLS-1$
        assertFalse("nothing may be created without consent", Files.exists(target, LinkOption.NOFOLLOW_LINKS)); //$NON-NLS-1$
    }

    // ---- helpers ----

    private static String rejection(String command)
    {
        try
        {
            List<String> argv = GitTool.parseCommand(command);
            fail("expected '" + command + "' to be rejected, got " + argv); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        catch (CommandRejectedException e)
        {
            return e.getMessage();
        }
    }

    private static JsonObject execute(Map<String, String> params)
    {
        return JsonParser.parseString(new GitTool().execute(params)).getAsJsonObject();
    }

    /** An absolute path in the temporary directory (not created). */
    private static String absolute(String name)
    {
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize() //$NON-NLS-1$
            .resolve("edt-mcp-clone-" + name).toString(); //$NON-NLS-1$
    }

    /** {@code text} quoted for the tool's tokenizer, which keeps backslashes inside single quotes. */
    private static String quoted(String text)
    {
        return "'" + text + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The checked verdict for a target that must be accepted. */
    private static GitCloneTarget.Checked checked(Path target)
    {
        GitCloneTarget.Checked checked = GitCloneTarget.check(target, List.of());
        assertTrue(String.valueOf(checked.refusal()), checked.ok());
        return checked;
    }

    /** A Windows directory junction at {@code link} pointing at {@code target}. */
    private static void junction(Path link, Path target) throws Exception
    {
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        Assume.assumeTrue("mklink /J failed", mklink.waitFor() == 0); //$NON-NLS-1$
    }

    private Path freeFolder() throws IOException
    {
        Path folder = Files.createTempDirectory("git-clone-934").toRealPath(); //$NON-NLS-1$
        folders.add(folder);
        Assume.assumeTrue("the temporary directory lies inside a git repository on this machine", //$NON-NLS-1$
            GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "scratch") == null); //$NON-NLS-1$
        return folder;
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
                path.toFile().delete();
            });
        }
    }
}
