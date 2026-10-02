/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;

/**
 * Tests for {@link BranchContexts} (#684): a branch binding is keyed by the FULL ref EDT reads
 * ({@code refs/heads/<branch>}), never by the short name.
 */
public class BranchContextsTest
{
    /** A real Cyrillic two-segment branch name (data, kept verbatim). */
    private static final String CYRILLIC =
        "задача/исправление"; //$NON-NLS-1$

    // ==================== resolve / toContext ====================

    @Test
    public void aShortNameMapsToTheFullHeadsRef()
    {
        assertEquals(InfobaseAssociationContext.of("refs/heads/feature/x"), //$NON-NLS-1$
            BranchContexts.toContext("feature/x")); //$NON-NLS-1$
    }

    @Test
    public void aFullHeadsRefIsNotDoubled()
    {
        assertEquals(InfobaseAssociationContext.of("refs/heads/feature/x"), //$NON-NLS-1$
            BranchContexts.toContext("refs/heads/feature/x")); //$NON-NLS-1$
    }

    @Test
    public void bothInputFormsResolveToTheSameTarget()
    {
        BranchContexts.Target fromShort = BranchContexts.resolve("feature/x").target(); //$NON-NLS-1$
        BranchContexts.Target fromFull = BranchContexts.resolve("refs/heads/feature/x").target(); //$NON-NLS-1$
        assertEquals("feature/x", fromShort.shortName()); //$NON-NLS-1$
        assertEquals("feature/x", fromFull.shortName()); //$NON-NLS-1$
        assertEquals("refs/heads/feature/x", fromShort.fullRef()); //$NON-NLS-1$
        assertEquals("refs/heads/feature/x", fromFull.fullRef()); //$NON-NLS-1$
    }

    @Test
    public void theLegacyKeyIsTheBareShortNameForBothInputForms()
    {
        assertEquals(InfobaseAssociationContext.of("feature/x"), //$NON-NLS-1$
            BranchContexts.resolve("feature/x").target().legacyContext()); //$NON-NLS-1$
        assertEquals(InfobaseAssociationContext.of("feature/x"), //$NON-NLS-1$
            BranchContexts.resolve("refs/heads/feature/x").target().legacyContext()); //$NON-NLS-1$
    }

