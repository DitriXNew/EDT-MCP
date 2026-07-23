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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link CommitGitChangesTool}.
 * <p>
 * The tool metadata, the input/output schema contract, and the argument-validation guards
 * (which fire BEFORE any repository access) are covered directly against {@code execute()}.
 * The actual staging + commit behaviour is covered against a REAL temporary git repository
 * through the package-visible {@link CommitGitChangesTool#commit} seam (extracted so the JGit
 * logic is unit-testable without a live EDT workspace/{@code ProjectContext} - the same seam
 * pattern as {@code GetGitStatusTool.renderStatus} / {@code GitRepositoryResolver.discoverFromDirectory};
 * no mocking of JGit anywhere here). The committer-identity contract is covered against the pure
 * {@link CommitGitChangesTool#committerIdentityError} helper.
 * <p>
 * There is deliberately NO happy-path test that drives {@code execute()} end-to-end: that needs a
 * live EDT workspace with a real git working tree, and a real commit would litter the plugin's own
 * repository (the CI fixture's backing repo) - the same reason {@code create_git_branch}/{@code
 * switch_git_branch} keep their happy path to a hand-run attended gate.
 */
public class CommitGitChangesToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_cgc_zzz"; //$NON-NLS-1$

    // ==================== metadata + schema contract ====================

    @Test
    public void testName()
    {
        assertEquals("commit_git_changes", new CommitGitChangesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CommitGitChangesTool.NAME, new CommitGitChangesTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CommitGitChangesTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new CommitGitChangesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('commit_git_changes')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParametersAndRequiredSet()
    {
        String schema = new CommitGitChangesTool().getInputSchema();
        assertNotNull(schema);
        for (String param : new String[] {"projectName", "message", "all", "paths"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("message must be required", requiredBlock.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String optional : new String[] {"all", "paths"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertFalse(optional + " must NOT be required", requiredBlock.contains("\"" + optional + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new CommitGitChangesTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "commitId", "branch", "stagedFiles"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    // ==================== argument validation (returns before any repository access) ====================

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("message", "some message"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CommitGitChangesTool().execute(params);
        assertError(result);
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingMessageIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CommitGitChangesTool().execute(params);
        assertError(result);
        assertTrue("error must name message", result.toLowerCase().contains("message")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBlankMessageIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("message", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CommitGitChangesTool().execute(params);
        assertError(result);
        assertTrue("error must name message", result.toLowerCase().contains("message")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say blank/required", result.toLowerCase().contains("blank") //$NON-NLS-1$ //$NON-NLS-2$
            || result.toLowerCase().contains("required")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyParamsRejectsOnProjectNameFirst()
    {
        // requireArguments checks in order: projectName is checked before message.
        String result = new CommitGitChangesTool().execute(new HashMap<>());
        assertError(result);
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonexistentProjectIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("message", "some message"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CommitGitChangesTool().execute(params);
        assertError(result);
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== committer-identity contract (pure helper) ====================

    @Test
    public void testIdentityErrorWhenBothPresentIsNull()
    {
        assertNull("a fully configured identity is no error", //$NON-NLS-1$
            CommitGitChangesTool.committerIdentityError("Ada Lovelace", "ada@example.org")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIdentityErrorNamesMissingName()
    {
        String err = CommitGitChangesTool.committerIdentityError(null, "ada@example.org"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue("must name user.name", err.contains("user.name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must claim failure", err.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIdentityErrorNamesMissingEmail()
    {
        String err = CommitGitChangesTool.committerIdentityError("Ada Lovelace", null); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue("must name user.email", err.contains("user.email")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIdentityErrorNamesBothWhenBothMissing()
    {
        String err = CommitGitChangesTool.committerIdentityError(null, null);
        assertNotNull(err);
        assertTrue("must name user.name", err.contains("user.name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name user.email", err.contains("user.email")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIdentityErrorTreatsBlankAsMissing()
    {
        assertNotNull("a blank name is missing", //$NON-NLS-1$
            CommitGitChangesTool.committerIdentityError("   ", "ada@example.org")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== commit() against a REAL temporary git repository ====================

    @Test
    public void testCommitReturnsShaForStagedContent() throws Exception
    {
        File repoDir = Files.createTempDirectory("cgc-sha").toFile(); //$NON-NLS-1$
        try (Repository repo = newRepoWithIdentity(repoDir))
        {
            // A Cyrillic commit message proves UTF-8 is preserved end to end. The message is built from
            // char-code literals (ASCII-only source, no raw Cyrillic, no unicode escapes) so it stays
            // encoding-neutral under the Tycho build; the codes spell a Cyrillic greeting + " commit".
            String message = new String(new char[] {0x41F, 0x440, 0x438, 0x432, 0x435, 0x442}) + " commit"; //$NON-NLS-1$
            writeFile(repoDir, "a.txt", "hello"); //$NON-NLS-1$ //$NON-NLS-2$
            Git.wrap(repo).add().addFilepattern("a.txt").call(); //$NON-NLS-1$

            String result = CommitGitChangesTool.commit(repo, message, false, null);
            JsonObject json = parse(result);
            assertTrue("commit must succeed", json.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
            String commitId = json.get("commitId").getAsString(); //$NON-NLS-1$
            assertTrue("commitId must be a 40-hex SHA-1: " + commitId, commitId.matches("[0-9a-f]{40}")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("commitId must be HEAD", repo.resolve("HEAD").getName(), commitId); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("one file was staged", 1, json.get("stagedFiles").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$

            try (RevWalk walk = new RevWalk(repo))
            {
                RevCommit head = walk.parseCommit(repo.resolve("HEAD")); //$NON-NLS-1$
                assertEquals("the Cyrillic message must be preserved verbatim", message, head.getFullMessage()); //$NON-NLS-1$
            }
        }
        finally
        {
            deleteRecursively(repoDir);
        }
    }

    @Test
    public void testNothingStagedIsAnErrorNotAFakeSuccess() throws Exception
    {
        File repoDir = Files.createTempDirectory("cgc-empty").toFile(); //$NON-NLS-1$
        try (Repository repo = newRepoWithIdentity(repoDir))
        {
            // An initial commit so HEAD exists and the tree is clean; then a commit with nothing staged.
            writeFile(repoDir, "a.txt", "hello"); //$NON-NLS-1$ //$NON-NLS-2$
            Git.wrap(repo).add().addFilepattern("a.txt").call(); //$NON-NLS-1$
            Git.wrap(repo).commit().setMessage("initial").call(); //$NON-NLS-1$
            String headBefore = repo.resolve("HEAD").getName(); //$NON-NLS-1$

            String result = CommitGitChangesTool.commit(repo, "nothing here", false, null); //$NON-NLS-1$
            JsonObject json = parse(result);
            assertFalse("a clean tree must not commit", json.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("error must say nothing to commit", //$NON-NLS-1$
                json.get("error").getAsString().toLowerCase().contains("nothing to commit")); //$NON-NLS-1$ //$NON-NLS-2$
            // Anti-fake-success: HEAD must not have moved (no empty commit was recorded).
            assertEquals("HEAD must be unchanged after a refused commit", //$NON-NLS-1$
                headBefore, repo.resolve("HEAD").getName()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(repoDir);
        }
    }

    @Test
    public void testAllStagesTrackedModifiedButNotUntracked() throws Exception
    {
        File repoDir = Files.createTempDirectory("cgc-all").toFile(); //$NON-NLS-1$
        try (Repository repo = newRepoWithIdentity(repoDir))
        {
            writeFile(repoDir, "a.txt", "v1"); //$NON-NLS-1$ //$NON-NLS-2$
            Git.wrap(repo).add().addFilepattern("a.txt").call(); //$NON-NLS-1$
            Git.wrap(repo).commit().setMessage("initial").call(); //$NON-NLS-1$

            // Modify a TRACKED file and add an UNTRACKED one.
            writeFile(repoDir, "a.txt", "v2"); //$NON-NLS-1$ //$NON-NLS-2$
            writeFile(repoDir, "b.txt", "brand new"); //$NON-NLS-1$ //$NON-NLS-2$

            String result = CommitGitChangesTool.commit(repo, "stage tracked only", true, null); //$NON-NLS-1$
            JsonObject json = parse(result);
            assertTrue("all=true must commit the tracked modification", json.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("only the tracked-modified file was staged", 1, json.get("stagedFiles").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$

            // Proof that all=true did NOT sweep in the untracked file: it is still untracked, and a
            // second all=true commit now has nothing to stage.
            assertTrue("the untracked file must remain untracked", //$NON-NLS-1$
                Git.wrap(repo).status().call().getUntracked().contains("b.txt")); //$NON-NLS-1$
            JsonObject second = parse(CommitGitChangesTool.commit(repo, "again", true, null)); //$NON-NLS-1$
            assertFalse("nothing tracked-modified remains, so the second all=true commit is refused", //$NON-NLS-1$
                second.get("success").getAsBoolean()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(repoDir);
        }
    }

    @Test
    public void testPathsStagesOnlyTheGivenPath() throws Exception
    {
        File repoDir = Files.createTempDirectory("cgc-paths").toFile(); //$NON-NLS-1$
        try (Repository repo = newRepoWithIdentity(repoDir))
        {
            writeFile(repoDir, "seed.txt", "seed"); //$NON-NLS-1$ //$NON-NLS-2$
            Git.wrap(repo).add().addFilepattern("seed.txt").call(); //$NON-NLS-1$
            Git.wrap(repo).commit().setMessage("initial").call(); //$NON-NLS-1$

            // Two untracked files; only x.txt is listed in paths[].
            writeFile(repoDir, "x.txt", "pick me"); //$NON-NLS-1$ //$NON-NLS-2$
            writeFile(repoDir, "y.txt", "leave me"); //$NON-NLS-1$ //$NON-NLS-2$

            String result = CommitGitChangesTool.commit(repo, "commit x only", false, Arrays.asList("x.txt")); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject json = parse(result);
            assertTrue("the explicit path must commit", json.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("only x.txt was staged", 1, json.get("stagedFiles").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("y.txt must remain untracked (not swept in)", //$NON-NLS-1$
                Git.wrap(repo).status().call().getUntracked().contains("y.txt")); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(repoDir);
        }
    }

    // ==================== helpers ====================

    private static void assertError(String result)
    {
        assertTrue("must reject with an error: " + result, //$NON-NLS-1$
            result.contains("\"success\":false") || result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** Initialises a fresh repo and configures a deterministic local identity (overriding any global). */
    private static Repository newRepoWithIdentity(File dir) throws Exception
    {
        Repository repo = Git.init().setDirectory(dir).call().getRepository();
        StoredConfig config = repo.getConfig();
        config.setString("user", null, "name", "Ada Lovelace"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        config.setString("user", null, "email", "ada@example.org"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        config.save();
        return repo;
    }

    private static void writeFile(File repoDir, String name, String content) throws Exception
    {
        Files.write(new File(repoDir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
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
