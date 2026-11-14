/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.junit.Test;

/**
 * #922 (Codex P2 on #928): {@code create_git_branch} re-reads the checked-out branch after work that ran outside the
 * branch lock - the reload wait and the application binding - before it reports {@code checkedOut}.
 */
public class CreateGitBranchRecheckTest
{
    @Test
    public void theNewBranchStillCheckedOutAddsNoWarning() throws Exception
    {
        withRepository(git -> {
            git.checkout().setName("feature").call(); //$NON-NLS-1$
            List<String> warnings = new ArrayList<>();
            assertTrue(CreateGitBranchTool.stillCheckedOut(git.getRepository(), "feature", "during", warnings)); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(warnings.isEmpty());
        });
    }

    @Test
    public void aRepositoryMovedAwayIsNotReportedCheckedOut() throws Exception
    {
        withRepository(git -> {
            List<String> warnings = new ArrayList<>();
            assertFalse(CreateGitBranchTool.stillCheckedOut(git.getRepository(), "feature", //$NON-NLS-1$
                "while this call attached the application", warnings)); //$NON-NLS-1$
            assertEquals(1, warnings.size());
            String warning = warnings.get(0);
            assertTrue(warning, warning.contains("'feature'") && warning.contains("'master'") //$NON-NLS-1$ //$NON-NLS-2$
                && warning.contains("while this call attached the application")); //$NON-NLS-1$
        });
    }

    private interface GitWork
    {
        void run(Git git) throws Exception;
    }

    private static void withRepository(GitWork work) throws Exception
    {
        Path dir = Files.createTempDirectory("wt922create"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("master").call()) //$NON-NLS-1$
        {
            git.commit().setMessage("base").setAllowEmpty(true) //$NON-NLS-1$
                .setAuthor("t", "t@example.invalid").setCommitter("t", "t@example.invalid").call(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            git.branchCreate().setName("feature").call(); //$NON-NLS-1$
            work.run(git);
        }
        finally
        {
            try (Stream<Path> walk = Files.walk(dir))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
