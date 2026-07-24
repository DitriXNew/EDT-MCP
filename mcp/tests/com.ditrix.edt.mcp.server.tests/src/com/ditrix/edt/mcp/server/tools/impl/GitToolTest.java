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

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.GitTool.CommandRejectedException;

/**
 * Contract + parser tests for {@link GitTool}. The exec path needs a real {@code git} process and a
 * repository, so it is covered by the e2e suite; here we exercise the security-critical parser
 * ({@link GitTool#tokenize} / {@link GitTool#parseCommand}) and the tool metadata directly.
 */
public class GitToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("git", new GitTool().getName()); //$NON-NLS-1$
        assertEquals(GitTool.NAME, new GitTool().getName());
    }

    @Test
    public void testResponseTypeAndSchema()
    {
        assertEquals(ResponseType.JSON, new GitTool().getResponseType());
        String schema = new GitTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"command\"")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionPointsToGuideAndSaysDisabledByDefault()
    {
        String desc = new GitTool().getDescription();
        assertTrue(desc.contains("get_tool_guide('git')")); //$NON-NLS-1$
        assertTrue("must state it is disabled by default", //$NON-NLS-1$
            desc.toLowerCase().contains("disabled by default")); //$NON-NLS-1$
    }

    @Test
    public void testAnnotationsOpenWorldAndDestructive()
    {
        // push/pull/fetch reach a remote -> openWorldHint=true; force-push/delete/restore/stash-drop can
        // destroy work -> destructiveHint=true.
        assertEquals(Boolean.TRUE, new GitTool().getAnnotations().getOpenWorldHint());
        assertEquals(Boolean.TRUE, new GitTool().getAnnotations().getDestructiveHint());
    }

    // ---- tokenizer ----

    @Test
    public void testTokenizeSplitsOnWhitespace() throws Exception
    {
        assertEquals(List.of("push", "origin", "main"), GitTool.tokenize("push origin main")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testTokenizeKeepsQuotedArgumentTogether() throws Exception
    {
        // A commit message with spaces stays one argument.
        List<String> t = GitTool.tokenize("commit -m \"my long message\""); //$NON-NLS-1$
        assertEquals(List.of("commit", "-m", "my long message"), t); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTokenizeSingleQuotes() throws Exception
    {
        assertEquals(List.of("commit", "-m", "a b"), GitTool.tokenize("commit -m 'a b'")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test(expected = CommandRejectedException.class)
    public void testTokenizeRejectsUnbalancedQuote() throws Exception
    {
        GitTool.tokenize("commit -m \"unterminated"); //$NON-NLS-1$
    }

    // ---- parseCommand: happy paths ----

    @Test
    public void testParseStripsLeadingGitAndBuildsArgv() throws Exception
    {
        assertEquals(List.of("git", "status"), GitTool.parseCommand("git status")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("git", "status"), GitTool.parseCommand("status")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testParseAcceptsWhitelistedSubcommandsWithArgs() throws Exception
    {
        assertEquals(List.of("git", "push", "origin", "main"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            GitTool.parseCommand("push origin main")); //$NON-NLS-1$
        assertEquals(List.of("git", "commit", "-m", "fix bug"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            GitTool.parseCommand("commit -m \"fix bug\"")); //$NON-NLS-1$
    }

    // ---- parseCommand: rejections ----

    @Test
    public void testParseRejectsEmpty()
    {
        assertRejected(""); //$NON-NLS-1$
        assertRejected("git"); //$NON-NLS-1$
        assertRejected("   "); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsNonWhitelistedSubcommand()
    {
        // config = arbitrary exec (core.sshCommand / aliases); clean/reset = data loss; init/clone out of
        // scope; rebase omitted because its --exec/-x runs a command per step.
        assertRejected("config core.sshCommand=evil"); //$NON-NLS-1$
        assertRejected("clean -fdx"); //$NON-NLS-1$
        assertRejected("reset --hard HEAD~5"); //$NON-NLS-1$
        assertRejected("clone https://evil/x.git"); //$NON-NLS-1$
        assertRejected("rebase -x /bin/sh"); //$NON-NLS-1$
        assertRejected("gc"); //$NON-NLS-1$
    }

    @Test
    public void testParseAllowsShortReusedFlagsAfterSubcommand() throws Exception
    {
        // -c / -C are legitimate SUBcommand flags (commit --reuse-message / branch --force-copy); only
        // their GLOBAL form (before the subcommand) is dangerous, and that is caught separately.
        assertEquals(List.of("git", "commit", "-c", "HEAD"), GitTool.parseCommand("commit -c HEAD")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals(List.of("git", "branch", "-C", "old", "new"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            GitTool.parseCommand("branch -C old new")); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsLeadingGlobalOption()
    {
        // A global option before the subcommand (git -c ... push) is an injection vector.
        assertRejected("-c core.sshCommand=evil push"); //$NON-NLS-1$
        assertRejected("-C /other/repo status"); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsBlockedExecFlagsAnywhere()
    {
        // Long git-level flags that could exec a program or redirect the repo - blocked after the
        // subcommand too (they are never a legitimate whitelisted-subcommand flag).
        assertRejected("push --receive-pack=/bin/sh origin main"); //$NON-NLS-1$
        assertRejected("fetch --upload-pack=/bin/sh origin"); //$NON-NLS-1$
        assertRejected("merge --exec /bin/sh"); //$NON-NLS-1$
        assertRejected("status --git-dir=/other/.git"); //$NON-NLS-1$
        assertRejected("status --work-tree=/other"); //$NON-NLS-1$
        assertRejected("log --config=core.pager=evil"); //$NON-NLS-1$
        assertRejected("log --config-env=CORE_PAGER=x"); //$NON-NLS-1$
        // --help spawns the man viewer; --output writes an arbitrary file; --ext-diff runs an external driver
        assertRejected("status --help"); //$NON-NLS-1$
        assertRejected("diff --output=/etc/passwd"); //$NON-NLS-1$
        assertRejected("diff --ext-diff"); //$NON-NLS-1$
        // --no-index makes diff read arbitrary files outside the repo (information disclosure)
        assertRejected("diff --no-index /etc/passwd /dev/null"); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsAbbreviatedBlockedFlag()
    {
        // Git resolves any unambiguous prefix of a long option, so an abbreviation of a blocked flag must
        // be rejected too (exact-match alone would be bypassable).
        assertRejected("push --upload-pa origin main"); //$NON-NLS-1$
        assertRejected("fetch --upl origin"); //$NON-NLS-1$
        assertRejected("diff --out=/etc/passwd"); //$NON-NLS-1$
    }

    @Test
    public void testParseScansDeniedFlagsEvenAfterDoubleDash()
    {
        // git may consume a standalone "--" as the value of a preceding option, so a later denied flag is
        // still parsed as an option. We fail closed: scan every token, including after a "--".
        assertRejected("fetch --server-option -- --upload-pack"); //$NON-NLS-1$
        assertRejected("push --push-option -- --receive-pack"); //$NON-NLS-1$
    }

    @Test
    public void testParseAllowsOrdinaryOperandAfterDoubleDash() throws Exception
    {
        // An operand that is not a denied flag is fine (this is the common `-- <pathspec>` use).
        assertEquals(List.of("git", "checkout", "main", "--", "src/File.bsl"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            GitTool.parseCommand("checkout main -- src/File.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testParseRejectsTransportHelperUrl()
    {
        // ext::/fd:: transport helpers run an arbitrary command; 'remote add' would even persist them.
        assertRejected("fetch ext::sh -c id"); //$NON-NLS-1$
        assertRejected("remote add evil ext::sh -c id"); //$NON-NLS-1$
        assertRejected("pull fd::7,8"); //$NON-NLS-1$
        // an unknown scheme via '//' also selects a remote helper (git-remote-<scheme>)
        assertRejected("remote add evil ext://placeholder"); //$NON-NLS-1$
        assertRejected("fetch custom-helper://example.com/r.git"); //$NON-NLS-1$
        // git dispatches digit-leading and case-preserved schemes as helpers too (git-remote-9foo / -HTTPS)
        assertRejected("remote add evil 9foo::payload"); //$NON-NLS-1$
        assertRejected("fetch 9foo://example.com/r.git"); //$NON-NLS-1$
        assertRejected("remote add evil HTTPS://example.com/r.git"); //$NON-NLS-1$
        // a normal https:// / ssh remote is accepted
        assertEquals(List.of("git", "remote", "add", "o", "https://example.com/r.git"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            parseNoThrow("remote add o https://example.com/r.git")); //$NON-NLS-1$
        assertEquals(List.of("git", "fetch", "git@github.com:o/r.git"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            parseNoThrow("fetch git@github.com:o/r.git")); //$NON-NLS-1$
    }

    private static List<String> parseNoThrow(String command)
    {
        try
        {
            return GitTool.parseCommand(command);
        }
        catch (CommandRejectedException e)
        {
            throw new AssertionError("unexpected rejection of '" + command + "': " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testParseRejectsCredentialBearingUrl()
    {
        // A URL with embedded user:password would be persisted and logged.
        assertRejected("remote add origin https://user:token@example.com/repo.git"); //$NON-NLS-1$
        assertRejected("push https://u:p@example.com/r.git main"); //$NON-NLS-1$
    }

    private static void assertRejected(String command)
    {
        try
        {
            List<String> argv = GitTool.parseCommand(command);
            fail("expected rejection of '" + command + "' but got argv " + argv); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (CommandRejectedException expected)
        {
            assertNotNull(expected.getMessage());
            assertFalse(expected.getMessage().isBlank());
        }
    }
}
