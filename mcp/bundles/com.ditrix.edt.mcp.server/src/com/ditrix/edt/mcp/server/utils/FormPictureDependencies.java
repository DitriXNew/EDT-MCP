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
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;

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
     * Re-fetches the source inside its model's read transaction, including an adopted-copy proxy.
     *
     * @param model the model owning the source
     * @param source the global-context object or proxy returned by the platform adopter
     * @return the dependency names
     */
    public static Result collect(IBmModel model, EObject source)
    {
        if (!(source instanceof BasicForm))
        {
            return collect((EObject)null);
        }
        URI uri = source.eIsProxy() ? ((InternalEObject)source).eProxyURI() : null;
        long id = uri == null && source instanceof IBmObject ? ((IBmObject)source).bmGetId() : 0L;
        return BmTransactions.read(model, "adopt_metadata_object: read form pictures", (tx, monitor) -> { //$NON-NLS-1$
            EObject inTx = uri != null ? (EObject)tx.getObjectByUri(uri) : (EObject)tx.getObjectById(id);
            if (inTx == null || inTx.eIsProxy())
            {
                throw new IllegalStateException("The form source could not be read. Wait for the project " //$NON-NLS-1$
                    + "to finish loading, then retry adopt_metadata_object."); //$NON-NLS-1$
            }
            return collect(inTx);
        });
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
        EObject content = FormElementWriter.getEditableForm(mdForm);
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
        try
        {
            Object resolved = ref.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true);
            if (resolved instanceof CommonPicture)
            {
                picture = (EObject)resolved;
                if (!picture.eIsProxy())
                {
                    String fqn = MetadataNodeResolver.resolvableAddressOf((CommonPicture)picture);
                    if (fqn != null)
                    {
                        found.add(fqn);
                        return;
                    }
                }
            }
            else if (!(picture instanceof CommonPicture))
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
            if (!(picture instanceof CommonPicture))
            {
                return;
            }
        }
        URI proxyUri = picture.eIsProxy() ? ((InternalEObject)picture).eProxyURI() : null;
        if (proxyUri != null && BmUriUtil.isBmUri(proxyUri)
            && proxyUri.path() != null && !proxyUri.path().isEmpty())
        {
            String fqn = BmUriUtil.extractTopObjectFqn(proxyUri);
            if (fqn != null && fqn.startsWith(PictureValueBuilder.COMMON_PREFIX)
                && fqn.length() > PictureValueBuilder.COMMON_PREFIX.length())
            {
                // Cross-project references in reloaded extension forms may remain unresolved here.
                // The caller still resolves this canonical address in the base scope before any write.
                found.add(fqn);
                return;
            }
        }
        unresolved.add(picture.eIsProxy() ? String.valueOf(((InternalEObject)picture).eProxyURI())
            : "CommonPicture with no programmatic Name"); //$NON-NLS-1$
    }
}
