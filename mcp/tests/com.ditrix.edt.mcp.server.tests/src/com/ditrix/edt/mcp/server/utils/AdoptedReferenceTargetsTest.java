/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com.ditrix.edt.mcp.server.utils.AdoptedReferenceTargets.Resolution;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/** Headless tests for adopted-target completeness and predefined-item correspondence. */
public class AdoptedReferenceTargetsTest
{
    @Test
    public void noExtensionDependenciesNeedNoTargetOrModelAugmentation()
    {
        Catalog baseTargetWithoutIdentity = MdClassFactory.eINSTANCE.createCatalog();

        Resolution result = AdoptedReferenceTargets.resolve((IBmObject)baseTargetWithoutIdentity,
            readySnapshot());

        assertTrue(result.isComplete());
        assertTrue(result.getTargetURIs().isEmpty());
    }

    @Test
    public void undeterminedDependencySnapshotMarksAdoptedAugmentationIncomplete()
    {
        Catalog baseTarget = MdClassFactory.eINSTANCE.createCatalog();

        Resolution result = AdoptedReferenceTargets.resolve((IBmObject)baseTarget,
            SearchDependenciesResult.undetermined());

        assertFalse(result.isComplete());
        assertTrue(result.getFailureReason().contains("dependencies")); //$NON-NLS-1$
    }

    @Test
    public void predefinedCounterpartUsesExactNameInsideAdoptedOwnerNotId()
    {
        Catalog baseOwner = catalogInResource("bm:/base"); //$NON-NLS-1$
        PredefinedItem baseItem = predefined(baseOwner, "Existing"); //$NON-NLS-1$
        baseItem.setId(UUID.fromString("11111111-1111-1111-1111-111111111111")); //$NON-NLS-1$

        Catalog adoptedOwner = catalogInResource("bm:/extension"); //$NON-NLS-1$
        PredefinedItem adoptedItem = predefined(adoptedOwner, "Existing"); //$NON-NLS-1$
        adoptedItem.setId(UUID.fromString("22222222-2222-2222-2222-222222222222")); //$NON-NLS-1$

        Resolution result = AdoptedReferenceTargets.resolveTargetForAdoptedOwner(
            (IBmObject)baseItem, adoptedOwner);

        assertNotEquals(baseItem.getId(), adoptedItem.getId());
        assertSame(baseOwner, AdoptedReferenceTargets.findOwningMdObject(baseItem));
        assertTrue(result.isComplete());
        assertEquals(Collections.singletonList(EcoreUtil.getURI((EObject)adoptedItem)),
            result.getTargetURIs());
    }

    @Test
    public void missingPredefinedCounterpartIsACompleteEmptyAddition()
    {
        Catalog baseOwner = catalogInResource("bm:/base"); //$NON-NLS-1$
        PredefinedItem baseItem = predefined(baseOwner, "BaseOnly"); //$NON-NLS-1$
        Catalog adoptedOwner = catalogInResource("bm:/extension"); //$NON-NLS-1$

        Resolution result = AdoptedReferenceTargets.resolveTargetForAdoptedOwner(
            (IBmObject)baseItem, adoptedOwner);

        assertTrue(result.isComplete());
        assertTrue(result.getTargetURIs().isEmpty());
    }

    @Test
    public void unavailablePredefinedOwnerMarksAugmentationIncomplete()
    {
        PredefinedItem detachedItem = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        detachedItem.setName("Detached"); //$NON-NLS-1$
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Base.tests"); //$NON-NLS-1$

        Resolution result = AdoptedReferenceTargets.resolve((IBmObject)detachedItem,
            readySnapshot(extension));

        assertFalse(result.isComplete());
        assertTrue(result.getFailureReason().contains("owner")); //$NON-NLS-1$
    }

    @Test
    public void producedTypesFeatureWithUnavailableValueMarksAugmentationIncomplete()
    {
        Catalog baseTarget = MdClassFactory.eINSTANCE.createCatalog();
        Catalog adoptedOwner = catalogInResource("bm:/extension"); //$NON-NLS-1$
        EStructuralFeature producedTypes =
            adoptedOwner.eClass().getEStructuralFeature("producedTypes"); //$NON-NLS-1$
        assertTrue(producedTypes != null);
        adoptedOwner.eUnset(producedTypes);
        assertNull(MdClassUtil.getProducedTypes(adoptedOwner));

        Resolution result = AdoptedReferenceTargets.resolveTargetForAdoptedOwner(
            (IBmObject)baseTarget, adoptedOwner);

        assertFalse(result.isComplete());
        assertEquals(Collections.singletonList(EcoreUtil.getURI((EObject)adoptedOwner)),
            result.getTargetURIs());
        assertTrue(result.getFailureReason().contains("produced types")); //$NON-NLS-1$
    }

    @Test
    public void metadataClassWithoutProducedTypesFeatureNeedsOnlyItsObjectUri()
    {
        CommonModule baseTarget = MdClassFactory.eINSTANCE.createCommonModule();
        CommonModule adoptedOwner = MdClassFactory.eINSTANCE.createCommonModule();
        attach(adoptedOwner, "bm:/extension"); //$NON-NLS-1$
        assertNull(adoptedOwner.eClass().getEStructuralFeature("producedTypes")); //$NON-NLS-1$

        Resolution result = AdoptedReferenceTargets.resolveTargetForAdoptedOwner(
            (IBmObject)baseTarget, adoptedOwner);

        assertTrue(result.isComplete());
        assertEquals(Collections.singletonList(EcoreUtil.getURI((EObject)adoptedOwner)),
            result.getTargetURIs());
    }

    private static Catalog catalogInResource(String uri)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        attach(catalog, uri);
        return catalog;
    }

    private static SearchDependenciesResult readySnapshot(IProject... extensions)
    {
        List<IProject> extensionProjects = Arrays.asList(extensions);
        List<IProject> searchProjects = new ArrayList<>(extensionProjects);
        Map<String, ProjectState> readiness = new LinkedHashMap<>();
        for (IProject extension : extensionProjects)
        {
            readiness.put(extension.getName(), ProjectState.READY);
        }
        return SearchDependenciesResult.determined(searchProjects, extensionProjects, readiness);
    }

    private static PredefinedItem predefined(Catalog owner, String name)
    {
        PredefinedWriter.WriteResult result =
            PredefinedWriter.create(owner, name, new PredefinedWriter.ItemProps(), false);
        if (result.isError())
        {
            throw new AssertionError(result.error);
        }
        return result.item;
    }

    private static void attach(EObject object, String uri)
    {
        new ResourceImpl(URI.createURI(uri)).getContents().add(object);
    }
}
