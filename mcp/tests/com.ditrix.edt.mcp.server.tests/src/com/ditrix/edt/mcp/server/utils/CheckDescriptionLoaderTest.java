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
        useOverrideFolder(folder, folder.toString());
    }

    /**
     * The same, storing {@code stored} as the preference VALUE while {@code folder} is the
     * directory it is meant to name - so a test can store a value that is not literally the path.
     *
     * @param folder the override folder that must end up being read
     * @param stored the preference value to store
     */
    private void useOverrideFolder(Path folder, String stored)
    {
        IPreferenceStore store = preferenceStore();
        Assume.assumeTrue("needs a preference store to point at an override folder", store != null);
        assertTrue("the folder must exist before it is configured", Files.isDirectory(folder)); //$NON-NLS-1$
        savedChecksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
        store.setValue(PreferenceConstants.PREF_CHECKS_FOLDER, stored);
        Assume.assumeTrue("the preference store must accept the override folder", //$NON-NLS-1$
            CheckDescriptionLoader.hasOverrideFolder());
    }

    /**
     * A fresh empty override folder.
     *
     * @return the folder
     */
    private static Path createOverrideFolder()
    {
        try
        {
            return Files.createTempDirectory("edt-mcp-checks-override"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            throw new AssertionError("could not create a temp folder", e); //$NON-NLS-1$
        }
    }

    /**
     * Writes {@code text} as UTF-8.
     *
     * @param file the file to write
     * @param text the content
     */
    private static void writeString(Path file, String text)
    {
        try
        {
            Files.writeString(file, text);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not write " + file, e); //$NON-NLS-1$
        }
    }

    /**
     * Writes raw bytes, so a test can produce content that is not valid UTF-8.
     *
     * @param file the file to write
     * @param bytes the content
     */
    private static void writeBytes(Path file, byte[] bytes)
    {
        try
        {
            Files.write(file, bytes);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not write " + file, e); //$NON-NLS-1$
        }
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
    public void anUnreadableOverrideFallsBackToTheShippedDescription()
    {
        // A regular file passes every existence check, so has() promises a body - but readString
        // reports malformed input, and a byte sequence that is not UTF-8 is exactly how a file
        // saved in another encoding arrives. If that failure ended the lookup, a stray bad file
        // would put a HOLE in the documentation for that check rather than merely failing to
        // improve it: get_project_errors would flag hasDocumentation and get_check_description
        // would answer nothing, for a check the plugin ships a perfectly good description for.
        Path folder = createOverrideFolder();
        // 0xFF cannot begin a UTF-8 sequence, so this file is unreadable as UTF-8 by construction.
        writeBytes(folder.resolve(SHIPPED_CHECK + ".md"), new byte[] { (byte)0xFF, (byte)0xFE, 'x' }); //$NON-NLS-1$
        useOverrideFolder(folder);

        assertTrue("the check still has a description", CheckDescriptionLoader.has(SHIPPED_CHECK)); //$NON-NLS-1$
        String body = CheckDescriptionLoader.load(SHIPPED_CHECK);
        assertNotNull("and the shipped one must be returned, not nothing", body); //$NON-NLS-1$
        assertFalse("what came back must not be the unreadable file's content", //$NON-NLS-1$
            body.isEmpty());
    }

    @Test
    public void anUnreadableOverrideForACheckWithNoShippedCopyIsNotAdvertised()
    {
        // The case the shipped fallback cannot rescue, because there is nothing to fall back to:
        // an override-only check whose file is unreadable. If has() answered on the file's mere
        // existence, get_project_errors would report hasDocumentation while get_check_description
        // returned nothing for the same id - the two disagreeing, which is the failure this pair
        // exists to prevent. The id is synthetic, so no shipped description can mask the case.
        String id = "not-a-real-check-unreadable-probe"; //$NON-NLS-1$
        Path folder = createOverrideFolder();
        writeBytes(folder.resolve(id + ".md"), new byte[] { (byte)0xFF, (byte)0xFE, 'x' }); //$NON-NLS-1$
        useOverrideFolder(folder);

        assertFalse("an unreadable override is not a description", CheckDescriptionLoader.has(id)); //$NON-NLS-1$
        assertNull("and load agrees there is none", CheckDescriptionLoader.load(id)); //$NON-NLS-1$
    }

    @Test
    public void aReadableOverrideOnlyCheckIsStillAdvertised()
    {
        // The other edge: reading the file to answer has() must not make an override-only check
        // invisible. A check the plugin ships nothing for is exactly what the override folder is
        // for, and it must be offered.
        String id = "not-a-real-check-readable-probe"; //$NON-NLS-1$
        Path folder = createOverrideFolder();
        writeString(folder.resolve(id + ".md"), "A CUSTOM CHECK, DOCUMENTED LOCALLY"); //$NON-NLS-1$ //$NON-NLS-2$
        useOverrideFolder(folder);

        assertTrue("an override-only check has a description", CheckDescriptionLoader.has(id)); //$NON-NLS-1$
        assertEquals("and it is the one in the folder", //$NON-NLS-1$
            "A CUSTOM CHECK, DOCUMENTED LOCALLY", CheckDescriptionLoader.load(id)); //$NON-NLS-1$
    }

    @Test
    public void theConfiguredFolderIsUsedExactlyAsItWasStored()
    {
        // A directory name may legitimately end in a space on a POSIX filesystem, and a folder
        // picker hands back the real name. Trimming before the lookup would send the loader to a
        // DIFFERENT directory and silently serve shipped descriptions instead of the operator's
        // translations. Windows will not create such a name, so the test asks for one and skips
        // where the filesystem refuses.
        Path spaced = null;
        try
        {
            Path parent = Files.createTempDirectory("edt-mcp-checks-parent"); //$NON-NLS-1$
            spaced = Files.createDirectory(parent.resolve("checks ")); //$NON-NLS-1$
        }
        catch (IOException | RuntimeException refused)
        {
            Assume.assumeNoException("this filesystem cannot hold a trailing space", refused); //$NON-NLS-1$
        }
        Assume.assumeTrue("the name must have survived creation", //$NON-NLS-1$
            spaced != null && spaced.getFileName().toString().endsWith(" ")); //$NON-NLS-1$

        writeString(spaced.resolve(SHIPPED_CHECK + ".md"), "FROM THE SPACED FOLDER"); //$NON-NLS-1$ //$NON-NLS-2$
        useOverrideFolder(spaced);

        assertEquals("the folder must be read at the path that was stored", //$NON-NLS-1$
            "FROM THE SPACED FOLDER", CheckDescriptionLoader.load(SHIPPED_CHECK)); //$NON-NLS-1$
    }

    @Test
    public void aPathPastedWithAStraySpaceStillResolves()
    {
        // The other direction, and the commoner one: nothing is named "<dir> ", the value simply
        // picked up a blank on its way into the field. Preferring the stored form must not cost
        // that case its override.
        Path folder = createOverrideFolder();
        writeString(folder.resolve(SHIPPED_CHECK + ".md"), "FROM THE PADDED VALUE"); //$NON-NLS-1$ //$NON-NLS-2$
        String padded = "  " + folder + "  "; //$NON-NLS-1$ //$NON-NLS-2$
        useOverrideFolder(folder, padded);

        // State the premise: if the store ever trimmed on the way in, this test would be proving
        // nothing about the loader and should say so here rather than pass by accident.
        assertEquals("the preference must keep the padding for this test to mean anything", //$NON-NLS-1$
            padded, preferenceStore().getString(PreferenceConstants.PREF_CHECKS_FOLDER));
        assertEquals("a padded preference value must still find its folder", //$NON-NLS-1$
            "FROM THE PADDED VALUE", CheckDescriptionLoader.load(SHIPPED_CHECK)); //$NON-NLS-1$
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
