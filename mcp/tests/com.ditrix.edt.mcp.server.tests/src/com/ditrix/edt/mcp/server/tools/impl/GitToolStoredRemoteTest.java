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
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

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
    public void testTheWorktreeConfigNeedsRepositoryFormatVersionOne() throws Exception
    {
        // 'extensions.*' is a repository-FORMAT setting, and git honours it only from format
        // version 1 on. At version 0 it ignores the extension and never reads config.worktree, so a
        // check that read the file anyway would take a repository git is perfectly happy with off
        // the air. (The same rule is why the switch is read from .git/config itself and not from
        // the merged chain - one left behind in a user's ~/.gitconfig turns nothing on for git.
        // That half is argued from git's semantics; only the version half is reproducible here,
        // because putting a switch into the USER configuration would mean writing to the machine's
        // own ~/.gitconfig.)
        Repository repo = newRepository("git-stored-worktree-version"); //$NON-NLS-1$
        File gitDir = repo.getDirectory();
        Files.write(new File(gitDir, CONFIG_FILE).toPath(),
            ("[core]\n\trepositoryformatversion = 0\n[extensions]\n\tworktreeConfig = true\n" //$NON-NLS-1$
                + "[remote \"" + ORIGIN + "\"]\n\turl = https://" + HOST + "/team/clean.git\n") //$NON-NLS-1$ //$NON-NLS-2$
                    .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(gitDir, "config.worktree").toPath(), //$NON-NLS-1$
            ("[remote \"ignored\"]\n\turl = " + poisonedUrl(SPACE) + "\n") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
        // Positive control: the switch really IS declared - so the only thing keeping the file
        // unread is the format version, which is exactly what this case is about.
        assertTrue("fixture: extensions.worktreeConfig must be declared", //$NON-NLS-1$
            repo.getConfig().getBoolean("extensions", "worktreeConfig", false)); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("format version 0 means git ignores the extension - and so must this check", //$NON-NLS-1$
            GitTool.storedRemoteRefusal(repo, List.of(PUSH)));
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
            lower.contains("cannot be masked")); //$NON-NLS-1$
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
