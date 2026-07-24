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
        // A bare token in the userinfo is just as sensitive as user:password.
        assertRejected("remote add origin https://ghp_token123@example.com/repo.git"); //$NON-NLS-1$
        // ...including when the URL rides on an option rather than starting the token.
        assertRejected("push --repo=https://ghp_token123@example.com/repo.git --all"); //$NON-NLS-1$
        // A URL with embedded user:password would be persisted and logged.
        assertRejected("remote add origin https://user:token@example.com/repo.git"); //$NON-NLS-1$
        assertRejected("push https://u:p@example.com/r.git main"); //$NON-NLS-1$
    }

    @Test
    public void testSigningAndUrlGuardsDoNotOverReject()
    {
        // -S means GPG-sign only on a commit-producing subcommand; on log/diff it is the pickaxe
        // search and on blame an unrelated option - those must keep working.
        assertAccepted("log -Spassword"); //$NON-NLS-1$
        assertAccepted("log -S \"needle\""); //$NON-NLS-1$
        assertAccepted("diff -Sneedle"); //$NON-NLS-1$
        assertAccepted("blame -S file.txt"); //$NON-NLS-1$
        // 'commit -s' is --signoff, not signing.
        assertAccepted("commit -s -m msg"); //$NON-NLS-1$
        // A URL inside ordinary text (a commit message) is not a remote and must not be refused.
        assertAccepted("commit -m \"see https://user@example.com for details\""); //$NON-NLS-1$
        // A '@' in a query string is not userinfo.
        assertAccepted("push https://example.com/r.git?ref=user@host"); //$NON-NLS-1$
        // A search string or a message is never a remote, so a URL inside one is not refused.
        assertAccepted("log -S https://user@example.com"); //$NON-NLS-1$
        assertAccepted("log --grep=https://user@example.com"); //$NON-NLS-1$
    }



    @Test
    public void testSigningIsNeutralizedByConfigNotByTheParser()
    {
        // Signing spellings are NOT parse errors any more: enumerating them would mean reimplementing
        // git's per-subcommand option arity, and every attempt at that produced false rejections of
        // legitimate values ('commit -m -S', 'log -S<text>', 'commit -mSubject'). They are accepted
        // here and neutralized where it is airtight - in the executed command's configuration.
        assertAccepted("commit -S -m msg"); //$NON-NLS-1$
        assertAccepted("commit --gpg-sign -m msg"); //$NON-NLS-1$
        assertAccepted("tag -s v1.0"); //$NON-NLS-1$
        assertAccepted("push --signed origin main"); //$NON-NLS-1$
        assertAccepted("commit -m -S"); // a message that looks like a flag //$NON-NLS-1$
        assertAccepted("tag -l --format -s"); //$NON-NLS-1$

        List<String> hardened = GitTool.nonInteractiveConfigForTest();
        assertTrue("the signing config must be off: " + hardened, //$NON-NLS-1$
            hardened.contains("commit.gpgSign=false") && hardened.contains("tag.gpgSign=false") //$NON-NLS-1$ //$NON-NLS-2$
                && hardened.contains("push.gpgSign=false") //$NON-NLS-1$
                && hardened.contains("tag.forceSignAnnotated=false")); //$NON-NLS-1$
        assertTrue("no usable signing program may remain: " + hardened, //$NON-NLS-1$
            hardened.contains("gpg.program=/nonexistent/edt-mcp-signing-disabled") //$NON-NLS-1$
                && hardened.contains("gpg.ssh.program=/nonexistent/edt-mcp-signing-disabled")); //$NON-NLS-1$
        assertTrue("ssh key discovery must not become interactive: " + hardened, //$NON-NLS-1$
            hardened.contains("gpg.ssh.defaultKeyCommand=")); //$NON-NLS-1$
    }

    @Test
    public void testOptionValuesAndOperandsAreNotScannedAsFlags()
    {
        // A value that merely looks like a flag is an operand, not an option.
        assertAccepted("commit -m \"-Subject line\""); //$NON-NLS-1$
        assertAccepted("tag -l -- -urgent"); //$NON-NLS-1$
    }

    @Test
    public void testCredentialUrlIsCaughtDespiteSurroundingWhitespace()
    {
        // git would persist the trimmed value, so leading whitespace must not hide the userinfo.
        assertRejected("remote add origin \" https://ghp_token@example.com/r.git\""); //$NON-NLS-1$
    }

    @Test
    public void testUrlInAMessageValueIsNotRejected()
    {
        // A commit message that mentions a URL is not a remote.
        assertAccepted("commit -m \"https://user@example.com is the contact\""); //$NON-NLS-1$
        assertAccepted("commit --message=\"ping https://user@example.com\""); //$NON-NLS-1$
    }

    private static void assertAccepted(String command)
    {
        try
        {
            assertNotNull(GitTool.parseCommand(command));
        }
        catch (CommandRejectedException unexpected)
        {
            fail("'" + command + "' must be accepted but was rejected: " + unexpected.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
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
