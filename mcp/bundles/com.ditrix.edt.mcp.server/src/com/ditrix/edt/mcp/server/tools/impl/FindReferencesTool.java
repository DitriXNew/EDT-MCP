/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.mcore.FieldSource;
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
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to find all references to a metadata object.
 * Returns all places where the object is used: in other metadata objects and BSL code.
 */
@SuppressWarnings("restriction")
public class FindReferencesTool implements IMcpTool
{
    public static final String NAME = "find_references"; //$NON-NLS-1$
    
    /** URI for getting BSL resource service provider */
    private static final URI BSL_URI = URI.createURI("/nopr/module.bsl"); //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Find all references to a metadata object. " + //$NON-NLS-1$
               "Returns all places where the object is used: in other metadata objects, " + //$NON-NLS-1$
               "in BSL code modules with line numbers, forms, roles, subsystems, etc."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "Fully qualified name of the object to find references for " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder', 'CommonModule.Common')", true) //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of results per category. Default: 100") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
    
    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn != null && !objectFqn.isEmpty())
        {
            String safeName = objectFqn.replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "references-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "references.md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        
        // Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return "Error: objectFqn is required"; //$NON-NLS-1$
        }
        
        int limit = 100;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int) Double.parseDouble(limitStr), 500);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }
        
        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final int maxResults = limit;
        
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = findReferencesInternal(projectName, objectFqn, maxResults);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error finding references", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Internal implementation that runs on UI thread.
     */
    private String findReferencesInternal(String projectName, String objectFqn, int limit)
    {
        // Get project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }
        
        // Get configuration provider
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider not available"; //$NON-NLS-1$
        }
        
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: Could not get configuration for project: " + projectName; //$NON-NLS-1$
        }
        
        // Get BM model manager
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return "Error: BM model manager not available"; //$NON-NLS-1$
        }
        
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return "Error: BM model not available for project: " + projectName; //$NON-NLS-1$
        }
        
        // Find target object by FQN
        MdObject targetObject = findMdObjectByFqn(config, objectFqn);
        if (targetObject == null)
        {
            return "Error: Object not found: " + objectFqn; //$NON-NLS-1$
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
            return "Error executing search: " + e.getMessage(); //$NON-NLS-1$
        }
        
        // Format output
        return formatOutput(objectFqn, collector);
    }
    
    /**
     * Finds MdObject by FQN in configuration.
     */
    private MdObject findMdObjectByFqn(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }
        
        String metadataType = parts[0].toLowerCase();
        String objectName = parts[1];
        
        List<? extends MdObject> objects = null;
        
        switch (metadataType)
        {
            case "catalog":
            case "catalogs":
                objects = config.getCatalogs();
                break;
            case "document":
            case "documents":
                objects = config.getDocuments();
                break;
            case "commonmodule":
            case "commonmodules":
                objects = config.getCommonModules();
                break;
            case "informationregister":
            case "informationregisters":
                objects = config.getInformationRegisters();
                break;
            case "accumulationregister":
            case "accumulationregisters":
                objects = config.getAccumulationRegisters();
                break;
            case "report":
            case "reports":
                objects = config.getReports();
                break;
            case "dataprocessor":
            case "dataprocessors":
                objects = config.getDataProcessors();
                break;
            case "enum":
            case "enums":
                objects = config.getEnums();
                break;
            case "constant":
            case "constants":
                objects = config.getConstants();
                break;
            case "exchangeplan":
            case "exchangeplans":
                objects = config.getExchangePlans();
                break;
            case "businessprocess":
            case "businessprocesses":
                objects = config.getBusinessProcesses();
                break;
            case "task":
            case "tasks":
                objects = config.getTasks();
                break;
            case "role":
            case "roles":
                objects = config.getRoles();
                break;
            case "subsystem":
            case "subsystems":
                objects = config.getSubsystems();
                break;
            case "commonattribute":
            case "commonattributes":
                objects = config.getCommonAttributes();
                break;
            case "eventsubscription":
            case "eventsubscriptions":
                objects = config.getEventSubscriptions();
                break;
            case "scheduledjob":
            case "scheduledjobs":
                objects = config.getScheduledJobs();
                break;
            case "commonform":
            case "commonforms":
                objects = config.getCommonForms();
                break;
            case "commoncommand":
            case "commoncommands":
                objects = config.getCommonCommands();
                break;
            case "sessionparameter":
            case "sessionparameters":
                objects = config.getSessionParameters();
                break;
            case "functionaloptionsparameter":
            case "functionaloptionsparameters":
                objects = config.getFunctionalOptionsParameters();
                break;
            case "functionaloption":
            case "functionaloptions":
                objects = config.getFunctionalOptions();
                break;
            case "commonpicture":
            case "commonpictures":
                objects = config.getCommonPictures();
                break;
            case "styleitem":
            case "styleitems":
                objects = config.getStyleItems();
                break;
            case "definedtype":
            case "definedtypes":
                objects = config.getDefinedTypes();
                break;
            case "webservice":
            case "webservices":
                objects = config.getWebServices();
                break;
            case "httpservice":
            case "httpservices":
                objects = config.getHttpServices();
                break;
            default:
                return null;
        }
        
        if (objects == null)
        {
            return null;
        }
        
        for (MdObject obj : objects)
        {
            if (obj.getName().equalsIgnoreCase(objectName))
            {
                return obj;
            }
        }
        
        return null;
    }
    
    /**
     * Formats output as markdown.
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
        
        // Group by category and output
        Map<String, List<ReferenceInfo>> grouped = collector.getGroupedReferences();
        
        for (Map.Entry<String, List<ReferenceInfo>> entry : grouped.entrySet())
        {
            String category = entry.getKey();
            List<ReferenceInfo> refs = entry.getValue();
            
            sb.append("## ").append(category).append(" (").append(refs.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            
            for (ReferenceInfo ref : refs)
            {
                sb.append("- "); //$NON-NLS-1$
                
                // Remove leading slash for cleaner output
                String displayPath = ref.sourcePath;
                if (displayPath != null && displayPath.startsWith("/")) //$NON-NLS-1$
                {
                    displayPath = displayPath.substring(1);
                }
                
                if (ref.isBslReference)
                {
                    // BSL code reference with module and line
                    sb.append("**").append(displayPath).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (ref.line > 0)
                    {
                        sb.append(" (line ").append(ref.line).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                else
                {
                    // Metadata reference
                    sb.append(displayPath);
                    if (ref.feature != null && !ref.feature.isEmpty())
                    {
                        sb.append(" → ").append(ref.feature); //$NON-NLS-1$
                    }
                }
                sb.append("\n"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        
        return sb.toString();
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
    private class ReferenceCollector extends AbstractBmTask<Void>
    {
        private final IBmModel bmModel;
        private final MdObject targetObject;
        private final int limit;
        private final List<ReferenceInfo> references = new ArrayList<>();
        /** Set to track unique references (category:path:feature) to avoid duplicates */
        private final java.util.Set<String> seenReferences = new java.util.HashSet<>();
        /** Target object FQN for filtering self-references */
        private String targetFqn;
        
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
            
            // Get target FQN for filtering self-references
            targetFqn = targetBmObject.bmIsTop() ? targetBmObject.bmGetFqn().toString() : null;
            
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
         * Adds reference if not duplicate and not self-reference.
         * @return true if added, false if duplicate or self-reference
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
            
            // Skip true self-references only (path starts with targetFqn)
            // Don't filter Catalog.ItemKeys.Attribute.Item when searching for Catalog.Items
            if (targetFqn != null && ref.sourcePath != null)
            {
                // Normalize path - remove leading slash for comparison
                String normalizedPath = ref.sourcePath.startsWith("/") ? ref.sourcePath.substring(1) : ref.sourcePath; //$NON-NLS-1$
                if (normalizedPath.equals(targetFqn) || normalizedPath.startsWith(targetFqn + ".")) //$NON-NLS-1$
                {
                    return false;
                }
            }
            
            seenReferences.add(key);
            references.add(ref);
            return true;
        }
        
        private void collectBackReferences(IBmEngine engine, IBmObject target)
        {
            Collection<IBmCrossReference> refs = engine.getBackReferences(target);
            
            for (IBmCrossReference ref : refs)
            {
                if (references.size() >= limit * 10) // Allow more before grouping limit
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
                String sourcePath = getObjectPath(sourceObject);
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
                    Collection<IBmCrossReference> refs = engine.getBackReferences((IBmObject) typeItem);
                    for (IBmCrossReference ref : refs)
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
                        String sourcePath = getObjectPath(sourceObject);
                        String feature = "Type: " + (ref.getFeature() != null ? ref.getFeature().getName() : ""); //$NON-NLS-1$ //$NON-NLS-2$
                        
                        addReference(new ReferenceInfo(category, sourcePath, feature));
                    }
                }
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
                for (IBmCrossReference ref : refs)
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
                    String sourcePath = getObjectPath(sourceObject);
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
                for (IBmCrossReference ref : refs)
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
                    String sourcePath = getObjectPath(sourceObject);
                    String feature = field.getName();
                    
                    addReference(new ReferenceInfo(category, sourcePath, feature));
                }
            }
        }
        
        private void collectBslReferences(IBmObject target)
        {
            try
            {
                IResourceServiceProvider resourceServiceProvider = 
                    IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_URI);
                
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
                // Try to load the resource and get the EObject
                org.eclipse.emf.ecore.resource.ResourceSet resourceSet = 
                    new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
                
                // Configure the resource set with proper provider
                IResourceServiceProvider rsp = 
                    IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(sourceUri);
                if (rsp != null)
                {
                    org.eclipse.xtext.resource.XtextResourceFactory factory = rsp.get(org.eclipse.xtext.resource.XtextResourceFactory.class);
                    if (factory != null)
                    {
                        resourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap()
                            .put("bsl", factory); //$NON-NLS-1$
                    }
                }
                
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
            
            // Fallback: try to parse line from fragment
            return extractLineNumberFromFragment(sourceUri.fragment());
        }
        
        /**
         * Fallback method to extract approximate line number from URI fragment.
         */
        private int extractLineNumberFromFragment(String fragment)
        {
            if (fragment == null || fragment.isEmpty())
            {
                return 0;
            }
            
            // Fragment format may contain method index info, e.g. "//@methods.5"
            // This is not accurate but better than nothing
            try
            {
                // Look for method index - methods typically correlate with lines
                if (fragment.contains("@methods.")) //$NON-NLS-1$
                {
                    int idx = fragment.indexOf("@methods."); //$NON-NLS-1$
                    String rest = fragment.substring(idx + 9);
                    int endIdx = rest.indexOf('/');
                    if (endIdx > 0)
                    {
                        rest = rest.substring(0, endIdx);
                    }
                    endIdx = rest.indexOf('@');
                    if (endIdx > 0)
                    {
                        rest = rest.substring(0, endIdx);
                    }
                    // Method index is not line number, return 0 for now
                }
            }
            catch (Exception e)
            {
                // Ignore
            }
            
            return 0;
        }
        
        private boolean isInternalReference(IBmCrossReference ref)
        {
            IBmObject object = ref.getObject();
            if (object == null)
            {
                return true;
            }
            
            // Skip transient and internal references
            EStructuralFeature feature = ref.getFeature();
            if (feature != null && feature.isTransient())
            {
                return true;
            }
            
            // Skip obvious parent-child relationships
            String packageUri = object.eClass().getEPackage().getNsURI();
            if (packageUri != null && packageUri.contains("dbview")) //$NON-NLS-1$
            {
                return true;
            }
            
            return false;
        }
        
        private String getCategoryFromObject(IBmObject object)
        {
            if (object == null)
            {
                return "Other"; //$NON-NLS-1$
            }
            
            String className = object.eClass().getName();
            
            // Map class names to readable categories
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
            
            // Check if it's an MdObject
            if (object instanceof MdObject)
            {
                MdObject mdObject = (MdObject) object;
                return mdObject.eClass().getName();
            }
            
            return className;
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
        
        public Map<String, List<ReferenceInfo>> getGroupedReferences()
        {
            // Group by category
            Map<String, List<ReferenceInfo>> grouped = references.stream()
                .collect(Collectors.groupingBy(r -> r.category));
            
            // Apply limit per category
            Map<String, List<ReferenceInfo>> limited = new HashMap<>();
            for (Map.Entry<String, List<ReferenceInfo>> entry : grouped.entrySet())
            {
                List<ReferenceInfo> refs = entry.getValue();
                if (refs.size() > limit)
                {
                    limited.put(entry.getKey() + " (showing first " + limit + " of " + refs.size() + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                               refs.subList(0, limit));
                }
                else
                {
                    limited.put(entry.getKey(), refs);
                }
            }
            
            return limited;
        }
    }
}
