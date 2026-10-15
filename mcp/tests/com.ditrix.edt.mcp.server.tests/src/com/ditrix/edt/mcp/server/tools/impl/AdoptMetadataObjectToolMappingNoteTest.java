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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.UUID;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pins the mapping note of {@link AdoptMetadataObjectTool} (issue #706). EDT maps an adopted copy to its base object
 * by ID only through the {@code extendedConfigurationObject} link its copier writes AT ADOPTION - and only when, at
 * that moment, the extension root keeps mapping by IDs and the platform version is above 8.3.14
 * ({@code AttributesOnlyAdoptingCopier}); nothing adds the link later, and EDT's rename of a base object carries along
 * exactly the counterpart whose link names it ({@code MdRefactoringService.getAdoptedCounterpart}). So the note is
 * decided by the COPY - read back in a read transaction of the extension's model - and the root's flag and the
 * platform version only name the reason: a copy adopted while the flag was off keeps no link after the flag is turned
 * on, and a copy adopted while it was on keeps its link after it is turned off.
 */
public class AdoptMetadataObjectToolMappingNoteTest
{
    private static final String EXT = "Base.Ext"; //$NON-NLS-1$

    private static final Version CURRENT = new Version("8.3.27"); //$NON-NLS-1$

    /** The BM id the adopted copy below carries. */
    private static final long COPY_ID = 42L;

    private static final UUID BASE_CALC = UUID.fromString("7c0ffee1-ca1c-4a0d-8e57-1234567890ab"); //$NON-NLS-1$

    private AdoptMetadataObjectTool.AdoptionSource source;

    /** The adopted copy as the adopter hands it over: only its BM id is ever read off it. */
    private EObject copy;

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

        CommonModule handed = mock(CommonModule.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)handed).bmGetId()).thenReturn(Long.valueOf(COPY_ID));
        copy = handed;
    }

    private static JsonObject json(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }

    /** The copy as the extension's model holds it: linked to the base object by ID, or not. */
    private static CommonModule copyInModel(boolean linked)
    {
        CommonModule module = MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Calc"); //$NON-NLS-1$
        if (linked)
        {
            module.setExtendedConfigurationObject(BASE_CALC);
        }
        return module;
    }

    private static Configuration extensionRoot(boolean keepsMappingByIds)
    {
        Configuration root = MdClassFactory.eINSTANCE.createConfiguration();
        root.setKeepMappingToExtendedConfigurationObjectsByIDs(keepsMappingByIds);
        return root;
    }

    /** A model whose read transaction sees {@code root} as its configuration root and {@code byId} under every id. */
    private static IBmModel model(Object root, Object byId)
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)root); //$NON-NLS-1$
        when(tx.getObjectById(anyLong())).thenReturn((IBmObject)byId);
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });
        return model;
    }

    /** The note the tool composes for {@link #copy} in {@code model}, with the flag read from that same model. */
    private String note(IBmModel model, Version version, boolean adoptedEarlier)
    {
        return AdoptMetadataObjectTool.mappingNote(EXT, model, copy, version,
            AdoptMetadataObjectTool.keepsMappingByIds(model), adoptedEarlier);
    }

    // ==================== the COPY decides, not the root's flag ====================

    @Test
    public void testAnEarlierCopyLinkedByIdNeedsNoNoteThoughTheRootNoLongerKeepsMapping()
    {
        // Adopted while the root kept mapping by IDs, then the flag was turned off: the copy still holds its link, and
        // EDT's rename of the base object still carries it along.
        String note = note(model(extensionRoot(false), copyInModel(true)), CURRENT, true);

        assertNull("a copy that carries the link is mapped by ID, whatever the root's flag says now: " + note, note); //$NON-NLS-1$
    }

    @Test
    public void testAnEarlierCopyWithoutTheLinkIsMappedByNameThoughTheRootNowKeepsMapping()
    {
        // Adopted while the root did not keep mapping by IDs, then the flag was turned on: EDT never adds the link.
        String note = note(model(extensionRoot(true), copyInModel(false)), CURRENT, true);

        assertNotNull("a copy without the link is mapped by name, whatever the root's flag says now", note); //$NON-NLS-1$
        assertTrue(note, note.contains("'" + EXT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertTrue(note, note.contains("carries no extendedConfigurationObject link")); //$NON-NLS-1$
        assertTrue("neither the flag nor the version explains it now, so the copy's likely history does", //$NON-NLS-1$
            note.contains("it was most likely adopted while the extension mapped by name")); //$NON-NLS-1$
        // Only the copy's link, the flag and the version NOW are known: a copy without a link also comes from an XML
        // import of an extension exported without IDs, or from a version raised after the adoption.
        assertFalse("a history inferred from the present is never stated as a fact", //$NON-NLS-1$
            note.contains("; it was adopted while")); //$NON-NLS-1$
        assertTrue(note, note.contains("objects adopted before stay mapped by name")); //$NON-NLS-1$
        assertFalse("the flag is on now, so it is not blamed", note.contains("is false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the version is fine, so it is not blamed", note.contains("the extension's platform version is")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("turning on a flag that is on already is no advice", note.contains("turn keepMapping")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAFreshCopyLinkedByIdNeedsNoNoteWhateverTheFlagReadBeforeTheAdoption()
    {
        assertNull(note(model(extensionRoot(false), copyInModel(true)), CURRENT, false));
        assertNull(note(model(extensionRoot(true), copyInModel(true)), CURRENT, false));
    }

    @Test
    public void testAFreshCopyWithoutTheLinkGetsTheNoteWhateverTheFlagReadBeforeTheAdoption()
    {
        String note = note(model(extensionRoot(true), copyInModel(false)), CURRENT, false);

        assertNotNull("the copy this call adopted has no link: it is mapped by name", note); //$NON-NLS-1$
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertFalse("a copy adopted just now has no earlier history to tell", //$NON-NLS-1$
            note.contains("adopted while the extension mapped by name")); //$NON-NLS-1$
        assertFalse(note, note.contains("is false")); //$NON-NLS-1$
    }

    @Test
    public void testAFreshCopyWithoutTheLinkNamesTheFlagItsAdoptionRanUnder()
    {
        String note = note(model(extensionRoot(false), copyInModel(false)), CURRENT, false);

        assertNotNull(note);
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertTrue(note, note.contains(", and here the root's keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
        assertFalse("for a copy adopted just now the flag IS the reason, not a likely one", //$NON-NLS-1$
            note.contains("likely")); //$NON-NLS-1$
        assertTrue("the way out is named", note.contains("create_project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(note, note.contains("objects adopted before that stay mapped by name")); //$NON-NLS-1$
        assertFalse("the version is fine here, so it is not blamed", //$NON-NLS-1$
            note.contains("the extension's platform version is")); //$NON-NLS-1$
        assertFalse("nor is its remedy given", note.contains("Mapping by ID needs a platform version")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnEarlierCopyWithoutTheLinkNamesTheFlagReadNowAsTheLikelyReason()
    {
        String note = note(model(extensionRoot(false), copyInModel(false)), CURRENT, true);

        assertNotNull(note);
        assertTrue(note, note.contains(
            "it was adopted earlier, and now the root's keepMappingToExtendedConfigurationObjectsByIDs is false - the likely reason")); //$NON-NLS-1$
        assertTrue("the way out is named", note.contains("create_project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the two paths: the copy already there, and the copy this call adopted ====================

    @Test
    public void testTheAlreadyAdoptedPathJudgesTheCopyAsAnEarlierAdoption()
    {
        IBmModel model = model(extensionRoot(true), copyInModel(false));
        AdoptMetadataObjectTool.MappingEvidence mapping = new AdoptMetadataObjectTool.MappingEvidence(EXT, model,
            CURRENT, AdoptMetadataObjectTool.keepsMappingByIds(model));

        String message = json(AdoptMetadataObjectTool.alreadyAdoptedResult(source, EXT, mapping.forEarlierCopy(copy)))
            .get("message").getAsString(); //$NON-NLS-1$

        assertTrue(message, message.startsWith("'Catalog.Goods' is already adopted in extension '" + EXT + "'. ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a copy that was already there has a history the flag no longer shows: " + message, //$NON-NLS-1$
            message.contains("it was most likely adopted while the extension mapped by name")); //$NON-NLS-1$
    }

    @Test
    public void testTheAdoptionPathJudgesTheCopyAsAdoptedByThisCall()
    {
        IBmModel model = model(extensionRoot(false), copyInModel(false));
        AdoptMetadataObjectTool.MappingEvidence mapping = new AdoptMetadataObjectTool.MappingEvidence(EXT, model,
            CURRENT, AdoptMetadataObjectTool.keepsMappingByIds(model));

        String note = mapping.forNewCopy(copy);

        assertNotNull(note);
        assertTrue(note, note.contains(", and here the root's keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
        assertFalse("the flag read before this adoption IS its reason, not a likely one", note.contains("likely")); //$NON-NLS-1$ //$NON-NLS-2$
        IBmModel linked = model(extensionRoot(false), copyInModel(true));
        assertNull("a new copy that carries the link needs no note", //$NON-NLS-1$
            new AdoptMetadataObjectTool.MappingEvidence(EXT, linked, CURRENT, Boolean.FALSE).forNewCopy(copy));
    }

    // ==================== the version as the reason ====================

    @Test
    public void testAtPlatform8314OrLowerTheVersionIsTheReasonNotTheFlag()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, Boolean.FALSE, new Version("8.3.14"), //$NON-NLS-1$
            Boolean.TRUE, false);

        assertNotNull(note);
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertTrue(note, note.contains("the extension's platform version is 8.3.14")); //$NON-NLS-1$
        assertTrue(note, note.contains("Mapping by ID needs a platform version above 8.3.14.")); //$NON-NLS-1$
        assertFalse("the root keeps mapping, so the flag is not blamed", //$NON-NLS-1$
            note.contains("keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
    }

    @Test
    public void testBothReasonsAreNamedWhenBothHold()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, Boolean.FALSE, new Version("8.3.12"), //$NON-NLS-1$
            Boolean.FALSE, false);

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
    public void testAnUnknownFlagIsNotBlamedButTheCopyStillSpeaks()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, Boolean.FALSE, CURRENT, null, false);

        assertNotNull("the copy has no link: that is known, so it is said", note); //$NON-NLS-1$
        assertTrue(note, note.contains("by NAME, not by ID")); //$NON-NLS-1$
        assertFalse(note, note.contains("is false")); //$NON-NLS-1$
        assertFalse("no known reason, so no remedy", note.contains("create_project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnUnknownVersionStillLetsAFalseFlagSpeak()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, Boolean.FALSE, null, Boolean.FALSE, false);

        assertNotNull(note);
        assertTrue(note, note.contains("keepMappingToExtendedConfigurationObjectsByIDs is false")); //$NON-NLS-1$
        assertTrue("an unknown version is not blamed, so the way out stays", note.contains("create_project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== a copy that cannot be read claims nothing ====================

    @Test
    public void testACopyThatCannotBeReadIsNeverBlamedWhateverTheFlagAndTheVersion()
    {
        // Even the strongest reasons - a false flag and a version at which EDT never links - name no copy that
        // could not be read: an unknown fact is never blamed.
        Version old = new Version("8.3.10"); //$NON-NLS-1$
        assertNull(AdoptMetadataObjectTool.byNameMappingNote(EXT, null, old, Boolean.FALSE, false));
        assertNull(AdoptMetadataObjectTool.byNameMappingNote(EXT, null, old, Boolean.FALSE, true));
        assertNull("no model", AdoptMetadataObjectTool.mappingNote(EXT, null, copy, old, Boolean.FALSE, true)); //$NON-NLS-1$
        assertNull("no copy", AdoptMetadataObjectTool.mappingNote(EXT, //$NON-NLS-1$
            model(extensionRoot(false), copyInModel(false)), null, old, Boolean.FALSE, true));
    }

    // ==================== the copy's link, read from the extension's model ====================

    @Test
    public void testTheLinkIsReadInAReadTransactionNeverAWrite()
    {
        IBmModel model = model(extensionRoot(true), copyInModel(true));

        assertEquals(Boolean.TRUE, AdoptMetadataObjectTool.carriesBaseLink(model, copy));
        verify(model, times(1)).executeReadonlyTask(any());
        verify(model, never()).execute(any());
    }

    @Test
    public void testACopyWithoutTheLinkIsReadAsUnlinked()
    {
        assertEquals(Boolean.FALSE,
            AdoptMetadataObjectTool.carriesBaseLink(model(extensionRoot(true), copyInModel(false)), copy));
    }

    @Test
    public void testTheCopyIsReResolvedInTheTransactionByItsBmId()
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getObjectById(COPY_ID)).thenReturn((IBmObject)copyInModel(true));
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });

        // The handed-over copy itself says nothing (a mock): the answer is what the transaction holds under its id.
        assertEquals(Boolean.TRUE, AdoptMetadataObjectTool.carriesBaseLink(model, copy));
        verify(tx).getObjectById(COPY_ID);
    }

    @Test
    public void testAProxyOfTheCopyIsReResolvedInTheTransactionByItsUri()
    {
        // EDT's adopter can name an existing copy by a global-scope entry - a proxy, whose BM id means nothing.
        CommonModule proxy = MdClassFactory.eINSTANCE.createCommonModule();
        URI uri = URI.createURI("bm://Base.Ext/CommonModule.Calc"); //$NON-NLS-1$
        ((InternalEObject)proxy).eSetProxyURI(uri);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getObjectByUri(uri)).thenReturn((IBmObject)copyInModel(true));
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            return task.execute(tx, null);
        });

        assertEquals(Boolean.TRUE, AdoptMetadataObjectTool.carriesBaseLink(model, proxy));
        verify(tx).getObjectByUri(uri);
        verify(tx, never()).getObjectById(anyLong());
    }

    @Test
    public void testNothingIsToldWhenTheCopyCannotBeRead()
    {
        assertNull("no model", AdoptMetadataObjectTool.carriesBaseLink(null, copy)); //$NON-NLS-1$
        assertNull("no copy", AdoptMetadataObjectTool.carriesBaseLink(model(null, copyInModel(true)), null)); //$NON-NLS-1$
        assertNull("not a BM object", //$NON-NLS-1$
            AdoptMetadataObjectTool.carriesBaseLink(model(null, copyInModel(true)), mock(EObject.class)));
        assertNull("nothing under its id", AdoptMetadataObjectTool.carriesBaseLink(model(null, null), copy)); //$NON-NLS-1$
        assertNull("not a metadata object under its id", //$NON-NLS-1$
            AdoptMetadataObjectTool.carriesBaseLink(model(null, mock(IBmObject.class)), copy));

        IBmModel failing = mock(IBmModel.class);
        when(failing.executeReadonlyTask(any())).thenThrow(new IllegalStateException("model is closing")); //$NON-NLS-1$
        assertNull("a failed read", AdoptMetadataObjectTool.carriesBaseLink(failing, copy)); //$NON-NLS-1$
    }

    // ==================== the root's flag, read from the extension's model ====================

    @Test
    public void testAFalseFlagIsReadInAReadTransactionNeverAWrite()
    {
        IBmModel model = model(extensionRoot(false), null);

        assertEquals(Boolean.FALSE, AdoptMetadataObjectTool.keepsMappingByIds(model));
        verify(model, times(1)).executeReadonlyTask(any());
        verify(model, never()).execute(any());
    }

    @Test
    public void testATrueFlagIsReadAsTrue()
    {
        assertEquals(Boolean.TRUE, AdoptMetadataObjectTool.keepsMappingByIds(model(extensionRoot(true), null)));
    }

    @Test
    public void testAModelWithoutAConfigurationRootTellsNothing()
    {
        assertNull(AdoptMetadataObjectTool.keepsMappingByIds(model(MdClassFactory.eINSTANCE.createCatalog(), null)));
        assertNull(AdoptMetadataObjectTool.keepsMappingByIds(model(null, null)));
    }

    @Test
    public void testAFailingReadTellsNothing()
    {
        IBmModel model = mock(IBmModel.class);
        when(model.executeReadonlyTask(any())).thenThrow(new IllegalStateException("model is closing")); //$NON-NLS-1$

        assertNull(AdoptMetadataObjectTool.keepsMappingByIds(model));
    }

    // ==================== the results ====================

    @Test
    public void testAnAdoptionMappedByNameSaysSoInItsMessage()
    {
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, Boolean.FALSE, CURRENT, Boolean.FALSE, false);

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
        String note = AdoptMetadataObjectTool.byNameMappingNote(EXT, Boolean.FALSE, CURRENT, Boolean.FALSE, true);

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
