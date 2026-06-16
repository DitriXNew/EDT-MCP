/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.common.Functions;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.PredefinedItemUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeSet;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypes;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Domain service that finds all references to a metadata object.
 * Returns all places where the object is used: in other metadata objects and BSL code.
 */
@SuppressWarnings("restriction")
public class MetadataReferenceService
{
    /**
     * Finds all references to the metadata object identified by {@code objectFqn}.
     * Must be invoked on the UI thread (see {@code FindReferencesTool}).
     */
    public String findReferences(String projectName, String objectFqn, int limit)
    {
        // Normalize Russian metadata type names: "Справочник.Номенклатура" -> "Catalog.Номенклатура"
        objectFqn = MetadataTypeUtils.normalizeFqn(objectFqn);

        // Resolve the project and its configuration
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();

        // Get BM model manager
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("BM model manager not available").toJson(); //$NON-NLS-1$
        }

        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Find target object by FQN
        MdObject targetObject = findMdObjectByFqn(config, objectFqn);
        if (targetObject == null)
        {
            // Check if the user passed a sub-object FQN (more than one dot after the type prefix)
            String[] dotParts = objectFqn.split("\\."); //$NON-NLS-1$
            if (dotParts.length > 2)
            {
                // Genuine unsupported-input error (sub-object FQN): surface it through
                // the structured contract, keeping the guidance in the message.
                return ToolResult.error("Object not found: " + objectFqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                    + "find_references only supports top-level metadata objects " //$NON-NLS-1$
                    + "(e.g. 'Catalog.DataAreas', 'Document.SalesOrder', 'CommonModule.Saas'). " //$NON-NLS-1$
                    + "Sub-objects such as attributes, forms, commands and tabular sections " //$NON-NLS-1$
                    + "are not supported (e.g. 'Catalog.DataAreas.Attribute.DataAreaStatus' is invalid).").toJson(); //$NON-NLS-1$
            }
            return ToolResult.error("Object not found: " + objectFqn).toJson(); //$NON-NLS-1$
        }

        // Collect all references
        ReferenceCollector collector = new ReferenceCollector(bmModel, targetObject, limit);

        try
        {
            // Execute as BM task
            bmModel.executeReadonlyTask(collector, true);
        }
        catch (Exception e)
        {
            Activator.logError("Error executing BM task", e); //$NON-NLS-1$
            return ToolResult.error("Error executing search: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Format output
        return formatOutput(objectFqn, collector);
    }

    /**
     * Finds MdObject by FQN in configuration.
     * Delegates to {@link MetadataTypeUtils} which supports English, Russian,
     * singular and plural forms.
     */
    private MdObject findMdObjectByFqn(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }

        String[] parts = fqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }

