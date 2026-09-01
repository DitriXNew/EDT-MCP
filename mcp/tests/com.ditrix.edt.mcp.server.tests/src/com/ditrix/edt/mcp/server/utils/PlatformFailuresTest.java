/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.jcraft.jsch.JSchException;

/**
 * Tests for {@link PlatformFailures}: an EDT failure must never reach a caller as an empty
 * sentence, the literal "null", or a generic wrapper message when the real reason is one hop
 * away in the {@link IStatus} tree.
 *
 * <p>The cases are the shapes the platform actually produces on the standalone-server update
 * path — a cancelled server operation carries {@code Status.CANCEL_STATUS}, whose message is the
 * empty string, and EDT's publish results are {@code MultiStatus} trees whose reason sits in a
 * child.
 *
 * <p>The {@code withoutObjectIdentity} cases pin the opposite obligation: the text that IS
 * chosen must not carry an EMF implementation object's identity out to the caller, and must be
 * left alone byte for byte when it carries none.
 */
public class PlatformFailuresTest
{
    private static final String PLUGIN = "com.ditrix.edt.mcp.server";

    /** The #452 leak, exactly as the platform reported it on the stand. */
    private static final String LEAKING_MESSAGE = "Failed to persist reference value"
        + " com._1c.g5.v8.dt.rights.model.impl.RoleDescriptionImpl@3f2a1b";

    /**
     * Any object identity still left in a message: an at-sign followed by hex. Deliberately NOT
     * "four or more" - the scrubber no longer has a length floor, so a probe that had one would
     * report a leaked "@abc" as clean.
     */
    private static final Pattern ANY_IDENTITY = Pattern.compile("@[0-9a-fA-F]+");

    /** Invokes the new API reflectively so every regression test runs and fails on the baseline. */
    private static String rootCause(Throwable failure)
    {
        try
        {
            Method method = PlatformFailures.class.getMethod("rootCause", Throwable.class); //$NON-NLS-1$
            return (String)method.invoke(null, failure);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("PlatformFailures.rootCause(Throwable) is missing or unusable", e); //$NON-NLS-1$
        }
    }

    /** Throwable with a deliberately cyclical cause chain. */
    private static final class CyclicFailure extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private Throwable next;

        CyclicFailure(String message)
        {
            super(message);
        }

        void setNext(Throwable next)
        {
            this.next = next;
        }

