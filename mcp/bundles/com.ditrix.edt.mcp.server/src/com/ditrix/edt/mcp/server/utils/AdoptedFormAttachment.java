/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Recovery of the native adopter's skipped containment insertion, inside its write transaction. */
public final class AdoptedFormAttachment
{
    private AdoptedFormAttachment()
    {
    }

    /** Completes only a proven cross-namespace false positive in the BM containment contains check. */
    public static EObject complete(IBmTransaction tx, BasicForm source, EObject allocation,
        ITopObjectFqnGenerator generator)
    {
        return complete(tx, source, allocation, generator, reason -> { });
    }

    /** Reports exactly one decline token; all callbacks and model access stay inside the caller's write. */
    public static EObject complete(IBmTransaction tx, BasicForm source, EObject allocation,
        ITopObjectFqnGenerator generator, Consumer<String> declined)
    {
        MdObject owner = AdoptedFormLookup.findOwner(tx, source);
        EObject baseOwner = source.eContainer();
        EStructuralFeature feature = source.eContainingFeature();
        if (owner == null)
        {
            return decline(declined, "ownerUnavailable"); //$NON-NLS-1$
        }
        if (!(baseOwner instanceof IBmObject))
        {
            return decline(declined, "sourceOwnerNotBm"); //$NON-NLS-1$
        }
        if (!(allocation instanceof BasicForm) || !(allocation instanceof IBmObject))
        {
            return decline(declined, "allocationNotForm"); //$NON-NLS-1$
        }
        if (allocation.eContainer() != null || ((IBmObject)allocation).bmGetId() != IBmObject.BM_NULL_ID)
        {
            return decline(declined, "allocationAlreadyAttached"); //$NON-NLS-1$
        }
        if (allocation.eIsProxy() || allocation.eClass() != source.eClass())
        {
            return decline(declined, "allocationTypeOrProxy"); //$NON-NLS-1$
        }
        if (!ExtensionServices.isAdopted(allocation))
        {
            return decline(declined, "allocationNotAdopted"); //$NON-NLS-1$
        }
        if (!originMatches(source, (MdObject)allocation))
        {
            return decline(declined, "allocationOriginMismatch"); //$NON-NLS-1$
        }
        if (!(feature instanceof EReference) || !((EReference)feature).isContainment() || !feature.isMany())
        {
            return decline(declined, "sourceFeatureNotContainment"); //$NON-NLS-1$
        }
        IBmObject originalOwner = (IBmObject)baseOwner;
        if (originalOwner.bmGetId() == IBmObject.BM_NULL_ID
            || originalOwner.bmGetNamespace() == null || originalOwner.bmGetNamespace() == tx.getNamespace()
            || originalOwner.bmGetId() != ((IBmObject)owner).bmGetId())
        {
            return decline(declined, "ownerIdsOrNamespacesDoNotCollide"); //$NON-NLS-1$
        }
        Object contents = owner.eGet(feature);
        if (!(contents instanceof EList<?>))
        {
            return decline(declined, "formsNotEList"); //$NON-NLS-1$
        }
        @SuppressWarnings("unchecked")
        EList<EObject> forms = (EList<EObject>)contents;
        if (forms.isEmpty() || !forms.contains(source))
        {
            return decline(declined, "notFalseContainment"); //$NON-NLS-1$
        }
        for (EObject candidate : forms)
        {
            // Iterate rather than contains: BM's containment fast path omits the namespace check.
            if (candidate == source || candidate == allocation || candidate instanceof MdObject
                && (sameName((MdObject)source, (MdObject)candidate)
                    || source.getUuid() != null
                        && source.getUuid().equals(((MdObject)candidate).getExtendedConfigurationObject())))
            {
                return decline(declined, "duplicateForm(" + label(candidate) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        BasicForm copy = (BasicForm)allocation;
        EObject content = copy.getForm();
        boolean expectsContent = content != null || source.getForm() != null;
        String expectedFqn = generator == null || !expectsContent ? null
            : generator.generateExternalPropertyFqn(allocation, MdClassPackage.Literals.BASIC_FORM__FORM);
        // BasicForm.form is transient; the native copier registers its external top object synchronously.
        IBmObject registered = expectedFqn == null ? null : tx.getTopObjectByFqn(expectedFqn);
        if (registered instanceof AbstractForm && !registered.eIsProxy() && BmObjectOwnership.isPrimary(tx, registered))
        {
            content = registered;
        }
        boolean linkedContent = false;
        if (!(content instanceof IBmObject) || !BmObjectOwnership.isPrimary(tx, (IBmObject)content))
        {
            // FormExtender sets mdForm to its allocation, even when that allocation was not inserted.
            Iterator<IBmObject> objects = tx.getTopObjectIterator();
            while (objects.hasNext())
            {
                IBmObject candidate = objects.next();
                if (candidate instanceof AbstractForm && !candidate.eIsProxy() && BmObjectOwnership.isPrimary(tx, candidate)
                    && ((AbstractForm)candidate).getMdForm() == allocation
                    && (!(candidate instanceof Form) || ((Form)candidate).getExtensionForm() == null))
                {
                    if (linkedContent)
                    {
                        return decline(declined, "ambiguousLinkedContent"); //$NON-NLS-1$
                    }
                    content = candidate;
                    linkedContent = true;
                }
            }
        }
        String contentState = contentState(tx, content == null ? "missingContent" : "contentNotPrimary", //$NON-NLS-1$ //$NON-NLS-2$
            expectedFqn, registered, content);
        if (content == null && source.getForm() != null)
        {
            return decline(declined, contentState);
        }
        List<IBmObject> external = new ArrayList<>();
        String oldFqn = null;
        if (content != null)
        {
            if (!(content instanceof AbstractForm) || !(content instanceof IBmObject) || content.eIsProxy())
            {
                return decline(declined, "contentTypeOrProxy(" + label(content) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!BmObjectOwnership.isPrimary(tx, (IBmObject)content))
            {
                return decline(declined, contentState);
            }
            oldFqn = BmObjectAddress.topFqnOrNull((IBmObject)content);
            if (oldFqn == null || expectedFqn == null || !linkedContent && !oldFqn.equals(expectedFqn))
            {
                return decline(declined, "contentFqnMismatch(old=" + oldFqn + ", expected=" + expectedFqn + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            Iterator<IBmObject> objects = tx.getTopObjectIterator();
            while (objects.hasNext())
            {
                IBmObject object = objects.next();
                String fqn = object.bmGetFqn();
                if (BmObjectOwnership.isPrimary(tx, object) && fqn != null && (fqn.equals(oldFqn) || fqn.startsWith(oldFqn + "."))) //$NON-NLS-1$
                {
                    external.add(object);
                }
            }
            if (!external.contains(content))
            {
                return decline(declined, "contentMissingFromTopObjects(" + oldFqn + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // Keep EDT's allocation, origin link, form and BaseForm; only finish the missed addChild.
        BasicForm attached = attach(tx, source, allocation, forms);
        if (oldFqn != null)
        {
            String newFqn = generator.generateExternalPropertyFqn(attached, MdClassPackage.Literals.BASIC_FORM__FORM);
            for (IBmObject object : external)
            {
                String fqn = newFqn + object.bmGetFqn().substring(oldFqn.length());
                IBmObject collision = tx.getTopObjectByFqn(fqn);
                if (collision != null && collision.bmGetId() != object.bmGetId())
                {
                    throw new IllegalStateException("Adopted form content already exists at " + fqn); //$NON-NLS-1$
                }
                tx.updateTopObjectFqn(object, fqn);
                if (object instanceof AbstractForm)
                {
                    ((AbstractForm)object).setMdForm((BasicForm)attached);
                }
            }
            ((BasicForm)attached).setForm((AbstractForm)content);
        }
        return attached;
    }

    private static BasicForm attach(IBmTransaction tx, BasicForm source, EObject allocation, EList<EObject> forms)
    {
        forms.add(allocation);
        MdObject attached = AdoptedFormLookup.find(tx, source);
        if (!(attached instanceof BasicForm))
        {
            throw new IllegalStateException("EDT did not attach its form allocation to the stored owner"); //$NON-NLS-1$
        }
        return (BasicForm)attached;
    }

    private static String contentState(IBmTransaction tx, String reason, String expectedFqn,
        EObject registered, EObject content)
    {
        return reason + "(expectedFqn=" + expectedFqn + ", registered=" + observed(() -> label(registered)) //$NON-NLS-1$ //$NON-NLS-2$
            + ", content=" + observed(() -> label(content)) + ", namespace=" + observed(() -> namespace(content)) //$NON-NLS-1$ //$NON-NLS-2$
            + ", store=" + observed(() -> content instanceof IBmObject //$NON-NLS-1$
                && tx.getPlatformTransaction().getContainingDataStore((IBmObject)content) != null
                    ? tx.getPlatformTransaction().getContainingDataStore((IBmObject)content).getName() : null) + ")"; //$NON-NLS-1$
    }

    private static EObject decline(Consumer<String> declined, String reason)
    {
        declined.accept(reason);
        return null;
    }

    /** Captures plain diagnostic text while all observed objects are still inside the transaction. */
    public static String describe(IBmTransaction tx, EObject source, EObject allocation)
    {
        StringBuilder text = new StringBuilder();
        text.append("source=").append(observed(() -> label(source))) //$NON-NLS-1$
            .append(", copy=").append(observed(() -> label(allocation))) //$NON-NLS-1$
            .append(", container=").append(observed(() -> label(allocation == null ? null : allocation.eContainer()))) //$NON-NLS-1$
            .append(", feature=").append(observed(() -> allocation == null || allocation.eContainingFeature() == null //$NON-NLS-1$
                ? null : allocation.eContainingFeature().getName()));
        BasicForm form = source instanceof BasicForm ? (BasicForm)source : null;
        text.append(", baseOwner=").append(observed(() -> label(form == null ? null : form.eContainer()))); //$NON-NLS-1$
        try
        {
            MdObject owner = form == null ? null : AdoptedFormLookup.findOwner(tx, form);
            text.append(", ownerFound=").append(owner != null).append(", owner=").append(observed(() -> label(owner))); //$NON-NLS-1$ //$NON-NLS-2$
            text.append(", baseNamespace=").append(observed(() -> form == null ? null : namespace(form.eContainer()))) //$NON-NLS-1$
                .append(", ownerNamespace=").append(observed(() -> namespace(owner))); //$NON-NLS-1$
            Object contents = owner == null ? null : owner.eGet(form.eContainingFeature());
            text.append(", formsCount=").append(contents instanceof List<?> ? ((List<?>)contents).size() : 0); //$NON-NLS-1$
            text.append(", containsSource=").append(contents instanceof List<?> && ((List<?>)contents).contains(source)); //$NON-NLS-1$
            text.append(", candidates=["); //$NON-NLS-1$
            if (contents instanceof List<?>)
            {
                List<?> forms = (List<?>)contents;
                for (int i = 0; i < Math.min(8, forms.size()); i++)
                {
                    EObject candidate = forms.get(i) instanceof EObject ? (EObject)forms.get(i) : null;
                    if (i > 0)
                    {
                        text.append("; "); //$NON-NLS-1$
                    }
                    text.append(observed(() -> label(candidate))).append("{adopted=") //$NON-NLS-1$
                        .append(observed(() -> ExtensionServices.isAdopted(candidate))).append(",primary=") //$NON-NLS-1$
                        .append(observed(() -> candidate instanceof IBmObject && BmObjectOwnership.isPrimary(tx, (IBmObject)candidate)))
                        .append(",originUuidMatch=").append(observed(() -> candidate instanceof MdObject && form.getUuid() != null //$NON-NLS-1$
                            && form.getUuid().equals(((MdObject)candidate).getExtendedConfigurationObject()))).append('}');
                }
                if (forms.size() > 8)
                {
                    text.append("; ..."); //$NON-NLS-1$
                }
            }
            text.append(']');
        }
        catch (RuntimeException e)
        {
            text.append(", ownerState=unreadable(").append(e.getClass().getSimpleName()).append(')'); //$NON-NLS-1$
        }
        return text.toString();
    }

    private static String observed(Supplier<?> read)
    {
        try
        {
            return String.valueOf(read.get());
        }
        catch (RuntimeException e)
        {
            return "unreadable(" + e.getClass().getSimpleName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String namespace(EObject object)
    {
        return object instanceof IBmObject && ((IBmObject)object).bmGetNamespace() != null
            ? ((IBmObject)object).bmGetNamespace().getName() : null;
    }

    private static String label(EObject object)
    {
        if (object == null)
        {
            return "null"; //$NON-NLS-1$
        }
        return object.getClass().getSimpleName() + ":" + (object instanceof MdObject ? ((MdObject)object).getName() : "") //$NON-NLS-1$ //$NON-NLS-2$
            + "#" + (object instanceof IBmObject ? ((IBmObject)object).bmGetId() : "no-bm-id"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean originMatches(MdObject source, MdObject copy)
    {
        UUID link = copy.getExtendedConfigurationObject();
        return link != null ? link.equals(source.getUuid()) : sameName(source, copy);
    }

    private static boolean sameName(MdObject source, MdObject copy)
    {
        return source.getName() != null && source.getName().equalsIgnoreCase(copy.getName());
    }
}
