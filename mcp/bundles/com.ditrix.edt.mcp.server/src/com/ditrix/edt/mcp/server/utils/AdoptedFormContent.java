/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IAfterAdopt;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IAdoptedObjectUpdater;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IAdopterContext.ITransactionalAdopterContext;
import com._1c.g5.v8.dt.md.extension.adopt.participants.IModelObjectAdopterParticipant;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com.ditrix.edt.mcp.server.EdtServices;

/** Runs EDT's form-content adoption after repairing the skipped metadata containment insertion. */
public final class AdoptedFormContent
{
    private AdoptedFormContent()
    {
    }

    /** The caller holds the workspace rule and the extension's global write transaction. */
    public static void adopt(IBmTransaction tx, IBmModel model, IModelObjectAdopter adopter,
        IExtensionProject target, BasicForm source, BasicForm attached)
    {
        adopt(tx, model, adopter, target, source, attached, EdtServices::getModelObjectAdopterParticipant);
    }

    @SuppressWarnings("unchecked")
    static void adopt(IBmTransaction tx, IBmModel model, IModelObjectAdopter adopter,
        IExtensionProject target, BasicForm source, BasicForm attached,
        Function<EClass, IModelObjectAdopterParticipant<?>> participants)
    {
        IModelObjectAdopterParticipant<?> metadataParticipant = participants.apply(source.eClass());
        if (!(metadataParticipant instanceof IAfterAdopt<?>))
        {
            throw new IllegalStateException("EDT's form after-adopt participant is unavailable"); //$NON-NLS-1$
        }
        EObject sourceContent = source.getForm();
        Map<EObject, EObject> cache = new IdentityHashMap<>();
        cache.put(source, attached);
        ITransactionalAdopterContext context = new ITransactionalAdopterContext()
        {
            @Override
            public <T extends EObject> T adopt(T nativeObject)
            {
                // Pin the metadata target instead of repeating the scope lookup that missed its insertion.
                if (nativeObject instanceof BasicForm && sameSource(source, (BasicForm)nativeObject))
                {
                    return (T)attached;
                }
                EObject key = sameStoredObject(sourceContent, nativeObject) ? sourceContent : nativeObject;
                EObject result = cache.get(key);
                if (result == null)
                {
                    if (key == sourceContent)
                    {
                        IModelObjectAdopterParticipant<T> participant =
                            (IModelObjectAdopterParticipant<T>)participants.apply(nativeObject.eClass());
                        if (participant == null)
                        {
                            throw new IllegalStateException("EDT's form-content adopter participant is unavailable"); //$NON-NLS-1$
                        }
                        result = participant.adopt(nativeObject, target.getVersion(), this);
                    }
                    else
                    {
                        try
                        {
                            List<EObject> adopted = adopter.adoptAndAttach(List.of(nativeObject), target, new NullProgressMonitor());
                            result = adopted.isEmpty() ? adopter.getAdopted(nativeObject, target) : adopted.get(0);
                        }
                        catch (CoreException e)
                        {
                            throw new IllegalStateException("EDT could not adopt a form-content dependency", e); //$NON-NLS-1$
                        }
                    }
                    if (result != null && result.eIsProxy())
                    {
                        result = (EObject)tx.getObjectByUri(((InternalEObject)result).eProxyURI());
                    }
                    if (!(result instanceof IBmObject) || result.eIsProxy()
                        || ((IBmObject)result).bmGetId() == IBmObject.BM_NULL_ID
                        || tx.getPlatformTransaction().getContainingDataStore((IBmObject)result)
                            != tx.getNamespace().getPrimaryStore())
                    {
                        throw new IllegalStateException("EDT's form-content adoption did not produce a primary-store object"); //$NON-NLS-1$
                    }
                    result = tx.toTransactionObject(result);
                    cache.put(key, result);
                }
                return (T)result;
            }

            @Override
            public IBmTransaction getTransaction()
            {
                return tx;
            }

            @Override
            public IBmModel getModel()
            {
                return model;
            }

            @Override
            public <T extends EObject> void updateAdopted(T nativeObject, T adoptedObject)
            {
                IModelObjectAdopterParticipant<?> participant = participants.apply(nativeObject.eClass());
                if (!(participant instanceof IAdoptedObjectUpdater<?>))
                {
                    throw new IllegalStateException("EDT's form-content updater participant is unavailable"); //$NON-NLS-1$
                }
                ((IAdoptedObjectUpdater<T>)participant).updateAdopted(nativeObject, adoptedObject, this);
            }
        };
        ((IAfterAdopt<BasicForm>)metadataParticipant).afterAdopt(source, attached, context);
    }

    private static boolean sameSource(BasicForm source, BasicForm candidate)
    {
        return candidate == source || candidate.eClass() == source.eClass() && source.getUuid() != null
            && source.getUuid().equals(candidate.getUuid()) && source instanceof IBmObject && candidate instanceof IBmObject
            && ((IBmObject)source).bmGetNamespace() == ((IBmObject)candidate).bmGetNamespace();
    }

    private static boolean sameStoredObject(EObject first, EObject second)
    {
        return first == second || first instanceof IBmObject && second instanceof IBmObject
            && ((IBmObject)first).bmGetId() != IBmObject.BM_NULL_ID
            && ((IBmObject)first).bmGetNamespace() != null
            && ((IBmObject)first).bmGetNamespace() == ((IBmObject)second).bmGetNamespace()
            && ((IBmObject)first).bmGetId() == ((IBmObject)second).bmGetId();
    }
}
