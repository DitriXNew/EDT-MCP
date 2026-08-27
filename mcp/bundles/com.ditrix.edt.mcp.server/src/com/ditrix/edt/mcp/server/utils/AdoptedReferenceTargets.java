/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeSet;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypes;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeParticipantsResult;

/**
 * Resolves the BSL target URIs of adopted copies of a base metadata object.
 * <p>
 * {@code MdObject.extendedConfigurationObject} is the generated mdclass UUID link from an adopted
 * object to its base object. Each extension is read through its own BM model and only URIs escape the
 * transaction. Not having an adopted counterpart is a successful empty result. An unavailable model,
 * configuration, or participant set is different: the caller may still run a best-effort search with
 * the targets found so far, but a destructive caller must not treat that partial augmentation as proof
 * that no reference exists.
 */
public final class AdoptedReferenceTargets
{
    private AdoptedReferenceTargets()
    {
        // Utility class.
    }

    /**
     * Finds adopted counterparts in the already-resolved extension participant set and returns their
     * own EObject and produced-type URIs.
     *
     * @param baseTarget base-configuration object whose adopted copies are targets too
     * @param participants the same participant snapshot used to scope the source scan
     * @return accumulated target URIs and whether every extension lookup completed
     */
    public static Resolution resolve(MdObject baseTarget, CascadeParticipantsResult participants)
    {
        try
        {
            return resolveInternal(baseTarget, participants);
        }
        catch (RuntimeException e)
        {
            // Target augmentation is additive: keep the base-target search usable. The incomplete
            // signal still prevents a strict destructive caller from treating this as proven absence.
            return Resolution.incomplete(Collections.emptyList(),
                "adopted-target lookup failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
        }
    }

    private static Resolution resolveInternal(MdObject baseTarget,
        CascadeParticipantsResult participants)
    {
        if (baseTarget == null)
        {
            return Resolution.complete(Collections.emptyList());
        }
        if (participants == null || !participants.isDetermined())
        {
            return Resolution.incomplete(Collections.emptyList(),
                "extension participants could not be determined"); //$NON-NLS-1$
        }

        UUID baseUuid = baseTarget.getUuid();
        String targetEClassName = baseTarget.eClass().getName();
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
        for (IProject extension : participants.getParticipants())
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
                ExtensionResolution extensionResult = BmTransactions.read(model.getModel(),
                    "Resolve adopted reference target", (transaction, monitor) -> { //$NON-NLS-1$
                        Configuration configuration = configurationProvider.getConfiguration(extension);
                        if (configuration == null)
                        {
                            return ExtensionResolution.failure("configuration is unavailable"); //$NON-NLS-1$
                        }
                        List<? extends MdObject> candidates =
                            MetadataTypeUtils.getObjects(configuration, targetEClassName);
                        if (candidates == null)
                        {
                            return ExtensionResolution.failure(
                                "target metadata collection is unavailable"); //$NON-NLS-1$
                        }
                        for (MdObject candidate : candidates)
                        {
                            if (baseUuid.equals(candidate.getExtendedConfigurationObject()))
                            {
                                return ExtensionResolution.complete(targetURIs(candidate));
                            }
                        }
                        return ExtensionResolution.complete(Collections.emptyList());
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

    private static List<URI> targetURIs(MdObject target)
    {
        List<URI> targetURIs = new ArrayList<>();
        targetURIs.add(EcoreUtil.getURI((EObject)target));
        MdTypes producedTypes = MdClassUtil.getProducedTypes(target);
        if (producedTypes != null)
        {
            for (EObject type : producedTypes.eContents())
            {
                if ((type instanceof MdType && ((MdType)type).getType() != null)
                    || (type instanceof MdTypeSet && ((MdTypeSet)type).getTypeSet() != null))
                {
                    targetURIs.add(EcoreUtil.getURI(type));
                }
            }
        }
        return targetURIs;
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

    private static final class ExtensionResolution
    {
        private final List<URI> targetURIs;
        private final boolean complete;
        private final String failureReason;

        private ExtensionResolution(List<URI> targetURIs, boolean complete, String failureReason)
        {
            this.targetURIs = targetURIs;
            this.complete = complete;
            this.failureReason = failureReason;
        }

        private static ExtensionResolution complete(List<URI> targetURIs)
        {
            return new ExtensionResolution(targetURIs, true, null);
        }

        private static ExtensionResolution failure(String failureReason)
        {
            return new ExtensionResolution(Collections.emptyList(), false, failureReason);
        }
    }
}