        return MetadataTypeUtils.findObject(config, parts[0], parts[1]);
    }

    /**
     * Formats output as markdown - sorted list similar to EDT.
     */
    private String formatOutput(String objectFqn, ReferenceCollector collector)
    {
        StringBuilder sb = new StringBuilder();

        int totalCount = collector.getTotalCount();

        sb.append("# References to ").append(objectFqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total references found:** ").append(totalCount).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (totalCount == 0)
        {
            sb.append("No references found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        // Get all references and merge BSL by module
        List<ReferenceInfo> allRefs = collector.getAllReferences();

        // Separate BSL and metadata references
        java.util.Map<String, List<Integer>> bslModuleLines = new java.util.TreeMap<>();
        List<ReferenceInfo> metadataRefs = new ArrayList<>();
        partitionReferences(allRefs, bslModuleLines, metadataRefs);

        // Sort metadata references by sourcePath
        metadataRefs.sort((a, b) -> {
            String pathA = a.sourcePath != null ? a.sourcePath : ""; //$NON-NLS-1$
            String pathB = b.sourcePath != null ? b.sourcePath : ""; //$NON-NLS-1$
            return pathA.compareToIgnoreCase(pathB);
        });

        appendMetadataRefs(sb, metadataRefs);
        appendBslModules(sb, bslModuleLines);

        return sb.toString();
    }

    /**
     * Splits the flat reference list into BSL lines grouped by module path (into
     * {@code bslModuleLines}) and metadata references (into {@code metadataRefs}), preserving the
     * original iteration order. Pure helper extracted from {@link #formatOutput}.
     */
    private static void partitionReferences(List<ReferenceInfo> allRefs,
        java.util.Map<String, List<Integer>> bslModuleLines, List<ReferenceInfo> metadataRefs)
    {
        for (ReferenceInfo ref : allRefs)
        {
            if (ref.isBslReference)
            {
                // Group BSL by module path
                String modulePath = ref.sourcePath;
                bslModuleLines.computeIfAbsent(modulePath, k -> new ArrayList<>()).add(ref.line);
            }
            else
            {
                metadataRefs.add(ref);
            }
        }
    }

    /**
     * Appends the (already sorted) metadata references to {@code sb} as a markdown bullet list,
     * stripping a leading slash from the display path and adding the feature when present. Pure
     * helper extracted from {@link #formatOutput}.
     */
    private static void appendMetadataRefs(StringBuilder sb, List<ReferenceInfo> metadataRefs)
    {
        for (ReferenceInfo ref : metadataRefs)
        {
            String displayPath = ref.sourcePath;
            if (displayPath != null && displayPath.startsWith("/")) //$NON-NLS-1$
            {
                displayPath = displayPath.substring(1);
            }

            sb.append("- ").append(displayPath); //$NON-NLS-1$
            if (ref.feature != null && !ref.feature.isEmpty())
            {
                sb.append(" - ").append(ref.feature); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
    }

    /**
     * Appends the BSL references grouped by module (the "### BSL Modules" section) to {@code sb}:
     * for each module path it sorts the line numbers, strips a leading slash, and renders them as
     * {@code [Line X; Line Y; ...]}. Does nothing when there are no BSL references. Pure helper
     * extracted from {@link #formatOutput}.
     */
    private static void appendBslModules(StringBuilder sb,
        java.util.Map<String, List<Integer>> bslModuleLines)
    {
        if (bslModuleLines.isEmpty())
        {
            return;
        }
        sb.append("\n### BSL Modules\n\n"); //$NON-NLS-1$

        for (java.util.Map.Entry<String, List<Integer>> entry : bslModuleLines.entrySet())
        {
            String modulePath = entry.getKey();
            List<Integer> lines = entry.getValue();

            // Sort lines
            lines.sort(Integer::compareTo);

            // Remove leading slash if present
            if (modulePath != null && modulePath.startsWith("/")) //$NON-NLS-1$
            {
                modulePath = modulePath.substring(1);
            }

            sb.append("- ").append(modulePath); //$NON-NLS-1$

            // Format lines as [Line X; Line Y; ...]
            appendBslLines(sb, lines);
            sb.append("\n"); //$NON-NLS-1$
        }
    }

    /**
     * Appends a module's line numbers as {@code [Line X; Line Y; ...]} to {@code sb}, or nothing
     * when the list is empty. Pure helper extracted from {@link #formatOutput}.
     */
    private static void appendBslLines(StringBuilder sb, List<Integer> lines)
    {
        if (lines.isEmpty())
        {
            return;
        }
        sb.append(" ["); //$NON-NLS-1$
        for (int i = 0; i < lines.size(); i++)
        {
            if (i > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append("Line ").append(lines.get(i)); //$NON-NLS-1$
        }
        sb.append("]"); //$NON-NLS-1$
    }

    /**
     * Reference information holder.
     */
    private static class ReferenceInfo
    {
        String category;
        String sourcePath;
        String feature;
        int line;
        boolean isBslReference;

        /** Constructor for metadata references */
        ReferenceInfo(String category, String sourcePath, String feature)
        {
            this.category = category;
            this.sourcePath = sourcePath;
            this.feature = feature;
            this.isBslReference = false;
        }

        /** Constructor for BSL code references */
        ReferenceInfo(String category, String sourcePath, int line)
        {
            this.category = category;
            this.sourcePath = sourcePath;
            this.line = line;
            this.isBslReference = true;
        }
    }

    /**
     * BM Task to collect all references.
     */
    private static class ReferenceCollector extends AbstractBmTask<Void>
    {
        private final IBmModel bmModel;
        private final MdObject targetObject;
        private final int limit;
        private final List<ReferenceInfo> references = new ArrayList<>();
        /** Set to track unique references (category:path:feature) to avoid duplicates */
        private final java.util.Set<String> seenReferences = new java.util.HashSet<>();
        /** Reused across references so each .bsl is parsed at most once for line resolution. */
        private org.eclipse.emf.ecore.resource.ResourceSet lineResolveResourceSet;

        ReferenceCollector(IBmModel bmModel, MdObject targetObject, int limit)
        {
            super("Find references to " + targetObject.getName()); //$NON-NLS-1$
            this.bmModel = bmModel;
            this.targetObject = targetObject;
            this.limit = limit;
        }

        @Override
        public Void execute(com._1c.g5.v8.bm.core.IBmTransaction transaction,
                           org.eclipse.core.runtime.IProgressMonitor progressMonitor)
        {
            IBmEngine engine = bmModel.getEngine();
            IBmObject targetBmObject = (IBmObject) targetObject;

            // 1. Collect direct back references
            collectBackReferences(engine, targetBmObject);

            // 2. Collect references to produced types
            collectProducedTypesReferences(engine, targetObject);

            // 3. Collect references to predefined items
            collectPredefinedItemsReferences(engine, targetObject);

            // 4. Collect references to fields (attributes, tabular sections, etc.)
            collectFieldReferences(engine, targetObject);

            // 5. Collect BSL code references
            collectBslReferences(targetBmObject);

            return null;
        }

        /**
         * Adds reference if not duplicate.
         * @return true if added, false if duplicate or internal path
         */
        private boolean addReference(ReferenceInfo ref)
        {
            // Create unique key
            String key = ref.category + ":" + ref.sourcePath + ":" + //$NON-NLS-1$ //$NON-NLS-2$
                (ref.isBslReference ? ref.line : ref.feature);

            // Skip duplicates
            if (seenReferences.contains(key))
            {
                return false;
            }

            // Note: EDT shows self-references (references within the same object)
            // so we don't filter them

            // Skip technical/internal objects
            if (ref.sourcePath != null && isInternalPath(ref.sourcePath))
            {
                return false;
            }

            seenReferences.add(key);
            references.add(ref);
            return true;
        }

        private void collectBackReferences(IBmEngine engine, IBmObject target)
        {
            Collection<IBmCrossReference> refs = engine.getBackReferences(target);

            for (IBmCrossReference ref : refs) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
            {
                if (references.size() >= limit * 10) // overall cap (all categories) before grouping
                {
                    break;
                }

                IBmObject sourceObject = ref.getObject();
                if (sourceObject == null)
                {
                    continue;
                }

                // Skip internal/obvious references
                if (isInternalReference(ref))
                {
                    continue;
                }

                String category = getCategoryFromObject(sourceObject);
                // Build full path including the feature path inside the object
                String sourcePath = getFullReferencePath(sourceObject, ref);
                // Skip if path is internal (returns null)
                if (sourcePath == null)
                {
                    continue;
                }
                String feature = ref.getFeature() != null ? ref.getFeature().getName() : null;

                addReference(new ReferenceInfo(category, sourcePath, feature));
            }
        }

        private void collectProducedTypesReferences(IBmEngine engine, MdObject target)
        {
            MdTypes producedTypes = MdClassUtil.getProducedTypes(target);
            if (producedTypes == null)
            {
                return;
            }

            for (EObject type : producedTypes.eContents())
            {
                TypeItem typeItem = getTypeItem(type);
                if (typeItem instanceof IBmObject)
                {
                    collectTypeItemBackReferences(engine, (IBmObject) typeItem);
                }
            }
        }

        /** Collects the (non-internal) back references of a single produced-type item, tagged "Type: ...". */
        private void collectTypeItemBackReferences(IBmEngine engine, IBmObject typeItem)
        {
            Collection<IBmCrossReference> refs = engine.getBackReferences(typeItem);
            for (IBmCrossReference ref : refs) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
            {
                if (references.size() >= limit * 10)
                {
                    break;
                }

                IBmObject sourceObject = ref.getObject();
                if (sourceObject == null || isInternalReference(ref))
                {
                    continue;
                }

                String category = getCategoryFromObject(sourceObject);
                String sourcePath = getFullReferencePath(sourceObject, ref);
                if (sourcePath == null)
                {
                    continue;
                }
                String feature = "Type: " + (ref.getFeature() != null ? ref.getFeature().getName() : ""); //$NON-NLS-1$ //$NON-NLS-2$

                addReference(new ReferenceInfo(category, sourcePath, feature));
            }
        }

        private TypeItem getTypeItem(EObject type)
        {
            if (type instanceof MdType)
            {
                return ((MdType) type).getType();
            }
            if (type instanceof MdTypeSet)
            {
                return ((MdTypeSet) type).getTypeSet();
            }
            return null;
        }

        private void collectPredefinedItemsReferences(IBmEngine engine, MdObject target)
        {
            for (PredefinedItem item : PredefinedItemUtil.getItems((EObject) target))
            {
                Collection<IBmCrossReference> refs = engine.getBackReferences((IBmObject) item);
                for (IBmCrossReference ref : refs) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
                {
                    if (references.size() >= limit * 10)
                    {
                        break;
                    }

                    IBmObject sourceObject = ref.getObject();
                    if (sourceObject == null)
                    {
                        continue;
                    }

                    String category = "Predefined items"; //$NON-NLS-1$
                    String sourcePath = getFullReferencePath(sourceObject, ref);
                    if (sourcePath == null)
                    {
                        continue;
                    }
                    String feature = item.getName();

                    addReference(new ReferenceInfo(category, sourcePath, feature));
                }
            }
        }

        private void collectFieldReferences(IBmEngine engine, MdObject target)
        {
            if (!(target instanceof FieldSource))
            {
                return;
            }

            FieldSource fieldSource = (FieldSource) target;
            for (var field : fieldSource.getFields())
            {
                if (!(field instanceof IBmObject))
                {
                    continue;
                }

                Collection<IBmCrossReference> refs = engine.getBackReferences((IBmObject) field);
                collectFieldBackReferences(refs, field, target);
            }
        }

        /**
         * Collects the back-references of a single field, skipping null source objects,
         * self-references and references with no resolvable path.
         */
        private void collectFieldBackReferences(Collection<IBmCrossReference> refs, Field field, MdObject target)
        {
            for (IBmCrossReference ref : refs) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
            {
                if (references.size() >= limit * 10)
                {
                    break;
                }

                IBmObject sourceObject = ref.getObject();
                if (sourceObject == null)
                {
                    continue;
                }

                // Skip self-references
                if (sourceObject == target)
                {
                    continue;
                }

                String category = "Field references"; //$NON-NLS-1$
                String sourcePath = getFullReferencePath(sourceObject, ref);
                if (sourcePath == null)
                {
                    continue;
                }
                String feature = field.getName();

                addReference(new ReferenceInfo(category, sourcePath, feature));
            }
        }

        private void collectBslReferences(IBmObject target)
        {
            try
            {
                IResourceServiceProvider resourceServiceProvider =
                    IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BslModuleUtils.BSL_LOOKUP_URI);

                if (resourceServiceProvider == null)
                {
                    return;
                }

                IReferenceFinder finder = resourceServiceProvider.get(IReferenceFinder.class);
                if (finder == null)
                {
                    return;
                }

                // Collect target URIs (including produced types)
                List<URI> targetURIs = new ArrayList<>();
                targetURIs.add(EcoreUtil.getURI((EObject) target));

                // Add produced types URIs
                if (target instanceof MdObject)
                {
                    MdTypes producedTypes = MdClassUtil.getProducedTypes((MdObject) target);
                    if (producedTypes != null)
                    {
                        for (EObject type : producedTypes.eContents())
                        {
                            TypeItem typeItem = getTypeItem(type);
                            if (typeItem != null)
                            {
                                targetURIs.add(EcoreUtil.getURI(type));
                            }
                        }
                    }
                }

                // Find all references in BSL code
                finder.findAllReferences(targetURIs, null, this::collectBslReferenceDescription, new NullProgressMonitor());
            }
            catch (Exception e)
            {
                Activator.logError("Error finding BSL references", e); //$NON-NLS-1$
            }
        }

        private void collectBslReferenceDescription(IReferenceDescription refDesc)
        {
            if (references.size() >= limit * 10)
            {
                return;
            }

            // Use sourceEObjectUri which contains the exact location in the AST
            URI sourceUri = refDesc.getSourceEObjectUri();
            if (sourceUri == null)
            {
                return;
            }

            // Get the resource path (without fragment)
            String path = sourceUri.path();
            if (path == null)
            {
                path = sourceUri.toString();
            }

            // Extract module path from URI
            String modulePath = extractModulePath(path);

            // Extract line number - we need to load the EObject and use NodeModelUtils
            int line = extractLineNumberFromSourceUri(sourceUri);

            // Use addReference for deduplication
            addReference(new ReferenceInfo("BSL modules", modulePath, line)); //$NON-NLS-1$
        }

        private String extractModulePath(String path)
        {
            if (path == null)
            {
                return "Unknown module"; //$NON-NLS-1$
            }

            // Try to extract meaningful path from URI
            // Example: /project/src/CommonModules/MyModule/Module.bsl
            int srcIdx = path.indexOf("/src/"); //$NON-NLS-1$
            if (srcIdx >= 0)
            {
                return path.substring(srcIdx + 5);
            }

            // Return last segments
            String[] parts = path.split("/"); //$NON-NLS-1$
            if (parts.length >= 3)
            {
                return parts[parts.length - 3] + "/" + parts[parts.length - 2] + "/" + parts[parts.length - 1]; //$NON-NLS-1$ //$NON-NLS-2$
            }

            return path;
        }

        /**
         * Extracts line number from sourceEObjectUri by loading the EObject
         * and using NodeModelUtils to get its position.
         */
        private int extractLineNumberFromSourceUri(URI sourceUri)
        {
            if (sourceUri == null)
            {
                return 0;
            }

            try
            {
                // Reuse a single ResourceSet across all references so each .bsl is
                // loaded/parsed at most once. Previously a new detached
                // ResourceSetImpl was created per reference, which re-parsed files
                // and resolved flakily, producing a false "Line 0".
                org.eclipse.emf.ecore.resource.ResourceSet resourceSet = getLineResolveResourceSet(sourceUri);

                // Get just the resource URI (without fragment)
                URI resourceUri = sourceUri.trimFragment();
                org.eclipse.emf.ecore.resource.Resource resource = resourceSet.getResource(resourceUri, true);

                if (resource != null)
                {
                    // Get the EObject by fragment
                    EObject eObject = resource.getEObject(sourceUri.fragment());
                    if (eObject != null)
                    {
                        org.eclipse.xtext.nodemodel.INode node =
                            org.eclipse.xtext.nodemodel.util.NodeModelUtils.findActualNodeFor(eObject);
                        if (node != null)
                        {
                            return node.getStartLine();
                        }
                    }
                }
            }
            catch (Exception e)
            {
                // Fall back to fragment parsing
                Activator.logError("Error extracting line number from URI: " + sourceUri, e); //$NON-NLS-1$
            }

            // The URI fragment carries a method index, not a line number, so the line
            // cannot be derived here; 0 signals "unknown line".
            return 0;
        }

        /**
         * Lazily creates and caches a single ResourceSet (configured with the BSL
         * Xtext resource factory) reused for every line-resolution load in this
         * find, instead of a detached ResourceSet per reference.
         */
        private org.eclipse.emf.ecore.resource.ResourceSet getLineResolveResourceSet(URI sourceUri)
        {
            if (lineResolveResourceSet == null)
            {
                org.eclipse.emf.ecore.resource.ResourceSet rs =
                    new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
                IResourceServiceProvider rsp =
                    IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(sourceUri);
                if (rsp != null)
                {
                    org.eclipse.xtext.resource.XtextResourceFactory factory =
                        rsp.get(org.eclipse.xtext.resource.XtextResourceFactory.class);
                    if (factory != null)
                    {
                        rs.getResourceFactoryRegistry().getExtensionToFactoryMap()
                            .put("bsl", factory); //$NON-NLS-1$
                    }
                }
                lineResolveResourceSet = rs;
            }
            return lineResolveResourceSet;
        }

        private boolean isInternalReference(IBmCrossReference ref)
        {
            IBmObject object = ref.getObject();
            if (object == null)
            {
                return true;
            }

            // Skip transient references
            EStructuralFeature feature = ref.getFeature();
            if (feature != null && feature.isTransient())
            {
                return true;
            }

            // Check package URI - following EDT's isInternal logic
            String packageUri = object.eClass().getEPackage().getNsURI();
            if (packageUri == null)
            {
                return true;
            }

            // Skip dbview package references (EDT's DB_VIEW_PACKAGE_URI)
            if (packageUri.contains("dbview")) //$NON-NLS-1$
            {
                return true;
            }

            // Skip derived command interface (cmi/deriveddata package)
            return packageUri.contains("cmi") && packageUri.contains("deriveddata"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private String getCategoryFromObject(IBmObject object)
        {
            if (object == null)
            {
                return "Other"; //$NON-NLS-1$
            }

            String className = object.eClass().getName();

            // Map class names to readable categories
            String category = mapClassNameToCategory(className);
            if (category != null)
            {
                return category;
            }

            // Check if it's an MdObject
            if (object instanceof MdObject)
            {
                MdObject mdObject = (MdObject) object;
                return mdObject.eClass().getName();
            }

            return className;
        }

        /**
         * Maps an EClass name to a readable reference category by the same ordered
         * {@code contains} checks as the original inline chain (first match wins),
         * or {@code null} when none matches. Pure helper extracted from
         * {@link #getCategoryFromObject}; the {@code null} result lets the caller
         * apply the unchanged MdObject / raw-class-name fallback.
         */
        private static String mapClassNameToCategory(String className) // NOSONAR reflective/form or transport god-method; further extraction deferred (reflective code)
        {
            if (className.contains("Subsystem")) return "Subsystems"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Role")) return "Roles"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("CommonModule")) return "Common modules"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("CommonAttribute")) return "Common attributes"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("EventSubscription")) return "Event subscriptions"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("ScheduledJob")) return "Scheduled jobs"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Form")) return "Forms"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Document")) return "Documents"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Catalog")) return "Catalogs"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Register")) return "Registers"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Report")) return "Reports"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("DataProcessor")) return "Data processors"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Command")) return "Commands"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("TypeDescription")) return "Type descriptions"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("FunctionalOption")) return "Functional options"; //$NON-NLS-1$ //$NON-NLS-2$
            if (className.contains("Template")) return "Templates"; //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        /**
         * Gets full reference path including the path inside the object to the referring feature.
         * Format: TopObject - FeatureLabel.ObjectName.FeatureLabel...
         * Example: Catalog.Items.Form.ItemForm.Form - Items.List.Items.Owner.Data path
         *
         * Follows EDT's TableItemsFactory algorithm:
         * - Build path from sourceObject up to topObject using eContainer()
         * - For each level: FeatureLabel (localized) + ObjectName (if available)
         */
        private String getFullReferencePath(IBmObject sourceObject, IBmCrossReference ref)
        {
            // Get the top-level container (form, catalog, etc.)
            IBmObject topObject = findTopContainer(sourceObject);
            if (topObject == null)
            {
                return getObjectPath(sourceObject);
            }

            String topPath = topObject.bmGetFqn();
            if (topPath == null || topPath.isEmpty())
            {
                return getObjectPath(sourceObject);
            }

            // Build the inner path EDT-style: FeatureLabel.ObjectName.FeatureLabel...
            String innerPath = buildInnerPathEdtStyle(sourceObject, ref.getFeature());

            // Filter out internal/technical paths that EDT doesn't show
            if (innerPath != null && isInternalPath(innerPath))
            {
                return null; // Signal to skip this reference
            }

            StringBuilder result = new StringBuilder(topPath);
            if (innerPath != null && !innerPath.isEmpty())
            {
                result.append(" - ").append(innerPath); //$NON-NLS-1$
            }

            return result.toString();
        }

        /**
         * Checks if path is internal/technical and should be filtered out.
         * EDT doesn't show references from Value types, Form context, Db view defs, etc.
         */
        private boolean isInternalPath(String path)
        {
            if (path == null || path.isEmpty())
            {
                return false;
            }

            // Skip paths starting with technical features
            return path.startsWith("Value types") || path.startsWith("Form context") //$NON-NLS-1$ //$NON-NLS-2$
                || path.startsWith("Db view defs") || path.startsWith("Standard commands"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Finds the top-level container of an object.
         */
        private IBmObject findTopContainer(IBmObject object)
        {
            if (object == null)
            {
                return null;
            }

            if (object.bmIsTop())
            {
                return object;
            }

            EObject current = (EObject) object;
            while (current != null)
            {
                if (current instanceof IBmObject && ((IBmObject) current).bmIsTop())
                {
                    return (IBmObject) current;
                }
                current = current.eContainer();
            }

            return null;
        }

        /**
         * Builds the inner path EDT-style: FeatureLabel.ObjectName.FeatureLabel.ObjectName...
         * Following EDT's TableItemsFactory algorithm.
         *
         * @param sourceObject the source object where reference is located
         * @param referenceFeature the feature that contains the reference
         * @return path string like "Items.List.Items.Owner.Data path"
         */
        private String buildInnerPathEdtStyle(IBmObject sourceObject, EStructuralFeature referenceFeature)
        {
            // Build path exactly like EDT's TableItemsFactory.getTopObjectPathToReference
            // Path is built from source up to topObject: Deque<Pair<EObject, EStructuralFeature>>
            Deque<PathSegment> path = new ArrayDeque<>();

            IBmObject parent = sourceObject;
            EStructuralFeature ref = referenceFeature;

            do
            {
                path.addFirst(new PathSegment((EObject) parent, ref));
                if (parent.bmIsTop())
                {
                    break;
                }
                ref = ((EObject) parent).eContainingFeature();
                EObject container = ((EObject) parent).eContainer();
                parent = container instanceof IBmObject ? (IBmObject) container : null;
            }
            while (parent != null);

            if (path.isEmpty())
            {
                return referenceFeature != null ? getFeatureLabel(referenceFeature) : null;
            }

            return buildPathString(path);
        }

        /**
         * Renders the EDT-style path string from the segment deque built by
         * {@link #buildInnerPathEdtStyle}. Consumes the (already non-empty) deque
         * head-first, exactly as the inline tail it was extracted from: same
         * feature-label seeding, same null-segment skipping, same redundant
         * "Items" collapsing, same empty-to-{@code null} mapping.
         *
         * @param path the non-empty segment deque, top object first
         * @return the rendered path, or {@code null} when nothing renders
         */
        private String buildPathString(Deque<PathSegment> path)
        {
            // Build path string exactly like EDT:
            // referenceName = featureLabel(topObjectPair.second)
            // for each segment: referenceName + "." + objectName + "." + featureLabel
            PathSegment topObjectPair = path.pollFirst();

            // Start with feature label of the first segment
            StringBuilder referenceName = new StringBuilder();
            if (topObjectPair.feature != null)
            {
                String label = getFeatureLabel(topObjectPair.feature);
                if (label != null)
                {
                    referenceName.append(label);
                }
            }

            // For remaining segments, add objectName.featureLabel
            // Optimization: skip redundant "Items" in path (e.g., Items.X.Items.Y -> Items.X.Y)
            String prevFeatureLabel = referenceName.length() > 0 ? referenceName.toString() : null;
            for (PathSegment segment : path)
            {
                String nextSegmentName = getSegmentObjectName(segment.object);
                // EDT skips segments where getSegmentName returns null!
                if (nextSegmentName == null)
                {
                    continue;
                }

                String currentFeatureLabel = getFeatureLabel(segment.feature);

                if (referenceName.length() > 0)
                {
                    referenceName.append("."); //$NON-NLS-1$
                }
                referenceName.append(nextSegmentName);

                // Skip adding featureLabel if it's "Items" and the previous featureLabel was also "Items"
                // This removes redundant ".Items" in paths like "Items.X.Items.Y" -> "Items.X.Y"
                boolean isItemsFeature = "Items".equals(currentFeatureLabel); //$NON-NLS-1$
                boolean prevWasItems = "Items".equals(prevFeatureLabel); //$NON-NLS-1$

                if (!isItemsFeature || !prevWasItems)
                {
                    referenceName.append("."); //$NON-NLS-1$
                    referenceName.append(currentFeatureLabel);
                }

                prevFeatureLabel = currentFeatureLabel;
            }

            return referenceName.length() > 0 ? referenceName.toString() : null;
        }

        /**
         * Gets the localized label for a feature, following EDT's Functions.featureToLabel() pattern.
         */
        private String getFeatureLabel(EStructuralFeature feature)
        {
            if (feature == null)
            {
                return null;
            }
            try
            {
                // Try to use EDT's localization
                String label = Functions.featureToLabel().apply(feature);
                if (label != null)
                {
                    return label;
                }
            }
            catch (Exception e)
            {
                // Ignore and fall back
            }
            // Fallback: capitalize feature name
            return capitalizeFirst(feature.getName());
        }

        /**
         * Gets the name of an object for path segment (following EDT's getSegmentName pattern).
         */
        private String getSegmentObjectName(EObject object)
        {
            // NamedElement has getName() - covers form items, commands, etc.
            if (object instanceof NamedElement)
            {
                return ((NamedElement) object).getName();
            }
            // MdObject also has getName()
            if (object instanceof MdObject)
            {
                return ((MdObject) object).getName();
            }
            // For ExtInfo (form extension info), return EClass name
            // Check by class name to avoid dependency on form bundle
            String className = object.eClass().getName();
            if (className.endsWith("ExtInfo")) //$NON-NLS-1$
            {
                return className;
            }
            return null;
        }

        /**
         * Helper class to hold path segment information (like EDT's Pair<EObject, EStructuralFeature>).
         */
        private static class PathSegment
        {
            final EObject object;
            final EStructuralFeature feature;

            PathSegment(EObject object, EStructuralFeature feature)
            {
                this.object = object;
                this.feature = feature;
            }
        }

        private String getObjectPath(IBmObject object)
        {
            if (object == null)
            {
                return "Unknown"; //$NON-NLS-1$
            }

            // Try to get FQN (for top-level objects)
            try
            {
                if (object.bmIsTop())
                {
                    String fqn = object.bmGetFqn();
                    if (fqn != null && !fqn.isEmpty())
                    {
                        // Make FQN more readable: "Catalog.Items.Form.ItemForm.Form" -> "Catalog.Items / Form.ItemForm"
                        return formatFqn(fqn);
                    }
                }
                else
                {
                    // For nested objects, build path from container to this object
                    return buildNestedObjectPath(object);
                }
            }
            catch (Exception e)
            {
                // Ignore
            }

            return getObjectPathFromUriOrClass(object);
        }

        /**
         * Derives an object path from its URI (preferring the part after "/src/"),
         * falling back to the EClass name when no URI path is available.
         */
        private String getObjectPathFromUriOrClass(IBmObject object)
        {
            // Try to get URI path
            if (object instanceof EObject)
            {
                org.eclipse.emf.common.util.URI uri = EcoreUtil.getURI((EObject) object);
                if (uri != null)
                {
                    String path = uri.path();
                    if (path != null)
                    {
                        // Extract meaningful part
                        int srcIdx = path.indexOf("/src/"); //$NON-NLS-1$
                        if (srcIdx >= 0)
                        {
                            return path.substring(srcIdx + 5);
                        }
                        return path;
                    }
                }
            }

            // Fallback to class name
            return object.eClass().getName();
        }

        /**
         * Builds path for nested objects like attributes, dimensions, etc.
         * Example: Catalog.ItemKeys.Attribute.Item or InformationRegister.Barcodes.Dimension.Barcode
         */
        private String buildNestedObjectPath(IBmObject object)
        {
            StringBuilder path = new StringBuilder();
            EObject current = (EObject) object;
            List<String> parts = new ArrayList<>();

            // Walk up the containment hierarchy
            while (current != null)
            {
                String part = getObjectPart(current);
                if (part != null && !part.isEmpty())
                {
                    parts.add(0, part);
                }

                if (current instanceof IBmObject && ((IBmObject) current).bmIsTop())
                {
                    break;
                }

                current = current.eContainer();
            }

            // Join parts
            for (int i = 0; i < parts.size(); i++)
            {
                if (i > 0)
                {
                    path.append("."); //$NON-NLS-1$
                }
                path.append(parts.get(i));
            }

            return formatFqn(path.toString());
        }

        /**
         * Gets a meaningful part name for an object in the path.
         */
        private String getObjectPart(EObject object)
        {
            if (object instanceof IBmObject && ((IBmObject) object).bmIsTop())
            {
                // Top-level object - use FQN
                String fqn = ((IBmObject) object).bmGetFqn();
                return fqn != null ? fqn : object.eClass().getName();
            }

            // Get containing feature name (like "attributes", "dimensions")
            EReference containingFeature = object.eContainmentFeature();
            String featureName = containingFeature != null ?
                capitalizeFirst(containingFeature.getName()) : null;

            // Get object name if available
            String objectName = null;
            if (object instanceof com._1c.g5.v8.dt.metadata.mdclass.MdObject)
            {
                objectName = ((com._1c.g5.v8.dt.metadata.mdclass.MdObject) object).getName();
            }
            else if (object instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicFeature)
            {
                objectName = ((com._1c.g5.v8.dt.metadata.mdclass.BasicFeature) object).getName();
            }

            if (objectName != null && !objectName.isEmpty())
            {
                // Format: FeatureType.ObjectName (e.g., "Attribute.Item")
                if (featureName != null && isCollectionFeature(featureName))
                {
                    // Convert plural to singular: "attributes" -> "Attribute"
                    return singularize(featureName) + "." + objectName; //$NON-NLS-1$
                }
                return objectName;
            }

            return null;
        }

        private String capitalizeFirst(String str)
        {
            if (str == null || str.isEmpty())
            {
                return str;
            }
            return Character.toUpperCase(str.charAt(0)) + str.substring(1);
        }

        private boolean isCollectionFeature(String name)
        {
            return name.endsWith("s") || name.equals("Content"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private String singularize(String name)
        {
            if (name == null || name.isEmpty())
            {
                return name;
            }
            // Convert common plurals: attributes -> Attribute, dimensions -> Dimension
            if (name.endsWith("ies")) //$NON-NLS-1$
            {
                return name.substring(0, name.length() - 3) + "y"; //$NON-NLS-1$
            }
            if (name.endsWith("ses")) //$NON-NLS-1$
            {
                return name.substring(0, name.length() - 2);
            }
            if (name.endsWith("s") && !name.endsWith("ss")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return name.substring(0, name.length() - 1);
            }
            return name;
        }

        /**
         * Formats FQN to be more readable.
         * Examples:
         * - "Catalog.Items.Form.ItemForm.Form" -> "Catalog.Items / Form.ItemForm"
         * - "Catalog.Items.Attribute.Code" -> "Catalog.Items / Attribute.Code"
         * - "CommonModule.GetItemInfo" -> "CommonModule.GetItemInfo"
         */
        private String formatFqn(String fqn)
        {
            if (fqn == null || fqn.isEmpty())
            {
                return fqn;
            }

            // Remove leading slash if present
            if (fqn.startsWith("/")) //$NON-NLS-1$
            {
                fqn = fqn.substring(1);
            }

            // Split by dots
            String[] parts = fqn.split("\\."); //$NON-NLS-1$
            if (parts.length <= 2)
            {
                return fqn; // Already short enough
            }

            // Check for known patterns: Type.Name.SubType.SubName.SubSubType
            // Keep first 2 parts (main object), then summarize the rest
            StringBuilder sb = new StringBuilder();
            sb.append(parts[0]).append(".").append(parts[1]); //$NON-NLS-1$

            if (parts.length > 2)
            {
                sb.append(" / "); //$NON-NLS-1$
                // Skip duplicate type at the end (like "Form.ItemForm.Form" -> "Form.ItemForm")
                int endIdx = parts.length;
                if (parts.length >= 4 && parts[parts.length - 1].equals("Form")) //$NON-NLS-1$
                {
                    endIdx = parts.length - 1;
                }
                for (int i = 2; i < endIdx; i++)
                {
                    if (i > 2)
                    {
                        sb.append("."); //$NON-NLS-1$
                    }
                    sb.append(parts[i]);
                }
            }

            return sb.toString();
        }

        public int getTotalCount()
        {
            return references.size();
        }

        public List<ReferenceInfo> getAllReferences()
        {
            return new ArrayList<>(references);
        }
    }
}
