/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/** Filesystem boundary for exercising the real preview and confirmation inventory. */
public final class MetadataCloneFilesFixture
{
    public final Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
    public final Catalog source = catalog("Source");
    public final Catalog clone = copiedCatalog();
    public final IProject project = mock(IProject.class);
    public final IBmModel model = mock(IBmModel.class);
    public final IProjectFileSystemSupport fs = mock(IProjectFileSystemSupport.class);
    public final IFile sourceFile;
    public final IFile cloneFile;
    public final IFile objectModule;
    public final IFile managerModule;
    public final IFile copiedObjectModule;
    public final IFile copiedManagerModule;

    public MetadataCloneFilesFixture() throws Exception
    {
        config.getCatalogs().add(source);
        sourceFile = file("Source/Source.mdo", true, "<Catalog><name>Source</name></Catalog>");
        cloneFile = file("Copy/Copy.mdo", true, "<Catalog><name>Copy</name></Catalog>");
        objectModule = file("Source/ObjectModule.bsl", false, "Procedure Probe()\nEndProcedure\n");
        managerModule = file("Source/ManagerModule.bsl", false, "// manager code\n");
        copiedObjectModule = file("Copy/ObjectModule.bsl", false, "Procedure Probe()\nEndProcedure\n");
        copiedManagerModule = file("Copy/ManagerModule.bsl", false, "// manager code\n");
        when(fs.getFile(source)).thenReturn(sourceFile);
        when(fs.getFile(clone)).thenReturn(cloneFile);
        when(fs.getFile(source, (EReference)source.eClass().getEStructuralFeature("objectModule"))).thenReturn(objectModule);
        when(fs.getFile(source, (EReference)source.eClass().getEStructuralFeature("managerModule"))).thenReturn(managerModule);
        when(fs.getFile(clone, (EReference)clone.eClass().getEStructuralFeature("objectModule"))).thenReturn(copiedObjectModule);
        when(fs.getFile(clone, (EReference)clone.eClass().getEStructuralFeature("managerModule"))).thenReturn(copiedManagerModule);
        IFolder folder = mock(IFolder.class);
        IFolder collection = mock(IFolder.class);
        when(sourceFile.getParent()).thenReturn(folder);
        when(folder.getProjectRelativePath()).thenReturn(new Path("src/Catalogs/Source"));
        when(folder.getParent()).thenReturn(collection);
        when(collection.getFolder(any(org.eclipse.core.runtime.IPath.class))).thenReturn(mock(IFolder.class));
    }

    public MetadataClonePlan preview()
    {
        return MetadataClonePlan.capture(project, model, config, source, "Copy", List.of(), fs, false);
    }

    private static Catalog catalog(String name)
    {
        Catalog result = MdClassFactory.eINSTANCE.createCatalog();
        result.setName(name);
        result.setUuid(UUID.randomUUID());
        com._1c.g5.v8.dt.bsl.model.Module object = BslFactory.eINSTANCE.createModule();
        object.setModuleType(ModuleType.OBJECT_MODULE);
        ((InternalEObject)object).eSetProxyURI(URI.createURI("unresolved:/Catalog." + name + ".ObjectModule"));
        result.setObjectModule(object);
        com._1c.g5.v8.dt.bsl.model.Module manager = BslFactory.eINSTANCE.createModule();
        manager.setModuleType(ModuleType.MANAGER_MODULE);
        ((InternalEObject)manager).eSetProxyURI(URI.createURI("unresolved:/Catalog." + name + ".ManagerModule"));
        result.setManagerModule(manager);
        return result;
    }

    private static Catalog copiedCatalog()
    {
        Catalog result = mock(Catalog.class);
        when(result.eClass()).thenReturn(com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.CATALOG);
        when(result.getName()).thenReturn("Copy");
        when(result.getUuid()).thenReturn(UUID.randomUUID());
        return result;
    }

    public static IFile file(String relative, boolean exists, String content) throws Exception
    {
        IFile file = mock(IFile.class);
        when(file.exists()).thenReturn(exists);
        when(file.getProjectRelativePath()).thenReturn(new Path("src/Catalogs/" + relative));
        when(file.getFullPath()).thenReturn(new Path("/P/src/Catalogs/" + relative));
        when(file.getContents(true)).thenAnswer(call -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return file;
    }
}
