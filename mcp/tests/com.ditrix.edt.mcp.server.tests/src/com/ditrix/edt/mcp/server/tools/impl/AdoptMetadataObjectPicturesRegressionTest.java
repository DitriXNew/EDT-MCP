/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Field;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.InputFieldExtInfo;
import com._1c.g5.v8.dt.form.service.extension.IFormExtensionService;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com.ditrix.edt.mcp.server.utils.FormPictureDependencies;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Compatibility, persisted-model recovery, and failure reporting regressions. */
public class AdoptMetadataObjectPicturesRegressionTest extends AdoptMetadataObjectPicturesTestSupport
{
    @Test
    public void testNativeContainmentCollisionIsCompletedBeforeCommitWithoutRetry() throws Exception
    {
        Catalog baseOwner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class)
            .defaultAnswer(delegatesTo(goods)));
        doReturn(2001L).when((IBmObject)baseOwner).bmGetId();
        doReturn(mock(IBmNamespace.class)).when((IBmObject)baseOwner).bmGetNamespace();
        CatalogForm source = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class)
            .defaultAnswer(delegatesTo(mdForm)));
        doReturn(baseOwner).when(source).eContainer();
        Catalog owner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(owner.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(owner.getName()).thenReturn("Goods");
        when(owner.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        doReturn(extensionTx.getNamespace()).when((IBmObject)owner).bmGetNamespace();
        when(((IBmObject)owner).bmGetId()).thenReturn(2001L);
        when(((IBmObject)owner).bmIsTop()).thenReturn(true);
        when(((IBmObject)owner).bmGetFqn()).thenReturn("Catalog.Goods");
        when(extensionTx.getTopObjectByFqn("Catalog.Goods")).thenReturn((IBmObject)owner);
        CatalogForm copy = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        when(copy.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        when(copy.getName()).thenReturn("Item");
        when(copy.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        AtomicLong id = new AtomicLong(IBmObject.BM_NULL_ID);
        doReturn(extensionTx.getNamespace()).when((IBmObject)copy).bmGetNamespace();
        when(((IBmObject)copy).bmGetId()).thenAnswer(inv -> id.get());
        when(((IBmObject)copy).bmGetTopObject()).thenReturn((IBmObject)owner);
        Form content = mock(Form.class, withSettings().extraInterfaces(IBmObject.class));
        when(copy.getForm()).thenReturn(content);
        doReturn(extensionTx.getNamespace()).when((IBmObject)content).bmGetNamespace();
        when(((IBmObject)content).bmGetId()).thenReturn(3001L);
        when(((IBmObject)content).bmIsTop()).thenReturn(true);
        when(((IBmObject)content).bmGetFqn()).thenReturn("CatalogForm.Item.Form");
        when(extensionTx.getTopObjectIterator()).thenAnswer(inv -> List.of((IBmObject)content).iterator());
        when(extensionTx.getTopObjectByFqn("Catalog.Goods.Form.Item.Form")).thenReturn(null);
        when(Activator.getDefault().getTopObjectFqnGenerator()
            .generateExternalPropertyFqn(copy, MdClassPackage.Literals.BASIC_FORM__FORM))
                .thenAnswer(inv -> id.get() == IBmObject.BM_NULL_ID
                    ? "CatalogForm.Item.Form" : "Catalog.Goods.Form.Item.Form");
        BasicEList<EObject> forms = new BasicEList<>()
        {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean contains(Object value)
            {
                return value == source || super.contains(value);
            }

            @Override
            public boolean add(EObject value)
            {
                if (value == copy)
                {
                    assertTrue("attachment must finish inside the original write", writing);
                    id.set(2002L);
                    committedObjects.put(2002L, (IBmObject)copy);
                    associated.add((IBmObject)copy);
                }
                return super.add(value);
            }
        };
        forms.add(MD.createCatalogForm());
        when(owner.eGet(MdClassPackage.Literals.CATALOG__FORMS)).thenReturn(forms);
        when(adopter.getAdopted(source, target)).thenReturn(null);
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenReturn(List.of(copy));
        workspaceRule = true;
        var result = AdoptMetadataObjectTool.adoptWithChanges(extensionModel, adopter, source, target);
        assertEquals(Long.valueOf(2002L), result.copyId);
        assertEquals(1, commits);
        assertEquals(List.of("Catalog.Goods", "Configuration"), result.changes.dirty);
        assertEquals(copy, forms.get(1));
        verify(extensionTx).updateTopObjectFqn((IBmObject)content, "Catalog.Goods.Form.Item.Form");
        verify(adopter, times(1)).adoptAndAttach(anyList(), eq(target), any());
    }

    @Test
    public void testFailureRetainsCommittedPictureWhenNativeLookupIsStale() throws Exception
    {
        assertFailureRetainsCommittedPicture(false, true, false);
    }

    @Test
    public void testCommittedPictureThenDependencyFailureDrainsBeforeReturning() throws Exception
    {
        assertFailureRetainsCommittedPicture(false, true, false);
        assertTrue(scope.hasRecordedWrite());
        assertEquals(List.of("CommonPicture.Logo", "Configuration"), scope.exportedTopObjects("tests"));
        for (DiskExportState state : DiskExportState.values())
        {
            List<String> awaited = new ArrayList<>();
            String answer = tool.awaitExports(rawResponse, scope, (project, budget) -> {
                assertFalse("The workspace rule must be released before draining", workspaceRule);
                assertFalse("The BM transaction must end before draining", writing);
                assertNull(org.eclipse.core.runtime.jobs.Job.getJobManager().currentRule());
                assertNull("The export drain must run off SWT", org.eclipse.swt.widgets.Display.getCurrent());
                assertTrue(budget > 59_000L && budget <= 60_000L);
                awaited.add(project);
                return state;
            });
            assertEquals(List.of("tests"), awaited);
            JsonObject expected = JsonParser.parseString(ToolResult.markErrorAfterMutation(rawResponse))
                .getAsJsonObject();
            if (state != DiskExportState.DRAINED)
            {
                expected.addProperty("exportState", state.name());
            }
            assertEquals("The original adoption failure and all mutation evidence must survive", expected,
                JsonParser.parseString(answer));
        }
    }

    @Test
    public void testAdoptionRefusalWithoutAWriteReturnsWithoutDraining() throws Exception
    {
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;
        form.getItems().add(field("Inherited", logo));
        formExtensions = null;
        assertRefusal(run(FORM_FQN), "export service", "clean_project");
        assertFalse(scope.hasRecordedWrite());
        assertEquals(rawResponse, tool.awaitExports(rawResponse, scope, (project, budget) -> {
            throw new AssertionError("A refusal without a recorded write must never wait");
        }));
    }

    @Test
    public void testFailureRetainsCommittedPictureWhenRecoveryReadThrows() throws Exception
    {
        assertFailureRetainsCommittedPicture(true, true, false);
    }

    @Test
    public void testFailureRetainsAnEarlierFailedExport() throws Exception
    {
        assertFailureRetainsCommittedPicture(false, false, false);
    }

    @Test
    public void testRecoveryExportsOnlyAdditionalPicturesFromTheFailingStep() throws Exception
    {
        assertFailureRetainsCommittedPicture(false, true, true);
    }

    private void assertFailureRetainsCommittedPicture(boolean unreadable, boolean exported,
        boolean additionalEffect) throws Exception
    {
        CommonPicture other = picture("Other");
        form.getItems().add(field("First", logo));
        form.getItems().add(field("Second", other));
        when(models.forceExport(eq(dtExtension), anyList())).thenReturn(exported);
        when(adopter.isAdopted(logo, target)).thenAnswer(inv -> {
            if (unreadable && commits > 0)
            {
                throw new IllegalStateException("Lookup is unavailable after the first commit");
            }
            return false;
        });
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            List<EObject> input = inv.getArgument(0);
            EObject candidate = input.get(0);
            if (candidate == other)
            {
                if (additionalEffect)
                {
                    adoptSource(other);
                }
                throw new IllegalStateException("Later picture adoption failed");
            }
            return List.of(adoptSource(candidate));
        });
        JsonObject result = run(FORM_FQN);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertFalse(result.get("formAdopted").getAsBoolean());
        assertNames(result, "adopted", additionalEffect
            ? new String[] {"CommonPicture.Logo", "CommonPicture.Other"} : new String[] {"CommonPicture.Logo"});
        assertNames(result, "unresolved", additionalEffect ? new String[0] : new String[] {"CommonPicture.Other"});
        assertEquals(result.toString(), exported, result.get("persisted").getAsBoolean());
        assertEquals(1, commits);
        verify(models, times(1)).forceExport(dtExtension, List.of("CommonPicture.Logo", "Configuration"));
        if (additionalEffect)
        {
            verify(models).forceExport(dtExtension, List.of("CommonPicture.Other", "Configuration"));
        }
        else
        {
            verify(models, times(1)).forceExport(eq(dtExtension), anyList());
        }
    }

    @Test
    public void testDetachedResultCapturesIdentityBeforeTheWriteTransactionEnds() throws Exception
    {
        EObject owner = exportedCopy("Catalog.Goods");
        doReturn(extensionTx.getNamespace()).when((IBmObject)owner).bmGetNamespace();
        when(((IBmObject)owner).bmGetId()).thenReturn(2001L);
        CatalogForm global = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        doReturn(extensionTx.getNamespace()).when((IBmObject)global).bmGetNamespace();
        when(((IBmObject)global).bmGetId()).thenReturn(2002L);
        CatalogForm local = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        when(local.eIsProxy()).thenAnswer(inv -> {
            assertTrue("transaction-local proxy state must not escape its write", writing);
            return false;
        });
        doReturn(extensionTx.getNamespace()).when((IBmObject)local).bmGetNamespace();
        when(((IBmObject)local).bmGetId()).thenAnswer(inv -> {
            assertTrue("transaction-local identity must be captured before commit", writing);
            return 2002L;
        });
        when(((IBmObject)local).bmGetTopObject()).thenReturn((IBmObject)owner);
        when(extensionTx.toTransactionObject(global)).thenReturn(local);
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            copies.put(mdForm, global);
            committedObjects.put(2001L, (IBmObject)owner);
            committedObjects.put(2002L, (IBmObject)global);
            associated.addAll(List.of((IBmObject)owner, (IBmObject)global));
            return List.of(MD.createCatalogForm());
        });
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertTrue("the mapping note must come from the re-resolved committed copy: " + result,
            result.has("message"));
        assertTrue(result.toString(), result.get("message").getAsString().contains("by NAME, not by ID"));
        verify(extensionTx).toTransactionObject(global);
        verify(extensionTx, times(2)).getObjectById(2002L);
    }

    @Test
    public void testReadoptionRepairsPicturesUsingTheRenamedAdoptedForm() throws Exception
    {
        assertRenamedReadoption(false, false);
    }

    @Test
    public void testReadoptionRepairsPicturesUnderTheRenamedAdoptedOwner() throws Exception
    {
        assertRenamedReadoption(true, false);
    }

    @Test
    public void testRenamedOwnerReadoptionDoesNotDependOnNativeNameLookup() throws Exception
    {
        when(adopter.isAdopted(mdForm, target)).thenReturn(false);
        when(adopter.getAdopted(mdForm, target)).thenReturn(null);
        assertRenamedReadoption(true, false);
    }

    @Test
    public void testReadoptionRepairsPicturesUsingTheRenamedAdoptedCommonForm() throws Exception
    {
        assertRenamedReadoption(false, true);
    }

    private void assertRenamedReadoption(boolean renameOwner, boolean common) throws Exception
    {
        MdObject source = mdForm;
        if (common)
        {
            CommonForm base = MD.createCommonForm();
            base.setName("Shared");
            base.setForm(form);
            config.getCommonForms().add(base);
            baseReadRoot = base;
            source = base;
        }
        source.setUuid(UUID.randomUUID());
        goods.setUuid(UUID.randomUUID());
        String baseFqn = common ? "CommonForm.Shared" : FORM_FQN;
        MdObject data = common ? MD.createCommonForm() : MD.createCatalogForm();
        data.setName(renameOwner ? "Item" : "\u0424\u043e\u0440\u043c\u0430");
        data.setObjectBelonging(ObjectBelonging.ADOPTED);
        data.setExtendedConfigurationObject(source.getUuid());
        Form own = FORM.createForm();
        own.getItems().add(field("Own", logo));
        ((BasicForm)data).setForm(own);
        MdObject stored = common
            ? mock(CommonForm.class, withSettings().extraInterfaces(IBmObject.class).defaultAnswer(delegatesTo(data)))
            : mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class).defaultAnswer(delegatesTo(data)));
        doReturn(7001L).when((IBmObject)stored).bmGetId();
        doReturn(extensionTx.getNamespace()).when((IBmObject)stored).bmGetNamespace();
        copies.put(source, stored);
        committedObjects.put(7001L, (IBmObject)stored);
        extensionReadRoot = stored;
        Configuration root = (Configuration)extensionTx.getTopObjectByFqn("Configuration");
        Catalog owner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(owner.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(owner.getName()).thenReturn(renameOwner ? "RenamedGoods" : "Goods");
        when(owner.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        when(owner.getExtendedConfigurationObject()).thenReturn(goods.getUuid());
        doReturn(extensionTx.getNamespace()).when((IBmObject)owner).bmGetNamespace();
        when(((IBmObject)owner).bmGetId()).thenReturn(7000L);
        if (!common)
        {
            when(owner.eGet(MdClassPackage.Literals.CATALOG__FORMS))
                .thenReturn(new BasicEList<>(List.of(stored)));
            when(root.eGet(MdClassPackage.Literals.CONFIGURATION__CATALOGS))
                .thenReturn(new BasicEList<>(List.of(owner)));
        }
        String contentFqn = (common ? "CommonForm." + data.getName()
            : "Catalog." + owner.getName() + ".Form." + data.getName()) + ".Form";
        when(Activator.getDefault().getTopObjectFqnGenerator()
            .generateExternalPropertyFqn(stored, MdClassPackage.Literals.BASIC_FORM__FORM)).thenReturn(contentFqn);
        when(extensionTx.getTopObjectByFqn(baseFqn + ".Form")).thenReturn(null);
        when(extensionTx.getTopObjectByFqn(contentFqn)).thenReturn((IBmObject)own);

        JsonObject result = run(baseFqn);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("alreadyAdopted", result.get("action").getAsString());
        assertNames(result, "found", "CommonPicture.Logo");
        assertNames(result, "adopted", "CommonPicture.Logo");
        assertNames(result, "unresolved");
        assertEquals(List.of(logo), adoptedSources);
        verify(extensionTx).getTopObjectByFqn(contentFqn);
    }

    @Test
    public void testAttachedFormInExistingOwnerSucceedsWhenNativeLookupLags() throws Exception
    {
        assertExistingOwnerFormWithoutIndex(false);
    }

    @Test
    public void testNameMappedRussianFormInExistingOwnerDoesNotNeedAnIndexedLookup() throws Exception
    {
        assertExistingOwnerFormWithoutIndex(true);
    }

    private void assertExistingOwnerFormWithoutIndex(boolean nameMapped) throws Exception
    {
        if (nameMapped)
        {
            goods.setName("\u0422\u043e\u0432\u0430\u0440\u044b");
            mdForm.setName("\u0424\u043e\u0440\u043c\u0430");
        }
        mdForm.setUuid(UUID.randomUUID());
        String ownerFqn = "Catalog." + goods.getName();
        Catalog owner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(owner.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        when(owner.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        doReturn(extensionTx.getNamespace()).when((IBmObject)owner).bmGetNamespace();
        when(((IBmObject)owner).bmGetId()).thenReturn(2001L);
        when(((IBmObject)owner).bmIsTop()).thenReturn(true);
        when(((IBmObject)owner).bmGetTopObject()).thenReturn((IBmObject)owner);
        when(((IBmObject)owner).bmGetFqn()).thenReturn(ownerFqn);
        when(extensionTx.getTopObjectByFqn(ownerFqn)).thenReturn((IBmObject)owner);
        BasicEList<CatalogForm> forms = new BasicEList<>();
        when(owner.eGet(MdClassPackage.Literals.CATALOG__FORMS)).thenReturn(forms);
        when(adopter.getAdopted(mdForm, target)).thenReturn(null); // The global scope has not caught up.
        CatalogForm attached = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        when(attached.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        when(attached.getName()).thenReturn(mdForm.getName());
        when(attached.getObjectBelonging()).thenReturn(ObjectBelonging.ADOPTED);
        when(attached.getExtendedConfigurationObject()).thenReturn(nameMapped ? null : mdForm.getUuid());
        doReturn(extensionTx.getNamespace()).when((IBmObject)attached).bmGetNamespace();
        when(((IBmObject)attached).bmGetId()).thenReturn(2002L);
        when(((IBmObject)attached).bmGetTopObject()).thenReturn((IBmObject)owner);
        committedObjects.put(2001L, (IBmObject)owner);
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            assertTrue(writing);
            assertEquals(0, commits);
            forms.add(attached);
            committedObjects.put(2002L, (IBmObject)attached);
            associated.add((IBmObject)attached);
            copies.put(mdForm, attached);
            return List.of(MD.createCatalogForm()); // Native member insertion may return its detached allocation.
        });
        JsonObject result = run(ownerFqn + ".Form." + mdForm.getName());
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("adopted", result.get("action").getAsString());
        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals(1, commits);
        verify(adopter, never()).getAdopted(mdForm, target);
        verify(extensionTx, never()).getObjectById(IBmObject.BM_NULL_ID);
        verify(models).forceExport(dtExtension, List.of(ownerFqn, "Configuration"));
    }

    @Test
    public void testSuccessfulFormWriteDoesNotDependOnPostCommitIndexedLookup() throws Exception
    {
        when(adopter.getAdopted(mdForm, target)).thenReturn(null);
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        verify(adopter, never()).getAdopted(mdForm, target);
    }

    @Test
    public void testDetachedFormUsesNativeLookupWhenItsOwnerIsAdoptedInTheSameWrite() throws Exception
    {
        assertDetachedFormWithNewOwner(false, FORM_FQN);
    }

    @Test
    public void testDetachedRussianFormResolvesTheNativeLookupProxyBeforeCommit() throws Exception
    {
        goods.setName("\u0422\u043e\u0432\u0430\u0440\u044b");
        mdForm.setName("\u0424\u043e\u0440\u043c\u0430");
        assertDetachedFormWithNewOwner(true,
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.\u0422\u043e\u0432\u0430\u0440\u044b.\u0424\u043e\u0440\u043c\u0430.\u0424\u043e\u0440\u043c\u0430");
    }

    private void assertDetachedFormWithNewOwner(boolean proxyLookup, String address) throws Exception
    {
        form.getItems().add(field("Logo", logo));
        String ownerFqn = "Catalog." + goods.getName();
        Catalog owner = mock(Catalog.class, withSettings().extraInterfaces(IBmObject.class));
        when(owner.eClass()).thenReturn(MdClassPackage.Literals.CATALOG);
        doReturn(extensionTx.getNamespace()).when((IBmObject)owner).bmGetNamespace();
        when(((IBmObject)owner).bmGetId()).thenReturn(2001L);
        when(((IBmObject)owner).bmIsTop()).thenReturn(true);
        when(((IBmObject)owner).bmGetTopObject()).thenReturn((IBmObject)owner);
        when(((IBmObject)owner).bmGetFqn()).thenReturn(ownerFqn);
        CatalogForm attached = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        when(attached.eClass()).thenReturn(MdClassPackage.Literals.CATALOG_FORM);
        doReturn(extensionTx.getNamespace()).when((IBmObject)attached).bmGetNamespace();
        when(((IBmObject)attached).bmGetId()).thenReturn(2002L);
        when(((IBmObject)attached).bmGetTopObject()).thenReturn((IBmObject)owner);
        CatalogForm global = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        doReturn(extensionTx.getNamespace()).when((IBmObject)global).bmGetNamespace();
        when(((IBmObject)global).bmGetId()).thenReturn(2002L);
        when(extensionTx.toTransactionObject(global)).thenReturn(attached);
        URI uri = BmUriUtil.createContainedBmObjectUri("tests", ownerFqn, "forms/" + mdForm.getName());
        CatalogForm proxy = MD.createCatalogForm();
        ((InternalEObject)proxy).eSetProxyURI(uri);
        EObject lookup = proxyLookup ? proxy : global;
        when(extensionTx.getObjectByUri(uri)).thenReturn((IBmObject)global);
        Configuration root = (Configuration)extensionTx.getTopObjectByFqn("Configuration");
        // The Configuration traversal has no owner yet; the adopter sees its transaction-local member.
        when(root.eGet(MdClassPackage.Literals.CONFIGURATION__CATALOGS)).thenReturn(new BasicEList<>());
        int[] lookupsInsideWrite = {0};
        when(adopter.getAdopted(mdForm, target)).thenAnswer(inv -> {
            if (writing)
            {
                assertEquals(1, commits); // Only the picture has committed; owner and form have not.
                lookupsInsideWrite[0]++;
            }
            return copies.containsKey(mdForm) ? lookup : null;
        });
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            EObject candidate = ((List<EObject>)inv.getArgument(0)).get(0);
            if (candidate != mdForm)
            {
                return List.of(adoptSource(candidate));
            }
            assertTrue(writing);
            copies.put(goods, owner);
            copies.put(mdForm, lookup);
            committedObjects.put(2001L, (IBmObject)owner);
            committedObjects.put(2002L, (IBmObject)attached);
            associated.addAll(List.of((IBmObject)owner, (IBmObject)attached));
            CatalogForm allocation = MD.createCatalogForm();
            allocation.setName(mdForm.getName());
            assertEquals(IBmObject.BM_NULL_ID, ((IBmObject)allocation).bmGetId());
            return List.of(allocation);
        });

        JsonObject result = run(address);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("adopted", result.get("action").getAsString());
        assertNames(result, "adopted", "CommonPicture.Logo");
        assertEquals(1, lookupsInsideWrite[0]);
        assertEquals(2, commits);
        verify(extensionTx).toTransactionObject(global);
        verify(extensionTx, never()).getObjectById(IBmObject.BM_NULL_ID);
        verify(models).forceExport(dtExtension, List.of(ownerFqn, "Configuration"));
    }

    @Test
    public void testMissingNativeCopyReportsASinglePeriodBeforeRecoveryAdvice() throws Exception
    {
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenReturn(List.of(MD.createCatalogForm()));
        List<IStatus> warnings = new ArrayList<>();
        ILog log = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));
        ILogListener listener = (status, plugin) -> {
            if (status.getMessage().startsWith("adopt_metadata_object: Adopted copy is unavailable"))
            {
                warnings.add(status);
            }
        };
        JsonObject result;
        log.addLogListener(listener);
        try
        {
            result = run(FORM_FQN);
        }
        finally
        {
            log.removeLogListener(listener);
        }
        String error = result.get("error").getAsString();
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertTrue(error, error.contains("get_metadata_objects before retrying adoption. Inspect"));
        assertFalse(error, error.contains("adoption.. Inspect"));
        assertTrue(error, error.contains("copy=CatalogFormImpl:null#-1, container=null, feature=null"));
        assertTrue(error, error.contains("ownerFound=false") && error.contains("formsCount=0"));
        assertTrue(error, error.contains("candidates=[]"));
        assertTrue(error, error.contains("declined=ownerUnavailable"));
        assertEquals("the failure must be diagnosable in edt.log", 1, warnings.size());
        assertEquals(IStatus.WARNING, warnings.get(0).getSeverity());
        assertTrue(error, error.contains(warnings.get(0).getMessage().substring("adopt_metadata_object: ".length())));
        verify(adopter).getAdopted(mdForm, target);
        verify(extensionTx, never()).getObjectById(IBmObject.BM_NULL_ID);
    }

    @Test
    public void testDetachedResultDoesNotAcceptALookupInASecondaryStore() throws Exception
    {
        CatalogForm found = mock(CatalogForm.class, withSettings().extraInterfaces(IBmObject.class));
        doReturn(extensionTx.getNamespace()).when((IBmObject)found).bmGetNamespace();
        when(((IBmObject)found).bmGetId()).thenReturn(2002L);
        when(extensionPlatformTx.getContainingDataStore((IBmObject)found))
            .thenReturn(mock(IBmNamespaceDataStore.class));
        when(adopter.getAdopted(mdForm, target)).thenReturn(found);
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenReturn(List.of(MD.createCatalogForm()));
        JsonObject result = run(FORM_FQN);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("Adopted copy is unavailable"));
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testNativeProjectionIgnoresInheritedPictureOnAnOtherwiseEditedItem() throws Exception
    {
        Form live = FORM.createForm();
        FormField merged = field("Description", logo);
        merged.getUserChangedProperties().put("visible", Boolean.TRUE);
        live.getItems().add(merged);
        Form snapshot = FORM.createForm();
        snapshot.getItems().add(field("Description", null));
        live.setBaseForm(snapshot);
        useNativeProjection(live);

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found");
        assertNames(result, "adopted");
        assertTrue(adoptedSources.isEmpty());
        verify(models, never()).forceExport(any(), anyList());
        assertEquals(logo, ((PictureRef)merged.getHeaderPicture()).getPicture());
        assertTrue(scope.statedNothingQueued());
    }

    @Test
    public void testNativeProjectionKeepsAnOwnPictureOverride() throws Exception
    {
        Form live = FORM.createForm();
        FormField own = field("Description", logo);
        own.getUserChangedProperties().put("headerPicture", Boolean.TRUE);
        live.getItems().add(own);
        Form snapshot = FORM.createForm();
        snapshot.getItems().add(field("Description", null));
        live.setBaseForm(snapshot);
        useNativeProjection(live);

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found", "CommonPicture.Logo");
        assertNames(result, "adopted", "CommonPicture.Logo");
        assertEquals(List.of(logo), adoptedSources);
    }

    @Test
    public void testNativeProjectionKeepsAnExplicitlyClearedPictureEmpty() throws Exception
    {
        Form live = FORM.createForm();
        FormField own = field("Description", null);
        own.setHeaderPicture(null);
        own.getUserChangedProperties().put("headerPicture", Boolean.TRUE);
        live.getItems().add(own);
        Form snapshot = FORM.createForm();
        snapshot.getItems().add(field("Description", logo));
        live.setBaseForm(snapshot);
        useNativeProjection(live);

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found");
        assertNames(result, "adopted");
        assertTrue(adoptedSources.isEmpty());
    }

    @Test
    public void testNativeProjectionRepairsTheSavedPictureInsteadOfTheNewInheritedValue() throws Exception
    {
        CommonPicture newer = picture("NewBasePicture");
        Form live = FORM.createForm();
        live.getItems().add(field("Description", newer));
        Form snapshot = FORM.createForm();
        snapshot.getItems().add(field("Description", logo));
        live.setBaseForm(snapshot);
        useNativeProjection(live);

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found", "CommonPicture.Logo");
        assertNames(result, "adopted", "CommonPicture.Logo");
        assertEquals(List.of(logo), adoptedSources);
    }

    @Test
    public void testNativeProjectionKeepsPicturesOnNewExtensionItems() throws Exception
    {
        Form live = FORM.createForm();
        live.getItems().add(field("ExtensionOnly", logo));
        live.setBaseForm(FORM.createForm());
        useNativeProjection(live);
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found", "CommonPicture.Logo");
        assertNames(result, "adopted", "CommonPicture.Logo");
    }

    @Test
    public void testNativeProjectionUsesTheItemFlagsForExtInfoPictures() throws Exception
    {
        assertExtInfoProjection(true);
    }

    @Test
    public void testNativeProjectionIgnoresInheritedExtInfoPictures() throws Exception
    {
        assertExtInfoProjection(false);
    }

    private void assertExtInfoProjection(boolean own) throws Exception
    {
        Form live = FORM.createForm();
        FormField merged = field("Description", null);
        InputFieldExtInfo info = FORM.createInputFieldExtInfo();
        info.setPicture(ref(logo));
        merged.setExtInfo(info);
        if (own)
        {
            merged.getUserChangedProperties().put("picture", Boolean.TRUE);
        }
        live.getItems().add(merged);
        Form snapshot = FORM.createForm();
        FormField saved = field("Description", null);
        saved.setExtInfo(FORM.createInputFieldExtInfo());
        snapshot.getItems().add(saved);
        live.setBaseForm(snapshot);
        useNativeProjection(live);

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found", own ? new String[] {"CommonPicture.Logo"} : new String[0]);
        assertNames(result, "adopted", own ? new String[] {"CommonPicture.Logo"} : new String[0]);
    }

    @Test
    public void testMissingBaseSnapshotRefusesBeforeReadingMergedPictures() throws Exception
    {
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;
        form.getItems().add(field("Inherited", logo));
        seedExtensionSnapshot = false;
        assertRefusal(run(FORM_FQN), "BaseForm", "clean_project");
    }

    @Test
    public void testUnavailableProjectionServiceRefusesBeforeReadingMergedPictures() throws Exception
    {
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;
        form.getItems().add(field("Inherited", logo));
        formExtensions = null;
        assertRefusal(run(FORM_FQN), "export service", "clean_project");
    }

    private void useNativeProjection(Form live) throws Exception
    {
        Class<?> type = Platform.getBundle("com._1c.g5.v8.dt.form").loadClass(
            "com._1c.g5.v8.dt.internal.form.service.extension.FormExtensionService");
        formExtensions = (IFormExtensionService)type.getConstructor().newInstance();
        IV8ProjectManager projects = mock(IV8ProjectManager.class);
        when(projects.getProject(any(EObject.class))).thenReturn(target);
        Field manager = type.getDeclaredField("v8ProjectManager");
        manager.setAccessible(true);
        manager.set(formExtensions, projects);
        ISymbolicNameService names = mock(ISymbolicNameService.class);
        when(names.generateSymbolicName(any(), any(), any())).thenAnswer(inv ->
            inv.getArgument(0) instanceof CommonPicture
                ? "CommonPicture." + ((CommonPicture)inv.getArgument(0)).getName() : null);
        Field symbolic = type.getDeclaredField("symbolicNameService");
        symbolic.setAccessible(true);
        symbolic.set(formExtensions, names);
        Form storedContent = mock(Form.class, delegatesTo(live));
        doReturn(FORM_FQN + ".Form").when(storedContent).bmGetFqn();
        CatalogForm stored = MD.createCatalogForm();
        stored.setForm(live);
        extensionReadRoot = stored;
        persistedExtensionContent = storedContent;
        copies.put(mdForm, stored);
    }

    @Test
    public void testDetachedAdopterResultNeverBecomesAStore255Lookup() throws Exception
    {
        form.getItems().add(field("Logo", logo));
        when(extensionTx.getObjectById(IBmObject.BM_NULL_ID))
            .thenThrow(new IllegalStateException("No store with '255' is assigned to namespace 'tests'"));
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            List<EObject> input = inv.getArgument(0);
            if (input.contains(logo))
            {
                adoptSource(logo);
                CommonPicture detached = MD.createCommonPicture();
                detached.setName(logo.getName());
                assertEquals(IBmObject.BM_NULL_ID, ((IBmObject)detached).bmGetId());
                return List.of(detached);
            }
            return List.of(adoptSource(input.get(0)));
        });
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "adopted", "CommonPicture.Logo");
        verify(extensionTx, never()).getObjectById(IBmObject.BM_NULL_ID);
        assertEquals(2, commits);
    }

    @Test
    public void testRepeatIgnoresForeignAdopterProxyAndInheritedGetFormValue() throws Exception
    {
        form.getItems().add(field("BaseOnly", logo));
        CatalogForm stored = MD.createCatalogForm();
        stored.setForm(form);
        CatalogForm foreign = MD.createCatalogForm();
        URI uri = BmUriUtil.createTopBmObjectUri("Base", "Catalog.Goods").appendFragment("forms/Item");
        ((InternalEObject)foreign).eSetProxyURI(uri);
        copies.put(mdForm, foreign);
        extensionReadRoot = stored;
        persistedExtensionContent = FORM.createForm();
        when(extensionTx.getObjectByUri(uri)).thenReturn((IBmObject)mdForm);

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found");
        assertNames(result, "adopted");
        assertTrue(adoptedSources.isEmpty());
        assertTrue(scope.statedNothingQueued());
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testRepeatRejectsSecondaryStoreContentBeforeAdoption() throws Exception
    {
        form.getItems().add(field("BaseOnly", logo));
        CatalogForm stored = MD.createCatalogForm();
        stored.setForm(form);
        copies.put(mdForm, stored);
        extensionReadRoot = stored;
        when(extensionPlatformTx.getContainingDataStore((IBmObject)form))
            .thenReturn(mock(IBmNamespaceDataStore.class));
        JsonObject result = run(FORM_FQN);
        assertRefusal(result, "unavailable in this project", "clean_project");
        assertNames(result, "found");
    }

    @Test
    public void testRepeatFindsOnlyPicturesInThePersistedPrimaryContent() throws Exception
    {
        form.getItems().add(field("BaseOnly", logo));
        CatalogForm stored = MD.createCatalogForm();
        stored.setForm(form);
        Form own = FORM.createForm();
        CommonPicture edited = picture("Edited");
        own.getItems().add(field("Own", edited));
        copies.put(mdForm, stored);
        extensionReadRoot = stored;
        persistedExtensionContent = own;
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "found", "CommonPicture.Edited");
        assertNames(result, "adopted", "CommonPicture.Edited");
        assertEquals(List.of(edited), adoptedSources);
    }

    @Test
    public void testGenericUnresolvedPictureUsesItsStoredCommonPictureBmAddress() throws Exception
    {
        Picture proxy = mock(Picture.class, withSettings().extraInterfaces(InternalEObject.class));
        when(proxy.eIsProxy()).thenReturn(true);
        when(((InternalEObject)proxy).eProxyURI()).thenReturn(
            BmUriUtil.createTopBmObjectUri("Base", "CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$
        PictureRef reference = mock(PictureRef.class, withSettings().extraInterfaces(InternalEObject.class));
        when(reference.eClass()).thenReturn(McorePackage.Literals.PICTURE_REF);
        when(reference.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, false)).thenReturn(proxy);
        when(reference.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true)).thenReturn(proxy);
        form.setCreateButtonsGroupPicture(reference);
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertNames(result, "found", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOmittedAndFalseFlagNeverReadEvenBrokenFormContent() throws Exception
    {
        Form proxy = FORM.createForm();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing:/form")); //$NON-NLS-1$
        mdForm.setForm(proxy);
        JsonObject first = run(FORM_FQN, null);
        assertTrue(first.toString(), first.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(first.has("dependencies")); //$NON-NLS-1$
        assertEquals(List.of(mdForm), adoptedSources);
        JsonObject again = run(FORM_FQN, false);
        assertEquals("alreadyAdopted", again.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(again.has("dependencies")); //$NON-NLS-1$
        verify(baseModel, never()).executeReadonlyTask(any());
        assertTrue(scope.statedNothingQueued());
    }

    @Test
    public void testOptInRefusesNonFormObjectAndMemberBeforeModelAccess() throws Exception
    {
        for (String fqn : List.of("Catalog.Goods", "CommonPicture.Logo", "Catalog.Goods.Attribute.Weight")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            JsonObject result = run(fqn);
            assertRefusal(result, fqn, "CommonForm.Name"); //$NON-NLS-1$
        }
        verify(baseModel, never()).executeReadonlyTask(any());
        verify(extensionModel, never()).executeReadonlyTask(any());
    }

    @Test
    public void testFormWithoutPicturesReturnsFourEmptyArrays() throws Exception
    {
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(List.of(mdForm), adoptedSources);
        for (String key : List.of("found", "adopted", "alreadyPresent", "unresolved")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertNames(result, key);
        }
    }

    @Test
    public void testPicturesCommitBeforeTheFormUsesTheNativeDependencyCopier() throws Exception
    {
        form.getItems().add(field("First", logo));
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            List<EObject> input = inv.getArgument(0);
            assertEquals("a mixed native batch can expose a detached store id", 1, input.size());
            EObject candidate = input.get(0);
            if (candidate == mdForm)
            {
                assertEquals(1, commits);
                assertTrue(copies.containsKey(logo));
            }
            return List.of(adoptSource(candidate));
        });
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "adopted", "CommonPicture.Logo");
        assertEquals(List.of(logo, mdForm), adoptedSources);
        assertEquals(2, commits);
    }

    @Test
    public void testMissingCommitEvidenceStillReportsExplicitlyAdoptedPictures() throws Exception
    {
        form.getItems().add(field("First", logo));
        emitCommitEvent = false;
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "adopted", "CommonPicture.Logo");
        assertNames(result, "alreadyPresent");
        assertEquals("unavailable", result.get("cascadeAttribution").getAsString());
        verify(models).forceExport(dtExtension, List.of("CommonPicture.Logo", "Configuration"));
        verify(models).forceExport(dtExtension, List.of("Catalog.Goods", "Configuration"));
    }

    @Test
    public void testExportFailureKeepsTheObservedDependencyOutcome() throws Exception
    {
        form.getItems().add(field("First", logo)); //$NON-NLS-1$
        when(models.forceExport(eq(dtExtension), anyList())).thenThrow(new IllegalStateException("Disk export failed")); //$NON-NLS-1$
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.get("persisted").getAsBoolean()); //$NON-NLS-1$
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "unresolved"); //$NON-NLS-1$
        assertTrue(scope.hasRecordedWrite());
    }

    @Test
    public void testFailureReportsAnEarlierCommittedPictureWithoutAdoptingTheForm() throws Exception
    {
        CommonPicture other = picture("Other");
        form.getItems().add(field("First", logo));
        form.getItems().add(field("Second", other));
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            EObject candidate = ((List<EObject>)inv.getArgument(0)).get(0);
            if (candidate == other)
            {
                throw new IllegalStateException("Later picture adoption failed");
            }
            return List.of(adoptSource(candidate));
        });
        JsonObject result = run(FORM_FQN);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertFalse(result.get("formAdopted").getAsBoolean());
        assertNames(result, "adopted", "CommonPicture.Logo");
        assertNames(result, "unresolved", "CommonPicture.Other");
        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals(List.of(logo), adoptedSources);
        assertEquals(1, commits);
    }

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

        run("Catalog.Goods", null); //$NON-NLS-1$
        assertEquals(AdoptMetadataObjectTool.adoptedResult(
            AdoptMetadataObjectTool.AdoptionSource.resolve(MetadataScope.ofConfiguration(config), "Catalog.Goods"), //$NON-NLS-1$
            "tests", true, AdoptMetadataObjectTool.mappingNote("tests", extensionModel, copies.get(goods),
                null, false, false)), rawResponse); //$NON-NLS-1$
        assertEquals(List.of(goods), adoptedSources);
        verify(baseModel, never()).executeReadonlyTask(any());
        verify(adopter).adoptAndAttach(anyList(), eq(target), any());
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

        run("CommonModule.Calc", false); //$NON-NLS-1$
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

        JsonObject result = run("Catalog.Goods.Attribute.Weight", null); //$NON-NLS-1$
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.has("dependencies")); //$NON-NLS-1$
        assertEquals(List.of(weight), adoptedSources);
        verify(baseModel, never()).executeReadonlyTask(any());
    }

    @Test
    public void testEveryMissingPictureAndTheFormUseSeparateNativeWrites() throws Exception
    {
        CommonPicture other = picture("Other");
        form.getItems().add(field("First", logo));
        form.getItems().add(field("Second", other));
        form.getItems().add(field("Duplicate", logo));
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(List.of(List.of(logo), List.of(other), List.of(mdForm)), batches);
        assertEquals(3, commits);
        assertNames(result, "adopted", "CommonPicture.Logo", "CommonPicture.Other");
        verify(adopter, never()).adoptAndAttach(any(EObject.class), eq(target), any());
        verify(models, times(1)).getModel(baseProject);
    }

    @Test
    public void testFirstPictureFailureReportsUnknownScopeWithoutPromisingRollback() throws Exception
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
        assertFalse(scope.statedNothingQueued());
        assertTrue(result.get("error").getAsString().contains("Inspect the extension")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("error").getAsString().contains("rolled back"));
        assertNames(result, "unresolved", "CommonPicture.Logo", "CommonPicture.Other"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testConcurrentPictureAdoptionDoesNotPreventTheRemainingInvocations() throws Exception
    {
        CommonPicture other = picture("Other");
        form.getItems().add(field("First", logo));
        form.getItems().add(field("Second", other));
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            EObject candidate = ((List<EObject>)inv.getArgument(0)).get(0);
            if (candidate == logo)
            {
                copies.put(logo, exportedPicture("CommonPicture.Logo"));
                return List.of();
            }
            return List.of(adoptSource(candidate));
        });
        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNames(result, "adopted", "CommonPicture.Other");
        assertNames(result, "alreadyPresent", "CommonPicture.Logo");
        verify(models).forceExport(dtExtension, List.of("CommonPicture.Other", "Configuration"));
        verify(models).forceExport(dtExtension, List.of("Catalog.Goods", "Configuration"));
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
    public void testUnreadableAdoptedFormRefusesAndReportsDependency() throws Exception
    {
        when(adopter.isAdopted(mdForm, target)).thenReturn(true);
        JsonObject result = run(FORM_FQN);
        assertIncompleteReadoption(result);
        assertTrue(result.toString(), result.getAsJsonObject("dependencies") //$NON-NLS-1$
            .getAsJsonObject("commonPictures").getAsJsonArray("unresolved").size() > 0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnreadableAdoptedCopyInTransactionRefuses() throws Exception
    {
        copies.put(mdForm, MD.createCatalogForm());
        JsonObject result = run(FORM_FQN);
        assertIncompleteReadoption(result);
        assertTrue(result.toString().contains("Form content:")); //$NON-NLS-1$
    }

    @Test
    public void testProxiedAdoptedContentRefusesAndReportsUri() throws Exception
    {
        CatalogForm copy = MD.createCatalogForm();
        Form proxy = FORM.createForm();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing:/extension/Form.form")); //$NON-NLS-1$
        copy.setForm(proxy);
        copies.put(mdForm, copy);
        extensionReadRoot = copy;
        JsonObject result = run(FORM_FQN);
        assertIncompleteReadoption(result);
        assertNames(result, "unresolved", "Form content: missing:/extension/Form.form"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnresolvedAdoptedPictureRefusesAndReportsUri() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing:/CommonPicture.Unknown")); //$NON-NLS-1$
        form.getItems().add(field("Broken", proxy)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;

        JsonObject result = run(FORM_FQN);
        assertIncompleteReadoption(result);
        assertNames(result, "unresolved", "missing:/CommonPicture.Unknown"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBasePictureOnReadoptionRefusesAndReportsName() throws Exception
    {
        CommonPicture missing = MD.createCommonPicture();
        missing.setName("Missing"); //$NON-NLS-1$
        form.getItems().add(field("Broken", missing)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;

        JsonObject result = run(FORM_FQN);
        assertIncompleteReadoption(result);
        assertNames(result, "unresolved", "CommonPicture.Missing"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonAdoptablePictureOnReadoptionRefuses() throws Exception
    {
        form.getItems().add(field("Broken", logo)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;
        when(adopter.isAdoptable(logo)).thenReturn(false);

        JsonObject result = run(FORM_FQN);
        assertIncompleteReadoption(result);
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

    private void assertIncompleteReadoption(JsonObject result) throws Exception
    {
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("retry"));
        assertNames(result, "adopted");
        assertTrue(scope.statedNothingQueued());
        assertTrue(adoptedSources.isEmpty());
        verify(models, never()).forceExport(any(), anyList());
    }
}
