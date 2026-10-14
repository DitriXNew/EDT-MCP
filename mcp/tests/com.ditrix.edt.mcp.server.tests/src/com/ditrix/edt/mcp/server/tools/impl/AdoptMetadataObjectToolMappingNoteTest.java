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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pins the mapping note of {@link AdoptMetadataObjectTool} (issue #706). EDT writes an adopted object's
 * {@code extendedConfigurationObject} link - the by-ID mapping to the base - only when, at adoption, the
 * extension root keeps mapping by IDs AND the platform version is above 8.3.14 (its
 * {@code AttributesOnlyAdoptingCopier}). An extension root built by an older create_project does not keep
 * mapping by IDs, so everything adopted into it is mapped by NAME, silently; the result now says so.
 */
public class AdoptMetadataObjectToolMappingNoteTest
{
    private static final String EXT = "Base.Ext"; //$NON-NLS-1$

    private static final Version CURRENT = new Version("8.3.27"); //$NON-NLS-1$

    private AdoptMetadataObjectTool.AdoptionSource source;

    @Before
    public void setUp()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Base"); //$NON-NLS-1$
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        config.getCatalogs().add(goods);
        source = AdoptMetadataObjectTool.AdoptionSource.resolve(MetadataScope.ofConfiguration(config),
            "Catalog.Goods"); //$NON-NLS-1$
        assertNotNull(source);
    }

    private static JsonObject json(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }

    // ==================== the predicate: EDT's own ====================

    @Test
    public void testARootThatKeepsMappingByIdsOnACurrentPlatformNeedsNoNote()
    {
        assertNull(AdoptMetadataObjectTool.byNameMappingNote(EXT, CURRENT, Boolean.TRUE));
    }

    @Test
    public void testARootThatDoesNotKeepMappingByIdsIsMappedByName()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, CURRENT, Boolean.FALSE);

        assertNotNull(note);
        assertTrue(note, note.contains("'" + EXT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertTrue(note, note.contains("keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
        assertTrue("the way out is named", note.contains("create_project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the version is fine here, so it is not blamed", //$NON-NLS-1$
            note.contains("the extension's platform version is")); //$NON-NLS-1$
        assertFalse("nor is its remedy given", note.contains("Mapping by ID needs a platform version")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAtPlatform8314OrLowerEvenAMappingRootMapsByName()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, new Version("8.3.14"), Boolean.TRUE); //$NON-NLS-1$

        assertNotNull("EDT writes the link only ABOVE 8.3.14 (V8_3_14.isLessThan(version))", note); //$NON-NLS-1$
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertTrue(note, note.contains("platform version is 8.3.14")); //$NON-NLS-1$
        assertTrue(note, note.contains("above 8.3.14")); //$NON-NLS-1$
        assertFalse("the root keeps mapping, so the flag is not blamed", //$NON-NLS-1$
            note.contains("keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
    }

    @Test
    public void testAbove8314TheVersionAloneNeverAddsANote()
    {
        assertNull(AdoptMetadataObjectTool.byNameMappingNote(EXT, new Version("8.3.15"), Boolean.TRUE)); //$NON-NLS-1$
    }

    @Test
    public void testBothReasonsAreNamedWhenBothHold()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, new Version("8.3.12"), Boolean.FALSE); //$NON-NLS-1$

        assertNotNull(note);
        assertTrue(note, note.contains("keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
        assertTrue(note, note.contains("platform version is 8.3.12")); //$NON-NLS-1$
        // A recreated extension inherits the same base version, and at it EDT maps by name whatever the flag:
        // turning the flag on, or recreating the extension, would change nothing - so neither is advised.
        assertTrue("the version is the remedy given", //$NON-NLS-1$
            note.contains("Mapping by ID needs a platform version above 8.3.14.")); //$NON-NLS-1$
        assertFalse("recreating keeps the old version, so it is not advised", note.contains("create_project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("turning the flag on cannot help at this version", //$NON-NLS-1$
            note.contains("turn keepMappingToExtendedConfigurationObjectsByIDs on")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownFlagClaimsNothingOnACurrentPlatform()
    {
        assertNull(AdoptMetadataObjectTool.byNameMappingNote(EXT, CURRENT, null));
    }

    @Test
    public void testAnUnknownFlagStillLetsAnOldPlatformSpeak()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, new Version("8.3.10"), null); //$NON-NLS-1$

        assertNotNull(note);
        assertTrue(note, note.contains("platform version is 8.3.10")); //$NON-NLS-1$
        assertFalse(note, note.contains("is false")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownVersionStillLetsAFalseFlagSpeak()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, null, Boolean.FALSE);

        assertNotNull(note);
        assertTrue(note, note.contains("keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
        assertTrue("an unknown version is not blamed, so the way out stays", note.contains("create_project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the flag, read from the extension's model ====================

    /** A model whose read transaction sees {@code top} as its configuration root. */
    private static IBmModel modelWithRoot(Object top)
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)top); //$NON-NLS-1$
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });
        return model;
    }

    private static Configuration extensionRoot(boolean keepsMappingByIds)
    {
        Configuration root = MdClassFactory.eINSTANCE.createConfiguration();
        root.setKeepMappingToExtendedConfigurationObjectsByIDs(keepsMappingByIds);
        return root;
    }

    @Test
    public void testAFalseFlagIsReadInAReadTransactionNeverAWrite()
    {
        IBmModel model = modelWithRoot(extensionRoot(false));

        assertEquals(Boolean.FALSE, AdoptMetadataObjectTool.keepsMappingByIds(model));
        verify(model, times(1)).executeReadonlyTask(any());
        verify(model, never()).execute(any());
    }

    @Test
    public void testATrueFlagIsReadAsTrue()
    {
        assertEquals(Boolean.TRUE, AdoptMetadataObjectTool.keepsMappingByIds(modelWithRoot(extensionRoot(true))));
    }

    @Test
    public void testAModelWithoutAConfigurationRootTellsNothing()
    {
        assertNull(AdoptMetadataObjectTool.keepsMappingByIds(modelWithRoot(MdClassFactory.eINSTANCE.createCatalog())));
        assertNull(AdoptMetadataObjectTool.keepsMappingByIds(modelWithRoot(null)));
    }

    @Test
    public void testAFailingReadTellsNothing()
    {
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenThrow(new IllegalStateException("model is closing")); //$NON-NLS-1$

        assertNull(AdoptMetadataObjectTool.keepsMappingByIds(model));
    }

    @Test
    public void testTheNoteIsBuiltFromTheFlagReadFromTheExtensionsModel()
    {
        String note = AdoptMetadataObjectTool.mappingNote(EXT, CURRENT, modelWithRoot(extensionRoot(false)));

        assertNotNull("a root read as not keeping mapping by IDs gets the note", note); //$NON-NLS-1$
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertTrue(note, note.contains("keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
        assertNull("a root read as keeping it gets none", //$NON-NLS-1$
            AdoptMetadataObjectTool.mappingNote(EXT, CURRENT, modelWithRoot(extensionRoot(true))));
    }

    @Test
    public void testWithoutAReadableFlagOnlyTheVersionCanSpeak()
    {
        IBmModel failing = mock(IBmModel.class);
        when(failing.executeReadonlyTask(any())).thenThrow(new IllegalStateException("model is closing")); //$NON-NLS-1$

        assertNull("no model: nothing is blamed", AdoptMetadataObjectTool.mappingNote(EXT, CURRENT, null)); //$NON-NLS-1$
        assertNull("a failed read: nothing is blamed", AdoptMetadataObjectTool.mappingNote(EXT, CURRENT, failing)); //$NON-NLS-1$
        String note = AdoptMetadataObjectTool.mappingNote(EXT, new Version("8.3.10"), null); //$NON-NLS-1$
        assertNotNull(note);
        assertTrue(note, note.contains("platform version is 8.3.10")); //$NON-NLS-1$
        assertFalse(note, note.contains("is false")); //$NON-NLS-1$
    }

    // ==================== the results ====================

    @Test
    public void testAnAdoptionMappedByNameSaysSoInItsMessage()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, CURRENT, Boolean.FALSE);

        JsonObject adopted = json(AdoptMetadataObjectTool.adoptedResult(source, EXT, true, note));

        assertTrue(adopted.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("adopted", adopted.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(note, adopted.get("message").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testAnAdoptionMappedByIdCarriesNoMessage()
    {
        JsonObject adopted = json(AdoptMetadataObjectTool.adoptedResult(source, EXT, true, null));

        assertFalse(adopted.has("message")); //$NON-NLS-1$
    }

    @Test
    public void testAnAlreadyAdoptedObjectKeepsItsMessageAndGainsTheNote()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, CURRENT, Boolean.FALSE);

        JsonObject already = json(AdoptMetadataObjectTool.alreadyAdoptedResult(source, EXT, note));

        String message = already.get("message").getAsString(); //$NON-NLS-1$
        assertTrue("the existing sentence comes first, unchanged", //$NON-NLS-1$
            message.startsWith("'Catalog.Goods' is already adopted in extension '" + EXT + "'. ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("merged, not overwritten", message.endsWith(note)); //$NON-NLS-1$
    }

    @Test
    public void testAnAlreadyAdoptedObjectWithoutANoteKeepsTheOldMessageExactly()
    {
        JsonObject already = json(AdoptMetadataObjectTool.alreadyAdoptedResult(source, EXT, null));

        assertEquals("'Catalog.Goods' is already adopted in extension '" + EXT + "'.", //$NON-NLS-1$ //$NON-NLS-2$
            already.get("message").getAsString()); //$NON-NLS-1$
    }
}
