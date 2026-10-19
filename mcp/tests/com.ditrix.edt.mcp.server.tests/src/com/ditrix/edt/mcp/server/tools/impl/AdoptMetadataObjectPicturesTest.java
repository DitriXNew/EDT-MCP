/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** End-to-end dispatch tests with real form and metadata models. */
public class AdoptMetadataObjectPicturesTest extends AdoptMetadataObjectPicturesTestSupport
{
    @Test
    public void testFormAdoptsNestedPicturesOnceAndExportsEveryTopObject() throws Exception
    {
        FormGroup group = FORM.createFormGroup();
        form.getItems().add(group);
        group.getItems().add(field("First", logo)); //$NON-NLS-1$
        group.getItems().add(field("Second", logo)); //$NON-NLS-1$
        JsonObject result = run(FORM_FQN);

        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(List.of(mdForm, logo), adoptedSources);
        assertNames(result, "found", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(models).forceExport(dtExtension,
            List.of("Catalog.Goods", "Configuration", "CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("tests"), scope.writtenProjects()); //$NON-NLS-1$
        assertTrue(scope.exportedTopObjects("tests").contains("CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(baseModel, never()).execute(any());
        verify(extensionModel, never()).execute(any());
    }

    @Test
    public void testCommonFormIncludesItsRootPicture() throws Exception
    {
        CommonForm common = MD.createCommonForm();
        common.setName("Dashboard"); //$NON-NLS-1$
        common.setForm(form);
        config.getCommonForms().add(common);
        form.setCreateButtonsGroupPicture(ref(logo));
        baseReadRoot = common;

        JsonObject result = run("CommonForm.Dashboard"); //$NON-NLS-1$
        assertEquals(List.of(common, logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAlreadyAdoptedFormRepairsPicturesFromTheExtensionCopy() throws Exception
    {
        form.getItems().add(field("BaseOnly", logo)); //$NON-NLS-1$
        CatalogForm copy = MD.createCatalogForm();
        Form editedContent = FORM.createForm();
        copy.setForm(editedContent);
        CommonPicture edited = picture("Edited"); //$NON-NLS-1$
        editedContent.getItems().add(field("Changed", edited)); //$NON-NLS-1$
        copies.put(mdForm, copy);
        extensionReadRoot = copy;

        JsonObject result = run(FORM_FQN);
        assertEquals("alreadyAdopted", result.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(edited), adoptedSources);
        assertNames(result, "found", "CommonPicture.Edited"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted", "CommonPicture.Edited"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(scope.statedNothingQueued());
        assertEquals(List.of("tests"), scope.writtenProjects()); //$NON-NLS-1$
    }

    @Test
    public void testExistingPictureIsReportedAndNeverReadopted() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        copies.put(logo, exportedCopy("CommonPicture.Logo")); //$NON-NLS-1$
        extensionReadRoot = mdForm;

        JsonObject result = run(FORM_FQN);
        assertNames(result, "alreadyPresent", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted"); //$NON-NLS-1$
        assertTrue(adoptedSources.isEmpty());
        assertTrue(scope.statedNothingQueued());
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testExtensionOwnedPictureNeedsNoBaseCounterpart() throws Exception
    {
        CommonPicture nativePicture = MD.createCommonPicture();
        nativePicture.setName("ExtensionLogo"); //$NON-NLS-1$
        Configuration nativeConfig = MD.createConfiguration();
        nativeConfig.getCommonPictures().add(nativePicture);
        CatalogForm copy = MD.createCatalogForm();
        Form edited = FORM.createForm();
        edited.getItems().add(field("Native", nativePicture)); //$NON-NLS-1$
        copy.setForm(edited);
        copies.put(mdForm, copy);
        extensionReadRoot = copy;
        when(extensionTx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)nativeConfig); //$NON-NLS-1$

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertNames(result, "alreadyPresent", "CommonPicture.ExtensionLogo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(adoptedSources.isEmpty());
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testAdoptedCopyProxyIsReadByUriInTheExtensionTransaction() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        CatalogForm proxy = MD.createCatalogForm();
        URI uri = URI.createURI("bm:/extension#Catalog.Goods.Form.Item"); //$NON-NLS-1$
        ((InternalEObject)proxy).eSetProxyURI(uri);
        copies.put(mdForm, proxy);
        when(extensionTx.getObjectByUri(uri)).thenReturn((IBmObject)mdForm);

        JsonObject result = run(FORM_FQN);
        assertEquals(List.of(logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(extensionTx, atLeastOnce()).getObjectByUri(uri);
    }

    @Test
    public void testCommonPictureProxyIsResolvedBeforeItsNameIsRead() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("bm:/base#CommonPicture.Logo")); //$NON-NLS-1$
        PictureRef resolving = mock(PictureRef.class, withSettings().extraInterfaces(InternalEObject.class));
        when(resolving.eClass()).thenReturn(McorePackage.Literals.PICTURE_REF);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, false)).thenReturn(proxy);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true)).thenReturn(logo);
        FormField field = FORM.createFormField();
        field.setHeaderPicture(resolving);
        form.getItems().add(field);

        JsonObject result = run(FORM_FQN);
        assertEquals(List.of(mdForm, logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGenericPictureProxyCanResolveToACommonPicture() throws Exception
    {
        Picture generic = mock(Picture.class, withSettings().extraInterfaces(InternalEObject.class));
        when(generic.eIsProxy()).thenReturn(true);
        PictureRef resolving = mock(PictureRef.class, withSettings().extraInterfaces(InternalEObject.class));
        when(resolving.eClass()).thenReturn(McorePackage.Literals.PICTURE_REF);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, false)).thenReturn(generic);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true)).thenReturn(logo);
        FormField item = FORM.createFormField();
        item.setHeaderPicture(resolving);
        form.getItems().add(item);

        JsonObject result = run(FORM_FQN);
        assertEquals(List.of(mdForm, logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnresolvedCommonPictureRefusesBeforeAnyAdoption() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        String uri = "unavailable:/CommonPicture.Missing"; //$NON-NLS-1$
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI(uri));
        form.getItems().add(field("Broken", proxy)); //$NON-NLS-1$

        JsonObject result = run(FORM_FQN);
        assertRefusal(result, uri, "list_common_pictures"); //$NON-NLS-1$
        assertNames(result, "unresolved", uri); //$NON-NLS-1$
    }

    @Test
    public void testMissingBasePictureRefusesBeforeAnyAdoption() throws Exception
    {
        CommonPicture absent = MD.createCommonPicture();
        absent.setName("Absent"); //$NON-NLS-1$
        form.getItems().add(field("Broken", absent)); //$NON-NLS-1$
        assertRefusal(run(FORM_FQN), "CommonPicture.Absent", "modify_metadata"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonAdoptablePictureRefusesBeforeTheFormIsWritten() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        when(adopter.isAdoptable(logo)).thenReturn(false);
        when(adopter.isAdopted(logo, target)).thenThrow(new IllegalArgumentException("not adoptable")); //$NON-NLS-1$
        assertRefusal(run(FORM_FQN), "CommonPicture.Logo", "modify_metadata"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(adopter, never()).isAdopted(logo, target);
    }

    @Test
    public void testPlatformPictureProxiesAreSkippedAlongsideCommonPictures() throws Exception
    {
        form.getItems().add(field("Common", logo)); //$NON-NLS-1$
        for (String prefix : List.of("Std", "StdExt")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Picture platform = (Picture)EcoreUtil.create(McorePackage.Literals.PICTURE_REF);
            ((InternalEObject)platform).eSetProxyURI(URI.createURI("platform:/Pictures/" + prefix + "#/Change")); //$NON-NLS-1$ //$NON-NLS-2$
            form.getItems().add(field(prefix, platform));
        }

        JsonObject result = run(FORM_FQN);
        assertNames(result, "found", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "unresolved"); //$NON-NLS-1$
        assertEquals(List.of(mdForm, logo), adoptedSources);
    }

    @Test
    public void testRepairPersistenceReflectsThePictureExportResult() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;
        when(models.forceExport(eq(dtExtension), anyList())).thenReturn(false);

        JsonObject result = run(FORM_FQN);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("persisted").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void testBilingualFormAddressKeepsPictureProgrammaticNameNotSynonym() throws Exception
    {
        String russianGoods = "\u0422\u043e\u0432\u0430\u0440\u044b"; //$NON-NLS-1$
        String russianItem = "\u042d\u043b\u0435\u043c\u0435\u043d\u0442"; //$NON-NLS-1$
        String russianLogo = "\u041b\u043e\u0433\u043e\u0442\u0438\u043f"; //$NON-NLS-1$
        goods.setName(russianGoods);
        mdForm.setName(russianItem);
        logo.setName(russianLogo);
        logo.getSynonym().put("ru", "Displayed picture"); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        String english = "Catalog." + russianGoods + ".Form." + russianItem; //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = run(english);
        assertNames(result, "adopted", "CommonPicture." + russianLogo); //$NON-NLS-1$ //$NON-NLS-2$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;

        String russian = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a." //$NON-NLS-1$
            + russianGoods + ".\u0424\u043e\u0440\u043c\u0430." + russianItem; //$NON-NLS-1$
        JsonObject again = run(russian);
        assertNames(again, "alreadyPresent", "CommonPicture." + russianLogo); //$NON-NLS-1$ //$NON-NLS-2$
        verify(adopter, times(1)).adoptAndAttach(anyList(), eq(target), any());
    }

    @Test
    public void testOutputSchemaAndDescriptionDeclareAutomaticDependencies()
    {
        assertTrue(new AdoptMetadataObjectTool().getDescription().contains("referenced form common pictures")); //$NON-NLS-1$
        JsonObject schema = JsonParser.parseString(new AdoptMetadataObjectTool().getOutputSchema()).getAsJsonObject();
        assertTrue(schema.getAsJsonObject("properties").has("dependencies")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(new AdoptMetadataObjectTool().getInputSchema().contains("includeReferencedPictures")); //$NON-NLS-1$
    }

}
