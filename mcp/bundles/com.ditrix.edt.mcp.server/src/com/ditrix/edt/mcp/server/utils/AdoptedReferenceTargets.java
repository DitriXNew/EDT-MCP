/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeSet;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypes;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/**
 * Resolves the BSL target URIs of adopted copies of a base metadata object or predefined item.
 * <p>
 * {@code MdObject.extendedConfigurationObject} is the generated mdclass UUID link from an adopted
 * object to its base object. A predefined item has no such owner link of its own, so its adopted
 * counterpart is found by exact Name within the already-matched adopted owner, matching EDT's own
 * {@code PredefinedItemAdopterParticipant}. Each extension is read through its own BM model and only
 * URIs escape the transaction. This target set is intentionally extension-only: external-object
 * projects belong in the BSL SOURCE scope because they can reference base objects, but they do not
 * adopt configuration objects and therefore cannot contribute adopted TARGET URIs. Not having an
 * adopted owner or predefined child is a successful empty result. An unavailable model,
 * configuration, produced-types value, or dependency snapshot is different:
 * the caller may still run a best-effort search with the targets found so far, but a destructive caller
 * must not treat that partial augmentation as proof that no reference exists.
 */
public final class AdoptedReferenceTargets
{
    private AdoptedReferenceTargets()
    {
        // Utility class.
    }

