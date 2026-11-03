/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.service.extension.IFormExtensionService;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;

/** Read-only CommonPicture dependencies of a metadata form. */
public final class FormPictureDependencies
{
    /** Immutable names only: no transaction-bound model objects escape the read. */
    public static final class Result
    {
        /** Canonical CommonPicture FQNs, deduplicated in encounter order. */
        public final List<String> found;
        /** References that could not be resolved; never silently omitted from the dependency set. */
        public final List<String> unresolved;

        private Result(Set<String> found, Set<String> unresolved)
        {
            this.found = List.copyOf(found);
            this.unresolved = List.copyOf(unresolved);
        }
    }

    private FormPictureDependencies()
    {
    }

    /**
     * Reads the selected project's persisted metadata and external form top object by address.
     *
     * @param model the model owning the source
     * @param fqn the form's metadata address, independent of any cross-project lookup proxy
     * @param extensionCopy whether the adopted form needs EDT's persisted extension projection
     * @param source the base form whose adopted counterpart supplies the extension's current address
     * @return the dependency names
     */
    public static Result collectPersisted(IBmModel model, String fqn, boolean extensionCopy, BasicForm source)
    {
        return BmTransactions.read(model, "adopt_metadata_object: read form pictures", (tx, monitor) -> { //$NON-NLS-1$
            IBmObject root = tx.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
            MdObject mdForm = extensionCopy ? AdoptedFormLookup.find(tx, source) : null;
            if (mdForm == null)
            {
                mdForm = root instanceof Configuration ? MetadataNodeResolver.resolveAddress(
                    MetadataScope.ofConfiguration((Configuration)root), fqn, false) : null;
            }
            ITopObjectFqnGenerator generator = Activator.getDefault().getTopObjectFqnGenerator();
            if (!(mdForm instanceof BasicForm) || generator == null)
            {
                throw new IllegalStateException("The persisted form '" + fqn //$NON-NLS-1$
                    + "' could not be read. Save the form and wait for the project to finish loading, then retry."); //$NON-NLS-1$
            }
            String contentFqn = generator.generateExternalPropertyFqn(mdForm, MdClassPackage.Literals.BASIC_FORM__FORM);
            IBmObject content = contentFqn == null ? null : tx.getTopObjectByFqn(contentFqn);
            if (content != null && content.eIsProxy())
            {
                return new Result(Set.of(), Set.of("Form content: " + ((InternalEObject)content).eProxyURI())); //$NON-NLS-1$
            }
            if (content == null
                || tx.getPlatformTransaction().getContainingDataStore((IBmObject)mdForm) != tx.getNamespace().getPrimaryStore()
                || tx.getPlatformTransaction().getContainingDataStore(content) != tx.getNamespace().getPrimaryStore())
            {
                throw new IllegalStateException("Persisted form content '" + contentFqn + "'" //$NON-NLS-1$ //$NON-NLS-2$
                    + " is unavailable in this project. Save the extension form and reload with clean_project, then retry."); //$NON-NLS-1$
            }
            Set<String> found = new LinkedHashSet<>();
            Set<String> unresolved = new LinkedHashSet<>();
            collectPictures(mdForm, found, unresolved);
            collectPictures(extensionCopy ? extensionContent(content) : content, found, unresolved);
            return new Result(found, unresolved);
        });
    }

