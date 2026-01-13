/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Ctor;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainer;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

import org.eclipse.emf.ecore.resource.ResourceSet;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tool to get platform documentation for types, methods, properties, etc.
 * Supports searching by type name (ValueTable, Array), member name (Add, Insert),
 * and different categories (type, builtin).
 */
public class GetPlatformDocumentationTool implements IMcpTool
{
    public static final String NAME = "get_platform_documentation"; //$NON-NLS-1$
    
    /** Category constants */
    private static final String CATEGORY_TYPE = "type"; //$NON-NLS-1$
    private static final String CATEGORY_BUILTIN = "builtin"; //$NON-NLS-1$
    
    /** Member type constants */
    private static final String MEMBER_ALL = "all"; //$NON-NLS-1$
    private static final String MEMBER_METHOD = "method"; //$NON-NLS-1$
    private static final String MEMBER_PROPERTY = "property"; //$NON-NLS-1$
    private static final String MEMBER_CONSTRUCTOR = "constructor"; //$NON-NLS-1$
    private static final String MEMBER_EVENT = "event"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get platform documentation for 1C:Enterprise types, methods, properties, " + //$NON-NLS-1$
               "and built-in functions. " + //$NON-NLS-1$
               "Examples: typeName='ValueTable', typeName='Array' memberName='Add'"; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("typeName", //$NON-NLS-1$
                "Type or symbol name (e.g. 'ValueTable', 'Array', 'Structure'). " + //$NON-NLS-1$
                "Supports both English and Russian names.", true) //$NON-NLS-1$
            .stringProperty("category", //$NON-NLS-1$
                "Category: 'type' (platform types like ValueTable), " + //$NON-NLS-1$
                "'builtin' (built-in functions). Default: 'type'") //$NON-NLS-1$
            .stringProperty("memberName", //$NON-NLS-1$
                "Filter by member name (method/property). Supports partial match. " + //$NON-NLS-1$
                "Example: 'Add', 'Insert', 'Count'") //$NON-NLS-1$
            .stringProperty("memberType", //$NON-NLS-1$
                "Filter by member type: 'method', 'property', 'constructor', 'event', 'all'. " + //$NON-NLS-1$
                "Default: 'all'") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name to determine platform version. Optional.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of results to return. Default: 50") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Output language: 'en' (English) or 'ru' (Russian). Default: 'en'") //$NON-NLS-1$
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
        String typeName = JsonUtils.extractStringArgument(params, "typeName"); //$NON-NLS-1$
        if (typeName != null && !typeName.isEmpty())
        {
            return "doc-" + typeName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "platform-documentation.md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String typeName = JsonUtils.extractStringArgument(params, "typeName"); //$NON-NLS-1$
        String category = JsonUtils.extractStringArgument(params, "category"); //$NON-NLS-1$
        String memberName = JsonUtils.extractStringArgument(params, "memberName"); //$NON-NLS-1$
        String memberType = JsonUtils.extractStringArgument(params, "memberType"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        
        // Validate required parameter
        if (typeName == null || typeName.isEmpty())
        {
            return "Error: typeName is required"; //$NON-NLS-1$
        }
        
        // Set defaults
        if (category == null || category.isEmpty())
        {
            category = CATEGORY_TYPE;
        }
        if (memberType == null || memberType.isEmpty())
        {
            memberType = MEMBER_ALL;
        }
        if (language == null || language.isEmpty())
        {
            language = "en"; //$NON-NLS-1$
        }
        
        int limit = 50;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int) Double.parseDouble(limitStr), 200);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }
        
        boolean useRussian = "ru".equalsIgnoreCase(language); //$NON-NLS-1$
        
        // Execute based on category
        switch (category.toLowerCase())
        {
            case CATEGORY_TYPE:
                return getTypeDocumentation(typeName, memberName, memberType, projectName, limit, useRussian);
            case CATEGORY_BUILTIN:
                return getBuiltinFunctionDocumentation(typeName, useRussian);
            default:
                return "Error: Unknown category '" + category + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                       "Supported: 'type', 'builtin'"; //$NON-NLS-1$
        }
    }
    
    /**
     * Gets documentation for a platform type (ValueTable, Array, etc.).
     */
    private String getTypeDocumentation(String typeName, String memberName, String memberType,
                                        String projectName, int limit, boolean useRussian)
    {
        AtomicReference<String> resultRef = new AtomicReference<>();
        
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getTypeDocumentationInternal(typeName, memberName, memberType,
                                                              projectName, limit, useRussian);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting type documentation", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Internal implementation that runs on UI thread.
     */
    private String getTypeDocumentationInternal(String typeName, String memberName, String memberType,
                                                 String projectName, int limit, boolean useRussian)
    {
        // Get version for type provider
        Version version = getProjectVersion(projectName);
        if (version == null)
        {
            version = Version.LATEST;
        }
        
        // Note: For platform types like Array, ValueTable, the types are
        // directly available from IEObjectDescription without needing project ResourceSet.
        
        // Get type provider using TYPE (not TYPE_ITEM - TYPE gives us platform types like ValueTable)
        IEObjectProvider.Registry registry = IEObjectProvider.Registry.INSTANCE;
        
        // Try TYPE first (platform types like Array, ValueTable)
        IEObjectProvider typeProvider = registry.get(McorePackage.Literals.TYPE, version);
        boolean typeProviderHasContent = false;
        if (typeProvider != null)
        {
            Iterable<IEObjectDescription> typeDes = typeProvider.getEObjectDescriptions(null);
            if (typeDes != null && typeDes.iterator().hasNext())
            {
                typeProviderHasContent = true;
            }
        }
        
        // Fall back to TYPE_ITEM if TYPE is empty (some EDT versions)
        IEObjectProvider typeItemProvider = registry.get(McorePackage.Literals.TYPE_ITEM, version);
        
        // Select the best provider with actual types
        if (!typeProviderHasContent)
        {
            typeProvider = typeItemProvider; // Fall back to TYPE_ITEM
        }
        
        if (typeProvider == null)
        {
            return "Error: Could not get type provider. Make sure EDT workspace is open."; //$NON-NLS-1$
        }
        
        // Find type by iterating through all type descriptions
        Type foundType = null;
        List<String> availableTypes = new ArrayList<>();
        
        Iterable<IEObjectDescription> descriptions = typeProvider.getEObjectDescriptions(null);
        if (descriptions != null)
        {
            for (IEObjectDescription desc : descriptions)
            {
                // Get last segment of qualified name (e.g., "DocumentRef" from "some.package.DocumentRef")
                String fullName = desc.getName().toString();
                String lastSegment = desc.getName().getLastSegment();
                
                // Collect first 30 types for debugging (show full name)
                if (availableTypes.size() < 30)
                {
                    availableTypes.add(lastSegment != null ? lastSegment : fullName);
                }
                
                // Check if this is the type we're looking for (case-insensitive, check both full and last segment)
                if (fullName.equalsIgnoreCase(typeName) || 
                    (lastSegment != null && lastSegment.equalsIgnoreCase(typeName)))
                {
                    // Get the object - for platform types from TYPE provider, 
                    // these should be fully resolved objects, not proxies
                    EObject resolved = desc.getEObjectOrProxy();
                    
                    if (resolved instanceof Type)
                    {
                        // If still a proxy, we can use the EcoreUtil registry to resolve
                        if (resolved.eIsProxy())
                        {
                            org.eclipse.emf.common.util.URI uri = desc.getEObjectURI();
                            try
                            {
                                // Try to resolve via platform resource
                                org.eclipse.emf.ecore.resource.impl.ResourceSetImpl tempResourceSet = 
                                    new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
                                resolved = EcoreUtil.resolve(resolved, tempResourceSet);
                            }
                            catch (Exception e)
                            {
                                Activator.logError("Error resolving type proxy: " + uri, e); //$NON-NLS-1$
                            }
                        }
                        
                        if (!resolved.eIsProxy())
                        {
                            foundType = (Type) resolved;
                            break;
                        }
                    }
                }
            }
        }
        
        // If not found, show available types
        if (foundType == null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: Type not found: ").append(typeName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("Available types (first ").append(availableTypes.size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String availType : availableTypes)
            {
                sb.append("- ").append(availType).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (availableTypes.isEmpty())
            {
                sb.append("(no types found - provider may be empty)\n"); //$NON-NLS-1$
            }
            else if (availableTypes.size() >= 30)
            {
                sb.append("... (more available)\n"); //$NON-NLS-1$
            }
            return sb.toString();
        }
        
        // Build documentation from resolved Type
        return buildTypeDocumentation(foundType, memberName, memberType, limit, useRussian);
    }
    
    /**
     * Builds markdown documentation for a Type.
     */
    private String buildTypeDocumentation(Type type, String memberName, String memberType, 
                                           int limit, boolean useRussian)
    {
        StringBuilder sb = new StringBuilder();
        
        // Type header
        String displayName = useRussian ? type.getNameRu() : type.getName();
        String altName = useRussian ? type.getName() : type.getNameRu();
        
        sb.append("# ").append(displayName != null ? displayName : "Unknown"); //$NON-NLS-1$ //$NON-NLS-2$
        if (altName != null && !altName.equals(displayName))
        {
            sb.append(" / ").append(altName); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
        
        // Type properties
        sb.append("**Type Info:**\n"); //$NON-NLS-1$
        sb.append("- Iterable: ").append(type.isIterable() ? "Yes" : "No").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        sb.append("- Index accessible: ").append(type.isIndexAccessible() ? "Yes" : "No").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        sb.append("- Created by New: ").append(type.isCreatedByNewOperator() ? "Yes" : "No").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        
        // Collection element types
        TypeContainer elementTypes = type.getCollectionElementTypes();
        if (elementTypes != null)
        {
            EList<TypeItem> elemTypesList = elementTypes.allTypes();
            if (elemTypesList != null && !elemTypesList.isEmpty())
            {
                sb.append("**Collection element types:** "); //$NON-NLS-1$
                List<String> typeNames = new ArrayList<>();
                for (TypeItem elemType : elemTypesList)
                {
                    String name = useRussian ? elemType.getNameRu() : elemType.getName();
                    if (name != null)
                    {
                        typeNames.add(name);
                    }
                }
                sb.append(String.join(", ", typeNames)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        
        int count = 0;
        
        // Constructors
        if (shouldIncludeMemberType(memberType, MEMBER_CONSTRUCTOR))
        {
            EList<Ctor> ctors = type.getCtors();
            if (ctors != null && !ctors.isEmpty())
            {
                sb.append("## Constructors\n\n"); //$NON-NLS-1$
                for (int i = 0; i < ctors.size(); i++)
                {
                    Ctor ctor = ctors.get(i);
                    if (count >= limit)
                        break;
                    appendCtorDocumentation(sb, ctor, i + 1, useRussian);
                    count++;
                }
                sb.append("\n"); //$NON-NLS-1$
            }
        }
        
        // Get context def for methods and properties
        ContextDef contextDef = type.getContextDef();
        if (contextDef != null)
        {
            // Methods
            if (shouldIncludeMemberType(memberType, MEMBER_METHOD))
            {
                EList<Method> methods = contextDef.allMethods();
                if (methods != null && !methods.isEmpty())
                {
                    sb.append("## Methods\n\n"); //$NON-NLS-1$
                    for (Method method : methods)
                    {
                        if (count >= limit)
                            break;
                        String methodName = useRussian ? method.getNameRu() : method.getName();
                        if (memberName == null || matchesMemberName(methodName, memberName) ||
                            matchesMemberName(method.getName(), memberName) ||
                            matchesMemberName(method.getNameRu(), memberName))
                        {
                            appendMethodDocumentation(sb, method, useRussian);
                            count++;
                        }
                    }
                    sb.append("\n"); //$NON-NLS-1$
                }
            }
            
            // Properties
            if (shouldIncludeMemberType(memberType, MEMBER_PROPERTY))
            {
                EList<Property> properties = contextDef.allProperties();
                if (properties != null && !properties.isEmpty())
                {
                    sb.append("## Properties\n\n"); //$NON-NLS-1$
                    for (Property prop : properties)
                    {
                        if (count >= limit)
                            break;
                        String propName = useRussian ? prop.getNameRu() : prop.getName();
                        if (memberName == null || matchesMemberName(propName, memberName) ||
                            matchesMemberName(prop.getName(), memberName) ||
                            matchesMemberName(prop.getNameRu(), memberName))
                        {
                            appendPropertyDocumentation(sb, prop, useRussian);
                            count++;
                        }
                    }
                    sb.append("\n"); //$NON-NLS-1$
                }
            }
        }
        
        // Events
        if (shouldIncludeMemberType(memberType, MEMBER_EVENT))
        {
            EList<Event> events = type.getEvents();
            if (events != null && !events.isEmpty())
            {
                sb.append("## Events\n\n"); //$NON-NLS-1$
                for (Event event : events)
                {
                    if (count >= limit)
                        break;
                    String eventName = useRussian ? event.getNameRu() : event.getName();
                    if (memberName == null || matchesMemberName(eventName, memberName) ||
                        matchesMemberName(event.getName(), memberName) ||
                        matchesMemberName(event.getNameRu(), memberName))
                    {
                        appendEventDocumentation(sb, event, useRussian);
                        count++;
                    }
                }
            }
        }
        
        if (count >= limit)
        {
            sb.append("\n*Results limited to ").append(limit).append(" items.*\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        return sb.toString();
    }
    
    /**
     * Gets platform version for a project.
     */
    private Version getProjectVersion(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            // Try to get from first available project
            IV8ProjectManager v8pm = Activator.getDefault().getV8ProjectManager();
            if (v8pm != null)
            {
                for (IV8Project project : v8pm.getProjects())
                {
                    return project.getVersion();
                }
            }
            return null;
        }
        
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project != null && project.exists())
            {
                IDtProjectManager dtpm = Activator.getDefault().getDtProjectManager();
                if (dtpm != null)
                {
                    IDtProject dtProject = dtpm.getDtProject(project);
                    if (dtProject != null)
                    {
                        IV8ProjectManager v8pm = Activator.getDefault().getV8ProjectManager();
                        if (v8pm != null)
                        {
                            IV8Project v8Project = v8pm.getProject(dtProject);
                            if (v8Project != null)
                            {
                                return v8Project.getVersion();
                            }
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project version", e); //$NON-NLS-1$
        }
        return null;
    }
    
    /**
     * Checks if member type should be included based on filter.
     */
    private boolean shouldIncludeMemberType(String memberTypeFilter, String actualType)
    {
        if (memberTypeFilter == null || memberTypeFilter.isEmpty() || MEMBER_ALL.equals(memberTypeFilter))
        {
            return true;
        }
        return memberTypeFilter.equalsIgnoreCase(actualType);
    }
    
    /**
     * Checks if member name matches the filter (case-insensitive partial match).
     */
    private boolean matchesMemberName(String actualName, String filter)
    {
        if (actualName == null || filter == null)
        {
            return false;
        }
        return actualName.toLowerCase().contains(filter.toLowerCase());
    }
    
    /**
     * Appends constructor documentation.
     * Note: Ctor in EDT API doesn't have getName(), only getParams() directly.
     */
    private void appendCtorDocumentation(StringBuilder sb, Ctor ctor, int ctorNumber, boolean useRussian)
    {
        sb.append("### Constructor ").append(ctorNumber).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Parameters directly from Ctor (not via ParamSet)
        EList<Parameter> params = ctor.getParams();
        if (params != null && !params.isEmpty())
        {
            sb.append("**Parameters:**\n"); //$NON-NLS-1$
            for (Parameter param : params)
            {
                appendParameterDocumentation(sb, param, useRussian);
            }
        }
        else
        {
            sb.append("*No parameters*\n"); //$NON-NLS-1$
        }
        
        sb.append("\n"); //$NON-NLS-1$
    }
    
    /**
     * Appends method documentation.
     */
    private void appendMethodDocumentation(StringBuilder sb, Method method, boolean useRussian)
    {
        String name = useRussian && method.getNameRu() != null ? method.getNameRu() : method.getName();
        String altName = useRussian ? method.getName() : method.getNameRu();
        
        sb.append("### ").append(name != null ? MarkdownUtils.escapeMarkdown(name) : "Unknown"); //$NON-NLS-1$ //$NON-NLS-2$
        if (altName != null && !altName.equals(name))
        {
            sb.append(" / ").append(MarkdownUtils.escapeMarkdown(altName)); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
        
        // Method flags
        if (method.isRetVal())
        {
            sb.append("*Returns a value*\n\n"); //$NON-NLS-1$
        }
        
        // Parameter sets (overloads) - use getParamSet() not getParamSets()
        EList<ParamSet> paramSets = method.getParamSet();
        if (paramSets != null && !paramSets.isEmpty())
        {
            for (int i = 0; i < paramSets.size(); i++)
            {
                ParamSet ps = paramSets.get(i);
                if (paramSets.size() > 1)
                {
                    sb.append("**Overload ").append(i + 1).append(":**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                appendParamSetDocumentation(sb, ps, useRussian);
            }
        }
        
        // Return type - on method level, not ParamSet
        EList<TypeItem> retValTypes = method.getRetValType();
        if (retValTypes != null && !retValTypes.isEmpty())
        {
            sb.append("**Returns:** "); //$NON-NLS-1$
            List<String> typeNames = new ArrayList<>();
            for (TypeItem typeItem : retValTypes)
            {
                String typeName = useRussian ? typeItem.getNameRu() : typeItem.getName();
                if (typeName != null)
                {
                    typeNames.add(typeName);
                }
            }
            sb.append(String.join(" | ", typeNames)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        sb.append("\n"); //$NON-NLS-1$
    }
    
    /**
     * Appends parameter set documentation.
     */
    private void appendParamSetDocumentation(StringBuilder sb, ParamSet paramSet, boolean useRussian)
    {
        EList<Parameter> params = paramSet.getParams();
        if (params != null && !params.isEmpty())
        {
            sb.append("**Parameters:**\n"); //$NON-NLS-1$
            for (Parameter param : params)
            {
                appendParameterDocumentation(sb, param, useRussian);
            }
        }
    }
    
    /**
     * Appends single parameter documentation.
     */
    private void appendParameterDocumentation(StringBuilder sb, Parameter param, boolean useRussian)
    {
        String paramName = useRussian && param.getNameRu() != null ? param.getNameRu() : param.getName();
        sb.append("- `").append(paramName != null ? paramName : "param").append("`"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        // Parameter types - getType() returns EList<TypeItem> directly
        EList<TypeItem> paramTypes = param.getType();
        if (paramTypes != null && !paramTypes.isEmpty())
        {
            sb.append(" ("); //$NON-NLS-1$
            List<String> typeNames = new ArrayList<>();
            for (TypeItem typeItem : paramTypes)
            {
                String typeName = useRussian ? typeItem.getNameRu() : typeItem.getName();
                if (typeName != null)
                {
                    typeNames.add(typeName);
                }
            }
            sb.append(String.join(" | ", typeNames)); //$NON-NLS-1$
            sb.append(")"); //$NON-NLS-1$
        }
        
        // isDefaultValue means parameter has default value (optional)
        if (param.isDefaultValue())
        {
            sb.append(" - *optional*"); //$NON-NLS-1$
        }
        // isOut means parameter is passed by reference (output parameter)
        if (param.isOut())
        {
            sb.append(" - *out*"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }
    
    /**
     * Appends property documentation.
     */
    private void appendPropertyDocumentation(StringBuilder sb, Property prop, boolean useRussian)
    {
        String name = useRussian && prop.getNameRu() != null ? prop.getNameRu() : prop.getName();
        String altName = useRussian ? prop.getName() : prop.getNameRu();
        
        sb.append("### ").append(name != null ? MarkdownUtils.escapeMarkdown(name) : "Unknown"); //$NON-NLS-1$ //$NON-NLS-2$
        if (altName != null && !altName.equals(name))
        {
            sb.append(" / ").append(MarkdownUtils.escapeMarkdown(altName)); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
        
        // Property flags
        List<String> flags = new ArrayList<>();
        if (prop.isReadable())
        {
            flags.add("read"); //$NON-NLS-1$
        }
        if (prop.isWritable())
        {
            flags.add("write"); //$NON-NLS-1$
        }
        if (!flags.isEmpty())
        {
            sb.append("*Access: ").append(String.join("/", flags)).append("*\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        
        // Property type - use getTypes() which returns EList<TypeItem>
        EList<TypeItem> propTypes = prop.getTypes();
        if (propTypes != null && !propTypes.isEmpty())
        {
            sb.append("**Type:** "); //$NON-NLS-1$
            List<String> typeNames = new ArrayList<>();
            for (TypeItem typeItem : propTypes)
            {
                String typeName = useRussian ? typeItem.getNameRu() : typeItem.getName();
                if (typeName != null)
                {
                    typeNames.add(typeName);
                }
            }
            sb.append(String.join(" | ", typeNames)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
    
    /**
     * Appends event documentation.
     */
    private void appendEventDocumentation(StringBuilder sb, Event event, boolean useRussian)
    {
        String name = useRussian && event.getNameRu() != null ? event.getNameRu() : event.getName();
        String altName = useRussian ? event.getName() : event.getNameRu();
        
        sb.append("### ").append(name != null ? MarkdownUtils.escapeMarkdown(name) : "Unknown"); //$NON-NLS-1$ //$NON-NLS-2$
        if (altName != null && !altName.equals(name))
        {
            sb.append(" / ").append(MarkdownUtils.escapeMarkdown(altName)); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
        
        // Event handler parameters - use getParamSet() (via AbstractMethod)
        EList<ParamSet> paramSets = event.getParamSet();
        if (paramSets != null && !paramSets.isEmpty())
        {
            for (ParamSet ps : paramSets)
            {
                appendParamSetDocumentation(sb, ps, useRussian);
            }
        }
    }
    
    /**
     * Gets documentation for built-in functions (Message, Format, FindFiles, etc.).
     * Uses McorePackage.Literals.METHOD provider to get global context methods.
     */
    private String getBuiltinFunctionDocumentation(String functionName, boolean useRussian)
    {
        AtomicReference<String> resultRef = new AtomicReference<>();
        
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getBuiltinFunctionDocumentationInternal(functionName, useRussian);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting builtin function documentation", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Internal implementation that runs on UI thread.
     */
    private String getBuiltinFunctionDocumentationInternal(String functionName, boolean useRussian)
    {
        Version version = getProjectVersion(null);
        if (version == null)
        {
            version = Version.LATEST;
        }
        
        // Get METHOD provider - this gives us global context methods (built-in functions)
        IEObjectProvider.Registry registry = IEObjectProvider.Registry.INSTANCE;
        IEObjectProvider methodProvider = registry.get(McorePackage.Literals.METHOD, version);
        
        if (methodProvider == null)
        {
            return "Error: Could not get method provider. Make sure EDT workspace is open."; //$NON-NLS-1$
        }
        
        // Get ResourceSet for resolving proxies
        ResourceSet resourceSet = null;
        BmAwareResourceSetProvider resourceSetProvider = Activator.getDefault().getResourceSetProvider();
        IV8ProjectManager v8pm = Activator.getDefault().getV8ProjectManager();
        if (v8pm != null && resourceSetProvider != null)
        {
            for (IV8Project project : v8pm.getProjects())
            {
                resourceSet = resourceSetProvider.get(project.getProject());
                if (resourceSet != null)
                {
                    break;
                }
            }
        }
        
        // Search for the function
        Method foundMethod = null;
        List<String> availableMethods = new ArrayList<>();
        
        Iterable<IEObjectDescription> descriptions = methodProvider.getEObjectDescriptions(null);
        if (descriptions != null)
        {
            for (IEObjectDescription desc : descriptions)
            {
                String methodName = desc.getName().getLastSegment();
                if (methodName == null)
                {
                    methodName = desc.getName().toString();
                }
                
                // Collect some methods for suggestions
                if (availableMethods.size() < 30)
                {
                    availableMethods.add(methodName);
                }
                
                // Check if this is the function we're looking for (case-insensitive)
                if (methodName.equalsIgnoreCase(functionName))
                {
                    EObject resolved = desc.getEObjectOrProxy();
                    if (resolved != null)
                    {
                        // Try to resolve proxy
                        if (resolved.eIsProxy() && resourceSet != null)
                        {
                            resolved = EcoreUtil.resolve(resolved, resourceSet);
                        }
                        else if (resolved.eIsProxy())
                        {
                            // Try with temp resource set
                            org.eclipse.emf.ecore.resource.impl.ResourceSetImpl tempResourceSet = 
                                new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
                            resolved = EcoreUtil.resolve(resolved, tempResourceSet);
                        }
                        
                        if (resolved instanceof Method && !resolved.eIsProxy())
                        {
                            foundMethod = (Method) resolved;
                            break;
                        }
                    }
                }
            }
        }
        
        // If not found, show available methods
        if (foundMethod == null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: Built-in function not found: ").append(functionName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("Available global methods (first ").append(availableMethods.size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String availMethod : availableMethods)
            {
                sb.append("- ").append(availMethod).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (availableMethods.isEmpty())
            {
                sb.append("(no methods found - provider may be empty)\n"); //$NON-NLS-1$
            }
            else if (availableMethods.size() >= 30)
            {
                sb.append("... (more available)\n"); //$NON-NLS-1$
            }
            return sb.toString();
        }
        
        // Build documentation for the found method
        return buildBuiltinMethodDocumentation(foundMethod, useRussian);
    }
    
    /**
     * Builds markdown documentation for a built-in method.
     */
    private String buildBuiltinMethodDocumentation(Method method, boolean useRussian)
    {
        StringBuilder sb = new StringBuilder();
        
        // Method header
        String displayName = useRussian ? method.getNameRu() : method.getName();
        String altName = useRussian ? method.getName() : method.getNameRu();
        
        sb.append("# ").append(displayName != null ? displayName : "Unknown"); //$NON-NLS-1$ //$NON-NLS-2$
        if (altName != null && !altName.equals(displayName))
        {
            sb.append(" / ").append(altName); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
        
        sb.append("**Category:** Built-in function (global method)\n\n"); //$NON-NLS-1$
        
        // Method flags
        if (method.isRetVal())
        {
            sb.append("*Returns a value*\n\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("*Procedure (no return value)*\n\n"); //$NON-NLS-1$
        }
        
        // Parameter sets (overloads)
        EList<ParamSet> paramSets = method.getParamSet();
        if (paramSets != null && !paramSets.isEmpty())
        {
            sb.append("## Parameters\n\n"); //$NON-NLS-1$
            for (int i = 0; i < paramSets.size(); i++)
            {
                ParamSet ps = paramSets.get(i);
                if (paramSets.size() > 1)
                {
                    sb.append("### Overload ").append(i + 1).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                appendParamSetDocumentation(sb, ps, useRussian);
                sb.append("\n"); //$NON-NLS-1$
            }
        }
        else
        {
            sb.append("## Parameters\n\n*No parameters*\n\n"); //$NON-NLS-1$
        }
        
        // Return type
        EList<TypeItem> retValTypes = method.getRetValType();
        if (retValTypes != null && !retValTypes.isEmpty())
        {
            sb.append("## Return Type\n\n"); //$NON-NLS-1$
            List<String> typeNames = new ArrayList<>();
            for (TypeItem typeItem : retValTypes)
            {
                String typeName = useRussian ? typeItem.getNameRu() : typeItem.getName();
                if (typeName != null)
                {
                    typeNames.add(typeName);
                }
            }
            sb.append("**Returns:** ").append(String.join(" | ", typeNames)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        
        return sb.toString();
    }
}
