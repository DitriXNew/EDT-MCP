/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
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
        // NOT 'blame -S': there it is --ignore-revs-file, i.e. a file operand (see
        // testMoreFileReadingOptionsAreRejected). The pickaxe spellings above stay accepted.
        // 'commit -s' is --signoff, not signing.
        assertAccepted("commit -s -m msg"); //$NON-NLS-1$
        // A URL inside ordinary text (a commit message) is not a remote and must not be refused.
        assertAccepted("commit -m \"see https://user@example.com for details\""); //$NON-NLS-1$
        // A remote URL with a QUERY is refused outright now (a token rides there just as well as in
        // the userinfo, and remote add/set-url would persist it) - see
        // testCredentialsInAUrlQueryAreRedacted.
        assertRejected("push https://example.com/r.git?ref=user@host"); //$NON-NLS-1$
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

    @Test
    public void testRedactionCoversAUnicodeSpaceInsideUserinfo()
    {
        // The scan must stop only on ASCII whitespace: a Unicode space inside the userinfo is part of
        // it, so stopping there would leave the secret in place.
        String output = "fatal: could not read from https://secret name@example.com/r.git";

        String redacted = GitTool.redactCredentialUrls(output);

        assertFalse("the secret must not survive: " + redacted, redacted.contains("secret")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the host must stay readable: " + redacted, //$NON-NLS-1$
            redacted.contains("https://***@example.com/r.git")); //$NON-NLS-1$
    }

    @Test
    public void testFileReadingOptionsAreRejectedOnAllowlistedSubcommands()
    {
        // The diff-operand guard cannot see these: the path is an option VALUE, not an operand.
        assertRejected("blame --contents /etc/passwd -- tracked.txt"); //$NON-NLS-1$
        assertRejected("blame --contents=/etc/passwd -- tracked.txt"); //$NON-NLS-1$
        assertRejected("commit --file /etc/passwd"); //$NON-NLS-1$
        assertRejected("commit -F /etc/passwd"); //$NON-NLS-1$
        assertRejected("tag -F /etc/passwd v1.0"); //$NON-NLS-1$
        assertRejected("add --pathspec-from-file /etc/passwd"); //$NON-NLS-1$

        // '-F' means --fixed-strings for log, which must keep working.
        assertAccepted("log -F --grep=needle"); //$NON-NLS-1$
        assertAccepted("blame -- tracked.txt"); //$NON-NLS-1$
    }

    @Test
    public void testStrategyOptionIsRejectedBecauseItNamesAProgram()
    {
        // git runs 'git-<strategy>' from PATH, so a strategy name is an arbitrary program.
        assertRejected("merge --no-ff -s pwn other"); //$NON-NLS-1$
        assertRejected("merge --strategy=pwn other"); //$NON-NLS-1$
        assertRejected("pull -spwn origin main"); //$NON-NLS-1$
        assertRejected("merge -nspwn other"); // a CLUSTER: git reads '-n -s pwn' //$NON-NLS-1$

        // 'cherry-pick -s' / 'revert -s' are --signoff, not --strategy: they must keep working.
        assertAccepted("cherry-pick -s abc123"); //$NON-NLS-1$
        assertAccepted("revert -s abc123"); //$NON-NLS-1$

        // '-s' means --no-patch for log/show, and -X only configures the built-in strategy.
        assertAccepted("log -s"); //$NON-NLS-1$
        assertAccepted("show -s HEAD"); //$NON-NLS-1$
        assertAccepted("merge -X ours other"); //$NON-NLS-1$
        // An ATTACHED strategy-option value carries an 's' but no flag after it.
        assertAccepted("merge -Xours other"); //$NON-NLS-1$
        assertAccepted("pull -Xtheirs origin main"); //$NON-NLS-1$
        assertAccepted("merge -mfixes other"); //$NON-NLS-1$
    }

    @Test
    public void testRemoteUrlGuardsOnlyApplyWhereATokenCanBecomeARemote()
    {
        // A commit message is TEXT: git never resolves it as a remote, so an app URL or an
        // "ext::"-looking prefix inside one must not be refused.
        assertAccepted("commit -m \"see vscode://file/c:/x\""); //$NON-NLS-1$
        assertAccepted("commit -m \"ext::note about helpers\""); //$NON-NLS-1$
        assertAccepted("tag -a v1.0 -m \"see myapp://release\""); //$NON-NLS-1$

        // Where a token CAN select or persist a remote, the guards stay.
        assertRejected("remote add origin ext::sh -c payload"); //$NON-NLS-1$
        assertRejected("push vscode://file/c:/x main"); //$NON-NLS-1$
        assertRejected("fetch fd::7"); //$NON-NLS-1$
    }

    @Test
    public void testClusteredFileOptionsAreRejected()
    {
        // git accepts short-option CLUSTERS, so the file option can hide behind another flag.
        assertRejected("commit -qF/etc/passwd"); //$NON-NLS-1$
        assertRejected("tag -aF/etc/passwd v1"); //$NON-NLS-1$
        assertRejected("blame -wS/etc/passwd -- tracked.bsl"); //$NON-NLS-1$

        // The SAME letter differs per subcommand: '-c' takes a commit for commit but is --cached for
        // ls-files/blame, so a cluster must not stop there.
        assertRejected("ls-files -cXC:/secret"); //$NON-NLS-1$
        assertRejected("blame -cS/etc/passwd -- tracked.bsl"); //$NON-NLS-1$
        assertAccepted("commit -cHEAD~1 --amend"); //$NON-NLS-1$

        // A value-taking letter ENDS the cluster: what follows is its value, not another flag.
        assertAccepted("commit -m \"Fixed\""); //$NON-NLS-1$
        assertAccepted("commit -mFixed"); //$NON-NLS-1$
        assertAccepted("tag -a v1.0 -mFine"); //$NON-NLS-1$
        assertAccepted("commit -qam \"msg\""); //$NON-NLS-1$
    }

    @Test
    public void testCredentialsInAUrlQueryAreRedacted()
    {
        // A stored remote can carry its secret in the QUERY, where there is no userinfo at all.
        String output = "origin\thttps://example.com/repo.git?access_token=ghp_secret (fetch)"; //$NON-NLS-1$

        String redacted = GitTool.redactCredentialUrls(output);

        assertFalse("the token must not survive: " + redacted, //$NON-NLS-1$
            redacted.contains("ghp_secret")); //$NON-NLS-1$
        assertTrue("the remote must stay readable: " + redacted, //$NON-NLS-1$
            redacted.contains("https://example.com/repo.git?***")); //$NON-NLS-1$
        assertTrue("what follows the URL must survive: " + redacted, //$NON-NLS-1$
            redacted.contains("(fetch)")); //$NON-NLS-1$

        // The same URL must not even be ACCEPTED where it would be persisted.
        assertRejected("remote add origin https://example.com/r.git?access_token=ghp_secret"); //$NON-NLS-1$
        assertRejected("push https://example.com/r.git?token=x main"); //$NON-NLS-1$
        // A '?' outside a URL (a commit message, a pathspec) is untouched.
        assertAccepted("commit -m \"why? because\""); //$NON-NLS-1$

        // Two URLs in one line: the first must not swallow the second's query - or its credential.
        String twoUrls = GitTool.redactCredentialUrls("https://a@h/x,https://secret@h/y?k=v"); //$NON-NLS-1$
        assertFalse("neither credential may survive: " + twoUrls, //$NON-NLS-1$
            twoUrls.contains("secret") || twoUrls.contains("k=v")); //$NON-NLS-1$ //$NON-NLS-2$

        // A '://' inside a query VALUE is not the next URL: the redaction must cover the whole query.
        String schemeInValue = GitTool.redactCredentialUrls("https://h/x?token=secret://tail"); //$NON-NLS-1$
        assertFalse("the secret must not survive: " + schemeInValue, //$NON-NLS-1$
            schemeInValue.contains("secret")); //$NON-NLS-1$

        // Userinfo AND query together.
        String both = GitTool.redactCredentialUrls("https://tok@host/r.git?k=v"); //$NON-NLS-1$
        assertFalse("neither may survive: " + both, both.contains("tok") || both.contains("k=v")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testMoreFileReadingOptionsAreRejected()
    {
        assertRejected("blame --ignore-revs-file /etc/passwd -- tracked.txt"); //$NON-NLS-1$
        assertRejected("blame -S /etc/passwd -- tracked.txt"); //$NON-NLS-1$
        assertRejected("ls-files -X /etc/passwd"); //$NON-NLS-1$
        assertRejected("ls-files --exclude-from=/etc/passwd"); //$NON-NLS-1$
        // The ATTACHED spellings too - including a bare name, which can be a symlink out of the repo.
        assertRejected("blame -S/etc/passwd -- tracked.txt"); //$NON-NLS-1$
        assertRejected("blame -Soutside -- tracked.txt"); //$NON-NLS-1$
        assertRejected("ls-files -X/etc/passwd"); //$NON-NLS-1$
        assertRejected("commit -F/etc/passwd"); //$NON-NLS-1$

        // The same letters mean something else elsewhere and must keep working.
        assertAccepted("log -S needle"); //$NON-NLS-1$
        assertAccepted("log -Sneedle"); //$NON-NLS-1$
        assertAccepted("merge -X ours other"); //$NON-NLS-1$
    }

    @Test
    public void testAdjacentUrlsAreRedactedIndependently()
    {
        // The scan walks to the LAST '@' of ONE authority: the '/' that opens the next URL ends it,
        // so two comma-separated remotes must not be merged into one redaction.
        assertEquals("https://***@one,https://***@two/x", //$NON-NLS-1$
            GitTool.redactCredentialUrls("https://a@one,https://b@two/x")); //$NON-NLS-1$

        // A URL WITHOUT userinfo followed by one that has it must leave the first intact.
        assertEquals("https://plain/host https://***@secret/r", //$NON-NLS-1$
            GitTool.redactCredentialUrls("https://plain/host https://tok@secret/r")); //$NON-NLS-1$
    }

    @Test
    public void testRedactionCoversAnEmailStyleUserName()
    {
        // git accepts an email-style user name, so the REAL userinfo separator is the LAST '@';
        // stopping at the first would leave the token in the output.
        String output = "origin\thttps://user@example.com:ghp_secrettoken@host/acme/repo.git (fetch)"; //$NON-NLS-1$

        String redacted = GitTool.redactCredentialUrls(output);

        assertFalse("the token must not survive: " + redacted, //$NON-NLS-1$
            redacted.contains("ghp_secrettoken")); //$NON-NLS-1$
        assertFalse("the user name must not survive either: " + redacted, //$NON-NLS-1$
            redacted.contains("user@example.com")); //$NON-NLS-1$
        assertTrue("the host must stay readable: " + redacted, //$NON-NLS-1$
            redacted.contains("https://***@host/acme/repo.git")); //$NON-NLS-1$
    }

    @Test
    public void testConsentIsAskedForEveryWriteAndForNoRead()
    {
        // Reading never asks, so an agent can inspect the repository freely.
        for (String readOnly : new String[]{"status", "diff", "log", "show", "blame", "ls-files", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "rev-parse", "describe"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNull(readOnly + " must not ask for consent", GitTool.destructiveForm(argv(readOnly))); //$NON-NLS-1$
        }

        // Everything that WRITES asks - deliberately NOT flag-driven: bundles (-fq), attached values
        // (-bfeature) and git's accepted abbreviations (--forc) make an option-level rule both leaky
        // (push +main:main, merge --abort) and over-eager.
        for (String subcommand : GitTool.ALLOWED_SUBCOMMANDS)
        {
            if (GitTool.destructiveForm(argv(subcommand)) == null)
            {
                assertTrue(subcommand + " is not read-only, so it must ask for consent", //$NON-NLS-1$
                    List.of("status", "diff", "log", "show", "blame", "ls-files", "rev-parse", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                        "describe").contains(subcommand)); //$NON-NLS-1$
            }
        }

        // The forms an option-level rule kept missing are covered by construction.
        assertNotNull(GitTool.destructiveForm(argv("push", "origin", "+main:main"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(GitTool.destructiveForm(argv("checkout", "--forc", "main"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(GitTool.destructiveForm(argv("merge", "--abort"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(GitTool.destructiveForm(argv("stash", "pop"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConsentGateAndAnnotationListsAgreeOnGit()
    {
        assertTrue("the git tool must be gated", //$NON-NLS-1$
            DestructiveConsentGate.GATED_TOOLS.contains(GitTool.NAME));
    }

    private static List<String> argv(String... tokens)
    {
        List<String> argv = new ArrayList<>();
        argv.add("git"); //$NON-NLS-1$
        argv.addAll(Arrays.asList(tokens));
        return argv;
    }

    @Test
    public void testRedactionIsLinearOnHostileOutput()
    {
        // The credential pattern must not backtrack: a long scheme-like run that never becomes a URL
        // would otherwise rescan every suffix and stall the CPU on git output that merely contains '@'.
        StringBuilder hostile = new StringBuilder(100_000);
        for (int i = 0; i < 100_000; i++)
        {
            hostile.append('a');
        }
        hostile.append(":@"); //$NON-NLS-1$

        long startedAt = System.nanoTime();
        String result = GitTool.redactCredentialUrls(hostile.toString());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals("nothing to redact here", hostile.toString(), result); //$NON-NLS-1$
        assertTrue("redaction must stay linear, took " + elapsedMillis + " ms", elapsedMillis < 2000); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRedactionStaysLinearOnManyUrlLikeFragments()
    {
        // The URL-boundary scan must be computed ONCE per URL: recomputing it inside every scanner
        // would rescan the rest of the output for each fragment, which is quadratic.
        StringBuilder hostile = new StringBuilder(120_000);
        for (int i = 0; i < 12_000; i++)
        {
            hostile.append("?a://x=a://x="); //$NON-NLS-1$
        }

        long startedAt = System.nanoTime();
        GitTool.redactCredentialUrls(hostile.toString());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue("redaction must stay linear, took " + elapsedMillis + " ms", elapsedMillis < 2000); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCredentialUrlsAreRedactedFromGitOutput()
    {
        // A repository can ALREADY hold a credential-bearing remote; 'remote -v' prints it verbatim,
        // which would hand an agent a token that merely sat in the repo config.
        String output = "origin\thttps://ghp_secrettoken@example.com/acme/repo.git (fetch)"; //$NON-NLS-1$

        String redacted = GitTool.redactCredentialUrls(output);

        assertFalse("the token must not survive: " + redacted, //$NON-NLS-1$
            redacted.contains("ghp_secrettoken")); //$NON-NLS-1$
        assertTrue("the remote must stay readable: " + redacted, //$NON-NLS-1$
            redacted.contains("https://***@example.com/acme/repo.git")); //$NON-NLS-1$
        assertTrue("the remote name must stay readable: " + redacted, //$NON-NLS-1$
            redacted.contains("origin")); //$NON-NLS-1$
    }

    @Test
    public void testRedactionLeavesOutputWithoutCredentialsUntouched()
    {
        String clean = "On branch master\nnothing to commit, working tree clean"; //$NON-NLS-1$

        assertSame("output without an '@' must not even be rebuilt", //$NON-NLS-1$
            clean, GitTool.redactCredentialUrls(clean));
        String scpStyle = "origin\tgit@github.com:acme/repo.git (push)"; //$NON-NLS-1$
        assertEquals("an scp-style remote carries no userinfo URL to redact", //$NON-NLS-1$
            scpStyle, GitTool.redactCredentialUrls(scpStyle));
    }

    @Test
    public void testCredentialHelpersAreKeptNonInteractive()
    {
        // core.askPass / GIT_TERMINAL_PROMPT do not cover a GUI credential HELPER (Git Credential
        // Manager), which pops its own window when nothing is cached.
        assertTrue("a GUI credential helper must be told not to prompt", //$NON-NLS-1$
            GitTool.nonInteractiveConfigForTest().contains("credential.interactive=false")); //$NON-NLS-1$
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