        @Override
        public synchronized Throwable getCause()
        {
            return next;
        }
    }

    @Test
    public void testOwnMessageWins()
    {
        assertEquals("a plain message is used as-is", "Database is locked",
            PlatformFailures.describe(new ApplicationException("Database is locked")));
    }

    @Test
    public void testBlankMessageFallsBackToTheStatusTree()
    {
        // ApplicationException(IStatus) copies status.getMessage() — the EMPTY STRING for a
        // cancelled server operation — so the exception's own message must not be trusted.
        MultiStatus status = new MultiStatus(PLUGIN, 0, "", null);
        status.add(new Status(IStatus.ERROR, PLUGIN, "Server \"S\" start attempt failed."));
        String described = PlatformFailures.describe(new ApplicationException(status));
        assertEquals("the child status carries the real reason",
            "Server \"S\" start attempt failed.", described);
    }

    @Test
    public void testSpecificChildBeatsTheGenericWrapperHeadline()
    {
        // The shape EDT actually produces: a MultiStatus whose own message is the headline the tool
        // already prints, with the reason in a child. Returning the headline would make this helper
        // hand back exactly the text it exists to replace.
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.ERROR, PLUGIN, "port 8429 is already in use"));
        assertEquals("the child names the cause and must win", "port 8429 is already in use",
            PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testFailingChildBeatsAnEarlierInformationalOne()
    {
        // An aggregated EDT result legitimately mixes progress/OK children with the failing one;
        // reporting the first child with text could hand back "Publishing..." for a failure.
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.INFO, PLUGIN, "Publishing configuration"));
        status.add(new Status(IStatus.ERROR, PLUGIN, "port 8429 is already in use"));
        assertEquals("the failing child must win over the informational one",
            "port 8429 is already in use",
            PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testInformationalChildIsUsedWhenNothingFailed()
    {
        MultiStatus status = new MultiStatus(PLUGIN, 0, "", null);
        status.add(new Status(IStatus.INFO, PLUGIN, "Publishing configuration"));
        assertEquals("with no failing child the informational text is all there is",
            "Publishing configuration",
            PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testWrapperHeadlineIsStillUsedWhenNoChildCarriesText()
    {
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.ERROR, PLUGIN, ""));
        assertEquals("with nothing better available the headline is the answer",
            "Database update failed", PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testStatusExceptionMessageIsUsedWhenTheStatusItselfIsBlank()
    {
        IStatus status = new Status(IStatus.ERROR, PLUGIN, "", new IllegalStateException("no shell"));
        assertEquals("the status's own exception is consulted", "no shell",
            PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testCauseChainIsWalked()
    {
        CoreException cause = new CoreException(new Status(IStatus.ERROR, PLUGIN, "ports busy"));
        ApplicationException outer = new ApplicationException(new Status(IStatus.ERROR, PLUGIN, "", cause));
        assertEquals("the cause's status message is reached", "ports busy",
            PlatformFailures.describe(outer));
    }

    @Test
    public void testTextlessCancelIsNamedByItsSeverity()
    {
        // The exact shape of an auto-cancelled standalone-server operation: nothing anywhere in
        // the failure carries text, so the severity IS the diagnosis — and "Database update
        // failed: " with nothing after it is what this replaces.
        String described = PlatformFailures.describe(new ApplicationException(Status.CANCEL_STATUS));
        assertTrue("a textless failure must name the exception type",
            described.contains("ApplicationException"));
        assertTrue("a textless failure must report the status severity",
            described.contains("CANCEL"));
        assertFalse("nothing may render as the literal null", described.contains("null"));
    }

    @Test
    public void testNullFailureIsDescribedNotThrown()
    {
        assertFalse("a null failure must not produce the literal null",
            PlatformFailures.describe(null).contains("null"));
    }

    @Test
    public void testMessagelessExceptionWithoutAStatus()
    {
        String described = PlatformFailures.describe(new IllegalStateException());
        assertTrue("the type is the only thing known", described.contains("IllegalStateException"));
        assertFalse("no severity may be claimed without a status", described.contains("severity"));
    }

    @Test
    public void testFirstMessageMatchingSearchesEverywhereDescribeStops()
    {
        // The two are deliberately opposite: describe() returns the headline, so a recogniser
        // that used it would never see the sentence that names the actual refusal.
        IllegalStateException reason = new IllegalStateException("port 8429 is already in use");
        CoreException inner = new CoreException(new Status(IStatus.ERROR, PLUGIN, "wrapper", reason));
        ApplicationException outer =
            new ApplicationException(new Status(IStatus.ERROR, PLUGIN, "wrapper", inner));
        assertEquals("wrapper", PlatformFailures.describe(outer));
        assertEquals("port 8429 is already in use",
            PlatformFailures.firstMessageMatching(outer, m -> m.contains("8429")));
    }

    @Test
    public void testFirstMessageMatchingFindsAChildStatus()
    {
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.INFO, PLUGIN, "Publishing configuration"));
        status.add(new Status(IStatus.ERROR, PLUGIN, "the module was rejected"));
        assertEquals("the module was rejected", PlatformFailures.firstMessageMatching(
            new ApplicationException(status), m -> m.contains("rejected")));
    }

    @Test
    public void testFirstMessageMatchingReturnsNullWhenNothingMatches()
    {
        assertEquals(null, PlatformFailures.firstMessageMatching(
            new ApplicationException("Database is locked"), m -> m.contains("port")));
        assertEquals(null, PlatformFailures.firstMessageMatching(null, m -> true));
    }

    @Test
    public void testDescriptionIsTrimmed()
    {
        assertEquals("surrounding whitespace is not part of the reason", "boom",
            PlatformFailures.describe(new ApplicationException("  boom  ")));
    }

    @Test
    public void testRootCauseReturnsTheDeepestDistinctMessageInAThreeDeepChain()
    {
        Throwable terminal = new IllegalStateException(
            "SSH key authentication was rejected by the remote designer agent"); //$NON-NLS-1$
        Throwable middle = new RuntimeException("Infobase authentication error", terminal); //$NON-NLS-1$
        Throwable failure = new ApplicationException(
            "Infobase connection runtime session open error", middle); //$NON-NLS-1$

        assertEquals("the deepest distinct diagnosis must reach the caller", //$NON-NLS-1$
            "SSH key authentication was rejected by the remote designer agent", //$NON-NLS-1$
            rootCause(failure));
    }

    @Test
    public void testRootCauseKeepsMiddleDiagnosisWhenTerminalRepeatsHeadline()
    {
        String headline = "Database update failed"; //$NON-NLS-1$
        String detail = "port 8429 is already in use"; //$NON-NLS-1$
        Throwable failure = new RuntimeException(headline,
            new RuntimeException(detail, new IllegalStateException(headline)));

        assertEquals("a repeated terminal headline must not erase the distinct middle diagnosis", //$NON-NLS-1$
            detail, rootCause(failure));
    }

    @Test
    public void testRootCauseCycleEndingOnHeadlineKeepsDistinctDiagnosis()
    {
        String headline = "Database update failed"; //$NON-NLS-1$
        String detail = "port 8429 is already in use"; //$NON-NLS-1$
        CyclicFailure first = new CyclicFailure(headline);
        CyclicFailure middle = new CyclicFailure(detail);
        CyclicFailure last = new CyclicFailure(headline);
        first.setNext(middle);
        middle.setNext(last);
        last.setNext(first);

        assertEquals("the final bounded hop must not erase the cycle's distinct diagnosis", //$NON-NLS-1$
            detail, rootCause(first));
    }

    @Test
    public void testRootCauseNeverReportsMultiStatusParentAsCauseOfSelectedChild()
    {
        String parent = "Database update failed"; //$NON-NLS-1$
        String child = "Auth fail"; //$NON-NLS-1$
        MultiStatus status = new MultiStatus(PLUGIN, 0, parent, null);
        status.add(new Status(IStatus.ERROR, PLUGIN, child));
        ApplicationException failure = new ApplicationException(status);

        assertEquals("describe must continue to promote the failing child", child, //$NON-NLS-1$
            PlatformFailures.describe(failure));
        String diagnosis = rootCause(failure);
        assertEquals("an ancestor status is not a cause of its selected child", "", diagnosis); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the parent text must never be emitted as the child's cause", //$NON-NLS-1$
            diagnosis.contains(parent));
    }

    @Test
    public void testRootCauseDoesNotTurnASelectedStatusAncestorIntoACause()
    {
        String headline = "Database update failed"; //$NON-NLS-1$
        String detail = "publishing was refused because the infobase is locked"; //$NON-NLS-1$
        MultiStatus status = new MultiStatus(PLUGIN, 0, headline, null);
        MultiStatus detailStatus = new MultiStatus(PLUGIN, 0, detail, null);
        detailStatus.add(new Status(IStatus.ERROR, PLUGIN, headline));
        status.add(detailStatus);
        ApplicationException failure = new ApplicationException(status);

        assertEquals("the fixture must select the deeper repeated headline", headline, //$NON-NLS-1$
            PlatformFailures.describe(failure));
        assertEquals("a status ancestor cannot be reported as its selected descendant's cause", //$NON-NLS-1$
            "", rootCause(failure)); //$NON-NLS-1$
    }

    @Test
    public void testRootCauseAddsNothingWhenTheDeepestMessageEqualsDescribe()
    {
        Throwable failure = new RuntimeException("same diagnosis", //$NON-NLS-1$
            new IllegalStateException("same diagnosis")); //$NON-NLS-1$

        assertEquals("equal selected and terminal messages must not be repeated", "", //$NON-NLS-1$ //$NON-NLS-2$
            rootCause(failure));
    }

    @Test
    public void testRootCauseHonoursTheCauseChainCap()
    {
        Throwable failure = new RuntimeException("level-11"); //$NON-NLS-1$
        for (int level = 10; level >= 0; level--)
        {
            failure = new RuntimeException("level-" + level, failure); //$NON-NLS-1$
        }

        assertEquals("only the first ten throwable hops may be inspected", "level-9", //$NON-NLS-1$ //$NON-NLS-2$
            rootCause(failure));
    }

    @Test(timeout = 1000)
    public void testRootCauseVisitsAnAliasedStatusGraphOnlyOncePerIdentity()
    {
        IStatus shared = new Status(IStatus.ERROR, PLUGIN, ""); //$NON-NLS-1$
        for (int depth = 0; depth < 4; depth++)
        {
            MultiStatus aliases = new MultiStatus(PLUGIN, 0, "", null); //$NON-NLS-1$
            for (int reference = 0; reference < 100; reference++)
            {
                aliases.add(shared);
            }
            shared = aliases;
        }
        MultiStatus carried = new MultiStatus(PLUGIN, 0, "wrapper detail", null); //$NON-NLS-1$
        carried.add(shared);
        Throwable failure = new RuntimeException("headline", new CoreException(carried)); //$NON-NLS-1$

        assertEquals("400 stored aliases must not expand into roughly 100 million visits", //$NON-NLS-1$
            "wrapper detail", //$NON-NLS-1$
            rootCause(failure));
    }

    @Test
    public void testRootCauseStatusDiscoveryStopsAtItsDepthBoundary()
    {
        String boundaryMessage = "boundary diagnosis"; //$NON-NLS-1$
        String beyondMessage = "beyond-depth diagnosis"; //$NON-NLS-1$
        MultiStatus root = new MultiStatus(PLUGIN, 0, "", null); //$NON-NLS-1$
        MultiStatus current = root;
        for (int depth = 1; depth < 4; depth++)
        {
            MultiStatus child = new MultiStatus(PLUGIN, 0, "", null); //$NON-NLS-1$
            current.add(child);
            current = child;
        }
        MultiStatus boundary = new MultiStatus(PLUGIN, 0, "", //$NON-NLS-1$
            new RuntimeException(boundaryMessage));
        current.add(boundary);
        boundary.add(new Status(IStatus.ERROR, PLUGIN, "", //$NON-NLS-1$
            new IllegalStateException(beyondMessage)));
        Throwable failure = new RuntimeException("headline", new CoreException(root)); //$NON-NLS-1$

        String diagnosis = rootCause(failure);
        assertEquals("a carried exception at status depth four must remain discoverable", //$NON-NLS-1$
            "java.lang.RuntimeException: " + boundaryMessage, diagnosis); //$NON-NLS-1$
        assertFalse("a carried exception at status depth five must be outside the walk: " //$NON-NLS-1$
            + diagnosis, diagnosis.contains(beyondMessage));
    }

    @Test
    public void testRootCauseCycleGuardStopsACyclicalCauseChain()
    {
        CyclicFailure first = new CyclicFailure("cycle-a"); //$NON-NLS-1$
        CyclicFailure second = new CyclicFailure("cycle-b"); //$NON-NLS-1$
        first.setNext(second);
        second.setNext(first);

        assertEquals("the bounded walk must stop instead of looping forever", "cycle-b", //$NON-NLS-1$ //$NON-NLS-2$
            rootCause(first));
    }

    @Test
    public void testRootCausePrefixesTheTerminalTypeForAShortGenericMessage()
    {
        Throwable failure = new RuntimeException("runtime session open error", //$NON-NLS-1$
            new IllegalStateException("Auth fail")); //$NON-NLS-1$

        assertEquals("a short generic terminal message needs its exception type", //$NON-NLS-1$
            "java.lang.IllegalStateException: Auth fail", rootCause(failure)); //$NON-NLS-1$
    }

    @Test
    public void testRootCauseAddsNothingWhenFormattingRecreatesTheHeadline()
    {
        Throwable failure = new RuntimeException(new IllegalStateException("Auth fail")); //$NON-NLS-1$

        assertEquals("the formatted diagnosis must not repeat the selected headline", //$NON-NLS-1$
            "", rootCause(failure)); //$NON-NLS-1$
    }

    @Test
    public void testRootCauseDoesNotAttributeCopiedStatusTextToApplicationException()
    {
        IStatus status = new Status(IStatus.ERROR, PLUGIN, "Auth fail", null); //$NON-NLS-1$
        Throwable failure = new RuntimeException("Database update failed", //$NON-NLS-1$
            new ApplicationException(status));

        String diagnosis = rootCause(failure);
        assertEquals("the status owns the text, so the generic wrapper must add no provenance", //$NON-NLS-1$
            "Auth fail", diagnosis); //$NON-NLS-1$
        assertFalse("the wrapper type must be absent from the diagnosis: " + diagnosis, //$NON-NLS-1$
            diagnosis.contains(ApplicationException.class.getName()));
    }

    @Test
    public void testRootCauseFollowsCauseOfExceptionCarriedByChildStatus()
    {
        MultiStatus status = new MultiStatus(PLUGIN, 0, "", null); //$NON-NLS-1$
        status.add(new Status(IStatus.ERROR, PLUGIN, "", //$NON-NLS-1$
            new RuntimeException("SSH error", new JSchException("Auth fail")))); //$NON-NLS-1$ //$NON-NLS-2$
        ApplicationException failure = new ApplicationException(status);

        assertEquals("the fixture headline comes from the child status's carried exception", //$NON-NLS-1$
            "SSH error", PlatformFailures.describe(failure)); //$NON-NLS-1$
        assertEquals("the carried exception's terminal cause is the actionable diagnosis", //$NON-NLS-1$
            "com.jcraft.jsch.JSchException: Auth fail", rootCause(failure)); //$NON-NLS-1$
    }

    @Test
    public void testRootCauseFollowsExceptionOfSelectedStatusWithDifferentMessage()
    {
        MultiStatus status = new MultiStatus(PLUGIN, 0, "", null); //$NON-NLS-1$
        status.add(new Status(IStatus.ERROR, PLUGIN, "Infobase authentication error", //$NON-NLS-1$
            new JSchException("Auth fail"))); //$NON-NLS-1$
        ApplicationException failure = new ApplicationException(status);

        assertEquals("the status text remains the selected headline", //$NON-NLS-1$
            "Infobase authentication error", PlatformFailures.describe(failure)); //$NON-NLS-1$
        assertEquals("IStatus.getException is a direct causal edge despite different text", //$NON-NLS-1$
            "com.jcraft.jsch.JSchException: Auth fail", rootCause(failure)); //$NON-NLS-1$
    }

    @Test
    public void testRootCauseCapsCauseChainCarriedByChildStatus()
    {
        Throwable carried = new RuntimeException("level-11"); //$NON-NLS-1$
        for (int level = 10; level >= 0; level--)
        {
            carried = new RuntimeException("level-" + level, carried); //$NON-NLS-1$
        }
        MultiStatus status = new MultiStatus(PLUGIN, 0, "", null); //$NON-NLS-1$
        status.add(new Status(IStatus.ERROR, PLUGIN, "", carried)); //$NON-NLS-1$

        ApplicationException failure = new ApplicationException(status);
        assertEquals("the fixture headline is the first carried throwable hop", "level-0", //$NON-NLS-1$ //$NON-NLS-2$
            PlatformFailures.describe(failure));
        assertEquals("the carried chain must reuse the ten-hop cause cap", "level-9", //$NON-NLS-1$ //$NON-NLS-2$
            rootCause(failure));
    }

    @Test
    public void testRootCauseCycleGuardAppliesToCauseChainCarriedByChildStatus()
    {
        CyclicFailure first = new CyclicFailure("cycle-a"); //$NON-NLS-1$
        CyclicFailure second = new CyclicFailure("cycle-b"); //$NON-NLS-1$
        first.setNext(second);
        second.setNext(first);
        MultiStatus status = new MultiStatus(PLUGIN, 0, "", null); //$NON-NLS-1$
        status.add(new Status(IStatus.ERROR, PLUGIN, "", first)); //$NON-NLS-1$

        ApplicationException failure = new ApplicationException(status);
        assertEquals("the bounded carried walk must stop instead of looping forever", "cycle-b", //$NON-NLS-1$ //$NON-NLS-2$
            rootCause(failure));
    }

    @Test
    public void testObjectIdentityIsReducedToTheSimpleTypeName()
    {
        String scrubbed = PlatformFailures.withoutObjectIdentity(LEAKING_MESSAGE);
        assertEquals("WHICH object could not be persisted is the diagnosis and must survive",
            "Failed to persist reference value RoleDescriptionImpl", scrubbed);
        assertFalse("no identity hash may reach the caller",
            ANY_IDENTITY.matcher(scrubbed).find());
    }

    @Test
    public void testIdentityInTheMiddleOfASentenceIsScrubbed()
    {
        // SymbolInfoService.meaningfulOrNull answers a different question with a WHOLE-STRING
        // match and would see nothing here; this one has to find the identity wherever it sits.
        assertEquals("Failed to persist reference value RoleDescriptionImpl for feature rights",
            PlatformFailures.withoutObjectIdentity(
                "Failed to persist reference value com.example.impl.RoleDescriptionImpl@3f2a1b"
                    + " for feature rights"));
    }

    @Test
    public void testEveryIdentityInTheMessageIsScrubbed()
    {
        // One replacement is not the contract: a platform message naming both ends of a broken
        // reference carries two identities, and leaving the second one is still a leak.
        assertEquals("RoleDescriptionImpl cannot reference ObjectRightImpl",
            PlatformFailures.withoutObjectIdentity(
                "com.example.RoleDescriptionImpl@3f2a1b cannot reference"
                    + " com.example.ObjectRightImpl@7c0de4"));
    }

    @Test
    public void testInnerClassIdentityIsScrubbedAndDoesNotBreakTheReplacement()
    {
        // An inner class name carries '$', which a replacement string reads as a group
        // reference: unquoted it throws instead of scrubbing. The name is a real one - EDT's
        // quick-fix hover renders exactly this shape.
        assertEquals("BslAnnotationWithQuickFixesHover$BslAnnotationInfo carries no text",
            PlatformFailures.withoutObjectIdentity(
                "com.e1c.BslAnnotationWithQuickFixesHover$BslAnnotationInfo@1f0a2b carries no text"));
    }

    @Test
    public void testNullInNullOutAndEmptyStaysEmpty()
    {
        assertNull("null in, null out - the caller decides what a missing message means",
            PlatformFailures.withoutObjectIdentity(null));
        assertEquals("", PlatformFailures.withoutObjectIdentity(""));
    }

    @Test
    public void testMessagesWithoutAnIdentityComeBackByteIdentical()
    {
        // Real platform texts. The scrubber sits on a path that runs for EVERY failure, so a
        // message carrying no identity has to come back unchanged by construction.
        String[] intact = { "Server \"S\" start attempt failed.", "port 8429 is already in use",
            "Object not found: Catalog.Products.Attribute.Price" };
        for (String message : intact)
        {
            assertSame("a message with no identity must come back as the very same string",
                message, PlatformFailures.withoutObjectIdentity(message));
        }
    }

    @Test
    public void testAtSignsThatAreNotObjectIdentitiesAreLeftAlone()
    {
        // What keeps the pattern off ordinary prose is the right-hand boundary, so the cases that
        // matter are the ones where a hex-looking run CONTINUES into a name: "example.com" starts
        // with the hex digit 'e', and a mention has no type name in front of the at-sign at all.
        String[] intact = { "write to user@example.com", "@codex review" };
        for (String message : intact)
        {
            assertSame("an at-sign that is not an object identity must not be touched", message,
                PlatformFailures.withoutObjectIdentity(message));
        }
    }

    @Test
    public void testAnAddressWhoseDomainLabelIsEntirelyHexIsLeftAlone()
    {
        // Its own test, and the one a length floor gets WRONG: every letter of "face" is a hex
        // digit, so a floor-only pattern matches "john@face", replaces it with the "simple type
        // name" of "john", and hands the caller back the corrupted address "john.book". The run
        // is not terminal - ".book" continues it - so it is not an identity.
        String message = "notify john@face.book about it";
        assertSame("a valid address must survive byte for byte, not be rewritten", message,
            PlatformFailures.withoutObjectIdentity(message));
    }

    @Test
    public void testAnIdentityHashShorterThanFourDigitsIsScrubbed()
    {
        // The other direction the floor got wrong. The hash is Integer.toHexString(hashCode()),
        // which is one to eight digits, so a real identity is routinely shorter than four - and a
        // floor of four let exactly the #452 leak through whenever the run came up short.
        assertEquals("a short hash is still a hash",
            "Failed to persist reference value RoleDescriptionImpl",
            PlatformFailures.withoutObjectIdentity(
                "Failed to persist reference value RoleDescriptionImpl@abc"));
    }

    @Test
    public void testAnIdentityThatEndsTheSentenceIsScrubbed()
    {
        // A full stop is a separator, not a name continuation, so it must not defeat the
        // boundary: the platform ends sentences, and a scrubber that only fired at end-of-string
        // would leak every identity that happens to be punctuated.
        assertEquals("Failed to persist reference value RoleDescriptionImpl.",
            PlatformFailures.withoutObjectIdentity(
                "Failed to persist reference value com.example.impl.RoleDescriptionImpl@3f2a1b."));
    }

    @Test
    public void testScrubbingComposesWithDescribeAndCannotBeReplacedByIt()
    {
        // describe() picks the message, withoutObjectIdentity() cleans it. describe alone cannot
        // help here, because the most informative message IS the leaking one.
        RuntimeException failure = new RuntimeException(LEAKING_MESSAGE);
        assertEquals("describe selects the leaking message, as it must", LEAKING_MESSAGE,
            PlatformFailures.describe(failure));
        String scrubbed =
            PlatformFailures.withoutObjectIdentity(PlatformFailures.describe(failure));
        assertFalse("the composition must leave no identity hash",
            ANY_IDENTITY.matcher(scrubbed).find());
        assertTrue("and must keep the type name that carries the diagnosis",
            scrubbed.contains("RoleDescriptionImpl"));
    }
}
