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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

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

    /** A second shipped check, so an override covering one can be shown not to hide the rest. */
    private static final String OTHER_SHIPPED_CHECK = "commit-transaction"; //$NON-NLS-1$

    /** The override folder this test pointed the preference at, restored in {@link #tearDown()}. */
    private String savedChecksFolder;

    @Before
    public void setUp()
    {
        CheckDescriptionLoader.clearCache();
    }

    @After
    public void tearDown()
    {
        if (savedChecksFolder != null)
        {
            preferenceStore().setValue(PreferenceConstants.PREF_CHECKS_FOLDER, savedChecksFolder);
            savedChecksFolder = null;
        }
    }

    /**
     * The preference store, or {@code null} when the runtime has no Activator.
     *
     * @return the store, or {@code null}
     */
    private static IPreferenceStore preferenceStore()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }

    /**
     * Points the checks-folder preference at {@code folder} for the duration of one test, or skips
     * the test when this runtime has no preference store to point.
     *
     * @param folder the override folder
     */
    private void useOverrideFolder(Path folder)
    {
        IPreferenceStore store = preferenceStore();
        Assume.assumeTrue("needs a preference store to point at an override folder", store != null);
        savedChecksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
        store.setValue(PreferenceConstants.PREF_CHECKS_FOLDER, folder.toString());
        Assume.assumeTrue("the preference store must accept the override folder", //$NON-NLS-1$
            CheckDescriptionLoader.hasOverrideFolder());
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
    public void aDirectoryNamedLikeADescriptionIsNotOne() throws IOException
    {
        // A DIRECTORY called "<id>.md" in the override folder satisfies Files.exists but cannot be
        // read, so an exists-based lookup would have has() promise a description that load() then
        // fails to produce - get_project_errors flagging hasDocumentation on a marker whose text
        // get_check_description cannot return. The id is synthetic, so nothing shipped can mask it.
        String id = "not-a-real-check-dir-probe"; //$NON-NLS-1$
        Path folder = Files.createTempDirectory("edt-mcp-checks-override"); //$NON-NLS-1$
        Files.createDirectory(folder.resolve(id + ".md")); //$NON-NLS-1$
        useOverrideFolder(folder);

        assertFalse("a directory is not a description", CheckDescriptionLoader.has(id)); //$NON-NLS-1$
        assertNull("and there is nothing to read", CheckDescriptionLoader.load(id)); //$NON-NLS-1$
    }

    @Test
    public void anOverrideFileWinsOverTheShippedOne() throws IOException
    {
        // The other half of the same lookup, and the reason the preference still exists: a file in
        // the override folder REPLACES the shipped description for that one check, and only that
        // one - which is what makes overriding a single check safe.
        Path folder = Files.createTempDirectory("edt-mcp-checks-override"); //$NON-NLS-1$
        Files.writeString(folder.resolve(SHIPPED_CHECK + ".md"), "OVERRIDDEN BODY"); //$NON-NLS-1$ //$NON-NLS-2$
        useOverrideFolder(folder);

        assertEquals("the override must win for the check it covers", //$NON-NLS-1$
            "OVERRIDDEN BODY", CheckDescriptionLoader.load(SHIPPED_CHECK)); //$NON-NLS-1$
        // ... and every other check still resolves from the plugin.
        assertTrue("a check the override does not cover must still come from the bundle", //$NON-NLS-1$
            CheckDescriptionLoader.has(OTHER_SHIPPED_CHECK));
    }

    @Test
    public void anUpperCasedIdResolvesToTheSameShippedFile()
    {
        // The lookup keeps the lower-case retry the pre-#31 folder lookup had, so a caller that
        // upper-cases an id is not told the check is undocumented. Locale.ROOT on BOTH sides: the
        // default-locale case mapping is the bug being guarded against (under tr_TR, 'i' and 'I'
        // do not round-trip), so a test that used it would fail on the very machine it protects.
        String upper = SHIPPED_CHECK.toUpperCase(Locale.ROOT);
        assertTrue("an upper-cased id must still resolve", CheckDescriptionLoader.has(upper)); //$NON-NLS-1$
        assertEquals("it must resolve to the same body", //$NON-NLS-1$
            CheckDescriptionLoader.load(SHIPPED_CHECK), CheckDescriptionLoader.load(upper));
    }
}
