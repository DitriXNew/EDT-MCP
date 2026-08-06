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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    public void testParseRejectsWhitespaceInsideACredentialUrl()
    {
        // A plain SPACE is 0x20, so it is NOT a control character: it walked straight through the
        // input guard, git stored the remote verbatim, and the output redaction - which stops at the
        // very same character - then printed the secret. Refused on input now. Every space under
        // test is written as a unicode escape, so it is VISIBLE in the source rather than being an
        // invisible byte in the middle of a URL.
        assertRejected("remote add origin \"https://user:s3cr3t\u0020ok@example.com/r.git\""); //$NON-NLS-1$
        assertRejected("remote set-url origin \"https://user:s3cr3t\u0020ok@example.com/r.git\""); //$NON-NLS-1$
        assertRejected("push \"https://user:s3cr3t\u0020ok@example.com/r.git\" main"); //$NON-NLS-1$
        assertRejected("fetch \"https://user:s3cr3t\u0020ok@example.com/r.git\""); //$NON-NLS-1$
        assertRejected("pull \"https://user:s3cr3t\u0020ok@example.com/r.git\" main"); //$NON-NLS-1$
        // The URL rides on an option's VALUE just as well as on a bare token.
        assertRejected("push --repo=\"https://user:s3cr3t\u0020ok@example.com/r.git\" --all"); //$NON-NLS-1$
        // A credential needs no ':' - a bare PAT in the userinfo is exactly how a token is passed.
        assertRejected("remote add origin \"https://ghp_s3cr3t\u0020ok@example.com/r.git\""); //$NON-NLS-1$

        // ...and the guard reaches no further than that. A normal remote,
        assertAccepted("remote add o https://example.com/r.git"); //$NON-NLS-1$
        // a SPACE that sits in the PATH (the authority ends at the first '/', so nothing can hide
        // a credential there),
        assertAccepted("remote add o \"https://example.com/Program\u0020Files/repo.git\""); //$NON-NLS-1$
        // and a URL-looking string inside a commit MESSAGE - git never resolves one as a remote -
        // all stay accepted.
        assertAccepted("commit -m \"see https://a\u0020b@c for details\""); //$NON-NLS-1$

        // Only a SPACE is accepted there, though. The other ASCII whitespace characters are C0
        // controls, which the older whole-URL guard refuses wherever they sit - quoting keeps the
        // tab inside a single token, so it really does reach that guard. Pinned next to the accepted
        // case because the refusal MESSAGE promises exactly this asymmetry: a message that promised
        // more would send this caller into a retry loop that cannot succeed.
        assertRejected("remote add o \"https://example.com/a\tb.git\""); //$NON-NLS-1$

        // The control-character rejections this widening grew out of still hold; they have to be
        // QUOTED to reach git as one token (see testCredentialUrlNormalizationIsConsistent).
        assertRejected("remote add origin \"https://user:ghp_secret\n@host/r.git\""); //$NON-NLS-1$
        assertRejected("push \"https://user:ghp_secret\t@host/r.git\""); //$NON-NLS-1$
    }

    @Test
    public void testTheWhitespaceRefusalNeverEchoesTheUrl()
    {
        // A refusal travels to the client, into the model's context and into the request history, so
        // it may name the PROBLEM but never the value that caused it.
        String message = refusalFor("remote add origin \"https://user:s3cr3t\u0020ok@example.com/r.git\""); //$NON-NLS-1$

        assertFalse("the credential must not be echoed back: " + message, message.contains("s3cr3t")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor the rest of the URL: " + message, message.contains("example.com")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal must say WHAT is wrong: " + message, //$NON-NLS-1$
            message.toLowerCase(java.util.Locale.ROOT).contains("whitespace or control character")); //$NON-NLS-1$
        // ...and it must scope each half of the rule to what is actually enforced. A SPACE is judged
        // in the AUTHORITY only (a space in the PATH stays accepted, see above), while a tab/newline
        // is a control character and is refused ANYWHERE in the URL. Naming the authority scope for
        // both would tell a caller with a tab in the path to retry a command that cannot be accepted.
        assertTrue("the refusal must scope the SPACE rule to the authority: " + message, //$NON-NLS-1$
            message.contains("no space before the first '/'")); //$NON-NLS-1$
        assertTrue("...and say that a control character is refused in the whole URL: " + message, //$NON-NLS-1$
            message.contains("anywhere in the URL")); //$NON-NLS-1$
    }

    private static String refusalFor(String command)
    {
        try
        {
            List<String> argv = GitTool.parseCommand(command);
            fail("expected a rejection but got argv " + argv); //$NON-NLS-1$
            return null; // unreachable: fail() always throws
        }
        catch (CommandRejectedException expected)
        {
            return expected.getMessage();
        }
    }

    /**
     * The characters an authority may not carry: ASCII whitespace and C0/DEL. They are refused for
     * two DIFFERENT reasons - the ASCII whitespace ones (space, tab, LF, CR, VT, FF) really do end
     * every scan the redaction makes FOR a credential, so a credential behind one cannot be masked
     * at all (the per-URL bound it also computes scans on past whitespace, but it only says where a
     * URL ends, it never locates a secret); DEL and the non-whitespace C0 controls end none of those
     * scans and ARE masked today, and are refused because they can never occur in a legitimate
     * authority and must not travel verbatim into the response.
     * <p>
     * Built from code points instead of being written into a literal - a raw VT/FF/DEL in the source
     * would be invisible and encoding-fragile, and a newline cannot be spelled as a unicode escape
     * inside a string literal at all (the lexer expands it before the literal is parsed).
     */
    private static final char[] AUTHORITY_HIDING_CHARACTERS = {' ', '\t', '\n', '\r', 0x0B, '\f', 0x7F};

    @Test
    public void testAuthorityOfCoversExactlyTheAuthority()
    {
        assertEquals("user:s3cr3t@example.com", //$NON-NLS-1$
            GitTool.authorityOf("https://user:s3cr3t@example.com/r.git")); //$NON-NLS-1$
        // It deliberately does NOT stop at whitespace: stopping there is precisely the blindness that
        // hid the credential from every check.
        assertEquals("user:s3cr3t\u0020ok@example.com", //$NON-NLS-1$
            GitTool.authorityOf("https://user:s3cr3t\u0020ok@example.com/r.git")); //$NON-NLS-1$
        // '?' and '#' end the authority as well as '/', and so does the end of the string.
        assertEquals("example.com", GitTool.authorityOf("https://example.com?access_token=x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("example.com", GitTool.authorityOf("https://example.com#token=x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("example.com", GitTool.authorityOf("https://example.com")); //$NON-NLS-1$ //$NON-NLS-2$
        // A local 'file://' URL has an EMPTY authority - every space in it belongs to the path.
        assertEquals("", GitTool.authorityOf("file:///C:/Program\u0020Files/repo")); //$NON-NLS-1$ //$NON-NLS-2$
        // Without a '://' there is no authority to judge (scp-style remotes, ordinary text).
        assertNull(GitTool.authorityOf("git@github.com:acme/repo.git")); //$NON-NLS-1$
        assertNull(GitTool.authorityOf("origin")); //$NON-NLS-1$
    }

    @Test
    public void testAuthorityWhitespaceOrControlIsAsciiOnlyAndAuthorityScoped()
    {
        // The whole class has to be seen - the whitespace half because it blinds the redaction, the
        // control half because such a character cannot be legitimate here (see the field's javadoc).
        for (char hidden : AUTHORITY_HIDING_CHARACTERS)
        {
            String url = "https://user:s3cr3t" + hidden + "ok@example.com/r.git"; //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("U+" + Integer.toHexString(hidden) + " must be seen inside the authority", //$NON-NLS-1$ //$NON-NLS-2$
                GitTool.authorityHasWhitespaceOrControl(url));
        }

        // A readable authority is left alone, credential or not.
        assertFalse(GitTool.authorityHasWhitespaceOrControl("https://user:s3cr3t@example.com/r.git")); //$NON-NLS-1$
        // Whitespace in the PATH is not in the authority.
        assertFalse(
            GitTool.authorityHasWhitespaceOrControl("https://example.com/Program\u0020Files/r.git")); //$NON-NLS-1$
        // 'file:///C:/Program Files/repo' is the everyday spelling of a local path and its authority
        // is empty, so this check must stay silent about it. (The command is still refused - by the
        // pre-existing 'file://' scheme rule, see testFileRemotesAreRefused - but not by this one,
        // which would be a wrong and unfixable diagnosis.)
        assertFalse(GitTool.authorityHasWhitespaceOrControl("file:///C:/Program\u0020Files/repo")); //$NON-NLS-1$
        // The QUERY is out of scope by decision: a secret there is redacted, not refused.
        assertFalse(
            GitTool.authorityHasWhitespaceOrControl("https://example.com?access_token=sec\u0020ret")); //$NON-NLS-1$
        // ASCII-ONLY on purpose: a U+2003 inside the userinfo must keep being REDACTED (see
        // testRedactionCoversAUnicodeSpaceInsideUserinfo), never refused.
        assertFalse(
            GitTool.authorityHasWhitespaceOrControl("https://secret\u2003name@example.com/r.git")); //$NON-NLS-1$
        // Nothing without a '<scheme>://' has an authority at all.
        assertFalse(GitTool.authorityHasWhitespaceOrControl("git@github.com:acme/repo.git")); //$NON-NLS-1$
        assertFalse(GitTool.authorityHasWhitespaceOrControl("fix the a b@c typo")); //$NON-NLS-1$
    }

    @Test
    public void testUnmaskableCredentialUrlNeedsBothAHiddenAuthorityAndACredential()
    {
        for (char hidden : AUTHORITY_HIDING_CHARACTERS)
        {
            String url = "https://user:s3cr3t" + hidden + "ok@example.com/r.git"; //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("U+" + Integer.toHexString(hidden) + " next to a credential must be refused", //$NON-NLS-1$ //$NON-NLS-2$
                GitTool.unmaskableCredentialUrl(url));
        }

        // The authority runs to the first '/', wider than RFC 3986 - which would end it at the '?'
        // or the '#'. Not because git reads such a URL as a credential: it ends the host portion at
        // the first of '/', '?' and '#' too, so it sends no credential at all here and takes
        // 'user:s3cr3t' for the HOST. The reason is the REDACTION - its userinfo scan bails at that
        // same character and finds no '@', so an RFC-shaped check would let the URL through and the
        // redaction would then mask what it takes for a query and print the first half of the secret
        // verbatim ('https://user:s3cr3t?*** b@example.com/r.git').
        assertTrue("a '?' inside the userinfo must not hide the credential from this check", //$NON-NLS-1$
            GitTool.unmaskableCredentialUrl("https://user:s3cr3t?a\u0020b@example.com/r.git")); //$NON-NLS-1$
        assertTrue("...and neither must a '#'", //$NON-NLS-1$
            GitTool.unmaskableCredentialUrl("https://user:s3cr3t#a\u0020b@example.com/r.git")); //$NON-NLS-1$
        // ...while a secret in the QUERY of a credential-free URL stays out of scope by decision: the
        // authority ends at the first '/', so that one is the redaction's business, not the refusal's.
        assertFalse(GitTool.unmaskableCredentialUrl(
            "https://example.com/r.git?access_token=sec\u0020ret")); //$NON-NLS-1$

        // An unreadable authority that carries NO credential has nothing to mask, so nothing to
        // refuse either - refusing it would be an outage for no gain.
        assertFalse(GitTool.unmaskableCredentialUrl("https://exa\u0020mple.com/r.git")); //$NON-NLS-1$
        // The '@' must be in the AUTHORITY: one in the path belongs to the path.
        assertFalse(GitTool.unmaskableCredentialUrl("https://ho\u0020st.example/a@b")); //$NON-NLS-1$
        // ...and so must the WHITESPACE - the other half of the same scoping, and the half no
        // case above can fail on, because none of them pairs an authority '@' with whitespace
        // outside the authority. Here the userinfo scan reaches the '@' long before the space, so
        // the credential is masked exactly as usual. It is an everyday remote too - an Azure
        // DevOps project name may legally contain a space - so scanning the whole URL instead of
        // the authority would refuse remote/push/fetch/pull for that remote permanently.
        assertFalse(GitTool.unmaskableCredentialUrl(
            "https://user:s3cr3t@dev.azure.example/org/My\u0020Project/_git/repo")); //$NON-NLS-1$
        // A credential the redactor CAN mask stays the redactor's job - including one hidden behind a
        // Unicode space, which the ASCII-only predicate must not claim.
        assertFalse(GitTool.unmaskableCredentialUrl("https://ghp_token@example.com/r.git")); //$NON-NLS-1$
        assertFalse(GitTool.unmaskableCredentialUrl("https://secret\u2003name@example.com/r.git")); //$NON-NLS-1$
        assertFalse(GitTool.unmaskableCredentialUrl("git@github.com:acme/repo.git")); //$NON-NLS-1$
    }

    @Test
    public void testSafeRemoteNameKeepsTheRefusalActionable()
    {
        assertEquals("origin", GitTool.safeRemoteName("origin")); //$NON-NLS-1$ //$NON-NLS-2$

        // A remote name written in any script is legal and must SURVIVE, or the refusal names no
        // remote and the operator cannot act on it: the bundle's existing sanitizers strip everything
        // outside [a-zA-Z0-9_-] and would reduce this one to an empty string.
        // 'istoki' - a real, legal remote name with not one ASCII letter in it.
        String cyrillic = "\u0438\u0441\u0442\u043e\u043a\u0438"; //$NON-NLS-1$
        assertEquals("a Cyrillic remote name must survive intact", cyrillic, //$NON-NLS-1$
            GitTool.safeRemoteName(cyrillic));

        // A config subsection name is UNTRUSTED text: a control character in it would travel into the
        // error, the log and the model's context. Built from code points, never a raw byte.
        String hostile = "ori" + (char)0x00 + "gin" + (char)0x1B + (char)0x7F; //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("C0 and DEL must be stripped", "origin", GitTool.safeRemoteName(hostile)); //$NON-NLS-1$ //$NON-NLS-2$

        // ...and it is bounded, so a huge name cannot flood the response.
        String bounded = GitTool.safeRemoteName("x".repeat(200)); //$NON-NLS-1$
        assertTrue("the echoed name must be bounded to 80 characters, was " + bounded.length(), //$NON-NLS-1$
            bounded.length() <= 80);
        assertTrue("the beginning must stay recognisable: " + bounded, //$NON-NLS-1$
            bounded.startsWith("xxxxxxxxxxxxxxxxxxxx")); //$NON-NLS-1$
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
    public void testCredentialUrlNormalizationIsConsistent()
    {
        // A Unicode space is not trimmed by trim() but is by strip(): with two different
        // normalizations the scheme guard saw a URL while the credential guard did not, and the
        // secret was persisted.
        assertRejected("remote add origin  https://ghp_secret@host/r.git"); //$NON-NLS-1$
        // A control character inside the URL ends the whitespace-based scanning before the '@',
        // hiding the credential from every check while git still accepts the URL. It has to be
        // QUOTED to reach git as one token - unquoted, the tokenizer splits on it.
        assertRejected("remote add origin \"https://user:ghp_secret\n@host/r.git\""); //$NON-NLS-1$
        assertRejected("push \"https://user:ghp_secret\t@host/r.git\""); //$NON-NLS-1$
    }

    @Test
    public void testAnEncodingOptionCannotHideTheOutputFromRedaction()
    {
        // git would emit UTF-16 bytes that this tool decodes as UTF-8, so a credential in the output
        // no longer looks like a URL to the redaction.
        assertRejected("log --encoding=UTF-16 -1"); //$NON-NLS-1$
        // Resolved per traversed directory, so it escapes the work tree from a nested one.
        assertRejected("ls-files --others --exclude-per-directory=../../secret.rules"); //$NON-NLS-1$
    }

    @Test
    public void testTheCommandStringItselfIsBounded()
    {
        StringBuilder huge = new StringBuilder("commit -m "); //$NON-NLS-1$
        for (int i = 0; i < GitTool.MAX_COMMAND_CHARS; i++)
        {
            huge.append('a');
        }

        // Everything reflected back (the echoed command, errors, the consent preview) derives from
        // this string, so an unbounded one would flood the response, the log and the dialog.
        assertRejected(huge.toString());
    }

    @Test
    public void testRevParseGitDirIsAllowedButNowhereElse()
    {
        // 'rev-parse --git-dir' PRINTS the resolved path - it redirects nothing, and it is the
        // documented way to ask where the repository is.
        assertAccepted("rev-parse --git-dir"); //$NON-NLS-1$
        // Everything else keeps the redirection blocked, including the value form.
        assertRejected("rev-parse --git-dir=/elsewhere/.git"); //$NON-NLS-1$
        assertRejected("status --git-dir"); //$NON-NLS-1$
        assertRejected("log --git-dir"); //$NON-NLS-1$
    }

    @Test
    public void testOrdinaryValuesThatLookLikePathsAreNotRefused()
    {
        // A leading '/' does not make a value a path: these read nothing, and refusing them broke
        // ordinary searches and messages.
        assertAccepted("log --grep=/api/v1/users"); //$NON-NLS-1$
        assertAccepted("commit -m \"/fix search endpoint\""); //$NON-NLS-1$
        assertAccepted("tag -a v2 -m \"/v2 release notes\""); //$NON-NLS-1$
        assertAccepted("stash push -m \"/wip: quick fix\""); //$NON-NLS-1$
    }

    @Test
    public void testOrderFileIsAFileOptionForLogAndShowToo()
    {
        // '-O<orderfile>' is a diff option, and log/show accept diff options - the containment check
        // has to follow it there as well. Decided against a real work tree, like the diff case.
        java.nio.file.Path root;
        java.nio.file.Path outside;
        try
        {
            root = java.nio.file.Files.createTempDirectory("edt-mcp-git-order-root"); //$NON-NLS-1$
            outside = java.nio.file.Files.createTempFile("edt-mcp-order", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException(e);
        }
        try
        {
            java.nio.file.Path canonicalRoot = root.toRealPath();
            String outsidePath = outside.toRealPath().toString();

            assertNotNull("log must follow -O into the cluster", //$NON-NLS-1$
                GitTool.escapingCandidate("-pO" + outsidePath, "log", canonicalRoot)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull("show must too", //$NON-NLS-1$
                GitTool.escapingCandidate("-O" + outsidePath, "show", canonicalRoot)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("the pickaxe value is still left alone", //$NON-NLS-1$
                GitTool.escapingCandidate("-S" + outsidePath, "log", canonicalRoot)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException(e);
        }
        finally
        {
            try
            {
                java.nio.file.Files.deleteIfExists(outside);
                java.nio.file.Files.deleteIfExists(root);
            }
            catch (java.io.IOException ignored)
            {
                // best-effort cleanup
            }
        }
    }

    @Test
    public void testFileRemotesAreRefused()
    {
        // A 'file://' remote reads - and on a push WRITES - a repository anywhere on disk, and that
        // path lives inside a URI where the containment check cannot see it.
        assertRejected("push file:///tmp/bare HEAD:main"); //$NON-NLS-1$
        assertRejected("remote add backup file:///tmp/bare"); //$NON-NLS-1$
        assertRejected("fetch file://C:/other/repo.git"); //$NON-NLS-1$

        // The normal remotes stay accepted.
        assertAccepted("push https://example.com/repo.git main"); //$NON-NLS-1$
        assertAccepted("fetch ssh://git@example.com/repo.git"); //$NON-NLS-1$
    }

    @Test
    public void testAPlainSshUserIsARemoteNotACredential()
    {
        // 'ssh://[user@]server/project.git' is how git documents an SSH remote - an explicit user or
        // a non-default port needs exactly that spelling, and it is the alternative the guide
        // recommends, so refusing it would break the advice.
        assertAccepted("push ssh://git@example.com/project.git main"); //$NON-NLS-1$
        assertAccepted("remote add origin ssh://git@example.com:2222/project.git"); //$NON-NLS-1$
        assertAccepted("fetch git+ssh://user@host/repo.git"); //$NON-NLS-1$

        // A PASSWORD is still a credential, wherever it rides - and for http(s) any userinfo is,
        // since a token is commonly passed as the user name itself.
        assertRejected("push ssh://user:secret@example.com/project.git"); //$NON-NLS-1$
        // A password hiding after a SECOND '@' (an email-style user name in front of it).
        assertRejected("push ssh://user@example.com:secret@host/project.git"); //$NON-NLS-1$
        // Percent encoding does not hide it either: git decodes '%3A' back to ':'.
        assertRejected("push ssh://user%3Asecret@example.com/project.git"); //$NON-NLS-1$
        assertRejected("push ssh://user%3asecret@example.com/project.git"); //$NON-NLS-1$
        // The exemption must reach an option-ATTACHED url too.
        assertAccepted("push --repo=ssh://git@host/repo.git"); //$NON-NLS-1$
        assertRejected("remote add origin https://ghp_token@example.com/repo.git"); //$NON-NLS-1$
    }

    @Test
    public void testAnUnsupportedSubcommandThatIsAUrlIsRedacted()
    {
        // A pasted remote URL in the SUBCOMMAND position still reaches the "not supported" error.
        try
        {
            GitTool.parseCommand("https://ghp_s3cret@example.com/repo.git"); //$NON-NLS-1$
            fail("a URL is not a subcommand"); //$NON-NLS-1$
        }
        catch (CommandRejectedException expected)
        {
            assertFalse("the credential must not be echoed: " + expected.getMessage(), //$NON-NLS-1$
                expected.getMessage().contains("ghp_s3cret")); //$NON-NLS-1$
            assertTrue("the host must stay, or the error is not actionable: " //$NON-NLS-1$
                + expected.getMessage(), expected.getMessage().contains("example.com")); //$NON-NLS-1$
        }
    }

    @Test
    public void testARefusalDoesNotEchoTheOptionValue()
    {
        // A refused command can carry a secret in the value of the very option that got it refused,
        // and the error text travels to the client, the model's context and the request history.
        // The cluster forms are covered too: a short option's value has no '=' to cut at.
        try
        {
            GitTool.parseCommand("fetch --config=http.extraHeader=Authorization:Bearer_s3cret origin"); //$NON-NLS-1$
            fail("a blocked option must be rejected"); //$NON-NLS-1$
        }
        catch (CommandRejectedException expected)
        {
            String message = expected.getMessage();
            assertFalse("the secret must not be echoed back: " + message, //$NON-NLS-1$
                message.contains("s3cret")); //$NON-NLS-1$
            assertTrue("the option NAME must stay, or the error is not actionable: " + message, //$NON-NLS-1$
                message.contains("--config")); //$NON-NLS-1$
        }

        // A SHORT option carries its value attached, and that value may itself contain an '='.
        for (String command : new String[]{"commit -FBearer_s3cret", //$NON-NLS-1$
            "commit -FBearer_s3cret=x", "blame -wSBearer_s3cret -- tracked.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            "merge -nsBearer_s3cret other"}) //$NON-NLS-1$
        {
            try
            {
                GitTool.parseCommand(command);
                fail("expected rejection of '" + command + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (CommandRejectedException expected)
            {
                assertFalse("the secret must not be echoed for '" + command + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + expected.getMessage(), expected.getMessage().contains("Bearer_s3cret")); //$NON-NLS-1$
            }
        }
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

        // The parser lets these through - the path guard below decides them, because whether a path
        // is outside the repository can only be answered against a real work tree.
        assertAccepted("diff -pO/etc/passwd"); //$NON-NLS-1$
        assertAccepted("log -Sfoo/etc/passwd"); //$NON-NLS-1$

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
        // A quoted operand keeps its spaces, and the scheme pattern is anchored.
        assertRejected("remote add origin \" https://example.com/r.git?access_token=secret\""); //$NON-NLS-1$
        // 'commit -t <file>' is --template: a file option, not a plain value.
        assertRejected("commit -t/etc/passwd"); //$NON-NLS-1$
        assertRejected("commit -qt/etc/passwd"); //$NON-NLS-1$

        // Attached to an OPTION, where the anchored scheme pattern would not see it.
        assertRejected("push --repo=https://host/repo.git?access_token=secret"); //$NON-NLS-1$
        assertRejected("remote add origin https://host/r.git#access_token=secret"); //$NON-NLS-1$
        // A '?' outside a URL (a commit message, a pathspec) is untouched.
        assertAccepted("commit -m \"why? because\""); //$NON-NLS-1$

        // Two URLs in one line: the first must not swallow the second's query - or its credential.
        String twoUrls = GitTool.redactCredentialUrls("https://a@h/x,https://secret@h/y?k=v"); //$NON-NLS-1$
        assertFalse("neither credential may survive: " + twoUrls, //$NON-NLS-1$
            twoUrls.contains("secret") || twoUrls.contains("k=v")); //$NON-NLS-1$ //$NON-NLS-2$

        // A '?' INSIDE a fragment must not push the redaction past the secret.
        String fragmentThenQuery =
            GitTool.redactCredentialUrls("https://h/r.git#access_token=ghp_secret?x=y"); //$NON-NLS-1$
        assertFalse("the fragment secret must not survive: " + fragmentThenQuery, //$NON-NLS-1$
            fragmentThenQuery.contains("ghp_secret")); //$NON-NLS-1$

        // A FRAGMENT hides a credential just as well, and carries neither '@' nor '?'.
        String fragment = GitTool.redactCredentialUrls("origin	https://example.com/r.git#access_token=ghp_secret (fetch)"); //$NON-NLS-1$
        assertFalse("the fragment secret must not survive: " + fragment, //$NON-NLS-1$
            fragment.contains("ghp_secret")); //$NON-NLS-1$
        assertTrue("what follows must survive: " + fragment, fragment.contains("(fetch)")); //$NON-NLS-1$ //$NON-NLS-2$

        // A query in an EARLIER url must not hide a later one: the state resets at whitespace.
        String afterQuery = GitTool.redactCredentialUrls(
            "https://h/r?x=y done x=https://ghp_secret@evil/r"); //$NON-NLS-1$
        assertFalse("the second credential must not survive: " + afterQuery, //$NON-NLS-1$
            afterQuery.contains("ghp_secret")); //$NON-NLS-1$

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

    @Test
    public void testClusteredFileOptionValueIsCheckedAgainstTheWorkTree()
        throws java.io.IOException
    {
        // 'diff -pO<file>' is '-p -O <file>': git reads that order file, so the value inside the
        // cluster must be checked - while a value that merely CONTAINS a path (a pickaxe string)
        // must not be, or an ordinary search would be refused.
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("edt-mcp-git-root"); //$NON-NLS-1$
        java.nio.file.Path outside = java.nio.file.Files.createTempFile("edt-mcp-outside", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            java.nio.file.Path canonicalRoot = root.toRealPath();
            String outsidePath = outside.toRealPath().toString();

            assertNotNull("an order file outside the repository must be caught inside a cluster", //$NON-NLS-1$
                GitTool.escapingCandidate("-pO" + outsidePath, "diff", canonicalRoot)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull("the separated-looking attached form too", //$NON-NLS-1$
                GitTool.escapingCandidate("-O" + outsidePath, "diff", canonicalRoot)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("a value-taking letter ends the scan: '-S' consumes the rest as a search string", //$NON-NLS-1$
                GitTool.escapingCandidate("-Sfoo" + outsidePath, "diff", canonicalRoot)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("'log' has no file-taking short option, so a pickaxe value is left alone", //$NON-NLS-1$
                GitTool.escapingCandidate("-Sfoo" + outsidePath, "log", canonicalRoot)); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("a path INSIDE the repository is fine", //$NON-NLS-1$
                GitTool.escapingCandidate("-O" + canonicalRoot.resolve("order.txt"), "diff", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    canonicalRoot));
        }
        finally
        {
            java.nio.file.Files.deleteIfExists(outside);
            java.nio.file.Files.deleteIfExists(root);
        }
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

    /**
     * Source-order ratchet for the ORDER OF THE GATES INSIDE the read-only pre-flight, not for their
     * predicates. Both live in {@link GitTool#preflightRefusal}, which
     * {@code GitToolStoredRemoteTest} drives directly - so their PRESENCE is pinned behaviourally,
     * and {@code GitToolPreflightOrderRatchetTest} pins that {@code execute()} runs the seam before
     * the consent gate. What neither pins is which of the two fires FIRST: no behavioural case in
     * the suite pairs a poisoned remote with an escaping operand, so none of them ever sees both
     * gates compete. Such a case IS constructible and would be the cheaper pin -
     * {@code preflightRefusal(repo, parseCommand("push .."), workTree)} on a repository with a
     * poisoned remote trips both ({@code escapingCandidate} tests a bare operand as a path whatever
     * the subcommand, and {@code push} is one of the REMOTE_SUBCOMMANDS), and it would assert WHICH
     * refusal comes back, the way
     * {@code GitToolStoredRemoteTest.testThePreFlightAlsoRefusesAnOperandOutsideTheWorkTree} already
     * asserts "points outside the repository" for the containment gate alone. Until that case is
     * written, this reads the source and pins the order.
     * <p>
     * The contract: containment check first (an operand outside the work tree is a cheaper, more
     * specific error, and it is about the command the caller just sent rather than about the
     * repository's stored state), then the stored-remote refusal - and consent LAST, outside this
     * seam, per the rule stated in {@code execute()} itself.
     */
    @Test
    public void testTheContainmentCheckRunsBeforeTheStoredRemoteRefusal()
    {
        String source = readToolImplSource("GitTool.java"); //$NON-NLS-1$
        // Positive control: without it, a locator that found the wrong file (or an empty one) would
        // make this ratchet's failure mode identical to its pass, and it would prove nothing.
        assertTrue("the located file is not GitTool's source", //$NON-NLS-1$
            source.contains("public class GitTool implements IMcpTool")); //$NON-NLS-1$

        int containment = source.indexOf("outsideRepositoryOperand(argv"); //$NON-NLS-1$
        int storedRemote = source.indexOf("storedRemoteRefusal(repo, argv)"); //$NON-NLS-1$
        int consent = source.indexOf("requireConsentFor(argv)"); //$NON-NLS-1$
        int preflight = source.indexOf("preflightRefusal(repo, argv, workTree)"); //$NON-NLS-1$

        assertTrue("the pre-flight no longer calls outsideRepositoryOperand(argv, ...)", //$NON-NLS-1$
            containment > -1);
        assertTrue("the pre-flight no longer calls storedRemoteRefusal(repo, argv): the " //$NON-NLS-1$
            + "stored-remote refusal is dead code and a poisoned remote reaches git again", //$NON-NLS-1$
            storedRemote > -1);
        assertTrue("execute() no longer calls requireConsentFor(argv)", consent > -1); //$NON-NLS-1$
        assertTrue("execute() no longer calls preflightRefusal(repo, argv, workTree): the read-only " //$NON-NLS-1$
            + "gauntlet is bypassed", preflight > -1); //$NON-NLS-1$
        assertTrue("the stored-remote refusal must run AFTER the containment check", //$NON-NLS-1$
            containment < storedRemote);
        // The gates are declared BELOW execute(), so this compares two call sites inside execute()
        // only; the bytecode ratchet is what proves the order actually compiled that way.
        assertTrue("the read-only pre-flight must run BEFORE the consent gate, otherwise a human " //$NON-NLS-1$
            + "is prompted for a command that can never run", preflight < consent); //$NON-NLS-1$
    }

    /**
     * Reads a source file from {@code tools/impl} by walking up from the working directory, the way
     * {@code SchemaExecuteParamParityTest} locates tool sources (Tycho surefire runs inside the
     * checkout). Fails loudly rather than returning nothing, so a source-order ratchet cannot pass
     * merely because the file was not found.
     */
    private static String readToolImplSource(String fileName)
    {
        String rel = "bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl"; //$NON-NLS-1$
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File direct = new File(new File(dir, rel), fileName);
            if (direct.isFile())
            {
                return readUtf8(direct);
            }
            File underMcp = new File(new File(dir, "mcp/" + rel), fileName); //$NON-NLS-1$
            if (underMcp.isFile())
            {
                return readUtf8(underMcp);
            }
            dir = dir.getParentFile();
        }
        fail("could not locate tools/impl/" + fileName + " by walking up from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir") //$NON-NLS-1$
            + " (looked for '" + rel + "'). Adjust the locator for this build layout - a source-order " //$NON-NLS-1$ //$NON-NLS-2$
            + "ratchet must never pass just because it read nothing."); //$NON-NLS-1$
        return null; // unreachable
    }

    private static String readUtf8(File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            fail("failed reading source " + file + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null; // unreachable
        }
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
