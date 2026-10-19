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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com.ditrix.edt.mcp.server.utils.FormPictureDependencies;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Gate-review regressions; all dispatch cases can run against the unchanged round-one tool. */
public class AdoptMetadataObjectPicturesRegressionTest extends AdoptMetadataObjectPicturesTestSupport
{
    @Test
    public void testCatalogAdoptionIgnoresAllOwnedFormsAndKeepsMasterResult() throws Exception
    {
        form.getItems().add(field("Unused", logo)); //$NON-NLS-1$
        CatalogForm unreadable = MD.createCatalogForm();
        unreadable.setName("Unreadable"); //$NON-NLS-1$
        Form proxy = FORM.createForm();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing:/form")); //$NON-NLS-1$
        unreadable.setForm(proxy);
        goods.getForms().add(unreadable);
        baseReadRoot = goods;

        run("Catalog.Goods"); //$NON-NLS-1$
        assertEquals(AdoptMetadataObjectTool.adoptedResult(
            AdoptMetadataObjectTool.AdoptionSource.resolve(MetadataScope.ofConfiguration(config), "Catalog.Goods"), //$NON-NLS-1$
            "tests", true, null), rawResponse); //$NON-NLS-1$
        assertEquals(List.of(goods), adoptedSources);
        verify(baseModel, never()).executeReadonlyTask(any());
        verify(adopter, never()).adoptAndAttach(anyList(), eq(target), any());
    }

    @Test
    public void testCollectorNeverWalksOwnerForms()
    {
        form.getItems().add(field("Unused", logo)); //$NON-NLS-1$
        assertTrue(FormPictureDependencies.collect(goods).found.isEmpty());
    }

    @Test
    public void testNonFormReadoptionKeepsExactMasterShapeAndStatesNothingQueued() throws Exception
    {
        CommonModule module = MD.createCommonModule();
        module.setName("Calc"); //$NON-NLS-1$
        config.getCommonModules().add(module);
        copies.put(module, exportedCopy("CommonModule.Calc")); //$NON-NLS-1$

        run("CommonModule.Calc"); //$NON-NLS-1$
        assertEquals(AdoptMetadataObjectTool.alreadyAdoptedResult(
            AdoptMetadataObjectTool.AdoptionSource.resolve(MetadataScope.ofConfiguration(config), "CommonModule.Calc"), //$NON-NLS-1$
            "tests", null), rawResponse); //$NON-NLS-1$
        assertTrue(scope.statedNothingQueued());
        assertEquals(List.of(), scope.writtenProjects());
        verify(baseModel, never()).executeReadonlyTask(any());
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testMemberAdoptionKeepsMasterShapeWithoutDependencies() throws Exception
    {
        CatalogAttribute weight = MD.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        goods.getAttributes().add(weight);
        form.getItems().add(field("Unused", logo)); //$NON-NLS-1$

        JsonObject result = run("Catalog.Goods.Attribute.Weight"); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.has("dependencies")); //$NON-NLS-1$
        assertEquals(List.of(weight), adoptedSources);
        verify(baseModel, never()).executeReadonlyTask(any());
    }

