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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

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
 * Returns all places where the object is used: in other metadata objects.
 */
public class FindReferencesTool implements IMcpTool
{
    public static final String NAME = "find_references"; //$NON-NLS-1$
    
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
                sb.append("- ").append(ref.sourcePath); //$NON-NLS-1$
                if (ref.feature != null && !ref.feature.isEmpty())
                {
                    sb.append(" → ").append(ref.feature); //$NON-NLS-1$
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
        
        ReferenceInfo(String category, String sourcePath, String feature)
        {
            this.category = category;
            this.sourcePath = sourcePath;
            this.feature = feature;
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
            
            // Note: BSL code references search will be added in future version
            
            return null;
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
                
                references.add(new ReferenceInfo(category, sourcePath, feature));
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
                        
                        references.add(new ReferenceInfo(category, sourcePath, feature));
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
                    
                    references.add(new ReferenceInfo(category, sourcePath, feature));
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
                    
                    references.add(new ReferenceInfo(category, sourcePath, feature));
                }
            }
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
            
            // Try to get FQN (only for top objects)
            try
            {
                if (object.bmIsTop())
                {
                    String fqn = object.bmGetFqn();
                    if (fqn != null && !fqn.isEmpty())
                    {
                        return fqn;
                    }
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
