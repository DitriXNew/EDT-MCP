/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers {@link CheckDescriptionLoader}: the shipped descriptions resolve without any
 * preference being set (#31), and the id sanitizer that keeps a lookup inside the folder.
 * <p>
 * These are about the FRESH-INSTALL state - nothing configured - so the one test that depends on
 * it skips itself (rather than asserting something weaker) when the runtime does have an override
 * folder set.
 * </p>
 */
public class CheckDescriptionLoaderTest
{
    /**
     * A check the plugin really ships. Chosen because it is one of the oldest entries in
     * {@code checks/} and is referenced from the tool's own guide, so a rename would be noticed.
     */
    private static final String SHIPPED_CHECK = "begin-transaction"; //$NON-NLS-1$

    @Before
    public void setUp()
    {
        CheckDescriptionLoader.clearCache();
    }

    @Test
    public void shippedDescriptionResolvesWithNoFolderConfigured()
    {
        // The point of #31: a fresh install answers. Before it, both of these were false/null
        // until the operator downloaded the checks folder and pointed a preference at it. An
        // environment that DID configure an override is not a fresh install, so the claim is
        // not testable there - skip rather than assert something else.
        Assume.assumeFalse("needs a fresh install (no override folder configured)", //$NON-NLS-1$
            CheckDescriptionLoader.hasOverrideFolder());

        assertTrue("the plugin must ship a description for " + SHIPPED_CHECK, //$NON-NLS-1$
            CheckDescriptionLoader.has(SHIPPED_CHECK));

        String body = CheckDescriptionLoader.load(SHIPPED_CHECK);
        assertNotNull("a shipped description must be readable", body); //$NON-NLS-1$
        assertFalse("a shipped description must not be empty", body.trim().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void hasAndLoadAgreeOnEveryAnswer()
    {
        // has() is the cheap probe get_project_errors calls per marker and load() is what the
        // tool call reads; a marker flagged hasDocumentation that then cannot be read (or the
        // reverse) is the failure mode of keeping two lookups. Pinned across all three outcomes.
        for (String id : new String[] {SHIPPED_CHECK, "no-such-check-xyz-unit", "../../etc/passwd"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertEquals("has/load disagree for " + id, //$NON-NLS-1$
                CheckDescriptionLoader.has(id), CheckDescriptionLoader.load(id) != null);
        }
    }

    @Test
    public void unknownCheckIsAbsent()
    {
        assertFalse(CheckDescriptionLoader.has("no-such-check-xyz-unit")); //$NON-NLS-1$
        assertNull(CheckDescriptionLoader.load("no-such-check-xyz-unit")); //$NON-NLS-1$
    }

    @Test
    public void nullAndEmptyIdsAreAbsent()
    {
        assertFalse(CheckDescriptionLoader.has(null));
        assertFalse(CheckDescriptionLoader.has("")); //$NON-NLS-1$
        assertNull(CheckDescriptionLoader.load(null));
        assertNull(CheckDescriptionLoader.load("")); //$NON-NLS-1$
    }

    @Test
    public void traversalShapedIdsNeverResolve()
    {
        // The id is REJECTED when sanitizing changes it, rather than stripped and looked up:
        // stripping "../../begin-transaction" would leave a valid id and hand the caller a file
        // it addressed by traversal. Each of these must come back absent.
        for (String evil : new String[] {"../../etc/passwd", "../" + SHIPPED_CHECK, //$NON-NLS-1$ //$NON-NLS-2$
            "checks/" + SHIPPED_CHECK, SHIPPED_CHECK + ".md", "begin transaction"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertFalse("must not resolve: " + evil, CheckDescriptionLoader.has(evil)); //$NON-NLS-1$
            assertNull("must not resolve: " + evil, CheckDescriptionLoader.load(evil)); //$NON-NLS-1$
        }
    }

    @Test
    public void anUpperCasedIdResolvesToTheSameShippedFile()
    {
        // The lookup keeps the lower-case retry the pre-#31 folder lookup had, so a caller that
        // upper-cases an id is not told the check is undocumented.
        String upper = SHIPPED_CHECK.toUpperCase();
        assertTrue("an upper-cased id must still resolve", CheckDescriptionLoader.has(upper)); //$NON-NLS-1$
        assertEquals("it must resolve to the same body", //$NON-NLS-1$
            CheckDescriptionLoader.load(SHIPPED_CHECK), CheckDescriptionLoader.load(upper));
    }
}