    @Test
    public void testFormAndAllMissingPicturesUseOneBatch() throws Exception
    {
        CommonPicture other = picture("Other"); //$NON-NLS-1$
        form.getItems().add(field("First", logo)); //$NON-NLS-1$
        form.getItems().add(field("Second", other)); //$NON-NLS-1$
        form.getItems().add(field("Duplicate", logo)); //$NON-NLS-1$

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(List.of(List.of(mdForm, logo, other)), batches);
        verify(adopter, never()).adoptAndAttach(any(EObject.class), eq(target), any());
        assertNames(result, "adopted", "CommonPicture.Logo", "CommonPicture.Other"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        verify(models, times(1)).getModel(baseProject);
    }

    @Test
    public void testBatchFailureRollsBackFormAndEveryPictureAndQueuesNothing() throws Exception
    {
        CommonPicture other = picture("Other"); //$NON-NLS-1$
        form.getItems().add(field("First", logo)); //$NON-NLS-1$
        form.getItems().add(field("Second", other)); //$NON-NLS-1$
        doThrow(new IllegalStateException("Picture service failed")) //$NON-NLS-1$
            .when(adopter).adoptAndAttach(anyList(), eq(target), any());
        doThrow(new IllegalStateException("Picture service failed")) //$NON-NLS-1$
            .when(adopter).adoptAndAttach(eq(other), eq(target), any());

        JsonObject result = run(FORM_FQN);
        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertNames(result, "adopted"); //$NON-NLS-1$
        assertTrue(adoptedSources.isEmpty());
        assertFalse(scope.hasRecordedWrite());
        assertTrue(scope.statedNothingQueued());
        assertTrue(result.get("error").getAsString().contains("rolled back")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("error").getAsString().contains("Completed adoptions")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testReturnedCopiesDetermineExportsWhenPlatformSkipsInputs() throws Exception
    {
        CommonPicture other = picture("Other"); //$NON-NLS-1$
        form.getItems().add(field("First", logo)); //$NON-NLS-1$
        form.getItems().add(field("Second", other)); //$NON-NLS-1$
        EObject otherCopy = exportedPicture("CommonPicture.Other"); //$NON-NLS-1$
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            copies.put(mdForm, exportedCopy("Catalog.Goods")); //$NON-NLS-1$
            copies.put(logo, exportedPicture("CommonPicture.Logo")); //$NON-NLS-1$
            copies.put(other, otherCopy);
            return List.of(otherCopy);
        });

        JsonObject result = run(FORM_FQN);
        assertNames(result, "adopted", "CommonPicture.Other"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "alreadyPresent", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(models).forceExport(dtExtension, List.of("CommonPicture.Other", "Configuration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBmUriProxyStillUnresolvedByEGetRecoversCanonicalPictureName() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        ((InternalEObject)proxy).eSetProxyURI(BmUriUtil.createTopBmObjectUri("Proj", "CommonPicture.Logo")); //$NON-NLS-1$
        PictureRef reference = mock(PictureRef.class, withSettings().extraInterfaces(InternalEObject.class));
        when(reference.eClass()).thenReturn(McorePackage.Literals.PICTURE_REF);
        when(reference.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, false)).thenReturn(proxy);
        when(reference.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true)).thenReturn(proxy);
        form.setCreateButtonsGroupPicture(reference);

        FormPictureDependencies.Result collected = FormPictureDependencies.collect(mdForm);
        assertEquals(List.of("CommonPicture.Logo"), collected.found); //$NON-NLS-1$
        assertEquals(List.of(), collected.unresolved);
        JsonObject result = run(FORM_FQN);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$

        // The review's fragment-only URI is malformed: the vendor API reads the FQN from the path.
        String malformed = "bm://Proj#/CommonPicture.Logo"; //$NON-NLS-1$
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI(malformed));
        collected = FormPictureDependencies.collect(mdForm);
        assertEquals(List.of(), collected.found);
        assertEquals(List.of(malformed), collected.unresolved);
    }

    @Test
    public void testBmUriFallbackIsResolvedInBaseBeforeAdoption() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        ((InternalEObject)proxy).eSetProxyURI(BmUriUtil.createTopBmObjectUri("Proj", "CommonPicture.Missing")); //$NON-NLS-1$
        form.getItems().add(field("Broken", proxy)); //$NON-NLS-1$

        JsonObject result = run(FORM_FQN);
        assertNames(result, "found", "CommonPicture.Missing"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "unresolved", "CommonPicture.Missing"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefusal(result, "CommonPicture.Missing", "modify_metadata"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnreadableAdoptedFormKeepsMasterSuccessAndReportsDependency() throws Exception
    {
        when(adopter.isAdopted(mdForm, target)).thenReturn(true);
        JsonObject result = run(FORM_FQN);
        assertBenignReadoption(result);
        assertTrue(result.toString(), result.getAsJsonObject("dependencies") //$NON-NLS-1$
            .getAsJsonObject("commonPictures").getAsJsonArray("unresolved").size() > 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnreadableAdoptedCopyInTransactionKeepsSuccess() throws Exception
    {
        copies.put(mdForm, MD.createCatalogForm());
        JsonObject result = run(FORM_FQN);
        assertBenignReadoption(result);
        assertTrue(result.toString().contains("Form content:")); //$NON-NLS-1$
    }

    @Test
    public void testProxiedAdoptedContentKeepsSuccessAndReportsUri() throws Exception
    {
        CatalogForm copy = MD.createCatalogForm();
        Form proxy = FORM.createForm();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing:/extension/Form.form")); //$NON-NLS-1$
        copy.setForm(proxy);
        copies.put(mdForm, copy);
        extensionReadRoot = copy;
        JsonObject result = run(FORM_FQN);
        assertBenignReadoption(result);
        assertNames(result, "unresolved", "Form content: missing:/extension/Form.form"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnresolvedAdoptedPictureKeepsSuccessAndReportsUri() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing:/CommonPicture.Unknown")); //$NON-NLS-1$
        form.getItems().add(field("Broken", proxy)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;

        JsonObject result = run(FORM_FQN);
        assertBenignReadoption(result);
        assertNames(result, "unresolved", "missing:/CommonPicture.Unknown"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBasePictureOnReadoptionKeepsSuccessAndReportsName() throws Exception
    {
        CommonPicture missing = MD.createCommonPicture();
        missing.setName("Missing"); //$NON-NLS-1$
        form.getItems().add(field("Broken", missing)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;

        JsonObject result = run(FORM_FQN);
        assertBenignReadoption(result);
        assertNames(result, "unresolved", "CommonPicture.Missing"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonAdoptablePictureOnReadoptionKeepsSuccess() throws Exception
    {
        form.getItems().add(field("Broken", logo)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;
        when(adopter.isAdoptable(logo)).thenReturn(false);

        JsonObject result = run(FORM_FQN);
        assertBenignReadoption(result);
        assertNames(result, "unresolved", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnreadableNewFormContentGivesContentRecoveryInstructions() throws Exception
    {
        Form proxy = FORM.createForm();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing:/base/Form.form")); //$NON-NLS-1$
        mdForm.setForm(proxy);
        JsonObject result = run(FORM_FQN);
        assertRefusal(result, "missing:/base/Form.form", "clean_project"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.get("error").getAsString().contains("get_metadata_details")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("error").getAsString().contains("list_common_pictures")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresEveryDependencyArrayWithStringItems()
    {
        JsonObject schema = JsonParser.parseString(new AdoptMetadataObjectTool().getOutputSchema()).getAsJsonObject();
        JsonObject dependencies = schema.getAsJsonObject("properties").getAsJsonObject("dependencies"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("dependencies must declare nested properties", dependencies.has("properties")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject pictures = dependencies.getAsJsonObject("properties").getAsJsonObject("commonPictures"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject properties = pictures.getAsJsonObject("properties"); //$NON-NLS-1$
        for (String key : List.of("found", "adopted", "alreadyPresent", "unresolved")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertEquals("array", properties.getAsJsonObject(key).get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("string", properties.getAsJsonObject(key).getAsJsonObject("items").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private void assertBenignReadoption(JsonObject result) throws Exception
    {
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("alreadyAdopted", result.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String mappingNote = AdoptMetadataObjectTool.mappingNote("tests", extensionModel, copies.get(mdForm), //$NON-NLS-1$
            null, null, true);
        JsonObject master = JsonParser.parseString(AdoptMetadataObjectTool.alreadyAdoptedResult(
            AdoptMetadataObjectTool.AdoptionSource.resolve(MetadataScope.ofConfiguration(config), FORM_FQN),
            "tests", mappingNote)).getAsJsonObject(); //$NON-NLS-1$
        assertEquals(master.get("message"), result.get("message")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted"); //$NON-NLS-1$
        assertTrue(scope.statedNothingQueued());
        assertTrue(adoptedSources.isEmpty());
        verify(models, never()).forceExport(any(), anyList());
    }
}
