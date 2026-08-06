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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Covers {@link GitTool#storedRemoteRefusal}: the pre-flight that REFUSES a command which would
 * print or use a remote whose STORED credential cannot be masked (issue #314). The command carries
 * no secret here - it sits in {@code remote.<name>.url} / {@code remote.<name>.pushurl}, where ASCII
 * whitespace inside the authority ends the output redaction's scan before the {@code @}, so what
 * follows could not be masked at all. A control character is refused alongside it for a different
 * reason: it ends none of those scans, but it can never be legitimate in an authority and must not
 * travel verbatim into the response.
 *
 * <p>The last section covers {@link GitTool#preflightRefusal}, the entry point {@code execute()}
 * actually calls: the predicate can be right and still be wired to nothing, so the refusal is also
 * driven through the shared seam and asserted in the shape the client receives it - a
 * {@code ToolResult.error(...)} result, not a bare string. That the seam runs before the consent
 * gate is pinned separately by {@code GitToolPreflightOrderRatchetTest}, which reads the compiled
 * {@code execute()} - the consent gate can ASK a human and so may not be called from a unit test.
 *
 * <p><b>Falsification.</b> Every case below fails on master by construction:
 * {@code storedRemoteRefusal} does not exist there, so this file does not even compile against it -
 * there is no version of this test that passes without the fix.
 *
 * <p><b>No process, no workspace.</b> Production reads the remotes from the JGit {@link Repository}
 * the call already holds, so these tests hold one too: real repositories are built in-process with
 * {@code Git.init()} (the way {@code GitRepositoryResolverTest} does it) and their configuration is
 * written through JGit. Nothing here runs {@code git}, touches the Eclipse workspace or mocks JGit.
 * The real-{@code git} parity - that git itself PERSISTS such a URL verbatim and JGit reads it back
 * unchanged - is proven by {@code GitToolProcessIntegrationTest}, which is skipped when git is
 * absent and therefore cannot be the only proof.
 *
 * <p><b>Positive control.</b> A poisoned URL is a valid fixture only if it survives the save/load
 * round-trip, so {@link #storeRemoteUrls} re-parses {@code .git/config} from disk with an
 * independent {@link Config} and asserts the offending character is still in place. Without that, a
 * character JGit escaped away or trimmed would make a green run mean nothing.
 *
 * <p>Assertions are made against OUR refusal text only - git's own wording and locale never enter
 * into it.
 */
public class GitToolStoredRemoteTest
{
    /** The fake credential every fixture carries; a refusal that echoes it has leaked. */
    private static final String SECRET = "s3cr3t-token"; //$NON-NLS-1$

    /** The fake host every fixture carries; a refusal must not name it either. */
    private static final String HOST = "example.com"; //$NON-NLS-1$

    private static final String REMOTE_SECTION = "remote"; //$NON-NLS-1$

    private static final String URL_KEY = "url"; //$NON-NLS-1$

    private static final String PUSHURL_KEY = "pushurl"; //$NON-NLS-1$

    private static final String ORIGIN = "origin"; //$NON-NLS-1$

    private static final String PUSH = "push"; //$NON-NLS-1$

    /** The file JGit reloads - and the one the fail-closed case corrupts. */
    private static final String CONFIG_FILE = "config"; //$NON-NLS-1$

    /**
     * The section name that makes the corrupt fixture unparseable. Deliberately unlike any English
     * word: JGit quotes it in the ConfigInvalidException ("Bad section entry: ..."), so a refusal
     * that embedded ANY link of the exception's cause chain would carry this string - which is what
     * the fail-closed case asserts never happens.
     */
    private static final String UNPARSEABLE_MARKER = "unparseable-marker-xyz123"; //$NON-NLS-1$

    /** ASCII space (0x20), named rather than written as an invisible literal. */
    private static final char SPACE = 0x20;

    /** EM SPACE: whitespace to a human, but NOT ASCII whitespace - it must stay redactable. */
    private static final char EM_SPACE = '\u2003';

    /**
     * A remote named in Cyrillic. Legal in git, and the two {@code [^a-zA-Z0-9_-]} sanitizers that
     * already live in the bundle would reduce it to an empty string, leaving the refusal
     * unactionable - hence its own case. Spelled with escapes because the tests project pins no
     * source encoding, so a raw non-ASCII byte could not be trusted to survive the build.
     */
    private static final String CYRILLIC_REMOTE = "\u0438\u0441\u0442\u043e\u043a\u0438"; //$NON-NLS-1$

    /**
     * A C0 byte planted INSIDE a remote's name. Both git and JGit accept one in a QUOTED subsection
     * name - only a bare LF is rejected there - so a name read back out of {@code .git/config} is
     * exactly as untrusted as the URL beside it. Written as a numeric constant: a raw control byte
     * in a source file is invisible.
     */
    private static final char HOSTILE_NAME_CONTROL = 0x01;

    /**
     * How far past the bound on an echoed name the hostile fixture runs. Well beyond the 80
     * characters the refusal allows, so an unbounded echo is unmistakable and the case keeps its
     * meaning if that bound is ever raised.
     */
    private static final int HOSTILE_NAME_PADDING = 200;

    /** Repositories opened by a test, closed in {@link #closeAndDeleteRepositories()}. */
    private final List<Git> opened = new ArrayList<>();

    /** Temporary directories created by a test, deleted in {@link #closeAndDeleteRepositories()}. */
    private final List<File> temporaries = new ArrayList<>();

    @After
    public void closeAndDeleteRepositories()
    {
        for (Git git : opened)
        {
            try
            {
                git.close();
            }
            catch (RuntimeException e) // NOSONAR cleanup must never mask the failure under test
            {
                // Nothing to do: the directory is deleted below either way.
            }
        }
        opened.clear();
        for (File directory : temporaries)
        {
            deleteRecursively(directory);
        }
        temporaries.clear();
    }

    // ==================== refused: a credential that cannot be masked ====================

    @Test
    public void testEveryAsciiWhitespaceInsideAStoredCredentialIsRefused() throws Exception
    {
        // The six characters a regex \s matches without UNICODE_CHARACTER_CLASS: space, tab, LF, CR,
        // vertical tab and form feed. Each of them ends the redaction scan before the '@', so
        // everything in front of it would reach the caller unmasked. Written as numeric escapes: a
        // raw control byte in a source file is invisible, and LF/CR cannot be written as unicode
        // escapes at all (the Java lexer would turn those into real line terminators).
        char[] separators = { 0x20, 0x09, 0x0A, 0x0D, 0x0B, 0x0C };
        for (char separator : separators)
        {
            Repository repo = newRepository("git-stored-whitespace"); //$NON-NLS-1$
            storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(separator));

            String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

            assertNotNull(hex(separator) + " inside the userinfo cannot be masked, so the command " //$NON-NLS-1$
                + "must be refused", refusal); //$NON-NLS-1$
            assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testAControlCharacterInsideAStoredCredentialIsRefused() throws Exception
    {
        // C0 controls and DEL. Unlike whitespace these do NOT end the redaction's scans - such a URL
        // is masked correctly today - so the reason is a different one: a character that cannot occur
        // in a legitimate authority must not be handed to git or echoed into the response, and the
        // input guard already rejects it, so only a remote poisoned OUTSIDE this tool can carry one.
        char[] controls = { 0x01, 0x1F, 0x7F };
        for (char control : controls)
        {
            Repository repo = newRepository("git-stored-control"); //$NON-NLS-1$
            storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(control));

            String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

            assertNotNull(hex(control) + " inside the userinfo must be refused", refusal); //$NON-NLS-1$
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testAQuestionMarkOrHashInsideTheUserinfoDoesNotHideTheCredential() throws Exception
    {
        // An RFC-shaped authority scan stops at the '?' or the '#', finds no '@' at all and would
        // let the remote through - and the redaction, whose userinfo scan bails at that same
        // character, would then mask what it takes for a query and print the first half of the
        // secret verbatim. Not a claim about git's own parser: git ends the host portion at the
        // first of '/', '?' and '#' too and sends no credential for this shape at all. The scan has
        // to run to the first '/' because the REDACTION cannot cope, not because git would.
        for (char delimiter : new char[] { '?', '#' })
        {
            Repository repo = newRepository("git-stored-userinfo-delimiter"); //$NON-NLS-1$
            String poisoned = "https://user:" + SECRET + delimiter + "a" + SPACE + "b@" + HOST //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "/team/repo.git"; //$NON-NLS-1$
            storeRemoteUrls(repo, ORIGIN, URL_KEY, poisoned);

            String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

            assertNotNull("a '" + delimiter + "' inside the userinfo must not hide the credential " //$NON-NLS-1$ //$NON-NLS-2$
                + "from the check", refusal); //$NON-NLS-1$
            assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testAPoisonedPushurlIsRefusedToo() throws Exception
    {
        // 'git push' uses pushurl when it is set and 'git remote -v' prints it, so a credential
        // parked there is exactly as exposed as one in 'url'.
        Repository repo = newRepository("git-stored-pushurl"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        storeRemoteUrls(repo, ORIGIN, PUSHURL_KEY, poisonedUrl(SPACE));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a poisoned pushurl must be refused even next to a clean url", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testTheFirstOfTwoStoredUrlValuesIsEnoughToRefuse() throws Exception
    {
        // 'remote set-url --add' makes url multi-valued and 'remote -v' prints every value, so all
        // of them have to be read. This case is the discriminator: git's last-one-wins getString
        // returns the CLEAN value, so only a getStringList read can see the poisoned first one.
        Repository repo = newRepository("git-stored-first-of-two"); //$NON-NLS-1$
        String clean = "https://" + HOST + "/team/mirror.git"; //$NON-NLS-1$ //$NON-NLS-2$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(SPACE), clean);

        assertEquals("fixture: getString must return the CLEAN value here, or this case would not " //$NON-NLS-1$
            + "tell a getStringList read from a getString one", clean, //$NON-NLS-1$
            repo.getConfig().getString(REMOTE_SECTION, ORIGIN, URL_KEY));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the FIRST of two url values carries the credential - it must be seen", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testTheSecondOfTwoStoredUrlValuesIsEnoughToRefuse() throws Exception
    {
        Repository repo = newRepository("git-stored-second-of-two"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://" + HOST + "/team/repo.git", //$NON-NLS-1$ //$NON-NLS-2$
            poisonedUrl('\t'));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the SECOND of two url values carries the credential - it must be seen", refusal); //$NON-NLS-1$
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testTheRemedyTextNamesTheDropBeforeTheSetUrl() throws Exception
    {
        // What is pinned here is the message's WORDING, not the effect of the commands it offers:
        // like every case in this file it starts no git process (see the class comment), it reads
        // the refusal string and asserts which commands appear in it and in which order. Nothing
        // below shows that either command clears anything - no test in this bundle runs
        // 'remote set-url --delete' or 'remote remove' at all.
        //
        // Git's behaviour is the RATIONALE for that wording, cited rather than exercised: 'remote
        // set-url --add' leaves url multi-valued, and against a multi-valued url a plain
        // 'git remote set-url <name> <url>' answers "remote.<name>.url has multiple values" and
        // exits non-zero without touching the config. A message that named only that command would
        // therefore leave the poisoned value in place and earn the next command this same refusal -
        // the endless retry the text exists to prevent. Hence the drop ('remote set-url --delete',
        // or 'remote remove' plus a re-add) has to be named BEFORE the plain set-url this branch
        // ends with, which is exactly what the ordering assertion below reads out of the text.
        Repository repo = newRepository("git-stored-multi-value-remedy"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://" + HOST + "/team/mirror.git", //$NON-NLS-1$ //$NON-NLS-2$
            poisonedUrl(SPACE));
        assertEquals("fixture: the remote must really hold TWO url values, or this case does not sit " //$NON-NLS-1$
            + "on the shape the wording under test is about", 2, //$NON-NLS-1$
            repo.getConfig().getStringList(REMOTE_SECTION, ORIGIN, URL_KEY).length);

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a poisoned value beside a clean one must still be refused", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testEverySubcommandThatCanReachARemoteIsChecked() throws Exception
    {
        Repository repo = newRepository("git-stored-subcommands"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(SPACE));

        for (String subcommand : List.of("remote", PUSH, "fetch", "pull")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertNotNull("'" + subcommand + "' can print or use the poisoned remote", //$NON-NLS-1$ //$NON-NLS-2$
                GitTool.storedRemoteRefusal(repo, List.of(subcommand)));
        }
    }

    @Test
    public void testAnArgvCarryingTheLeadingGitTokenIsCheckedToo() throws Exception
    {
        // execute() passes the vector parseCommand produced, and that one starts with 'git'. Were
        // the subcommand read from index 0 alone, the production path would check nothing at all.
        Repository repo = newRepository("git-stored-leading-git"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(SPACE));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of("git", "remote", "-v")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull("['git', 'remote', '-v'] is what parseCommand hands execute()", refusal); //$NON-NLS-1$
        assertRefusalLeaksNothing(refusal);
    }

    // ==================== not refused: everything the redaction still covers ====================

    @Test
    public void testASubcommandThatCannotReachARemoteIsNotChecked() throws Exception
    {
        // A poisoned remote must not block reading the history: 'log' neither prints nor uses it.
        Repository repo = newRepository("git-stored-local-subcommand"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(SPACE));

        assertNull("'log' cannot reach a remote", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of("log"))); //$NON-NLS-1$
        assertNull("'status' cannot reach a remote", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of("status"))); //$NON-NLS-1$
        assertNull("...and neither can it in the argv spelling parseCommand produces", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of("git", "log", "-1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testARepositoryWithoutRemotesIsNotRefused() throws Exception
    {
        Repository repo = newRepository("git-stored-no-remotes"); //$NON-NLS-1$

        assertNull("a freshly created repository has nothing to refuse", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testAMaskableCredentialUrlIsNotRefused() throws Exception
    {
        // The ordinary case the redaction was written for: no whitespace in the authority, so
        // 'remote -v' prints the URL with its userinfo masked and the command may run.
        Repository repo = newRepository("git-stored-maskable"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://user:" + SECRET + "@" + HOST + "/r.git"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull("a credential the redaction CAN mask must not be refused", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testAUnicodeSpaceInsideTheUserinfoIsNotRefused() throws Exception
    {
        // U+2003 is not ASCII whitespace: it does NOT end the redaction scan, so such a credential
        // is still masked and refusing it would be over-reach. The paired half of
        // GitToolTest.testRedactionCoversAUnicodeSpaceInsideUserinfo, which must stay green.
        Repository repo = newRepository("git-stored-unicode-space"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(EM_SPACE));

        assertNull("a U+2003 inside the userinfo stays REDACTED - it is not refused", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testWhitespaceOutsideTheAuthorityIsNotRefused() throws Exception
    {
        // The authority ends at the first '/', so whitespace further along the PATH hides nothing:
        // the redaction's userinfo scan has reached the '@' long before it and masks the credential
        // as usual.
        //
        // This is the case that pins the SCOPING of the whitespace scan, so the fixture carries a
        // credential too - the shape of an ordinary Azure DevOps remote, whose project name may
        // legally contain a space. Judge the whole URL instead of the authority and this remote is
        // refused forever, taking remote/push/fetch/pull down with it; without the credential the
        // missing '@' alone would keep the case green and that mutation would go unnoticed.
        Repository repo = newRepository("git-stored-path-space"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY,
            "https://user:" + SECRET + "@" + HOST + "/team/my" + SPACE + "repo.git"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertNull("whitespace in the PATH hides no credential - the userinfo is still maskable", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testWhitespaceInAnAuthorityWithoutACredentialIsNotRefused() throws Exception
    {
        // Odd, but there is no '@': nothing is hidden behind the whitespace, so there is nothing to
        // refuse. The refusal exists for credentials that cannot be masked, not for odd hostnames.
        Repository repo = newRepository("git-stored-no-userinfo"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://exa" + SPACE + "mple.com/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("an authority without an '@' carries no credential to mask", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    // ==================== the message itself ====================

    @Test
    public void testACyrillicRemoteNameSurvivesInTheRefusal() throws Exception
    {
        Repository repo = newRepository("git-stored-cyrillic-name"); //$NON-NLS-1$
        storeRemoteUrls(repo, CYRILLIC_REMOTE, URL_KEY, poisonedUrl(SPACE));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the remote's name has no bearing on WHETHER it is refused", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, CYRILLIC_REMOTE);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testAHostileRemoteNameIsShortenedBeforeItIsEchoed() throws Exception
    {
        // The name is the ONE dynamic field of the refusal, and it comes out of .git/config, so it is
        // untrusted in both directions the URL is: it can carry a raw C0 byte (git and JGit reject
        // only a bare LF inside a quoted subsection name) and it has no length bound at all.
        //
        // This is the fixture that makes the sanitizing call REAL. Every other stored-remote case
        // here is named 'origin' or its Cyrillic sibling, for which the sanitizer is the identity -
        // drop it from the refusal and echo the raw name instead, and all of them stay green. Only
        // this one turns that mutation red, and it does so on exactly the two damages the mutation
        // causes: a control byte riding out of the configuration into the MCP response, and an
        // arbitrarily long name flooding the message.
        String hostile = "ori" + HOSTILE_NAME_CONTROL + "gin" //$NON-NLS-1$ //$NON-NLS-2$
            + "z".repeat(HOSTILE_NAME_PADDING); //$NON-NLS-1$
        Repository repo = newRepository("git-stored-hostile-name"); //$NON-NLS-1$
        storeRemoteUrls(repo, hostile, URL_KEY, poisonedUrl(SPACE));
        assertTrue("fixture: the production code reads the names from getSubsections, so JGit has to " //$NON-NLS-1$
            + "hand it this one VERBATIM - otherwise the sanitizer is never asked to do anything", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).contains(hostile));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the remote's name has no bearing on WHETHER it is refused", refusal); //$NON-NLS-1$
        assertRefusalStatesTheFix(refusal);
        // (a) No control character reaches the caller. That is the same bar every other case is held
        // to - this is just the only fixture whose NAME can breach it.
        assertRefusalLeaksNothing(refusal);
        // (b) ...and no unbounded name floods the message.
        assertFalse("the whole name must not be echoed back: " + refusal, //$NON-NLS-1$
            refusal.contains("z".repeat(HOSTILE_NAME_PADDING))); //$NON-NLS-1$
        // (c) ...and where it was cut, it SAYS so: an echo that simply stopped would read as the real
        // name and send the operator to 'git remote set-url' with a name git does not know.
        assertTrue("a shortened name must end in the ellipsis that marks it as shortened: " + refusal, //$NON-NLS-1$
            refusal.contains("z...")); //$NON-NLS-1$
        // Shortened, not gutted: what survives still identifies the remote.
        assertTrue("the readable head of the name must survive: " + refusal, //$NON-NLS-1$
            refusal.contains("origin")); //$NON-NLS-1$
    }

    // ==================== fail closed ====================

    @Test
    public void testAnUnreadableConfigurationFailsClosed() throws Exception
    {
        Repository repo = newRepository("git-stored-corrupt-config"); //$NON-NLS-1$
        // JGit parses the good configuration once here, so the outcome below can only come from the
        // RELOAD of the broken one - and it starts from a state in which nothing is refused.
        assertTrue("fixture: a fresh repository has no remotes", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).isEmpty());

        // A configuration that carries a credential AND cannot be parsed: the unterminated section
        // header is what makes JGit throw, and the value above it is what an embedded exception
        // message would hand back.
        String broken = "[remote \"" + ORIGIN + "\"]\n\turl = https://user:" + SECRET + "@" + HOST //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "/r.git\n[" + UNPARSEABLE_MARKER + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        assertConfigTextIsUnparseable(broken);

        File configFile = new File(repo.getDirectory(), CONFIG_FILE);
        Files.write(configFile.toPath(), broken.getBytes(StandardCharsets.UTF_8));
        // JGit reloads when the size OR the timestamp changed; both are moved, so the test does not
        // depend on which of the two this filesystem happens to notice. The result is deliberately
        // ignored: a platform that refuses the timestamp change still leaves the size difference,
        // and a refusal that never arrives fails the assertion below anyway.
        configFile.setLastModified(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a configuration that cannot be read cannot be shown to be safe: the command " //$NON-NLS-1$
            + "must be refused, not run blind", refusal); //$NON-NLS-1$
        // The reason stays generic on purpose: JGit's ConfigInvalidException quotes the offending
        // line, which here IS the credential.
        assertRefusalLeaksNothing(refusal);
        assertFalse("no configuration content may reach the caller: " + refusal, //$NON-NLS-1$
            refusal.contains("[remote")); //$NON-NLS-1$
        assertFalse("no configuration content may reach the caller: " + refusal, //$NON-NLS-1$
            refusal.contains("url = ")); //$NON-NLS-1$
        // The two places the configuration actually surfaces: JGit names the offending section in
        // the ConfigInvalidException and the file in the one wrapping it, so a refusal that carried
        // any link of that cause chain would show up here.
        assertFalse("the exception's cause chain must not be embedded: " + refusal, //$NON-NLS-1$
            refusal.contains(UNPARSEABLE_MARKER));
        assertFalse("nor where the configuration lives: " + refusal, //$NON-NLS-1$
            refusal.contains(repo.getDirectory().getPath()));
    }

    // ==================== the pre-flight execute() actually runs ====================

    @Test
    public void testThePreFlightHandsBackTheStoredRefusalAsAnErrorResult() throws Exception
    {
        // The seam execute() calls. Every case above drives storedRemoteRefusal directly, which
        // proves the PREDICATE and nothing about the path a request takes: drop the call from the
        // shared entry point and each of them stays green while a poisoned remote prints verbatim.
        // This one goes through the entry point instead, and through the whole contract - the
        // refusal has to come back as the structured error result the client receives, not as a
        // bare string (CLAUDE.md #8).
        Repository repo = newRepository("git-preflight-poisoned"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(SPACE));
        List<String> argv = GitTool.parseCommand("remote -v"); //$NON-NLS-1$

        String json = GitTool.preflightRefusal(repo, argv, repo.getWorkTree());

        assertNotNull("the pre-flight must refuse a command that would print a poisoned remote", //$NON-NLS-1$
            json);
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse("a refusal is a failed result: " + json, //$NON-NLS-1$
            result.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = result.get("error").getAsString(); //$NON-NLS-1$
        assertEquals("the result must carry the stored-remote refusal itself, not a rewrite of it", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, argv), error);
        assertRefusalNamesTheRemoteAndTheFix(error, ORIGIN);
        assertRefusalLeaksNothing(error);
        assertFalse("nothing of the offending value may reach the wire: " + json, //$NON-NLS-1$
            json.contains(SECRET) || json.contains(HOST));
    }

    @Test
    public void testThePreFlightAlsoRefusesAnOperandOutsideTheWorkTree() throws Exception
    {
        // The other gate behind the same entry point, and the one no other case here would miss:
        // 'diff' cannot reach a remote, so the stored-remote check returns null for it and only the
        // containment check can produce this refusal. Remove that check from the seam and this goes
        // null.
        Repository repo = newRepository("git-preflight-containment"); //$NON-NLS-1$
        // The work tree's own parent: it exists (so it is a real read, not a revision) and it is
        // outside the repository by construction.
        List<String> argv = GitTool.parseCommand("diff .."); //$NON-NLS-1$

        String json = GitTool.preflightRefusal(repo, argv, repo.getWorkTree());

        assertNotNull("an operand outside the work tree must be refused by the same pre-flight", //$NON-NLS-1$
            json);
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse("a refusal is a failed result: " + json, //$NON-NLS-1$
            result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("...and it must say what is wrong: " + json, //$NON-NLS-1$
            result.get("error").getAsString().contains("points outside the repository")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testThePreFlightLetsACleanRepositoryThrough() throws Exception
    {
        // The pre-flight must be a gate, not a wall: with nothing to refuse it has to return null,
        // or every command in a healthy repository would fail before the consent gate is reached.
        Repository repo = newRepository("git-preflight-clean"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("a clean repository has nothing to refuse", GitTool.preflightRefusal(repo, //$NON-NLS-1$
            GitTool.parseCommand("push origin main"), repo.getWorkTree())); //$NON-NLS-1$
    }

    // ==================== assertions ====================

    /**
     * Asserts that a refusal is actionable: it names the remote, the way out, and WHERE that way out
     * has to be taken, so the caller can repair the repository instead of guessing - or looping.
     *
     * @param refusal the message under test
     * @param remote the remote the fixture poisoned
     */
    private static void assertRefusalNamesTheRemoteAndTheFix(String refusal, String remote)
    {
        assertTrue("the refusal must name the remote to fix: " + refusal, refusal.contains(remote)); //$NON-NLS-1$
        assertRefusalStatesTheFix(refusal);
    }

    /**
     * Asserts everything an actionable refusal says APART from the remote's name: what is wrong, how
     * to repair it, where that repair has to happen, which configuration the entry may live in, and
     * that retrying through this tool cannot work. Split out because a name too long or too hostile
     * to echo whole is quoted in a shortened form, so the case that pins the shortening cannot
     * assert the name as it was stored.
     *
     * @param refusal the message under test
     */
    private static void assertRefusalStatesTheFix(String refusal)
    {
        String lower = refusal.toLowerCase(Locale.ROOT);
        assertTrue("the refusal must say WHAT is wrong: " + refusal, //$NON-NLS-1$
            lower.contains("whitespace or control character")); //$NON-NLS-1$
        // The remedy names ONE command on purpose. 'git remote set-url' repairs a single-valued url
        // but not a multi-valued one; 'set-url --delete' refuses to remove a remote's last non-push
        // url. Dropping the remote and adding it again is the only step that works whatever shape
        // the entry has, so that is what the message says - a remedy that fits one shape only would
        // send an unattended caller into the retry loop this text exists to prevent.
        assertTrue("the refusal must say HOW to fix it: " + refusal, //$NON-NLS-1$
            lower.contains("git remote remove")); //$NON-NLS-1$
        // ...and WHERE. The pre-flight keys on the SUBCOMMAND, so 'remote set-url' and
        // 'remote remove' - the only commands that could clear the entry - are refused by this very
        // message while the entry is there (testEverySubcommandThatCanReachARemoteIsChecked pins
        // that 'remote' is one of them). A remedy that reads as if this tool could run it sends an
        // unattended caller into an endless retry, so the message must send it to a terminal.
        assertTrue("the refusal must say the repair happens OUTSIDE this tool: " + refusal, //$NON-NLS-1$
            lower.contains("outside this tool")); //$NON-NLS-1$
        assertTrue("...and name where instead - a terminal: " + refusal, //$NON-NLS-1$
            lower.contains("terminal")); //$NON-NLS-1$
        assertTrue("...and warn that retrying it through this tool cannot work: " + refusal, //$NON-NLS-1$
            lower.contains("cannot work")); //$NON-NLS-1$
        // ...and it has to say WHERE the entry may live, because the commands it names are
        // REPOSITORY-scoped while the check is not: storedRemoteRefusal reads repo.getConfig(), the
        // merged configuration, whose getSubsections walks the base chain (repository -> user ->
        // system). For a remote inherited from the user or system file the repository-scoped
        // commands answer "No such remote", so the caller has to be told where else to look.
        assertTrue("the refusal must say the entry may be inherited from the user or system " //$NON-NLS-1$
            + "configuration: " + refusal, lower.contains("user or system")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Asserts that a refusal carries no part of the offending URL. It travels back to the client,
     * into the model's context and into the request history, so naming the problem is all it may do.
     *
     * @param refusal the message under test
     */
    private static void assertRefusalLeaksNothing(String refusal)
    {
        assertFalse("the credential must never be echoed back: " + refusal, refusal.contains(SECRET)); //$NON-NLS-1$
        assertFalse("nor the host: " + refusal, refusal.contains(HOST)); //$NON-NLS-1$
        assertFalse("nor any other part of the URL: " + refusal, refusal.contains("https://")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor the userinfo in front of the credential: " + refusal, //$NON-NLS-1$
            refusal.contains("user:")); //$NON-NLS-1$
        for (int i = 0; i < refusal.length(); i++)
        {
            char c = refusal.charAt(i);
            if (c < 0x20 || c == 0x7F)
            {
                // A control character can only have come from the configuration - and it would ride
                // straight through the response into whatever renders it.
                fail("the refusal must stay plain text - " + hex(c) + " reached it: " + refusal); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Asserts that a configuration text really cannot be parsed - the precondition the fail-closed
     * branch reacts to. Parsed with an independent {@link Config}, so this says nothing about
     * whether the repository under test reloaded: it proves only that the FIXTURE is broken.
     *
     * @param text the configuration file content
     */
    private static void assertConfigTextIsUnparseable(String text)
    {
        try
        {
            new Config().fromText(text);
            fail("fixture: this configuration parses fine, so the test would prove nothing"); //$NON-NLS-1$
        }
        catch (ConfigInvalidException expected)
        {
            // Sound fixture: JGit cannot read this file, which is what makes the check fail closed.
        }
    }

    // ==================== fixtures ====================

    /**
     * A URL whose userinfo is split by {@code offender} - the shape the redaction cannot mask when
     * that character is ASCII whitespace, and the shape that may not reach git at all when it is a
     * control character.
     *
     * @param offender the character to plant inside the userinfo
     * @return the URL to store
     */
    private static String poisonedUrl(char offender)
    {
        return "https://user:" + SECRET + offender + "ok@" + HOST + "/team/repo.git"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Creates a real repository in a temporary directory and registers it for cleanup.
     *
     * @param prefix the temporary directory name prefix
     * @return the open repository
     * @throws Exception when the repository cannot be created
     */
    private Repository newRepository(String prefix) throws Exception
    {
        File directory = Files.createTempDirectory(prefix).toFile();
        temporaries.add(directory);
        Git git = Git.init().setDirectory(directory).call();
        opened.add(git);
        return git.getRepository();
    }

    /**
     * Stores {@code urls} under {@code remote.<name>.<key>} and PROVES they landed verbatim: the
     * file is re-parsed from disk with an independent {@link Config}, so a character JGit escaped
     * away or trimmed can never masquerade as a passing test.
     *
     * @param repo the repository to write into
     * @param remote the remote's subsection name
     * @param key {@code url} or {@code pushurl}
     * @param urls the values to store, in order
     * @throws Exception when the configuration cannot be written or read back
     */
    private static void storeRemoteUrls(Repository repo, String remote, String key, String... urls)
        throws Exception
    {
        StoredConfig config = repo.getConfig();
        config.setStringList(REMOTE_SECTION, remote, key, Arrays.asList(urls));
        config.save();

        Config onDisk = new Config();
        onDisk.fromText(new String(
            Files.readAllBytes(new File(repo.getDirectory(), CONFIG_FILE).toPath()),
            StandardCharsets.UTF_8));
        assertEquals("fixture: the stored value must survive the save/load round-trip unchanged, " //$NON-NLS-1$
            + "or nothing below is under test", Arrays.asList(urls), //$NON-NLS-1$
            Arrays.asList(onDisk.getStringList(REMOTE_SECTION, remote, key)));
    }

    /**
     * Renders a character as {@code U+XXXX}, so a failure names the invisible byte it was about.
     *
     * @param c the character
     * @return its code point in the {@code U+XXXX} form
     */
    private static String hex(char c)
    {
        return String.format("U+%04X", (int)c); //$NON-NLS-1$
    }

    /** Recursively deletes a temporary directory tree (best-effort test cleanup). */
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
