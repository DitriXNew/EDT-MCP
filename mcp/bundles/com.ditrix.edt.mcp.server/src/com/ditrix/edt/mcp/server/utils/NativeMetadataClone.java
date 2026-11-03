/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.core.resources.IFile;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ISymbolicNameService;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IEditingLanguageManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.md.copy.AttachingModelObjectCopier;
import com._1c.g5.v8.dt.md.copy.IModelObjectCopySupport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com.google.inject.Injector;
import org.eclipse.xtext.naming.IQualifiedNameProvider;

/** EDT's paste copier with the requested root name chosen BEFORE attachment and file copying. */
public final class NativeMetadataClone extends AttachingModelObjectCopier
{
    private final MdObject source;
    private final String newName;
    private Consumer<MdObject> onRootAttached = object -> { };
    private final Set<String> attachedTopFqns = new LinkedHashSet<>();

    NativeMetadataClone(Injector services, IBmTransaction tx, IBmModel model,
        MdObject source, String newName)
    {
        super(services.getInstance(IV8ProjectManager.class), services.getInstance(IModelObjectFactory.class),
            services.getInstance(IRuntimeVersionSupport.class), services.getInstance(IEditingLanguageManager.class),
            services.getInstance(ITopObjectFqnGenerator.class), services.getInstance(IQualifiedNameProvider.class),
            services.getInstance(IResourceLookup.class), services.getInstance(IProjectFileSystemSupportProvider.class),
            services.getInstance(IBmModelManager.class), services.getInstance(IBmEmfIndexManager.class),
            services.getInstance(ISymbolicNameService.class), tx, model);
        this.source = source;
        this.newName = newName;
    }

    /** The service's private factory cannot select a name or join an existing BM transaction. */
    public static NativeMetadataClone create(IBmTransaction tx, IBmModel model, MdObject source,
        String newName, Consumer<MdObject> onRootAttached)
    {
        NativeMetadataClone copier = new NativeMetadataClone(MdPlugin.getDefault().getInjector(), tx, model,
            source, newName);
        copier.onRootAttached = onRootAttached;
        return copier;
    }

    @Override
    protected EObject attach(EObject copy, EObject parent, EReference reference)
    {
        EObject attached = super.attach(copy, parent, reference);
        recordAttachedTop(attached);
        if (parent instanceof Configuration && attached instanceof MdObject)
        {
            onRootAttached.accept((MdObject)attached);
        }
        return attached;
    }

    @Override
    protected <T extends IBmObject> T attachExternalProperty(T object, EObject owner, EReference reference)
    {
        T attached = super.attachExternalProperty(object, owner, reference);
        recordAttachedTop(attached);
        return attached;
    }

    @Override
    protected <T extends IBmObject> T attachModelObject(T object, boolean isTop)
    {
        T attached = super.attachModelObject(object, isTop);
        if (isTop)
        {
            recordAttachedTop(attached);
        }
        return attached;
    }

    /** Actual attached roots, including external models absent from the metadata containment tree. */
    public Set<String> attachedTopFqns()
    {
        return Set.copyOf(attachedTopFqns);
    }

    private void recordAttachedTop(EObject object)
    {
        if (object instanceof IBmObject && ((IBmObject)object).bmIsTop())
        {
            attachedTopFqns.add(((IBmObject)object).bmGetFqn());
        }
    }

    @Override
    protected String generateMdCopyName(MdObject original, MdObject target, EObject parent,
        EReference reference, CopyContext copyContext)
    {
        if (original == source && newName != null)
        {
            copyContext.addCreatedName(target.eClass(), newName);
            return newName;
        }
        return super.generateMdCopyName(original, target, parent, reference, copyContext);
    }

    // Match IModelObjectCopySupport's configuration copier, which is private in EDT.
    @Override
    protected void copyAttribute(EObject original, EAttribute from, EObject target, EAttribute to,
        EObject parent, EReference reference, CopyContext copyContext)
    {
        if ("AdditionalIndex".equals(to.getEContainingClass().getName()) && "id".equals(to.getName())
            && original.eContainer() != null)
        {
            EStructuralFeature.Setting setting = getTarget(to, original, target);
            if (setting != null)
            {
                setting.set(UUID.randomUUID());
            }
            return;
        }
        super.copyAttribute(original, from, target, to, parent, reference, copyContext);
    }

    @Override
    protected boolean shouldCopyContainment(EObject original, EReference from, EObject target,
        EReference to, CopyContext copyContext)
    {
        return from != MdClassPackage.Literals.MD_OBJECT__EXTENSION
            && super.shouldCopyContainment(original, from, target, to, copyContext);
    }

    @Override
    protected boolean shouldCopyAttribute(EAttribute from, EObject original, EAttribute to,
        EObject target, CopyContext copyContext)
    {
        return from != MdClassPackage.Literals.MD_OBJECT__OBJECT_BELONGING
            && super.shouldCopyAttribute(from, original, to, target, copyContext);
    }

    @Override
    protected void copyResource(IFile sourceFile, IFile targetFile)
    {
        if (targetFile.exists())
        {
            throw new IllegalStateException("Clone would overwrite '" + targetFile.getProjectRelativePath()
                + "'. Inspect the destination files and choose an unused name.");
        }
        super.copyResource(sourceFile, targetFile);
    }
}