    @Test
    public void aSingleSegmentNameMapsToo()
    {
        assertEquals(InfobaseAssociationContext.of("refs/heads/main"), BranchContexts.toContext("main")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCyrillicNameIsKeptVerbatim()
    {
        BranchContexts.Resolution resolution = BranchContexts.resolve(CYRILLIC);
        assertTrue(String.valueOf(resolution.error()), resolution.ok());
        assertEquals("refs/heads/" + CYRILLIC, resolution.target().fullRef()); //$NON-NLS-1$
        assertEquals(CYRILLIC, resolution.target().shortName());
    }

    @Test
    public void aRemoteTrackingRefIsRefused()
    {
        BranchContexts.Resolution resolution = BranchContexts.resolve("refs/remotes/origin/feature/x"); //$NON-NLS-1$
        assertFalse(resolution.ok());
        assertNull(resolution.target());
        assertTrue(resolution.error(), resolution.error().contains("remote-tracking")); //$NON-NLS-1$
        assertTrue("the refusal must point at the fix: " + resolution.error(), //$NON-NLS-1$
            resolution.error().contains("create_git_branch")); //$NON-NLS-1$
    }

    @Test
    public void aTagRefIsRefused()
    {
        BranchContexts.Resolution resolution = BranchContexts.resolve("refs/tags/v1"); //$NON-NLS-1$
        assertFalse(resolution.ok());
        assertTrue(resolution.error(), resolution.error().contains("refs/tags/v1")); //$NON-NLS-1$
        assertTrue(resolution.error(), resolution.error().contains("not a local branch ref")); //$NON-NLS-1$
    }

    @Test
    public void aBlankInputIsRefused()
    {
        assertFalse(BranchContexts.resolve(null).ok());
        assertFalse(BranchContexts.resolve("").ok()); //$NON-NLS-1$
        assertFalse(BranchContexts.resolve("   ").ok()); //$NON-NLS-1$
        assertTrue(BranchContexts.resolve("  ").error().contains("blank")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aBranchNamedLikeARefHasNoLegacyKey()
    {
        // The branch literally named 'refs/heads/x': its bare name is branch x's real context, never a
        // legacy key of this branch.
        BranchContexts.Resolution resolution = BranchContexts.resolve("refs/heads/refs/heads/x"); //$NON-NLS-1$
        assertTrue(String.valueOf(resolution.error()), resolution.ok());
        assertEquals("refs/heads/x", resolution.target().shortName()); //$NON-NLS-1$
        assertNull("no legacy fallback onto another branch's context", //$NON-NLS-1$
            resolution.target().legacyContext());
        assertNull(BranchContexts.resolve("refs/heads/refs/tags/v1").target().legacyContext()); //$NON-NLS-1$
    }

    @Test
    public void theDefaultNoteSaysTheAttachStandsAndHowToGetTheDefault()
    {
        BranchContexts.Target target = BranchContexts.resolve("feature/x").target(); //$NON-NLS-1$
        String note = BranchContexts.defaultNotSetNote("app1", target, new IllegalArgumentException("why")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(note, note.startsWith("Application 'app1' was attached to branch 'feature/x' " //$NON-NLS-1$
            + "(context 'refs/heads/feature/x'), but EDT did not record it")); //$NON-NLS-1$
        assertTrue(note, note.contains("(EDT: why)")); //$NON-NLS-1$
        assertTrue(note, note.contains("setDefault=true")); //$NON-NLS-1$
        String noMessage = BranchContexts.defaultNotSetNote("app1", target, new IllegalArgumentException()); //$NON-NLS-1$
        assertTrue(noMessage, noMessage.contains("(EDT: IllegalArgumentException)")); //$NON-NLS-1$
    }

    @Test
    public void aStoreFailureOfTheDefaultIsReportedWithoutTheCheckoutAdvice()
    {
        // Anything but EDT's IllegalArgumentException refusal (InfobaseAssociationException: storing the
        // property or reading the current context failed) is a platform failure a checkout would not cure.
        BranchContexts.Target target = BranchContexts.resolve("feature/x").target(); //$NON-NLS-1$
        String note = BranchContexts.defaultNotSetNote("app1", target, //$NON-NLS-1$
            new InfobaseAssociationException("disk full")); //$NON-NLS-1$
        assertEquals("Application 'app1' was attached to branch 'feature/x' (context 'refs/heads/feature/x'), " //$NON-NLS-1$
            + "but EDT failed to store it as the branch's default infobase: disk full. The attach stands; " //$NON-NLS-1$
            + "the EDT log has the details.", note); //$NON-NLS-1$
        assertFalse(note, note.contains("switch_git_branch")); //$NON-NLS-1$
        assertFalse(note, note.contains("setDefault=true")); //$NON-NLS-1$
    }

    @Test
    public void aBareHeadsPrefixNamesNoBranchAndIsRefused()
    {
        assertFalse(BranchContexts.resolve("refs/heads/").ok()); //$NON-NLS-1$
    }

    @Test
    public void aNameGitCannotUseAsABranchIsRefused()
    {
        // git check-ref-format: no space, no '..', no trailing '/', no component starting with '.'
        for (String bad : new String[] {"feature x", "a..b", "feature/", ".hidden", "x.lock"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            BranchContexts.Resolution resolution = BranchContexts.resolve(bad);
            assertFalse("must refuse '" + bad + "'", resolution.ok()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(resolution.error(), resolution.error().contains("not a valid git branch name")); //$NON-NLS-1$
        }
    }

    @Test
    public void toContextThrowsTheActionableRefusal()
    {
        try
        {
            BranchContexts.toContext("refs/remotes/origin/x"); //$NON-NLS-1$
            fail("a remote-tracking ref must not become a branch context"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("remote-tracking")); //$NON-NLS-1$
        }
    }

    // ==================== isBranchContext ====================

    @Test
    public void onlyAHeadsRefIsABranchContext()
    {
        assertTrue(BranchContexts.isBranchContext(InfobaseAssociationContext.of("refs/heads/feature/x"))); //$NON-NLS-1$
        assertFalse(BranchContexts.isBranchContext(InfobaseAssociationContext.of("feature/x"))); //$NON-NLS-1$
        assertFalse(BranchContexts.isBranchContext(InfobaseAssociationContext.of("refs/remotes/origin/x"))); //$NON-NLS-1$
        assertFalse(BranchContexts.isBranchContext(InfobaseAssociationContext.empty()));
        assertFalse(BranchContexts.isBranchContext(null));
    }

    // ==================== displayName ====================

    @Test
    public void aFullRefDisplaysAsTheShortName()
    {
        assertEquals("feature/x", //$NON-NLS-1$
            BranchContexts.displayName(InfobaseAssociationContext.of("refs/heads/feature/x"))); //$NON-NLS-1$
    }

    @Test
    public void aCyrillicFullRefDisplaysVerbatim()
    {
        assertEquals(CYRILLIC, BranchContexts.displayName(InfobaseAssociationContext.of("refs/heads/" + CYRILLIC))); //$NON-NLS-1$
    }

    @Test
    public void theEmptyContextDisplaysAsDefault()
    {
        assertEquals("(default)", BranchContexts.displayName(InfobaseAssociationContext.empty())); //$NON-NLS-1$
        assertEquals("(default)", BranchContexts.displayName(null)); //$NON-NLS-1$
    }

    @Test
    public void aLegacyShortKeyDisplaysAsIsWithTheLegacyMarker()
    {
        String shown = BranchContexts.displayName(InfobaseAssociationContext.of("feature/x")); //$NON-NLS-1$
        assertEquals("feature/x" + BranchContexts.LEGACY_SHORT_KEY_MARKER, shown); //$NON-NLS-1$
        assertTrue(shown, shown.contains("matches no branch")); //$NON-NLS-1$
        assertTrue(shown, shown.contains("set_branch_infobase")); //$NON-NLS-1$
    }

    @Test
    public void aRemoteRefDisplaysAsIsWithTheNonBranchMarker()
    {
        assertEquals("refs/remotes/origin/x" + BranchContexts.NON_BRANCH_REF_MARKER, //$NON-NLS-1$
            BranchContexts.displayName(InfobaseAssociationContext.of("refs/remotes/origin/x"))); //$NON-NLS-1$
    }

    @Test
    public void aCommitIdDisplaysAsADetachedHeadContext()
    {
        String sha = "0123456789abcdef0123456789abcdef01234567"; //$NON-NLS-1$
        assertEquals(sha + BranchContexts.COMMIT_CONTEXT_MARKER,
            BranchContexts.displayName(InfobaseAssociationContext.of(sha)));
    }

    @Test
    public void everyMarkerIsDistinctAndNonEmpty()
    {
        assertNotNull(BranchContexts.LEGACY_SHORT_KEY_MARKER);
        assertFalse(BranchContexts.LEGACY_SHORT_KEY_MARKER.equals(BranchContexts.NON_BRANCH_REF_MARKER));
        assertFalse(BranchContexts.LEGACY_SHORT_KEY_MARKER.equals(BranchContexts.COMMIT_CONTEXT_MARKER));
        assertFalse(BranchContexts.NON_BRANCH_REF_MARKER.equals(BranchContexts.COMMIT_CONTEXT_MARKER));
    }
}