    private static EObject extensionContent(EObject content)
    {
        if (!(content instanceof Form))
        {
            throw new IllegalStateException("The adopted content is not a managed form. " //$NON-NLS-1$
                + "Inspect it with get_metadata_details before retrying."); //$NON-NLS-1$
        }
        Form form = (Form)content;
        Form snapshot = form.getBaseForm();
        IFormExtensionService extensions = ExtensionServices.formExtensionService();
        if (snapshot == null || snapshot.eIsProxy() || extensions == null)
        {
            throw new IllegalStateException("The adopted form's BaseForm snapshot or EDT form export service " //$NON-NLS-1$
                + "is unavailable. Reload the extension with clean_project and retry."); //$NON-NLS-1$
        }
        // FormExporter uses this projection to replace inherited live values with saved snapshot values.
        EcoreUtil.Copier copier = new EcoreUtil.Copier(true, true)
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected void copyContainment(EReference reference, EObject source, EObject copy)
            {
                if (!reference.isTransient() && !reference.isDerived())
                {
                    super.copyContainment(reference, source, copy);
                }
            }

            @Override
            protected void copyReference(EReference reference, EObject source, EObject copy)
            {
                if (!reference.isTransient() && !reference.isDerived())
                {
                    super.copyReference(reference, source, copy);
                }
            }
        };
        Form own = (Form)copier.copy(form);
        copier.copyReferences();
        extensions.filterExtensionBelongingObject(own, form);
        return own;
    }

    /**
     * Collects pictures from transaction-bound metadata and its managed-form content. The caller
     * must hold a read boundary for live BM objects. Detached EMF fixtures need no BM runtime.
     * Computed/transient containments and references to other metadata objects are never walked.
     *
     * @param source the form; other metadata kinds are ignored
     * @return the dependency names
     */
    public static Result collect(EObject source)
    {
        Set<String> found = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        if (source instanceof BasicForm)
        {
            collectForm(source, found, unresolved);
        }
        return new Result(found, unresolved);
    }

    private static void collectForm(EObject mdForm, Set<String> found, Set<String> unresolved)
    {
        collectPictures(mdForm, found, unresolved);
        // BasicForm.form is external content: a metadata-only walk does not reliably load it.
        EObject content = ((BasicForm)mdForm).getForm();
        if (content != null)
        {
            if (content.eIsProxy())
            {
                unresolved.add("Form content: " + ((InternalEObject)content).eProxyURI()); //$NON-NLS-1$
            }
            else
            {
                collectPictures(content, found, unresolved);
            }
        }
    }

    private static void collectPictures(EObject root, Set<String> found, Set<String> unresolved)
    {
        collectPicture(root, found, unresolved);
        for (EObject child : PersistedContents.descendants(root))
        {
            collectPicture(child, found, unresolved);
        }
    }

    private static void collectPicture(EObject ref, Set<String> found, Set<String> unresolved)
    {
        if (!McorePackage.Literals.PICTURE_REF.isSuperTypeOf(ref.eClass()))
        {
            return;
        }
        Object raw = ref.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, false);
        if (!(raw instanceof EObject))
        {
            return;
        }
        EObject picture = (EObject)raw;
        URI rawUri = picture.eIsProxy() ? ((InternalEObject)picture).eProxyURI() : null;
        String rawFqn = PictureValueBuilder.commonPictureFqn(rawUri);
        try
        {
            Object resolved = ref.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true);
            if (resolved instanceof CommonPicture)
            {
                picture = (EObject)resolved;
                if (!picture.eIsProxy())
                {
                    String fqn = PictureValueBuilder.commonPictureFqn((CommonPicture)picture);
                    if (fqn != null)
                    {
                        found.add(fqn);
                        return;
                    }
                }
            }
            else if (!(picture instanceof CommonPicture) && rawFqn == null)
            {
                // Standard/extended platform pictures do not belong to the configuration and
                // may intentionally remain proxies. Only CommonPicture dependencies are adopted.
                return;
            }
        }
        catch (RuntimeException e)
        {
            // A CommonPicture may still be identified by its BM URI. A platform picture is
            // outside the dependency set even when its catalogue is unavailable.
            if (!(picture instanceof CommonPicture) && rawFqn == null)
            {
                return;
            }
        }
        URI proxyUri = picture.eIsProxy() ? ((InternalEObject)picture).eProxyURI() : rawUri;
        String fqn = PictureValueBuilder.commonPictureFqn(proxyUri);
        if (fqn != null)
        {
            found.add(fqn);
            return;
        }
        unresolved.add(picture.eIsProxy() ? String.valueOf(((InternalEObject)picture).eProxyURI())
            : "CommonPicture with no programmatic Name"); //$NON-NLS-1$
    }
}