    /**
     * Finds adopted counterparts in the extension subset already derived from the source-scope
     * dependency snapshot and returns their own EObject and produced-type URIs. For a predefined item,
     * returns the URI of the exact-name child in each adopted owner.
     *
     * @param baseTarget base-configuration object or predefined item whose adopted copies are targets too
     * @param dependencies the same dependency snapshot used for the BSL source scope; its derived
     *     extension subset supplies adopted targets
     * @return accumulated target URIs and whether every extension lookup completed
     */
    public static Resolution resolve(IBmObject baseTarget, SearchDependenciesResult dependencies)
    {
        try
        {
            return resolveInternal(baseTarget, dependencies);
        }
        catch (RuntimeException e)
        {
            // Target augmentation is additive: keep the base-target search usable. The incomplete
            // signal still prevents a strict destructive caller from treating this as proven absence.
            return Resolution.incomplete(Collections.emptyList(),
                "adopted-target lookup failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
        }
    }

    private static Resolution resolveInternal(IBmObject baseTarget,
        SearchDependenciesResult dependencies)
    {
        if (baseTarget == null)
        {
            return Resolution.incomplete(Collections.emptyList(),
                "base target is unavailable"); //$NON-NLS-1$
        }
        if (!(baseTarget instanceof MdObject) && !(baseTarget instanceof PredefinedItem))
        {
            return Resolution.complete(Collections.emptyList());
        }
        if (dependencies == null || !dependencies.isDetermined()
            || !dependencies.isAllReady())
        {
            return Resolution.incomplete(Collections.emptyList(),
                "search dependencies or readiness could not be determined"); //$NON-NLS-1$
        }
        List<IProject> extensionProjects = dependencies.getExtensionProjects();
        if (extensionProjects.isEmpty())
        {
            // No extension target can exist, so identity/model services are irrelevant here. Requiring
            // them would turn a proven empty addition into a false incomplete strict scan.
            return Resolution.complete(Collections.emptyList());
        }

        MdObject baseOwner;
        if (baseTarget instanceof MdObject)
        {
            baseOwner = (MdObject)baseTarget;
        }
        else
        {
            PredefinedItem baseItem = (PredefinedItem)baseTarget;
            if (baseItem.getName() == null || baseItem.getName().isEmpty())
            {
                return Resolution.incomplete(Collections.emptyList(),
                    "base predefined-item name could not be determined"); //$NON-NLS-1$
            }
            baseOwner = findOwningMdObject(baseItem);
            if (baseOwner == null)
            {
                return Resolution.incomplete(Collections.emptyList(),
                    "base predefined-item owner could not be determined"); //$NON-NLS-1$
            }
        }

        UUID baseUuid = baseOwner.getUuid();
        String targetEClassName = baseOwner.eClass().getName();
        if (baseUuid == null || targetEClassName == null || targetEClassName.isEmpty())
        {
            return Resolution.incomplete(Collections.emptyList(),
                "base target identity could not be determined"); //$NON-NLS-1$
        }

        Activator activator = Activator.getDefault();
        IConfigurationProvider configurationProvider =
            activator != null ? activator.getConfigurationProvider() : null;
        if (configurationProvider == null)
        {
            return Resolution.incomplete(Collections.emptyList(),
                "configuration provider is unavailable"); //$NON-NLS-1$
        }

        Set<URI> targetURIs = new LinkedHashSet<>();
        String firstFailure = null;
        for (IProject extension : extensionProjects)
        {
            String extensionName = projectName(extension);
            BmModelResolver.Resolution model;
            try
            {
                model = BmModelResolver.resolve(extension);
            }
            catch (RuntimeException e)
            {
                firstFailure = firstFailure(firstFailure, "extension '" + extensionName //$NON-NLS-1$
                    + "' model lookup failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
                continue;
            }
            if (!model.isAvailable())
            {
                firstFailure = firstFailure(firstFailure,
                    "BM model is unavailable for extension '" + extensionName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }

            try
            {
                Resolution extensionResult = BmTransactions.read(model.getModel(),
                    "Resolve adopted reference target", (transaction, monitor) -> { //$NON-NLS-1$
                        Configuration configuration = configurationProvider.getConfiguration(extension);
                        if (configuration == null)
                        {
                            return Resolution.incomplete(Collections.emptyList(),
                                "configuration is unavailable"); //$NON-NLS-1$
                        }
                        List<? extends MdObject> candidates =
                            MetadataTypeUtils.getObjects(configuration, targetEClassName);
                        if (candidates == null)
                        {
                            return Resolution.incomplete(Collections.emptyList(),
                                "target metadata collection is unavailable"); //$NON-NLS-1$
                        }
                        for (MdObject candidate : candidates)
                        {
                            if (baseUuid.equals(candidate.getExtendedConfigurationObject()))
                            {
                                return resolveTargetForAdoptedOwner(baseTarget, candidate);
                            }
                        }
                        return Resolution.complete(Collections.emptyList());
                    });
                targetURIs.addAll(extensionResult.targetURIs);
                if (!extensionResult.complete)
                {
                    firstFailure = firstFailure(firstFailure,
                        "extension '" + extensionName + "': " //$NON-NLS-1$ //$NON-NLS-2$
                            + extensionResult.failureReason);
                }
            }
            catch (RuntimeException e)
            {
                firstFailure = firstFailure(firstFailure, "extension '" + extensionName //$NON-NLS-1$
                    + "' lookup failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
            }
        }

        List<URI> resolved = new ArrayList<>(targetURIs);
        return firstFailure == null ? Resolution.complete(resolved)
            : Resolution.incomplete(resolved, firstFailure);
    }

    /**
     * Resolves the target nested inside an adopted owner. Package-visible for headless model tests.
     * The caller has already proven that {@code adoptedOwner} corresponds to the base owner.
     */
    static Resolution resolveTargetForAdoptedOwner(IBmObject baseTarget, MdObject adoptedOwner)
    {
        List<URI> targetURIs = new ArrayList<>();
        if (adoptedOwner == null)
        {
            return Resolution.incomplete(targetURIs,
                "adopted owner is unavailable"); //$NON-NLS-1$
        }
        if (baseTarget instanceof PredefinedItem)
        {
            String itemName = ((PredefinedItem)baseTarget).getName();
            if (itemName == null || itemName.isEmpty())
            {
                return Resolution.incomplete(targetURIs,
                    "base predefined-item name could not be determined"); //$NON-NLS-1$
            }
            PredefinedItem adoptedItem = PredefinedWriter.findByNameExact(adoptedOwner, itemName);
            if (adoptedItem == null)
            {
                return Resolution.complete(targetURIs);
            }
            targetURIs.add(EcoreUtil.getURI((EObject)adoptedItem));
            return Resolution.complete(targetURIs);
        }
        if (!(baseTarget instanceof MdObject))
        {
            return Resolution.complete(targetURIs);
        }

        targetURIs.add(EcoreUtil.getURI((EObject)adoptedOwner));
        boolean hasProducedTypes =
            adoptedOwner.eClass().getEStructuralFeature("producedTypes") != null; //$NON-NLS-1$
        MdTypes producedTypes = MdClassUtil.getProducedTypes(adoptedOwner);
        if (producedTypes == null)
        {
            return hasProducedTypes
                ? Resolution.incomplete(targetURIs, "produced types are unavailable") //$NON-NLS-1$
                : Resolution.complete(targetURIs);
        }

        String firstFailure = null;
        for (EObject type : producedTypes.eContents())
        {
            if (type instanceof MdType)
            {
                if (((MdType)type).getType() == null)
                {
                    firstFailure = firstFailure(firstFailure,
                        "a produced type is unavailable"); //$NON-NLS-1$
                    continue;
                }
            }
            else if (type instanceof MdTypeSet)
            {
                if (((MdTypeSet)type).getTypeSet() == null)
                {
                    firstFailure = firstFailure(firstFailure,
                        "a produced type set is unavailable"); //$NON-NLS-1$
                    continue;
                }
            }
            else
            {
                firstFailure = firstFailure(firstFailure,
                    "an indexed produced type could not be classified"); //$NON-NLS-1$
                continue;
            }
            targetURIs.add(EcoreUtil.getURI(type));
        }
        return firstFailure == null ? Resolution.complete(targetURIs)
            : Resolution.incomplete(targetURIs, firstFailure);
    }

    static MdObject findOwningMdObject(PredefinedItem item)
    {
        Set<EObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        EObject current = (EObject)item;
        while (current != null && visited.add(current))
        {
            if (current instanceof MdObject)
            {
                return (MdObject)current;
            }
            current = current.eContainer();
        }
        return null;
    }

    private static String projectName(IProject project)
    {
        if (project == null)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
        try
        {
            String name = project.getName();
            return name != null ? name : "<unknown>"; //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
    }

    private static String firstFailure(String current, String candidate)
    {
        return current == null ? candidate : current;
    }

    /** Result of adopted-target augmentation. */
    public static final class Resolution
    {
        private final List<URI> targetURIs;
        private final boolean complete;
        private final String failureReason;

        private Resolution(List<URI> targetURIs, boolean complete, String failureReason)
        {
            this.targetURIs = Collections.unmodifiableList(new ArrayList<>(targetURIs));
            this.complete = complete;
            this.failureReason = failureReason;
        }

        private static Resolution complete(List<URI> targetURIs)
        {
            return new Resolution(targetURIs, true, null);
        }

        private static Resolution incomplete(List<URI> targetURIs, String failureReason)
        {
            return new Resolution(targetURIs, false, failureReason);
        }

        /** @return adopted EObject and produced-type URIs found so far */
        public List<URI> getTargetURIs()
        {
            return targetURIs;
        }

        /** @return whether every extension could be checked */
        public boolean isComplete()
        {
            return complete;
        }

        /** @return the first lookup failure, or {@code null} when complete */
        public String getFailureReason()
        {
            return failureReason;
        }
    }

}
