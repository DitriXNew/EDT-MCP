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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.utils.git.GitCommonDirectory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Covers {@link GitTool#storedRemoteRefusal}: the pre-flight that REFUSES a command which would
 * print or use a remote whose STORED credential cannot be masked (issue #314). The command carries
 * no secret here - it sits in {@code remote.<name>.url} / {@code remote.<name>.pushurl}, where ASCII
 * whitespace - or a {@code ?} / {@code #} in front of the {@code @} - ends the output redaction's
 * scan before that {@code @}, so what precedes it could not be masked at all. A control character is
 * refused alongside them for a different reason: it ends none of those scans, but it can never be
 * legitimate in an authority and must not travel verbatim into the response.
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

    /**
     * A second fake credential, planted in a remote's NAME rather than in its URL. Distinct from
     * {@link #SECRET} on purpose: a leak from the name and a leak from the value must be
     * distinguishable, or one assertion would cover for the other.
     */
    private static final String NAME_SECRET = "n4me-s3cr3t-token"; //$NON-NLS-1$

    private static final String REMOTE_SECTION = "remote"; //$NON-NLS-1$

    private static final String URL_KEY = "url"; //$NON-NLS-1$

    private static final String PUSHURL_KEY = "pushurl"; //$NON-NLS-1$

    private static final String ORIGIN = "origin"; //$NON-NLS-1$

    private static final String PUSH = "push"; //$NON-NLS-1$

    /** The file JGit reloads - and the one the fail-closed case corrupts. */
    private static final String CONFIG_FILE = "config"; //$NON-NLS-1$

    /**
     * Shortest exception message the log-line case will look for inside the logged text. A message of
     * one or two characters could occur there by coincidence and turn the assertion into noise.
     */
    private static final int MIN_TELLTALE_MESSAGE_CHARS = 8;

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

    /**
     * Linked worktrees opened by {@link #linkedWorktreeOf} - owned by the caller (they come from
     * {@code discoverFromDirectory}, not from EGit's cache), so they are closed here.
     */
    private final List<Repository> linkedRepositories = new ArrayList<>();

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
        for (Repository linked : linkedRepositories)
        {
            try
            {
                linked.close();
            }
            catch (RuntimeException e) // NOSONAR cleanup must never mask the failure under test
            {
                // Nothing to do: the directory is deleted below either way.
            }
        }
        linkedRepositories.clear();
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
    public void testASchemelessStoredCredentialIsRefused() throws Exception
    {
        // git's scp-like remote form. There is no 'scheme://' anywhere in it, so redactCredentialUrls
        // does not even look at the value - it masks a userinfo only inside a URL it recognises - and
        // 'git remote -v' prints the whole thing verbatim. Judging only what LOOKS like a URL leaves
        // this one out; asking instead what the redaction is ABLE to mask puts it in.
        for (String url : List.of("user:" + SECRET + SPACE + "ok@" + HOST + ":team/repo.git", //$NON-NLS-1$ //$NON-NLS-2$
            "user:" + SECRET + "@" + HOST + ":team/repo.git")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Repository repo = newRepository("git-stored-schemeless"); //$NON-NLS-1$
            storeRemoteUrls(repo, ORIGIN, URL_KEY, url);
            // Positive control: the redaction really does hand this value back untouched, which is
            // the whole reason it has to be refused rather than masked.
            assertEquals("fixture: a value with no scheme is not redactable at all", url, //$NON-NLS-1$
                GitTool.redactCredentialUrls(url));

            String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

            assertNotNull("a schemeless 'user:password@host:path' cannot be masked, so the command " //$NON-NLS-1$
                + "must be refused", refusal); //$NON-NLS-1$
            assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testASchemelessCredentialInARemoteNameIsRefused() throws Exception
    {
        // The same shape in the one other field 'remote -v' prints. The url stored beside it is
        // clean, so if the name is not judged by the very same predicate no refusal is built at all.
        String hostileName = "user:" + NAME_SECRET + SPACE + "ok@" + HOST; //$NON-NLS-1$ //$NON-NLS-2$
        Repository repo = newRepository("git-stored-schemeless-name"); //$NON-NLS-1$
        storeRemoteUrls(repo, hostileName, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fixture: JGit must return the credential-shaped name unchanged", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).contains(hostileName));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a NAME carrying a schemeless credential must be refused too", refusal); //$NON-NLS-1$
        assertFalse("...and the credential it carries must not be echoed back: " + refusal, //$NON-NLS-1$
            refusal.contains(NAME_SECRET));
        assertRefusalLeaksNothing(refusal);
        assertRefusalStatesTheFix(refusal);
    }

    @Test
    public void testAnScpRemoteWithoutACredentialIsNotRefused() throws Exception
    {
        // The half that decides whether the rule above is usable. 'git@github.com:owner/repo.git' is
        // git's DOCUMENTED ssh spelling - the very alternative this tool's guide recommends - and a
        // local path may carry an '@' in a directory name. Widen the schemeless rule to "contains an
        // '@'" and every one of these remotes is refused forever.
        Repository repo = newRepository("git-stored-scp-clean"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "git@github.com:acme/repo.git"); //$NON-NLS-1$
        storeRemoteUrls(repo, "upstream", URL_KEY, "alice@" + HOST + ":team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        storeRemoteUrls(repo, "local", URL_KEY, "C:\\repos\\my@project"); //$NON-NLS-1$ //$NON-NLS-2$
        storeRemoteUrls(repo, "mirror", URL_KEY, "/srv/git:mirrors/my@project"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("git's own ssh remote form is a LOGIN, not a credential to mask", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testAControlCharacterWithNoCredentialIsRefusedAndSaysSo() throws Exception
    {
        // The second thing the redaction cannot do: it masks credentials, it never REMOVES a byte.
        // Neither fixture carries a credential at all, so the credential rule alone would let both
        // through and the raw byte would ride into the response, the EDT log and the request history.
        String controlUrl = "https://exa\u001bmple.com/r.git"; //$NON-NLS-1$
        Repository byUrl = newRepository("git-stored-control-only-url"); //$NON-NLS-1$
        storeRemoteUrls(byUrl, ORIGIN, URL_KEY, controlUrl);
        // Positive control: the redaction leaves the byte exactly where it is - masking it is not
        // something it does, which is why this has to be a refusal.
        assertEquals("fixture: the redaction does not remove a control byte", controlUrl, //$NON-NLS-1$
            GitTool.redactCredentialUrls(controlUrl));

        String urlRefusal = GitTool.storedRemoteRefusal(byUrl, List.of(PUSH));

        assertNotNull("a raw control byte in a stored URL must be refused on its own", urlRefusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(urlRefusal, ORIGIN);
        assertRefusalLeaksNothing(urlRefusal);
        // ...and it says WHICH of the two flaws fired, or the operator greps the config for a
        // credential that is not there.
        assertTrue("the refusal must name the control character: " + urlRefusal, //$NON-NLS-1$
            urlRefusal.toLowerCase(Locale.ROOT).contains("control character")); //$NON-NLS-1$

        String hostileName = "ori\u001bgin"; //$NON-NLS-1$
        Repository byName = newRepository("git-stored-control-only-name"); //$NON-NLS-1$
        storeRemoteUrls(byName, hostileName, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fixture: JGit must return the control-bearing name unchanged", //$NON-NLS-1$
            byName.getConfig().getSubsections(REMOTE_SECTION).contains(hostileName));

        String nameRefusal = GitTool.storedRemoteRefusal(byName, List.of(PUSH));

        assertNotNull("...and so must one in the NAME - 'remote -v' prints that too", nameRefusal); //$NON-NLS-1$
        assertRefusalLeaksNothing(nameRefusal);
        assertRefusalStatesTheFix(nameRefusal);
        // The name marks no credential, so it is SHORTENED of its control byte rather than withheld:
        // withholding it would cost the operator the one field that says which entry to repair.
        assertTrue("a name that marks no credential must still be named: " + nameRefusal, //$NON-NLS-1$
            nameRefusal.contains("origin")); //$NON-NLS-1$
    }

    @Test
    public void testAQuestionMarkOrHashInsideTheUserinfoDoesNotHideTheCredential() throws Exception
    {
        // An RFC-shaped authority scan stops at the '?' or the '#', finds no '@' at all and would
        // let the remote through - and the redaction, whose userinfo scan bails at that same
        // character, would then mask what it takes for a query and print everything in front of it
        // verbatim. Not a claim about git's own parser: git ends the host portion at the first of
        // '/', '?' and '#' too and sends no credential for this shape at all. The scan has to run to
        // the first '/' because the REDACTION cannot cope, not because git would.
        //
        // The fixture carries NO whitespace on purpose. With a space in it the case would be refused
        // by the whitespace rule and say nothing at all about the delimiter - which is exactly how
        // this shape slipped through: the URL below was accepted until the delimiter was judged too.
        for (char delimiter : new char[] { '?', '#' })
        {
            Repository repo = newRepository("git-stored-userinfo-delimiter"); //$NON-NLS-1$
            String poisoned = "https://user:" + SECRET + delimiter + "x@" + HOST //$NON-NLS-1$ //$NON-NLS-2$
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
    public void testTheRemedyFitsAMultiValuedUrlToo() throws Exception
    {
        // What is pinned here is the message's WORDING, not the effect of the commands it offers:
        // like every case in this file it starts no git process (see the class comment), it reads
        // the refusal string and asserts which commands appear in it. Nothing below shows that any
        // of them clears anything - no test in this bundle runs 'remote remove' at all.
        //
        // Git's behaviour is the RATIONALE for that wording, cited rather than exercised: 'remote
        // set-url --add' leaves url multi-valued, and against a multi-valued url a plain
        // 'git remote set-url <name> <url>' answers "remote.<name>.url has multiple values" and
        // exits non-zero without touching the config. A message that named THAT command would
        // therefore leave the poisoned value in place and earn the next command this same refusal -
        // the endless retry the text exists to prevent. Hence the remedy is remove-and-re-add,
        // which clears the section whatever it holds, and this fixture is the shape that rules the
        // one-step alternative out.
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
    public void testARemoteNameThatIsItselfAnUnmaskableCredentialUrlIsRefused() throws Exception
    {
        // Git takes a URL as a subsection name, and 'remote -v' prints that name beside the URL. So
        // the name is a second place a credential can be stored - and this fixture puts it there
        // ALONE: the url stored for it is clean, so if the name is not judged no refusal is built at
        // all, the command runs, and the output redactor - whose scan ends at the whitespace before
        // the '@' - hands the secret back. Judging the values only cannot reach this.
        String hostileName = "https://user:" + NAME_SECRET + SPACE + "ok@" + HOST; //$NON-NLS-1$ //$NON-NLS-2$
        String cleanUrl = "https://" + HOST + "/team/repo.git"; //$NON-NLS-1$ //$NON-NLS-2$
        Repository repo = newRepository("git-stored-credential-name-only"); //$NON-NLS-1$
        storeRemoteUrls(repo, hostileName, URL_KEY, cleanUrl);
        // Positive control (a): production reads the names from getSubsections, so JGit has to hand
        // it this one VERBATIM - otherwise nothing here is under test.
        assertTrue("fixture: JGit must return the credential-shaped name unchanged", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).contains(hostileName));
        // Positive control (b): the URL beside it really is clean, so the NAME is the only thing that
        // can produce a refusal here.
        assertFalse("fixture: the stored URL must be maskable, or the name is not what is judged", //$NON-NLS-1$
            GitTool.unmaskableCredentialUrl(cleanUrl));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a NAME that is itself an un-maskable credential URL must be refused", refusal); //$NON-NLS-1$
        assertFalse("...and the credential it carries must not be echoed back: " + refusal, //$NON-NLS-1$
            refusal.contains(NAME_SECRET));
        assertRefusalLeaksNothing(refusal);
        assertRefusalStatesTheFix(refusal);
        assertTrue("a withheld name must say so, or the placeholder reads as the real name: " //$NON-NLS-1$
            + refusal, refusal.contains("withheld")); //$NON-NLS-1$
    }

    @Test
    public void testAPoisonedUrlLaterInARemoteNameIsRefusedToo() throws Exception
    {
        // A subsection name is not a URL, it is free text that may CONTAIN several. Judge only the
        // first 'scheme://' in it and this name passes on its harmless opening - while 'remote -v'
        // prints the whole of it and the redaction, which walks the output one 'scheme://' at a
        // time, hands the second one back through the whitespace it cannot scan past.
        String hostileName = "https://clean." + HOST + "/r https://user:" + NAME_SECRET + SPACE //$NON-NLS-1$ //$NON-NLS-2$
            + "ok@" + HOST; //$NON-NLS-1$
        Repository repo = newRepository("git-stored-second-url-in-name"); //$NON-NLS-1$
        storeRemoteUrls(repo, hostileName, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        // Positive control: the name's OPENING really is harmless, so this case can only pass by
        // judging past it - a check that stopped at the first URL would find nothing to refuse.
        assertFalse("fixture: the name must start with a URL that is maskable", //$NON-NLS-1$
            GitTool.unmaskableCredentialUrl(hostileName));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a poisoned URL later in the name must be refused as well", refusal); //$NON-NLS-1$
        assertFalse("...and its credential must not be echoed back: " + refusal, //$NON-NLS-1$
            refusal.contains(NAME_SECRET));
        assertRefusalLeaksNothing(refusal);
        assertRefusalStatesTheFix(refusal);
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
    public void testAnOrdinaryRemoteNameIsNotRefused() throws Exception
    {
        // The other half of judging the NAME: it must not turn everyday names into an outage. None of
        // these three reaches the predicate's authority for the same reason a real remote never does -
        // an ordinary name is not a URL at all - and the third one IS a URL whose credential the
        // redaction masks correctly, which is the boundary this refusal keeps: it fires on what
        // cannot be masked, not on every '@'. Widen the name check to "contains an '@'", or to "is
        // shaped like a URL", and this case turns red while every refusal case above stays green.
        Repository repo = newRepository("git-stored-ordinary-names"); //$NON-NLS-1$
        String cleanUrl = "https://" + HOST + "/team/repo.git"; //$NON-NLS-1$ //$NON-NLS-2$
        String maskableName = "https://user:" + NAME_SECRET + "@" + HOST; //$NON-NLS-1$ //$NON-NLS-2$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, cleanUrl);
        storeRemoteUrls(repo, CYRILLIC_REMOTE, URL_KEY, cleanUrl);
        storeRemoteUrls(repo, maskableName, URL_KEY, cleanUrl);
        // Positive control: all three names really are enumerated, or "nothing is refused" would be
        // true because there was nothing to judge.
        assertTrue("fixture: every name must be enumerated by getSubsections", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).containsAll(
                List.of(ORIGIN, CYRILLIC_REMOTE, maskableName)));

        assertNull("an ordinary remote name carries no credential the redaction would miss", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testASchemelessMarkerInARemoteNameIsNotRefused() throws Exception
    {
        // A '://' with no scheme in front of it is not a URL, and redactCredentialUrls skips exactly
        // such a marker (hasSchemeBefore), so nothing behind it can be printed as a mis-masked
        // credential. Judge it anyway and this name - which carries no credential at all - takes
        // remote/push/fetch/pull down with it.
        String oddName = "label ://alice?team@corp"; //$NON-NLS-1$
        Repository repo = newRepository("git-stored-schemeless-marker"); //$NON-NLS-1$
        storeRemoteUrls(repo, oddName, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        // Positive control: the very same text WOULD be refused with a scheme in front of it, so this
        // case turns on the scheme and not on the text being harmless in some other way.
        assertTrue("fixture: with a scheme in front, this text must be un-maskable", //$NON-NLS-1$
            GitTool.unmaskableCredentialUrl("https" + oddName.substring(oddName.indexOf("://")))); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("a '://' with no scheme in front of it is not a URL - it must not be refused", //$NON-NLS-1$
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

    @Test
    public void testARemoteWhoseNameIsItselfACredentialUrlIsNotEchoed() throws Exception
    {
        // The name is untrusted configuration text in the same sense the URL is - and git accepts a
        // URL as a subsection name: '[remote "https://user:s3cr3t@example.com"]' is enumerated by
        // 'git remote' like any other. Quoting such a name back would hand the caller the very thing
        // the refusal exists to withhold, in the one field the message still carries.
        String hostile = "https://user:" + NAME_SECRET + "@" + HOST; //$NON-NLS-1$ //$NON-NLS-2$
        // Positive control (a): the secret really IS in the name under test - an assertion that the
        // refusal does not contain it would otherwise pass on a fixture that never carried it.
        assertTrue("fixture: the name must carry the secret", hostile.contains(NAME_SECRET)); //$NON-NLS-1$
        Repository repo = newRepository("git-stored-credential-name"); //$NON-NLS-1$
        storeRemoteUrls(repo, hostile, URL_KEY, poisonedUrl(SPACE));
        // Positive control (b): production reads the names from getSubsections, so JGit has to hand
        // it this one VERBATIM - otherwise nothing here is under test.
        assertTrue("fixture: JGit must return the credential-shaped name unchanged", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).contains(hostile));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the remote's name has no bearing on WHETHER it is refused", refusal); //$NON-NLS-1$
        assertFalse("the credential in the NAME must not be echoed back: " + refusal, //$NON-NLS-1$
            refusal.contains(NAME_SECRET));
        // ...and the same bar every other case is held to: nothing of the URL, the host included -
        // which the name carried too.
        assertRefusalLeaksNothing(refusal);
        // ...and the message stays actionable: it still says what is wrong and how to repair it, and
        // it says the name was withheld rather than printing something that reads like one.
        assertRefusalStatesTheFix(refusal);
        assertTrue("a withheld name must say so, or the placeholder reads as the real name: " //$NON-NLS-1$
            + refusal, refusal.contains("withheld")); //$NON-NLS-1$
    }

    @Test
    public void testTheSuggestedCommandsCarryNoConfigSuppliedName() throws Exception
    {
        // The name is untrusted configuration text and git accepts characters in it that a shell
        // reads as syntax, so the refusal quotes it ONCE - to say which remote is at fault - and
        // spells every command with a literal '<name>' placeholder. An operator pasting a suggested
        // line into a terminal must not run something .git/config chose for them.
        String hostile = "or&i|gin"; //$NON-NLS-1$
        Repository repo = newRepository("git-stored-shell-name"); //$NON-NLS-1$
        storeRemoteUrls(repo, hostile, URL_KEY, poisonedUrl(SPACE));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the remote's name has no bearing on WHETHER it is refused", refusal); //$NON-NLS-1$
        // Positive control: the name IS quoted, or the operator cannot tell which remote to repair -
        // an assertion that merely counted zero would pass on a message that named nothing.
        assertTrue("the refusal must still name the remote: " + refusal, refusal.contains(hostile)); //$NON-NLS-1$
        assertEquals("the name may appear ONCE: no command the message offers may carry it: " //$NON-NLS-1$
            + refusal, 1, occurrencesOf(refusal, hostile));
        // ...and that once has to be the OPENING sentence, before any command. Counting alone would
        // pass a message that dropped the opening quote and interpolated the name into
        // 'git remote remove or&i|gin' instead - exactly the paste this case exists to prevent.
        assertTrue("the name must be quoted BEFORE the commands, not inside one: " + refusal, //$NON-NLS-1$
            refusal.indexOf(hostile) < refusal.indexOf("git remote remove")); //$NON-NLS-1$
        // ...and where a command needs the name, the literal placeholder has to stand there.
        assertTrue("a command that needs the name must spell it '<name>': " + refusal, //$NON-NLS-1$
            refusal.contains("git remote remove <name>")); //$NON-NLS-1$
        assertRefusalStatesTheFix(refusal);
    }

    // ==================== how far the merged configuration reaches ====================

    @Test
    public void testTheUserConfigurationTheCheckReadsIsGitsTwoFilePair() throws Exception
    {
        // What the guide promises about the check's reach: it reads the MERGED configuration, and the
        // USER half of that is git's two files - '~/.gitconfig' and '$XDG_CONFIG_HOME/git/config'
        // (default '~/.config/git/config'). JGit pairs them in a UserConfigFile whose BASE is the XDG
        // one; its own 'jgit/config' is a THIRD, JGit-only file, not a replacement for it. Read from
        // the live SystemReader - the same object FileRepository asks for its user config - so the
        // day that pairing goes away, this fails instead of the documentation quietly becoming false.
        // It pins the DEPENDENCY, not this bundle's code: what the check does with the merged
        // configuration is pinned by the cases above. (It reads the machine's own SystemReader, so a
        // host with no user home at all would have no user configuration to pair.)
        FileBasedConfig userConfig = SystemReader.getInstance().openUserConfig(null, FS.DETECTED);
        assertEquals("fixture: the outer user config file JGit opens is '~/.gitconfig'", //$NON-NLS-1$
            ".gitconfig", userConfig.getFile().getName()); //$NON-NLS-1$
        Config base = userConfig.getBaseConfig();
        assertTrue("the user configuration must be a CHAIN, or git's XDG file is not read at all", //$NON-NLS-1$
            base instanceof FileBasedConfig);
        File xdgFile = ((FileBasedConfig)base).getFile();
        assertEquals("...and the file behind it is git's own, not JGit's 'jgit/config': " + xdgFile, //$NON-NLS-1$
            "config", xdgFile.getName()); //$NON-NLS-1$
        assertEquals("...under the 'git' directory: " + xdgFile, "git", //$NON-NLS-1$ //$NON-NLS-2$
            xdgFile.getParentFile().getName());

        // ...and a remote defined in a BASE configuration really is enumerated by the merged one -
        // the walk storedRemoteRefusal relies on when it calls getSubsections.
        Config inherited = new Config();
        inherited.setString(REMOTE_SECTION, "inherited-remote", URL_KEY, //$NON-NLS-1$
            "https://" + HOST + "/r.git"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a remote defined in a base configuration must be enumerated by the merged one", //$NON-NLS-1$
            new Config(inherited).getSubsections(REMOTE_SECTION).contains("inherited-remote")); //$NON-NLS-1$
    }

    // ==================== read from disk, not from JGit's cache ====================

    @Test
    public void testAConfigEditedBehindJGitsCacheIsStillJudged() throws Exception
    {
        // The Repository is not ours and outlives one call - EGit hands out a cached,
        // reference-counted instance - and JGit refreshes its configuration only when its
        // FileSnapshot NOTICES a change: size, file key, or mtime. An in-place edit that keeps all
        // three is invisible to it, while the native git started afterwards re-reads the file
        // regardless. Without a forced re-read the check approves yesterday's clean remote and
        // 'remote -v' prints today's credential.
        //
        // The fixture reproduces exactly that: same byte count, same mtime, different content.
        Repository repo = newRepository("git-stored-cache-bypass"); //$NON-NLS-1$
        File configFile = new File(repo.getDirectory(), CONFIG_FILE);
        String poisoned = poisonedUrl(SPACE);
        // Padded to the poisoned value's length, so the FILE keeps its size across the edit.
        String clean = "https://" + HOST + "/team/" //$NON-NLS-1$ //$NON-NLS-2$
            + "c".repeat(poisoned.length() - ("https://" + HOST + "/team/.git").length()) //$NON-NLS-1$
            + ".git"; //$NON-NLS-1$
        assertEquals("fixture: the two URLs must be the same length, or the file size changes and " //$NON-NLS-1$
            + "JGit notices the edit for a reason that has nothing to do with this case", //$NON-NLS-1$
            poisoned.length(), clean.length());
        String before = configText(clean);
        String after = configText(poisoned);
        assertEquals("fixture: and so must the two config FILES", before.length(), after.length()); //$NON-NLS-1$

        // Written far enough in the past that the snapshot taken below cannot be "racily clean" -
        // otherwise JGit re-reads out of caution and the case would prove nothing.
        Files.write(configFile.toPath(), before.getBytes(StandardCharsets.UTF_8));
        configFile.setLastModified(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1));
        long mtime = configFile.lastModified();
        assertTrue("fixture: the clean remote must be visible before the edit", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).contains(ORIGIN));

        // The edit JGit cannot see: same length, and the mtime put back exactly as it was.
        Files.write(configFile.toPath(), after.getBytes(StandardCharsets.UTF_8));
        assertTrue("fixture: the mtime must be restorable, or the edit is visible for the wrong " //$NON-NLS-1$
            + "reason", configFile.setLastModified(mtime)); //$NON-NLS-1$
        assertEquals("fixture: the mtime really has to be back where it was", mtime, //$NON-NLS-1$
            configFile.lastModified());
        // Positive control, and the whole premise of the case: JGit is STILL serving the old value.
        // Without this the test would pass on a JGit that noticed the edit by itself, proving
        // nothing about the forced re-read.
        assertEquals("fixture: JGit must NOT notice this edit on its own - if it does, this case " //$NON-NLS-1$
            + "is not about a stale cache at all", clean, //$NON-NLS-1$
            repo.getConfig().getString(REMOTE_SECTION, ORIGIN, URL_KEY));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the check must read the configuration from DISK: what git will print is the " //$NON-NLS-1$
            + "poisoned value, not the one JGit has cached", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
        assertRefusalLeaksNothing(refusal);
    }

    /**
     * A minimal config file text carrying one remote - written directly, because this case is about
     * what JGit does NOT see, so the write may not go through JGit.
     *
     * @param url the value to store for {@code remote.origin.url}
     * @return the file content
     */
    private static String configText(String url)
    {
        return "[core]\n\trepositoryformatversion = 0\n[remote \"" + ORIGIN + "\"]\n\turl = " //$NON-NLS-1$
            + url + "\n"; //$NON-NLS-1$
    }

    @Test
    public void testARemoteLivingOnlyInTheWorktreeConfigIsJudged() throws Exception
    {
        // With 'extensions.worktreeConfig = true' git reads <git dir>/config.worktree after
        // config, and a remote can live there and nowhere else. JGit 6.8 does not know the file at
        // all - neither 'config.worktree' nor 'worktreeConfig' occurs in its jar - so
        // repo.getConfig() lists only what .git/config declares, while 'git remote -v' prints both.
        Repository repo = newRepository("git-stored-worktree-config"); //$NON-NLS-1$
        File gitDir = repo.getDirectory();
        Files.write(new File(gitDir, CONFIG_FILE).toPath(),
            ("[core]\n\trepositoryformatversion = 1\n[extensions]\n\tworktreeConfig = true\n" //$NON-NLS-1$
                + "[remote \"" + ORIGIN + "\"]\n\turl = https://" + HOST + "/team/clean.git\n") //$NON-NLS-1$ //$NON-NLS-2$
                    .getBytes(StandardCharsets.UTF_8));
        String poisonedRemote = "worktree-remote"; //$NON-NLS-1$
        Files.write(new File(gitDir, "config.worktree").toPath(), //$NON-NLS-1$
            ("[remote \"" + poisonedRemote + "\"]\n\turl = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.UTF_8));

        // Positive control (a): the extension really is on, so this is the shape git reads that way.
        assertTrue("fixture: extensions.worktreeConfig must be set", //$NON-NLS-1$
            repo.getConfig().getBoolean("extensions", "worktreeConfig", false)); //$NON-NLS-1$ //$NON-NLS-2$
        // Positive control (b), and the premise of the whole case: JGit is BLIND to that file. If it
        // ever learns to read it, this assertion fails and the case stops claiming something false.
        assertFalse("fixture: JGit must not see the worktree remote by itself - if it does, this " //$NON-NLS-1$
            + "case is not about the gap it was written for", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).contains(poisonedRemote));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a remote that lives only in config.worktree is printed by git and must be " //$NON-NLS-1$
            + "judged like any other", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, poisonedRemote);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testTheWorktreeConfigIsReadAtTheDEFAULTFormatVersionToo() throws Exception
    {
        // MEASURED, not read off the documentation. 'extensions.*' is a repository-FORMAT setting
        // and the manual ties extensions to format version 1, so this case first asserted the
        // opposite - that version 0 keeps the file unread. Git disagrees: with the switch in
        // .git/config and 'repositoryformatversion = 0' - the default every ordinary repository
        // carries - 'git remote -v' prints the remote from config.worktree all the same
        // (git 2.35.1). A version gate here would not be a second belt, it would be a hole in
        // exactly the repositories most likely to exist.
        Repository repo = newRepository("git-stored-worktree-version-zero"); //$NON-NLS-1$
        File gitDir = repo.getDirectory();
        Files.write(new File(gitDir, CONFIG_FILE).toPath(),
            ("[core]\n\trepositoryformatversion = 0\n[extensions]\n\tworktreeConfig = true\n" //$NON-NLS-1$
                + "[remote \"" + ORIGIN + "\"]\n\turl = https://" + HOST + "/team/clean.git\n") //$NON-NLS-1$ //$NON-NLS-2$
                    .getBytes(StandardCharsets.UTF_8));
        String poisonedRemote = "wt-at-version-zero"; //$NON-NLS-1$
        Files.write(new File(gitDir, "config.worktree").toPath(), //$NON-NLS-1$
            ("[remote \"" + poisonedRemote + "\"]\n\turl = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
        // Positive control (a): the version really is the default one, or this case is not about it.
        assertEquals("fixture: the repository must carry format version 0", 0, //$NON-NLS-1$
            repo.getConfig().getInt("core", "repositoryformatversion", -1)); //$NON-NLS-1$ //$NON-NLS-2$
        // Positive control (b): JGit is still blind to the file, so the refusal can only come from
        // this check reading it.
        assertFalse("fixture: JGit must not see the worktree remote by itself", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).contains(poisonedRemote));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("git reads config.worktree at format version 0 as well, so this check must " //$NON-NLS-1$
            + "read it too", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, poisonedRemote);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testTheWorktreeConfigIsNotReadWhenTheExtensionIsOff() throws Exception
    {
        // The other half: without the extension git ignores the file, so reading it would refuse a
        // repository git is perfectly happy with. A leftover config.worktree is exactly what an
        // abandoned experiment leaves behind.
        Repository repo = newRepository("git-stored-worktree-config-off"); //$NON-NLS-1$
        File gitDir = repo.getDirectory();
        storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$
        Files.write(new File(gitDir, "config.worktree").toPath(), //$NON-NLS-1$
            ("[remote \"ignored\"]\n\turl = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
        assertFalse("fixture: the extension must be OFF for this half", //$NON-NLS-1$
            repo.getConfig().getBoolean("extensions", "worktreeConfig", false)); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("git does not read config.worktree without the extension, so neither may this " //$NON-NLS-1$
            + "check", GitTool.storedRemoteRefusal(repo, List.of(PUSH))); //$NON-NLS-1$
    }

    @Test
    public void testARemoteGROUPIsJudgedThoughItHasNoRemoteSection() throws Exception
    {
        // '[remotes] grp = <url>' needs no [remote] subsection at all, and 'git fetch grp' prints
        // one 'Fetching <value>' line per member. Measured on git 2.35.1: the line came out as
        // 'Fetching https://user:s3cr3t' - the credential verbatim, cut at the space, exactly the
        // shape the redaction cannot mask. Enumerating [remote] subsections alone never sees it.
        Repository repo = newRepository("git-stored-remote-group"); //$NON-NLS-1$
        StoredConfig config = repo.getConfig();
        config.setString("remotes", null, "mygroup", poisonedUrl(SPACE)); //$NON-NLS-1$ //$NON-NLS-2$
        config.save();
        // Positive control: there is NO [remote] subsection here, so the refusal cannot come from
        // the enumeration this check started life with.
        assertTrue("fixture: no remote subsection may exist, or this case proves nothing", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).isEmpty());

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a remote group git would print must be judged too", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name the group to fix: " + refusal, //$NON-NLS-1$
            refusal.contains("mygroup")); //$NON-NLS-1$
        assertRefusalIsActionable(refusal);
        // ...and the remedy has to be the one that WORKS here. A group is a plain config key:
        // 'git remote remove' leaves 'remotes.<group>' exactly where it was - measured - so advising
        // it would send an unattended caller round the same refusal for ever.
        assertTrue("a group must be cleared by unsetting its KEY: " + refusal, //$NON-NLS-1$
            refusal.contains("--unset-all remotes.<name>")); //$NON-NLS-1$
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testGitsLegacyRemoteFilesAreJudged() throws Exception
    {
        // '$GIT_DIR/remotes/<name>' and '$GIT_DIR/branches/<name>' are not configuration at all,
        // and JGit's config never mentions them - but 'git remote get-url <name>' prints what
        // stands in them. Measured on git 2.35.1 for both directories: the full URL came back with
        // the credential and the space in it.
        for (String directory : List.of("remotes", "branches")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Repository repo = newRepository("git-stored-legacy-" + directory); //$NON-NLS-1$
            storeRemoteUrls(repo, ORIGIN, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$
            File dir = new File(repo.getDirectory(), directory);
            // JGit's init() already creates some of these, so "exists afterwards" is the condition,
            // not "was created by this call".
            assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
            String entry = "oldstyle"; //$NON-NLS-1$
            Files.write(new File(dir, entry).toPath(),
                ("URL: " + poisonedUrl(SPACE) + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            // Positive control: the configuration itself is clean, so nothing but the legacy file
            // can produce a refusal.
            assertNull("fixture: the stored config must be clean", //$NON-NLS-1$
                GitTool.storedTextFlaw("https://" + HOST + "/team/repo.git")); //$NON-NLS-1$

            String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

            assertNotNull(directory + "/<name> is printed by 'git remote get-url' and must be " //$NON-NLS-1$
                + "judged", refusal); //$NON-NLS-1$
            assertTrue("the refusal must name the file to fix: " + refusal, //$NON-NLS-1$
                refusal.contains(entry));
            assertRefusalIsActionable(refusal);
            // A legacy file is not configuration at all, so the remedy is to delete it - 'git remote
            // remove' does not know it exists. The remedy has to say WHICH file without handing out
            // a path that does not exist: in a linked worktree '.git' is a FILE and these
            // directories live in the shared repository, so 'rev-parse --git-path' is what names
            // them in every layout. Both halves are pinned, because dropping either one leaves the
            // caller in the retry loop this text exists to prevent.
            assertTrue("a legacy file must be cleared by deleting it: " + refusal, //$NON-NLS-1$
                refusal.contains("Delete the file itself")); //$NON-NLS-1$
            assertTrue("...and the remedy must locate it in EVERY layout, so it points at " //$NON-NLS-1$
                + "'rev-parse --git-path' rather than at a bare '.git/...' path that a linked " //$NON-NLS-1$
                + "worktree does not have: " + refusal, //$NON-NLS-1$
                refusal.contains("git rev-parse --git-path " + directory)); //$NON-NLS-1$
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testACleanLegacyRemoteFileIsNotRefused() throws Exception
    {
        // The other side: these files are ordinary in old repositories, so reading them may not turn
        // every one of them into an outage.
        Repository repo = newRepository("git-stored-legacy-clean"); //$NON-NLS-1$
        File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
        Files.write(new File(dir, "upstream").toPath(), //$NON-NLS-1$
            ("URL: git@github.com:acme/repo.git\nPush: refs/heads/main\n") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));

        assertNull("a legacy file carrying git's documented ssh remote is not a credential", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testALegacyLineKeepsItsBytesUntilItIsJudged() throws Exception
    {
        // The value was trimmed before judging, and String.trim() removes everything up to U+0020 -
        // that is, exactly the control bytes this check exists to catch. git prints the line as it
        // stands, so the byte has to survive until storedTextFlaw has seen it.
        Repository repo = newRepository("git-stored-legacy-control"); //$NON-NLS-1$
        File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
        Files.write(new File(dir, "withcontrol").toPath(), //$NON-NLS-1$
            ("URL: https://" + HOST + "/team/repo.git" + "\u0001" + "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.UTF_8));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a control byte at the end of a legacy line is printed by git and must be " //$NON-NLS-1$
            + "judged, not trimmed away", refusal); //$NON-NLS-1$
        assertRefusalIsActionable(refusal);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testALegacyLineWithCRLFIsNotRefusedForItsLineEnding() throws Exception
    {
        // The other side of not trimming: a CR is a control byte too, and a legacy file written on
        // Windows ends every line with one. Refusing those would turn the fix above into an outage
        // for every CRLF repository, so the line TERMINATOR is dropped and nothing else is.
        Repository repo = newRepository("git-stored-legacy-crlf"); //$NON-NLS-1$
        File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
        Files.write(new File(dir, "crlf").toPath(), //$NON-NLS-1$
            ("URL: https://" + HOST + "/team/repo.git\r\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertNull("a CRLF line ending is not a credential and not a stray control byte", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testTheHeadOfALegacyBranchesFileIsJudgedAsAREF() throws Exception
    {
        // The documented format of a branches/ file is '<url>#<head>', and git turns the tail into a
        // REF, not a URL fragment: measured, 'https://example.com/r.git#sec:ret@x' produced
        // "fatal: invalid refspec 'refs/heads/sec:ret@x:refs/heads/bh'" - the text printed in a
        // refspec with nothing masked. Judging it as a fragment would hand it to the redaction,
        // which never sees a URL there at all.
        //
        // This is NOT the query/fragment boundary of a URL. That one is about a fragment the
        // redaction DOES mask, it is an open question with the author, and it stays where it is.
        Repository repo = newRepository("git-stored-legacy-head"); //$NON-NLS-1$
        File dir = new File(repo.getDirectory(), "branches"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
        Files.write(new File(dir, "withhead").toPath(),
            ("https://" + HOST + "/team/repo.git#user:" + SECRET + SPACE + "ok@x\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.UTF_8));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the head of a branches/ file lands in a refspec git prints - judge it", //$NON-NLS-1$
            refusal);
        assertRefusalIsActionable(refusal);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testAGroupInheritedFromABaseConfigurationIsEnumerated() throws Exception
    {
        // getNames(section) is NOT recursive - it stops at the top link of the chain. With the
        // config.worktree layer on top, a group declared in .git/config sits one link down, and git
        // reads it all the same. The two-argument call would walk straight past it.
        Repository repo = newRepository("git-stored-group-inherited"); //$NON-NLS-1$
        File gitDir = repo.getDirectory();
        Files.write(new File(gitDir, CONFIG_FILE).toPath(),
            ("[core]\n\trepositoryformatversion = 1\n[extensions]\n\tworktreeConfig = true\n" //$NON-NLS-1$
                + "[remotes]\n\tinherited = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$ //$NON-NLS-2$
                    .getBytes(StandardCharsets.UTF_8));
        // The worktree layer exists and is EMPTY, so it is the top link and the group is below it.
        Files.write(new File(gitDir, "config.worktree").toPath(), //$NON-NLS-1$
            "[core]\n\tbare = false\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a group one link down the chain is read by git and must be judged", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name the group: " + refusal, refusal.contains("inherited")); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testTheConfigRemedyNamesTheWorktreeScopeToo() throws Exception
    {
        // Self-audit, same class as the group remedy: measured, 'git remote remove' against a remote
        // that lives in config.worktree answers "error: Could not remove config section
        // 'remote.<name>'" and the remote is STILL listed afterwards. A message that named only that
        // command would send an unattended caller round the same refusal for ever.
        Repository repo = newRepository("git-stored-worktree-remedy"); //$NON-NLS-1$
        storeRemoteUrls(repo, ORIGIN, URL_KEY, poisonedUrl(SPACE));

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("the poisoned remote must be refused", refusal); //$NON-NLS-1$
        assertRefusalStatesTheFix(refusal);
        assertTrue("...and the remedy must reach a remote that lives in config.worktree: " //$NON-NLS-1$
            + refusal, refusal.contains("--worktree --remove-section")); //$NON-NLS-1$
    }

    @Test
    public void testABranchesFileKeepsItsWholeLineAsTheValue() throws Exception
    {
        // The 'URL: ' key strip belongs to the remotes/ format, where a line is 'key: value'. A
        // branches/ file has no keys - its line IS the value - and taking a prefix off there removes
        // text git prints. Worse: what gets removed ends in a colon, and a colon before an '@' is
        // the password marker itself, so the strip deleted the one thing that condemns this value.
        //
        // Measured: 'git remote get-url bad' on this file prints 'user: sec ret@example.com:path'
        // whole, credential and space and all.
        Repository repo = newRepository("git-stored-branches-verbatim"); //$NON-NLS-1$
        File dir = new File(repo.getDirectory(), "branches"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
        String value = "user:" + SPACE + "sec" + SPACE + "ret@" + HOST + ":path"; //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(dir, "bad").toPath(), //$NON-NLS-1$
            (value + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        // Positive control: with the prefix taken off - the remotes/ reading - the marker is gone and
        // the remainder is harmless, which is exactly how this slipped through.
        assertNull("fixture: the stripped remainder must look clean, or the case proves nothing", //$NON-NLS-1$
            GitTool.storedTextFlaw("sec" + SPACE + "ret@" + HOST + ":path")); //$NON-NLS-1$ //$NON-NLS-2$

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("a branches/ line is the value whole - the password marker in it must be " //$NON-NLS-1$
            + "seen", refusal); //$NON-NLS-1$
        assertRefusalIsActionable(refusal);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testARemotesFileStillHasItsKeyPrefixTaken() throws Exception
    {
        // The other half of the split: remotes/ really is 'key: value', and judging the raw line
        // there would refuse every legacy file ever written, because 'URL:' ends in a colon.
        Repository repo = newRepository("git-stored-remotes-prefix"); //$NON-NLS-1$
        File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
        Files.write(new File(dir, "upstream").toPath(),
            ("URL: git@github.com:acme/repo.git\nPush: refs/heads/main\n") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));

        assertNull("a remotes/ file carrying git's documented ssh remote is not a credential", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
    }

    @Test
    public void testEverySpellingGitAcceptsAfterALegacyKeyIsNotRefused() throws Exception
    {
        // A FALSE REFUSAL, and those matter more here than a missed leak: a leak leaves things as
        // they were, a refusal breaks a repository that worked. Demanding 'URL: ' with exactly one
        // space left the key sitting on 'URL:git@github.com:acme/repo.git', and the colon of the KEY
        // then read as the password marker in front of the '@'.
        //
        // The spellings below are the ones git was measured to accept - key, colon, then any run of
        // whitespace or none.
        String[] healthy = {
            "URL: git@github.com:acme/repo.git", //$NON-NLS-1$
            "URL:git@github.com:acme/repo.git", //$NON-NLS-1$
            "URL:\tgit@github.com:acme/repo.git", //$NON-NLS-1$
            "URL:   git@github.com:acme/repo.git", //$NON-NLS-1$
            "Push:refs/heads/main", //$NON-NLS-1$
        };
        for (String line : healthy)
        {
            Repository repo = newRepository("git-stored-legacy-spelling"); //$NON-NLS-1$
            File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
            assertTrue("fixture: the legacy directory must exist", //$NON-NLS-1$
                dir.mkdirs() || dir.isDirectory());
            Files.write(new File(dir, "upstream").toPath(),
                (line + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

            assertNull("git accepts this spelling and it carries no credential, so it must not be " //$NON-NLS-1$
                + "refused: " + line, GitTool.storedRemoteRefusal(repo, List.of(PUSH))); //$NON-NLS-1$
        }
    }

    @Test
    public void testAPoisonedLegacyValueIsStillRefusedWhateverTheIndent() throws Exception
    {
        // The other side: widening the indent may not open a way past the check. Same spellings,
        // this time carrying the credential the refusal exists for.
        String[] poisoned = {
            "URL: user:s3cr3t" + SPACE + "ok@bad.example/r.git", //$NON-NLS-1$ //$NON-NLS-2$
            "URL:user:s3cr3t" + SPACE + "ok@bad.example/r.git", //$NON-NLS-1$ //$NON-NLS-2$
            "URL:\tuser:s3cr3t" + SPACE + "ok@bad.example/r.git", //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String line : poisoned)
        {
            Repository repo = newRepository("git-stored-legacy-poisoned"); //$NON-NLS-1$
            File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
            assertTrue("fixture: the legacy directory must exist", //$NON-NLS-1$
                dir.mkdirs() || dir.isDirectory());
            Files.write(new File(dir, "poisoned").toPath(),
                (line + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

            String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

            assertNotNull("the indent may not open a way past the check: " + line, refusal); //$NON-NLS-1$
            assertRefusalIsActionable(refusal);
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testALineGitDoesNotRecogniseIsNotJudged() throws Exception
    {
        // git reads only 'URL:' / 'Push:' / 'Pull:', case and position included - measured: 'url:'
        // in lower case yields no address at all, so nothing from such a line is ever printed.
        // Judging it anyway would only invent refusals out of text git ignores.
        Repository repo = newRepository("git-stored-legacy-unknown-key"); //$NON-NLS-1$
        File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", dir.mkdirs() || dir.isDirectory()); //$NON-NLS-1$
        Files.write(new File(dir, "ignored").toPath(),
            ("url: user:s3cr3t" + SPACE + "ok@bad.example/r.git\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.UTF_8));

        assertNull("git prints nothing from a line it does not recognise, so neither may this " //$NON-NLS-1$
            + "check refuse for it", GitTool.storedRemoteRefusal(repo, List.of(PUSH))); //$NON-NLS-1$
    }

    @Test
    public void testOnlyWhatGitSkipsAfterALegacyKeyIsSkipped() throws Exception
    {
        // Measured byte by byte, because "whitespace" is not one set here. git CONSUMES a space, a
        // tab, runs and mixtures of them, and a carriage return - the value comes back clean. It
        // does NOT consume a vertical tab or a form feed: 'URL:<VT>https://host/r.git' comes out of
        // 'git remote get-url' with the byte still on it.
        //
        // So skipping "any whitespace" deleted exactly the control byte this check refuses on, and
        // handed back a value git prints with it. Only what git skips is skipped now.
        for (String indent : List.of(" ", "\t", "  ", " \t", "\r", "")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Repository repo = newRepository("git-stored-legacy-indent"); //$NON-NLS-1$
            File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
            assertTrue("fixture: the legacy directory must exist", //$NON-NLS-1$
                dir.mkdirs() || dir.isDirectory());
            Files.write(new File(dir, "upstream").toPath(),
                ("URL:" + indent + "git@github.com:acme/repo.git\n") //$NON-NLS-1$
                    .getBytes(StandardCharsets.UTF_8));

            assertNull("git skips this indent, so the value behind it is clean and must not be " //$NON-NLS-1$
                + "refused", GitTool.storedRemoteRefusal(repo, List.of(PUSH))); //$NON-NLS-1$
        }

        // ...and the two git does NOT skip stay on the value, where they are exactly what a control
        // byte refusal is for.
        for (char kept : new char[]{0x0B, 0x0C})
        {
            Repository repo = newRepository("git-stored-legacy-kept"); //$NON-NLS-1$
            File dir = new File(repo.getDirectory(), "remotes"); //$NON-NLS-1$
            assertTrue("fixture: the legacy directory must exist", //$NON-NLS-1$
                dir.mkdirs() || dir.isDirectory());
            Files.write(new File(dir, "upstream").toPath(),
                ("URL:" + kept + "https://" + HOST + "/team/repo.git\n") //$NON-NLS-1$ //$NON-NLS-2$
                    .getBytes(StandardCharsets.UTF_8));

            String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

            assertNotNull(hex(kept) + " is printed by git, not skipped - it must be judged", //$NON-NLS-1$
                refusal);
            assertRefusalIsActionable(refusal);
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testGitsDottedRemoteSectionIsJudgedToo() throws Exception
    {
        // git's other spelling of a remote: '[remote.origin]' with a dot instead of a subsection.
        // Measured - native git prints it in 'remote -v' like any other remote, credential and all -
        // while JGit reports it as a SECTION called 'remote.origin' and getSubsections("remote")
        // returns NOTHING, so the walk over subsections never saw it.
        Repository repo = newRepository("git-stored-dotted-section"); //$NON-NLS-1$
        File gitDir = repo.getDirectory();
        Files.write(new File(gitDir, CONFIG_FILE).toPath(),
            ("[core]\n\trepositoryformatversion = 0\n[remote.origin]\n\turl = " //$NON-NLS-1$
                + poisonedUrl(SPACE) + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        // Positive control, and the whole premise: JGit really does hand this back as a section and
        // not as a subsection, so nothing but the new walk can produce a refusal here.
        assertTrue("fixture: JGit must report it as a SECTION", //$NON-NLS-1$
            repo.getConfig().getSections().contains("remote.origin")); //$NON-NLS-1$
        assertTrue("fixture: ...and NOT as a subsection of 'remote'", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).isEmpty());

        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));

        assertNotNull("git prints a dotted remote like any other, so it must be judged", refusal); //$NON-NLS-1$
        assertRefusalNamesTheRemoteAndTheFix(refusal, ORIGIN);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testACleanDottedRemoteSectionIsNotRefused() throws Exception
    {
        // The other side: the dotted spelling is legal and ordinary, so reading it may not turn
        // every repository that uses it into an outage.
        Repository repo = newRepository("git-stored-dotted-clean"); //$NON-NLS-1$
        Files.write(new File(repo.getDirectory(), CONFIG_FILE).toPath(),
            ("[core]\n\trepositoryformatversion = 0\n[remote.origin]\n" //$NON-NLS-1$
                + "\turl = git@github.com:acme/repo.git\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertNull("a dotted remote carrying git's documented ssh form is not a credential", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
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

    @Test
    public void testTheFailClosedPathLogsNoConfigurationContent() throws Exception
    {
        // The refusal says nothing (the case above), but this path also LOGS, and the EDT error log
        // is permanent - so a throwable handed to it would move the leak rather than close it.
        // JGit reports an '[include]' entry whose key is not 'path' as "Invalid line in config file:
        // <ConfigLine>", and ConfigLine renders 'section.name=VALUE': the exception carries a
        // configuration value verbatim, and here that value is the credential.
        Repository repo = newRepository("git-stored-log-leak"); //$NON-NLS-1$
        assertTrue("fixture: a fresh repository has no remotes", //$NON-NLS-1$
            repo.getConfig().getSubsections(REMOTE_SECTION).isEmpty());
        String credentialUrl = "https://user:" + SECRET + "@" + HOST + "/r.git"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String broken = "[remote \"" + ORIGIN + "\"]\n\turl = " + credentialUrl //$NON-NLS-1$ //$NON-NLS-2$
            + "\n[include]\n\tnotpath = " + credentialUrl + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

        Throwable thrown = configReadFailure(repo, broken);

        // Positive control: with no credential inside JGit's own exception there would be nothing
        // for the log line to withhold, and this case would pass on an empty premise.
        String reported = causeChainMessages(thrown);
        assertTrue("fixture: JGit's exception must really quote the credential: " + reported, //$NON-NLS-1$
            reported.contains(SECRET));

        String logged = GitTool.configReadFailureLog(thrown);

        assertFalse("the log line must not carry the credential: " + logged, //$NON-NLS-1$
            logged.contains(SECRET));
        assertFalse("nor the host, nor any other configuration content: " + logged, //$NON-NLS-1$
            logged.contains(HOST));
        // And not by luck of WHICH link happens to quote it: no message from the chain may be
        // embedded at all. Asserting the credential alone would stay green on a log line that
        // rendered the outermost exception, whose own message names the file rather than the value -
        // and it is the same rendering that would carry the cause along in production.
        for (Throwable link = thrown; link != null; link = link.getCause())
        {
            String message = link.getMessage();
            if (message != null && message.length() >= MIN_TELLTALE_MESSAGE_CHARS)
            {
                assertFalse("no exception message may reach the log line: " + logged, //$NON-NLS-1$
                    logged.contains(message));
            }
        }
        // ...and it must still be a usable report: the exception TYPE names what failed, and a type
        // name can carry no configuration.
        assertTrue("the log line must name what failed: " + logged, //$NON-NLS-1$
            logged.contains(thrown.getClass().getName()));

        // ...while the caller still gets the generic refusal, from the same unreadable state.
        String refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));
        assertNotNull("a configuration that cannot be read must still be refused", refusal); //$NON-NLS-1$
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testTheFailClosedPathAttachesNoThrowableToTheEdtLog() throws Exception
    {
        // The case above pins what configReadFailureLog RENDERS; this one pins what the fail-closed
        // branch actually HANDS to the log. They are different claims: 'logError(sanitized, e)' would
        // keep every assertion above green while Eclipse wrote the whole cause chain - JGit's
        // exception among it - into a permanent file. So the plug-in's own log is listened to while
        // the production path runs, and the recorded Status is read back.
        Bundle bundle = FrameworkUtil.getBundle(GitTool.class);
        assertNotNull("this case can only observe the log from inside OSGi; without the bundle it " //$NON-NLS-1$
            + "would 'pass' by seeing nothing at all", bundle); //$NON-NLS-1$
        ILog log = Platform.getLog(bundle);
        List<IStatus> recorded = new ArrayList<>();
        ILogListener listener = (status, plugin) -> recorded.add(status);
        String refusal;
        log.addLogListener(listener);
        try
        {
            Repository repo = newRepository("git-stored-log-status"); //$NON-NLS-1$
            String credentialUrl = "https://user:" + SECRET + "@" + HOST + "/r.git"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            configReadFailure(repo, "[include]\n\tnotpath = " + credentialUrl + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            refusal = GitTool.storedRemoteRefusal(repo, List.of(PUSH));
        }
        finally
        {
            log.removeLogListener(listener);
        }

        assertNotNull("a configuration that cannot be read must be refused", refusal); //$NON-NLS-1$
        // Positive control: a listener that recorded nothing would make this case pass without ever
        // having looked at a log entry.
        assertFalse("the fail-closed branch must really log, or nothing here was observed", //$NON-NLS-1$
            recorded.isEmpty());
        for (IStatus status : recorded)
        {
            assertNull("no throwable may be attached - Eclipse writes its whole cause chain, and " //$NON-NLS-1$
                + "JGit puts configuration text in it: " + status.getMessage(), //$NON-NLS-1$
                status.getException());
            assertFalse("nor may the credential reach the message: " + status.getMessage(), //$NON-NLS-1$
                status.getMessage().contains(SECRET));
        }
    }

    // ==================== a LINKED worktree: the shared repository is what git reads ====================

    // Inside a linked worktree ('git worktree add') git reads its configuration and both legacy
    // remote directories from the SHARED repository the worktree was added to, never from the
    // worktree's own git directory - measured on git 2.35.1, one probe at a time:
    //
    //   $GIT_COMMON_DIR/remotes/legacy  -> 'git remote get-url legacy' prints it, credential and all
    //   $GIT_DIR/remotes/wtonly         -> 'error: No such remote' - IGNORED
    //   --git-path config / remotes / branches   -> all resolve into the shared directory
    //   --git-path config.worktree               -> stays in the worktree's own git directory
    //
    // JGit 6.8 knows none of it: 'commondir' appears nowhere in its sources and FileRepository reads
    // '<git dir>/config', which does not exist there - so this check used to enumerate no remotes at
    // all and approve because it had found nothing, which is indistinguishable from having looked.
    //
    // Both halves of that blindness - the configuration and the legacy files - are closed together
    // here, because they had one cause. The fixtures below are built BY HAND rather than by shelling
    // out to 'git worktree add': it keeps these unit tests free of a git executable, and the layout
    // is the one measured above.

    @Test
    public void testARemoteInTheSHAREDConfigIsJudgedFromALinkedWorktree() throws Exception
    {
        // The heart of it: every remote of every ordinary clone lives in the shared config, so a
        // check that cannot see that file sees nothing whatsoever in a linked worktree.
        Repository shared = newRepository("git-stored-linked-shared"); //$NON-NLS-1$
        storeRemoteUrls(shared, ORIGIN, URL_KEY, poisonedUrl(SPACE));
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$

        String refusal = GitTool.storedRemoteRefusal(linked, List.of(PUSH));

        assertNotNull("git reads the SHARED config in a linked worktree and 'remote -v' prints " //$NON-NLS-1$
            + "this remote there - it must be judged", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name the remote: " + refusal, refusal.contains(ORIGIN)); //$NON-NLS-1$
        assertRefusalIsActionable(refusal);
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testALegacyFileInTheSHAREDDirectoryIsJudgedFromALinkedWorktree() throws Exception
    {
        // The other half. 'remotes/' and 'branches/' are listed in git's own set of paths that live
        // in the common directory, so a linked worktree reads exactly the main worktree's files.
        for (String directory : List.of("remotes", "branches")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Repository shared = newRepository("git-stored-linked-legacy-" + directory); //$NON-NLS-1$
            File legacy = new File(shared.getDirectory(), directory);
            assertTrue("fixture: the legacy directory must exist", //$NON-NLS-1$
                legacy.mkdirs() || legacy.isDirectory());
            // A branches/ file is a bare URL, a remotes/ file a 'URL: ' line - two formats, and the
            // check reads each on its own terms.
            String content = "remotes".equals(directory) //$NON-NLS-1$
                ? "URL: " + poisonedUrl(SPACE) + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                : poisonedUrl(SPACE) + "\n"; //$NON-NLS-1$
            Files.write(new File(legacy, "shared-legacy").toPath(), //$NON-NLS-1$
                content.getBytes(StandardCharsets.UTF_8));
            Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$

            String refusal = GitTool.storedRemoteRefusal(linked, List.of(PUSH));

            assertNotNull("'git remote get-url' inside a linked worktree prints the SHARED " //$NON-NLS-1$
                + directory + "/<name> verbatim - it must be judged", refusal); //$NON-NLS-1$
            assertTrue("the refusal must name the file: " + refusal, //$NON-NLS-1$
                refusal.contains("shared-legacy")); //$NON-NLS-1$
            assertRefusalLeaksNothing(refusal);
        }
    }

    @Test
    public void testTheWorktreeConfigIsSwitchedOnByTheSHAREDConfigInALinkedWorktree() throws Exception
    {
        // The switch and the file it switches on live in DIFFERENT places here:
        // 'extensions.worktreeConfig' in the shared config, 'config.worktree' beside the worktree's
        // own HEAD. Measured - that pair makes 'remote -v' print the remote from the linked worktree
        // and NOT from the main one. Reading the switch from '<git dir>/config' would find nothing
        // and silently turn this whole layer off, which is the failure mode that looks like success.
        Repository shared = newRepository("git-stored-linked-wtconfig"); //$NON-NLS-1$
        // repositoryformatversion 0 on purpose: the default every ordinary repository carries, and
        // git was measured to honour the extension at that version all the same.
        Files.write(new File(shared.getDirectory(), CONFIG_FILE).toPath(),
            ("[core]\n\trepositoryformatversion = 0\n[extensions]\n\tworktreeConfig = true\n") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        Files.write(new File(linked.getDirectory(), "config.worktree").toPath(), //$NON-NLS-1$
            ("[remote \"wtonly\"]\n\turl = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.UTF_8));

        String refusal = GitTool.storedRemoteRefusal(linked, List.of(PUSH));

        assertNotNull("the switch lives in the SHARED config and the file beside the worktree - " //$NON-NLS-1$
            + "git reads both, so this remote must be judged", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name the remote: " + refusal, refusal.contains("wtonly")); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefusalLeaksNothing(refusal);
    }

    @Test
    public void testAnEditBehindJGitsCacheIsSeenInTheSHAREDConfigToo() throws Exception
    {
        // The same case as testAConfigEditedBehindJGitsCacheIsStillJudged, on the shared file: the
        // layer added for a linked worktree must be built FRESH on every call. Cache it once and
        // this goes green for ever while 'remote -v' prints the credential written afterwards.
        Repository shared = newRepository("git-stored-linked-fresh"); //$NON-NLS-1$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        File sharedConfig = new File(shared.getDirectory(), CONFIG_FILE);
        Files.write(sharedConfig.toPath(),
            configText("https://" + HOST + "/team/repo.git").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("fixture: the clean state must not be refused, or the edit below proves nothing", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(linked, List.of(PUSH)));

        Files.write(sharedConfig.toPath(),
            configText(poisonedUrl(SPACE)).getBytes(StandardCharsets.UTF_8));

        assertNotNull("the shared config is re-read on every call - a credential written after the " //$NON-NLS-1$
            + "first one must still be refused", GitTool.storedRemoteRefusal(linked, List.of(PUSH))); //$NON-NLS-1$
    }

    // ---- what must STAY silent: the places git does NOT read ----
    //
    // Every fixture below carries a value that WOULD be refused if it were judged. That is the
    // whole point: a green result then means "this source was not read", not "there was nothing
    // there". A clean fixture would go green with or without the fix and prove neither.

    @Test
    public void testAPoisonedConfigInTheWORKTREEsOwnGitDirIsNotJudged() throws Exception
    {
        // git ignores '<git dir>/config' in a linked worktree - it reads the shared one instead.
        // JGit reads it all the same, so it arrives as the top link of the chain, and the chain does
        // not merge the way one file does: getSubsections UNIONS every link and getStringList
        // CONCATENATES them, so an entry here could be neither hidden nor shadowed. Refusing over a
        // file git never opens is the expensive mistake - it takes a healthy repository off the air.
        Repository shared = newRepository("git-stored-linked-ignored-config"); //$NON-NLS-1$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        Files.write(new File(linked.getDirectory(), CONFIG_FILE).toPath(),
            ("[remote \"ignored-by-git\"]\n\turl = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.UTF_8));
        // Positive control: the very same bytes in the SHARED config ARE refused, so a green result
        // below cannot be the predicate failing to recognise this value.
        Repository control = newRepository("git-stored-linked-ignored-control"); //$NON-NLS-1$
        Files.write(new File(control.getDirectory(), CONFIG_FILE).toPath(),
            ("[remote \"ignored-by-git\"]\n\turl = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$ //$NON-NLS-2$
                .getBytes(StandardCharsets.UTF_8));
        assertNotNull("control: this exact entry must be refused when git WOULD read it", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(linkedWorktreeOf(control, "wt"), List.of(PUSH))); //$NON-NLS-1$

        assertNull("git does not read '<git dir>/config' in a linked worktree, so refusing over " //$NON-NLS-1$
            + "it would block a repository git is perfectly happy with", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(linked, List.of(PUSH)));
    }

    @Test
    public void testAPoisonedLegacyFileInTheWORKTREEsOwnGitDirIsNotJudged() throws Exception
    {
        // Measured: the same file in the worktree's own git directory answers 'No such remote'.
        // git's repository-layout says it outright - when a common directory is set, the worktree's
        // own 'remotes' and 'branches' are ignored and the shared ones used instead.
        Repository shared = newRepository("git-stored-linked-ignored-legacy"); //$NON-NLS-1$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        File legacy = new File(linked.getDirectory(), "remotes"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", legacy.mkdirs()); //$NON-NLS-1$
        Files.write(new File(legacy, "ignored-by-git").toPath(), //$NON-NLS-1$
            ("URL: " + poisonedUrl(SPACE) + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        // Positive control: the identical file in the SHARED directory IS refused (asserted by
        // testALegacyFileInTheSHAREDDirectoryIsJudgedFromALinkedWorktree), so this value is one the
        // predicate does recognise.

        assertNull("git ignores the worktree's own legacy files - judging them would invent a " //$NON-NLS-1$
            + "refusal over a file no command can reach", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(linked, List.of(PUSH)));
    }

    @Test
    public void testAHealthyLegacyFileInTheSHAREDDirectoryIsNotRefused() throws Exception
    {
        // The #358 regression, replayed on the path that can finally reach the file: stripping the
        // 'URL:' key demanded a ': ' and this perfectly ordinary line kept its key, whose colon then
        // read as the password marker in front of the '@'. That refused EVERY remote command of a
        // healthy repository. It could not fire in a linked worktree before, because the file was
        // invisible there; it can now, so it is pinned here as well.
        Repository shared = newRepository("git-stored-linked-healthy-legacy"); //$NON-NLS-1$
        File legacy = new File(shared.getDirectory(), "remotes"); //$NON-NLS-1$
        assertTrue("fixture: the legacy directory must exist", //$NON-NLS-1$
            legacy.mkdirs() || legacy.isDirectory());
        Files.write(new File(legacy, "ssh").toPath(), //$NON-NLS-1$
            "URL:git@github.com:acme/repo.git\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$

        assertNull("an ordinary ssh remote in a legacy file must pass - refusing it would block " //$NON-NLS-1$
            + "every remote command of a healthy repository", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(linked, List.of(PUSH)));
    }

    @Test
    public void testAHealthyLinkedWorktreeIsNotRefused() throws Exception
    {
        // Green with or without the fix, and recorded as such: this is a regression guard, not
        // evidence that anything was read.
        Repository shared = newRepository("git-stored-linked-healthy"); //$NON-NLS-1$
        storeRemoteUrls(shared, ORIGIN, URL_KEY, "https://" + HOST + "/team/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$

        assertNull("nothing about a healthy linked worktree may be refused", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(linked, List.of(PUSH)));
    }

    // ---- what the check may not do to a repository it does not own ----

    @Test
    public void testTheCheckLeavesTheSharedRepositoryObjectUntouched() throws Exception
    {
        // The Repository is EGit's cached, reference-counted instance, handed to list_git_branches
        // and the branch tools as well - GitRepositoryResolver borrows it and never closes it. A
        // check has no business changing what they read, so the layers built here are private to the
        // call: splice the shared config into repo.getConfig() to "save a read" and this goes red.
        Repository shared = newRepository("git-stored-linked-no-mutation"); //$NON-NLS-1$
        storeRemoteUrls(shared, ORIGIN, URL_KEY, poisonedUrl(SPACE));
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        StoredConfig before = linked.getConfig();
        Config beforeBase = before.getBaseConfig();
        // The identity assertions below are cheap and are NOT what carries this test - JGit keeps
        // the same config object and the same base reference on its own. What carries it is the
        // pair of CONTENT assertions: the shared config declares a remote, JGit cannot see it here,
        // and it must still be unable to see it afterwards.
        assertTrue("fixture: JGit must see no remote here, or the assertion below is vacuous", //$NON-NLS-1$
            before.getSubsections(REMOTE_SECTION).isEmpty());
        Set<String> beforeSections = new TreeSet<>(before.getSections());

        assertNotNull("fixture: the shared remote must be refused, so the check really ran", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(linked, List.of(PUSH)));

        assertTrue("the repository's own configuration object must be the same one", //$NON-NLS-1$
            before == linked.getConfig());
        assertTrue("...and its base chain must be the same one", //$NON-NLS-1$
            beforeBase == linked.getConfig().getBaseConfig());
        assertTrue("...and it must still be unable to see the shared remote: the check builds a " //$NON-NLS-1$
            + "private view, it does not enrich shared state", //$NON-NLS-1$
            linked.getConfig().getSubsections(REMOTE_SECTION).isEmpty());
        assertEquals("...and no section of any kind may have appeared in it either - 'remote' is " //$NON-NLS-1$
            + "the one this check reads, but enriching the shared object with ANY of the shared " //$NON-NLS-1$
            + "file's content is the thing being ruled out", //$NON-NLS-1$
            beforeSections, new TreeSet<>(linked.getConfig().getSections()));
    }

    // ---- the chain the shared layer is built ON ----

    @Test
    public void testTheInheritedConfigurationSurvivesTheSharedLayer() throws Exception
    {
        // Nothing built out of FILES can catch this: a fixture cannot plant a remote in the
        // machine's ~/.gitconfig, so a mutation that passes 'null' as the shared layer's base -
        // dropping the user and system configuration, and only inside a linked worktree - stays
        // green through every test above. Driven through the seam instead, with a chain of its own.
        Repository shared = newRepository("git-stored-linked-inherited"); //$NON-NLS-1$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        Config system = new Config();
        system.setString(REMOTE_SECTION, "inherited-remote", URL_KEY, //$NON-NLS-1$
            "https://" + HOST + "/inherited.git"); //$NON-NLS-1$ //$NON-NLS-2$
        // Stands in for the link JGit puts on top of the inherited chain.
        Config repositoryLayer = new Config(system);

        Config effective = GitTool.effectiveConfig(linked, repositoryLayer,
            GitCommonDirectory.of(linked.getDirectory()));

        assertTrue("a remote inherited from the user or system configuration is read by git in a " //$NON-NLS-1$
            + "linked worktree exactly as anywhere else - adding the shared layer may not cost it", //$NON-NLS-1$
            effective.getSubsections(REMOTE_SECTION).contains("inherited-remote")); //$NON-NLS-1$
    }

    @Test
    public void testTheIgnoredRepositoryLayerIsDroppedOnlyWhenItIsIdentified() throws Exception
    {
        // Two branches, and the second is the one that matters. Dropping a link BLINDLY would, on a
        // repository whose configuration is not shaped the way JGit 6.8 shapes it, throw away the
        // USER configuration and stop judging remotes inherited from it - the very blindness this
        // change removes. So the link comes off only when it can be named, by the same expression
        // FileRepository built it from; anything else keeps the whole chain.
        Repository shared = newRepository("git-stored-linked-drop"); //$NON-NLS-1$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        GitCommonDirectory common =
            GitCommonDirectory.of(linked.getDirectory());
        assertTrue("fixture: this must be recognised as a linked worktree", common.linked()); //$NON-NLS-1$
        Config inherited = new Config();

        StoredConfig ignoredFile = new FileBasedConfig(inherited,
            linked.getFS().resolve(linked.getDirectory(), CONFIG_FILE), linked.getFS());
        assertTrue("the link git ignores here is identified and taken out", //$NON-NLS-1$
            inherited == GitTool.inheritedChain(linked, ignoredFile, common));

        StoredConfig someOtherFile = new FileBasedConfig(inherited,
            new File(linked.getDirectory(), "config.worktree"), linked.getFS()); //$NON-NLS-1$
        assertTrue("an unrecognised shape keeps its whole chain - guessing there would drop the " //$NON-NLS-1$
            + "inherited configuration instead", //$NON-NLS-1$
            someOtherFile == GitTool.inheritedChain(linked, someOtherFile, common));
    }

    @Test
    public void testAnOrdinaryCloneKeepsItsWholeChain() throws Exception
    {
        // The negative control for the branch above: outside a linked worktree the repository's own
        // config is exactly what git reads, and taking it out would blind the check on every
        // ordinary clone - which is every repository this tool normally meets.
        Repository repo = newRepository("git-stored-ordinary-chain"); //$NON-NLS-1$
        GitCommonDirectory common =
            GitCommonDirectory.of(repo.getDirectory());
        assertFalse("fixture: an ordinary clone is not a linked worktree", common.linked()); //$NON-NLS-1$

        StoredConfig config = repo.getConfig();

        assertTrue("an ordinary clone's chain is handed on untouched", //$NON-NLS-1$
            config == GitTool.inheritedChain(repo, config, common));
    }

    // ---- a commondir that cannot be resolved ----

    @Test
    public void testAnUnresolvableCommonDirIsRefusedAndNamesItsOwnRepair() throws Exception
    {
        // Fail closed: that one file says where the whole shared repository is, so without it the
        // effective set of remotes cannot be established. For THIS fault - a pointer naming a
        // directory that does not exist - git dies too ('fatal: not a git repository', measured),
        // so the refusal cannot take a working repository off the air.
        //
        // What the refusal must NOT say is 'git worktree repair'. Measured on git 2.35.1: pointed
        // at exactly this worktree it left the file byte for byte unchanged and reported
        // 'repair: .git file broken' about an intact .git file. An assertion that merely looked for
        // that phrase would pass on the old, wrong advice AND on the warning that replaced it -
        // which is what the previous version of this test did.
        Repository shared = newRepository("git-stored-linked-broken-commondir"); //$NON-NLS-1$
        Repository linked = linkedWorktreeOf(shared, "wt"); //$NON-NLS-1$
        Files.write(new File(linked.getDirectory(), "commondir").toPath(), //$NON-NLS-1$
            "../nowhere-at-all\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        String refusal = GitTool.storedRemoteRefusal(linked, List.of(PUSH));

        assertNotNull("a commondir that resolves to nothing must be refused, not run blind", //$NON-NLS-1$
            refusal);
        assertTrue("the refusal must name the file at fault: " + refusal, //$NON-NLS-1$
            refusal.contains("commondir")); //$NON-NLS-1$
        assertTrue("...and the repair, which is the FILE and not the config repair: " + refusal, //$NON-NLS-1$
            refusal.contains("repair that file itself")); //$NON-NLS-1$
        assertTrue("...stated as what the file MUST be, not as what it currently is - the old " //$NON-NLS-1$
            + "wording claimed it 'holds one line' in the very message saying it is empty: " //$NON-NLS-1$
            + refusal, refusal.contains("must be a regular file holding exactly one line")); //$NON-NLS-1$
        assertTrue("...and it must warn AGAINST the command that does not fix this, or an " //$NON-NLS-1$
            + "operator follows the obvious one and gets the same refusal back: " + refusal, //$NON-NLS-1$
            refusal.contains("Do NOT reach for 'git worktree repair'"));
        assertFalse("...and it must not quote what the file said: " + refusal, //$NON-NLS-1$
            refusal.contains("nowhere-at-all")); //$NON-NLS-1$
        assertRefusalLeaksNothing(refusal);

        // It must name THE fault this pointer hit - and only it. The earlier version pasted in every
        // ours() reason and left the operator to work out which line was theirs; an assertion over
        // that aggregate passed no matter which fault the fixture actually produced, which is a
        // predicate that cannot fail for the reason it exists.
        GitCommonDirectory.Fault expected = GitCommonDirectory.Fault.NOT_A_DIRECTORY;
        assertTrue("the refusal must name the fault this pointer actually hit: " + refusal, //$NON-NLS-1$
            refusal.contains(expected.reason()));
        assertTrue("...and say that THIS TOOL refused, without claiming what git would do: " //$NON-NLS-1$
            + refusal, refusal.contains("This tool refused rather than run blind")); //$NON-NLS-1$
        for (GitCommonDirectory.Fault other : GitCommonDirectory.Fault.values())
        {
            if (other != expected && !other.reason().equals(expected.reason()))
            {
                assertFalse(other + ": no OTHER fault may be named - an operator reading a list of " //$NON-NLS-1$
                    + "five has to work out which line is about their repository: " + refusal, //$NON-NLS-1$
                    refusal.contains(other.reason()));
            }
        }
    }

    @Test
    public void testTheUnclassifiedFailureClaimsNothingItHasNotEstablished()
    {
        // This branch fires when resolving the layout threw something GitCommonDirectory does not
        // classify - and that can happen BEFORE anything is known, including whether this is a
        // linked worktree at all (the throw can come from the very call that would have told us).
        // It used to borrow the head written for the classified case, which asserts both that this
        // IS a linked worktree and that its commondir pointer is the thing at fault. Two claims
        // from a branch that established neither.
        String refusal = GitTool.commonDirRefusal(null);

        assertFalse("it may not assert this is a linked worktree - that is what could not be " //$NON-NLS-1$
            + "established: " + refusal, refusal.contains("linked git worktree")); //$NON-NLS-1$ //$NON-NLS-2$
        // The word may appear - the repair sentence says "IF this worktree has a 'commondir'
        // file" - and that is fine, because it asserts nothing. What must not appear is the head's
        // flat statement that there IS one and that it is the thing at fault.
        assertFalse("nor ASSERT that a 'commondir' pointer exists and is at fault: " + refusal, //$NON-NLS-1$
            refusal.contains("the 'commondir' file in its git directory")); //$NON-NLS-1$
        assertTrue("...though naming the file conditionally in the repair is fine: " + refusal, //$NON-NLS-1$
            refusal.contains("If this worktree has a 'commondir' file")); //$NON-NLS-1$
        assertFalse("nor name a fault, since none was identified: " + refusal, //$NON-NLS-1$
            refusal.contains("The fault:")); //$NON-NLS-1$
        assertTrue("it must still say the operation was refused rather than run: " + refusal, //$NON-NLS-1$
            refusal.contains("refused")); //$NON-NLS-1$
        for (GitCommonDirectory.Fault fault : GitCommonDirectory.Fault.values())
        {
            assertFalse(fault + ": no fault's words may appear either: " + refusal, //$NON-NLS-1$
                refusal.contains(fault.reason()));
        }
    }

    @Test
    public void testAnUNCONFIRMEDFaultDoesNotBorrowTheCommondirHeadEither()
    {
        // The null branch was fixed first, but it was only half the hole: a fault can be CLASSIFIED
        // and still have established nothing, because the failure came from the very look that
        // would have told us whether a commondir exists. LAYOUT_UNREADABLE is that case, and it
        // must get the same neutral head as the unclassified one.
        //
        // Without this, confirmed() could be ignored entirely and every test stayed green - which is
        // how the mutation run found it.
        String refusal = GitTool.commonDirRefusal(GitCommonDirectory.Fault.LAYOUT_UNREADABLE);

        assertFalse("an unconfirmed fault may not assert there IS a commondir at fault: " + refusal, //$NON-NLS-1$
            refusal.contains("the 'commondir' file in its git directory")); //$NON-NLS-1$
        assertFalse("nor that this is a linked worktree: " + refusal, //$NON-NLS-1$
            refusal.contains("This is a linked git worktree")); //$NON-NLS-1$
        assertTrue("it must still name the fault it did reach: " + refusal, //$NON-NLS-1$
            refusal.contains(GitCommonDirectory.Fault.LAYOUT_UNREADABLE.reason()));
        assertTrue("...and claim nothing about git, as no refusal does any more: " + refusal, //$NON-NLS-1$
            refusal.contains("is not something it determines")); //$NON-NLS-1$

        // The contrast that makes it a real assertion: a CONFIRMED fault does get the head.
        String confirmed = GitTool.commonDirRefusal(GitCommonDirectory.Fault.EMPTY);
        assertTrue("a confirmed fault DOES speak of the commondir - otherwise the head would be " //$NON-NLS-1$
            + "dead code and this test would pass on a version that never used it: " + confirmed, //$NON-NLS-1$
            confirmed.contains("the 'commondir' file in its git directory")); //$NON-NLS-1$
    }

    @Test
    public void testTheRepairAdviceFitsTheFaultRatherThanAlwaysNamingTheFile()
    {
        // TARGET_UNREADABLE means the pointer may be flawless and what it NAMES could not be
        // examined - a denied directory, say. Telling the operator to repair the file would send
        // them to edit something already correct, which is the retry loop every other refusal in
        // this tool is held away from.
        String target = GitTool.commonDirRefusal(GitCommonDirectory.Fault.TARGET_UNREADABLE);

        assertTrue("it must point at what the pointer NAMES: " + target, //$NON-NLS-1$
            target.contains("what it POINTS AT is what could not be examined")); //$NON-NLS-1$
        assertFalse("...and must not order the file repaired: " + target, //$NON-NLS-1$
            target.contains("repair that file itself")); //$NON-NLS-1$

        // The contrast: a fault that IS about the file still gets the file's repair.
        String pointer = GitTool.commonDirRefusal(GitCommonDirectory.Fault.EMPTY);
        assertTrue("a fault about the file itself keeps the file's repair: " + pointer, //$NON-NLS-1$
            pointer.contains("repair that file itself")); //$NON-NLS-1$

        // And a layout failure has no pointer to send anyone to at all.
        String layout = GitTool.commonDirRefusal(GitCommonDirectory.Fault.LAYOUT_UNREADABLE);
        assertTrue("a layout failure sends the operator to the git directory: " + layout, //$NON-NLS-1$
            layout.contains("Check the git directory of this project")); //$NON-NLS-1$
        assertFalse("...and not to a file it never established exists: " + layout, //$NON-NLS-1$
            layout.contains("repair that file itself")); //$NON-NLS-1$
    }

    @Test
    public void testThePermanentLogDoesNotAssertWhatTheRefusalStoppedAsserting()
    {
        // The response was corrected not to claim a linked worktree it had not established. The EDT
        // log is permanent and outlives the response, so leaving the old claim there would be the
        // worse half of the same mistake.
        String unconfirmed =
            GitTool.commonDirFailureLog(GitCommonDirectory.Fault.LAYOUT_UNREADABLE, null);
        assertFalse("an unconfirmed fault may not be logged as a commondir resolution: " //$NON-NLS-1$
            + unconfirmed, unconfirmed.contains("commondir")); //$NON-NLS-1$

        String unclassified = GitTool.commonDirFailureLog(null, null);
        assertFalse("nor may an unclassified one: " + unclassified, //$NON-NLS-1$
            unclassified.contains("commondir")); //$NON-NLS-1$

        String confirmed = GitTool.commonDirFailureLog(GitCommonDirectory.Fault.EMPTY, null);
        assertTrue("a CONFIRMED fault still says what it really was, or the head would be dead " //$NON-NLS-1$
            + "code: " + confirmed, confirmed.contains("commondir")); //$NON-NLS-1$
    }

    @Test
    public void testNoRefusalNamesASideForANYFault()
    {
        // What the collapse GUARANTEES, pinned over every member rather than sampled.
        //
        // The refusal used to say whose limit the fault was. Measured against git 2.35.1, FIVE of
        // eleven were wrong - and the decisive one shows the claim could not be fixed, only
        // dropped: a commondir of '\shared' makes git answer 'fatal: not a git repository' on
        // Windows and is an ordinary relative path git resolves happily on POSIX. The same bytes,
        // opposite answers, so no constant could have been right on both.
        for (GitCommonDirectory.Fault fault : GitCommonDirectory.Fault.values())
        {
            String refusal = GitTool.commonDirRefusal(fault);
            assertTrue(fault + ": every refusal must still name the fault it hit: " + refusal, //$NON-NLS-1$
                refusal.contains(fault.reason()));
            for (String side : new String[]{"THIS TOOL's limit", "git was measured to fail", //$NON-NLS-1$ //$NON-NLS-2$
                "git may well carry on", "would have hit anyway", //$NON-NLS-1$ //$NON-NLS-2$
                "has not been established either way"}) //$NON-NLS-1$
            {
                assertFalse(fault + ": no refusal may claim whose limit it is - that depends on " //$NON-NLS-1$
                    + "the platform and the git version, not on the fault: " + refusal, //$NON-NLS-1$
                    refusal.contains(side));
            }
        }
        assertFalse("nor may the unclassified one", //$NON-NLS-1$
            GitTool.commonDirRefusal(null).contains("git was measured to fail")); //$NON-NLS-1$

        // The blacklist above is a FLOOR - it only knows the phrases the old renderer used, so a
        // NEW sentence naming a side would walk straight past it. What follows is the property
        // itself, and it needs no list: two faults that share a repair tail must produce messages
        // that differ ONLY in the fault's own words. Any sentence added per-fault - about git or
        // anything else - breaks that equality, whatever it happens to say.
        GitCommonDirectory.Fault a = GitCommonDirectory.Fault.EMPTY;
        GitCommonDirectory.Fault b = GitCommonDirectory.Fault.NOT_A_DIRECTORY;
        assertEquals("two faults with the same repair must differ only in their reason - a " //$NON-NLS-1$
            + "message that says anything else ABOUT the fault is saying something this code " //$NON-NLS-1$
            + "does not know", //$NON-NLS-1$
            GitTool.commonDirRefusal(a).replace(a.reason(), "<R>"), //$NON-NLS-1$
            GitTool.commonDirRefusal(b).replace(b.reason(), "<R>")); //$NON-NLS-1$
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
     * Asserts what EVERY refusal says, whatever source the entry came from: what is wrong, that the
     * repair happens outside this tool, where instead, and that retrying here cannot work.
     * <p>
     * Split out from {@link #assertRefusalStatesTheFix} when the remedy stopped being one sentence:
     * a remote GROUP and a LEGACY file need different commands, and asserting the
     * {@code [remote "<name>"]} remedy against them would demand text that would be wrong there.
     *
     * @param refusal the message under test
     */
    private static void assertRefusalIsActionable(String refusal)
    {
        String lower = refusal.toLowerCase(Locale.ROOT);
        assertTrue("the refusal must say WHAT is wrong: " + refusal, //$NON-NLS-1$
            lower.contains("cannot be masked")); //$NON-NLS-1$
        assertTrue("the refusal must say the repair happens OUTSIDE this tool: " + refusal, //$NON-NLS-1$
            lower.contains("outside this tool")); //$NON-NLS-1$
        assertTrue("...and name where instead - a terminal: " + refusal, //$NON-NLS-1$
            lower.contains("terminal")); //$NON-NLS-1$
        assertTrue("...and warn that retrying it through this tool cannot work: " + refusal, //$NON-NLS-1$
            lower.contains("cannot work")); //$NON-NLS-1$
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
        // The repository-scoped remedy names remove-and-re-add on purpose. 'git remote set-url'
        // writes 'url' only, so it would leave a poisoned 'pushurl' in place, and against a
        // multi-valued url it refuses to run at all. Dropping the remote and adding it again is the
        // only step that works whatever shape the entry has - a remedy that fits one shape only
        // would send an unattended caller into the retry loop this text exists to prevent.
        assertTrue("the refusal must say HOW to fix it: " + refusal, //$NON-NLS-1$
            lower.contains("git remote remove")); //$NON-NLS-1$
        // ...and WHERE. The pre-flight keys on the SUBCOMMAND, so 'remote set-url' and
        // 'remote remove' - the only commands that could clear the entry - are refused by this very
        // message while the entry is there (testEverySubcommandThatCanReachARemoteIsChecked pins
        // that 'remote' is one of them). A remedy that reads as if this tool could run it sends an
        // unattended caller into an endless retry, so the message must send it to a terminal.
        assertRefusalIsActionable(refusal);
        // ...and it has to say WHERE the entry may live, because the commands it names are
        // REPOSITORY-scoped while the check is not: storedRemoteRefusal reads repo.getConfig(), the
        // merged configuration, whose getSubsections walks the base chain (repository -> user ->
        // system). For a remote inherited from the user or system file the repository-scoped
        // commands answer "No such remote", so the caller has to be told where else to look.
        assertTrue("the refusal must say the entry may be inherited from the user or system " //$NON-NLS-1$
            + "configuration: " + refusal, lower.contains("user or system")); //$NON-NLS-1$ //$NON-NLS-2$
        // ...and give a remedy that reaches THERE. Naming the scope without a command that clears it
        // leaves the caller with 'No such remote' and no way out at all, which is the same retry
        // loop one file down.
        assertTrue("...and name a command that clears it in that file, section included: " + refusal, //$NON-NLS-1$
            lower.contains("--remove-section remote.<name>")); //$NON-NLS-1$
        assertTrue("...for both files a remote can be inherited from: " + refusal, //$NON-NLS-1$
            lower.contains("--global") && lower.contains("--system")); //$NON-NLS-1$
    }

    /**
     * Counts non-overlapping occurrences of {@code needle}.
     *
     * @param text the message under test
     * @param needle the substring to count
     * @return how many times it occurs
     */
    private static int occurrencesOf(String text, String needle)
    {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length()))
        {
            count++;
        }
        return count;
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
     * Overwrites a repository's configuration with text JGit cannot load, and returns the exception
     * the reload throws - the very object the fail-closed path catches.
     *
     * @param repo the repository whose configuration to break
     * @param brokenConfig the configuration text to write
     * @return the unchecked exception JGit threw
     * @throws Exception when the file cannot be written
     */
    private static Throwable configReadFailure(Repository repo, String brokenConfig) throws Exception
    {
        File configFile = new File(repo.getDirectory(), CONFIG_FILE);
        Files.write(configFile.toPath(), brokenConfig.getBytes(StandardCharsets.UTF_8));
        // JGit reloads when the size OR the timestamp changed; both are moved, so the case does not
        // depend on which of the two this filesystem notices.
        configFile.setLastModified(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1));
        try
        {
            repo.getConfig().getSubsections(REMOTE_SECTION);
        }
        catch (RuntimeException expected)
        {
            return expected;
        }
        fail("fixture: this configuration loaded fine, so the fail-closed path is never reached"); //$NON-NLS-1$
        return null;
    }

    /**
     * Every message in a throwable's cause chain, joined. Bounded: a cause chain can be cyclic.
     *
     * @param failure the exception to walk
     * @return the messages, one per line
     */
    private static String causeChainMessages(Throwable failure)
    {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++)
        {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
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

    /**
     * Builds a LINKED worktree of {@code shared} and opens it the way this plug-in opens one, then
     * PROVES the fixture is what it claims to be.
     * <p>
     * Built by hand rather than by shelling out to {@code git worktree add}: no git executable is
     * needed, so this stays a unit test on every platform, and the four files written below are the
     * ones a real {@code git worktree add} was measured to produce - {@code commondir} holding
     * {@code ../..}, {@code gitdir} pointing back at the worktree's {@code .git} file, a private
     * {@code HEAD}, and the {@code .git} FILE that sends everything to the admin directory.
     * <p>
     * Two traps this method exists to close, both of which would leave a green test proving nothing:
     * <ul>
     * <li>{@link org.eclipse.jgit.storage.file.FileRepositoryBuilder#findGitDir} SWALLOWS a
     * {@code .git} pointer it cannot use and keeps walking UP. A worktree placed inside the shared
     * repository's own tree would therefore quietly open the SHARED repository, and every assertion
     * about the linked one would be about the wrong object. The directory is created OUTSIDE it, and
     * the resolved git directory is asserted afterwards;</li>
     * <li>{@code build()} succeeding proves nothing on its own - {@code mustExist} is false by
     * default, so it happily returns a repository for a pointer to a directory that does not exist.
     * The check is on {@code getDirectory()}, not on the absence of an exception.</li>
     * </ul>
     * The returned repository is BARE as far as JGit is concerned - it derives a work tree from the
     * configuration it reads from the wrong place - which is exactly why the tool cannot open one of
     * these yet, and is beside the point for a check that never asks for a work tree.
     *
     * @param shared the repository the worktree is added to
     * @param name the worktree's name
     * @return the linked worktree, opened as this plug-in opens a repository
     * @throws Exception when the fixture cannot be built or does not come out as intended
     */
    private Repository linkedWorktreeOf(Repository shared, String name) throws Exception
    {
        File adminDir = new File(new File(shared.getDirectory(), "worktrees"), name); //$NON-NLS-1$
        assertTrue("fixture: the worktree admin directory must be created", adminDir.mkdirs()); //$NON-NLS-1$
        // OUTSIDE the shared work tree, or findGitDir walks up into the shared repository.
        File worktreeDir = Files.createTempDirectory("git-linked-" + name).toFile(); //$NON-NLS-1$
        temporaries.add(worktreeDir);
        File pointer = new File(worktreeDir, ".git"); //$NON-NLS-1$
        Files.write(pointer.toPath(),
            ("gitdir: " + adminDir.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(adminDir, "commondir").toPath(), //$NON-NLS-1$
            "../..\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Files.write(new File(adminDir, "gitdir").toPath(), //$NON-NLS-1$
            (pointer.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Files.write(new File(adminDir, "HEAD").toPath(), //$NON-NLS-1$
            ("ref: refs/heads/" + name + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        // The same JGit call GitRepositoryResolver's discovery fallback makes, so the object
        // under test is the one a real request would be handed.
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(worktreeDir);
        assertNotNull("fixture: the linked worktree must be discoverable", builder.getGitDir()); //$NON-NLS-1$
        Repository linked = builder.build();
        linkedRepositories.add(linked);
        assertEquals("fixture: it must resolve to the WORKTREE's admin directory - findGitDir " //$NON-NLS-1$
            + "swallows an unusable pointer and keeps walking up, and opening the shared " //$NON-NLS-1$
            + "repository instead would make every assertion here vacuous", //$NON-NLS-1$
            adminDir.getCanonicalFile(), linked.getDirectory().getCanonicalFile());
        assertFalse("fixture: and the two must be different directories, or there is nothing " //$NON-NLS-1$
            + "'shared' about the one under test", //$NON-NLS-1$
            adminDir.getCanonicalFile().equals(shared.getDirectory().getCanonicalFile()));
        return linked;
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
