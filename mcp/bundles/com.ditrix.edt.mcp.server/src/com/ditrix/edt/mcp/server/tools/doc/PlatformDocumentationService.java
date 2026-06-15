/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.doc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
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
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Service holding the domain logic for platform documentation lookup and
 * markdown rendering (types, methods, properties, constructors, events, and
 * built-in functions). Extracted verbatim from {@code GetPlatformDocumentationTool}
 * so the tool class keeps only its {@code IMcpTool} contract.
 */
public class PlatformDocumentationService
{
    /**
     * Whether a rendered doc is the soft "not found" banner (it begins
     * {@code "Error: <kind> not found: <name>"} followed by an available-items
     * list). Lives here, not in the tool, so the tool can detect/strip the banner
     * without embedding a bare {@code "Error:"} literal of its own — that literal
     * is the exact anti-pattern {@code BareErrorStringRatchetTest} scans tool
     * classes for, and this service is not a tool.
     *
     * @param rendered the rendered markdown returned by a get*Documentation call
     * @return {@code true} when it is a not-found banner rather than a real doc
     */
    public static boolean isNotFoundBanner(String rendered)
    {
        return rendered != null && rendered.startsWith("Error:"); //$NON-NLS-1$
    }

    /**
     * Strips the soft-banner {@code "Error:"} prefix, returning the actionable
     * body (the {@code "<kind> not found: <name>"} line plus the available-items
     * list) so the caller can wrap it in a real {@code ToolResult.error}.
     *
     * @param rendered a banner for which {@link #isNotFoundBanner} is true
     * @return the body without the leading {@code "Error:"} token
     */
    public static String stripNotFoundBanner(String rendered)
    {
        return rendered.substring("Error:".length()).trim(); //$NON-NLS-1$
    }

    /** Member type constants */
    private static final String MEMBER_ALL = "all"; //$NON-NLS-1$
    private static final String MEMBER_METHOD = "method"; //$NON-NLS-1$
    private static final String MEMBER_PROPERTY = "property"; //$NON-NLS-1$
    private static final String MEMBER_CONSTRUCTOR = "constructor"; //$NON-NLS-1$
    private static final String MEMBER_EVENT = "event"; //$NON-NLS-1$

    /** Fallback heading label when a documented element has no name. */
    private static final String UNKNOWN_LABEL = "Unknown"; //$NON-NLS-1$

    /**
     * Gets documentation for a platform type (ValueTable, Array, etc.).
     */
    public String getTypeDocumentation(String typeName, String memberName, String memberType,
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
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
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
            return ToolResult.error("Could not get type provider. Make sure EDT workspace is open.").toJson(); //$NON-NLS-1$
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

        appendTypeHeader(sb, type, useRussian);
        appendTypeInfo(sb, type);
        appendCollectionElementTypes(sb, type, useRussian);

        int count = 0;
        count = appendConstructorsSection(sb, type, memberType, limit, count, useRussian);

        // Get context def for methods and properties
        ContextDef contextDef = type.getContextDef();
        if (contextDef != null)
        {
            count = appendMethodsSection(sb, contextDef, memberName, memberType, limit, count, useRussian);
            count = appendPropertiesSection(sb, contextDef, memberName, memberType, limit, count, useRussian);
        }

        count = appendEventsSection(sb, type, memberName, memberType, limit, count, useRussian);

        if (count >= limit)
        {
            sb.append("\n*Results limited to ").append(limit).append(" items.*\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    /**
     * Appends the type header line (localized name plus optional alternate name).
     */
    private void appendTypeHeader(StringBuilder sb, Type type, boolean useRussian)
    {
        String displayName = useRussian ? type.getNameRu() : type.getName();
        String altName = useRussian ? type.getName() : type.getNameRu();

        sb.append("# ").append(displayName != null ? displayName : UNKNOWN_LABEL); //$NON-NLS-1$
        if (altName != null && !altName.equals(displayName))
        {
            sb.append(" / ").append(altName); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
    }

    /**
     * Appends the "Type Info" block (iterable / index accessible / created by New flags).
     */
    private void appendTypeInfo(StringBuilder sb, Type type)
    {
        sb.append("**Type Info:**\n"); //$NON-NLS-1$
        sb.append("- Iterable: ").append(type.isIterable() ? "Yes" : "No").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        sb.append("- Index accessible: ").append(type.isIndexAccessible() ? "Yes" : "No").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        sb.append("- Created by New: ").append(type.isCreatedByNewOperator() ? "Yes" : "No").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Appends the "Collection element types" line, when the type exposes any.
     */
    private void appendCollectionElementTypes(StringBuilder sb, Type type, boolean useRussian)
    {
        TypeContainer elementTypes = type.getCollectionElementTypes();
        if (elementTypes == null)
        {
            return;
        }
        EList<TypeItem> elemTypesList = elementTypes.allTypes();
        if (elemTypesList == null || elemTypesList.isEmpty())
        {
            return;
        }
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

    /**
     * Appends the "Constructors" section, honoring the member-type filter and the
     * running item limit. Returns the updated running item count.
     */
    private int appendConstructorsSection(StringBuilder sb, Type type, String memberType,
                                          int limit, int count, boolean useRussian)
    {
        if (!shouldIncludeMemberType(memberType, MEMBER_CONSTRUCTOR))
        {
            return count;
        }
        EList<Ctor> ctors = type.getCtors();
        if (ctors == null || ctors.isEmpty())
        {
            return count;
        }
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
        return count;
    }

    /**
     * Appends the "Methods" section, honoring the member-name/type filters and the
     * running item limit. Returns the updated running item count.
     */
    private int appendMethodsSection(StringBuilder sb, ContextDef contextDef, String memberName,
                                     String memberType, int limit, int count, boolean useRussian)
    {
        if (!shouldIncludeMemberType(memberType, MEMBER_METHOD))
        {
            return count;
        }
        EList<Method> methods = contextDef.allMethods();
        if (methods == null || methods.isEmpty())
        {
            return count;
        }
        sb.append("## Methods\n\n"); //$NON-NLS-1$
        for (Method method : methods)
        {
            if (count >= limit)
                break;
            String methodName = useRussian ? method.getNameRu() : method.getName();
            if (memberNameMatches(methodName, method.getName(), method.getNameRu(), memberName))
            {
                appendMethodDocumentation(sb, method, useRussian);
                count++;
            }
        }
        sb.append("\n"); //$NON-NLS-1$
        return count;
    }

    /**
     * Appends the "Properties" section, honoring the member-name/type filters and the
     * running item limit. Returns the updated running item count.
     */
    private int appendPropertiesSection(StringBuilder sb, ContextDef contextDef, String memberName,
                                        String memberType, int limit, int count, boolean useRussian)
    {
        if (!shouldIncludeMemberType(memberType, MEMBER_PROPERTY))
        {
            return count;
        }
        EList<Property> properties = contextDef.allProperties();
        if (properties == null || properties.isEmpty())
        {
            return count;
        }
        sb.append("## Properties\n\n"); //$NON-NLS-1$
        for (Property prop : properties)
        {
            if (count >= limit)
                break;
            String propName = useRussian ? prop.getNameRu() : prop.getName();
            if (memberNameMatches(propName, prop.getName(), prop.getNameRu(), memberName))
            {
                appendPropertyDocumentation(sb, prop, useRussian);
                count++;
            }
        }
        sb.append("\n"); //$NON-NLS-1$
        return count;
    }

    /**
     * Appends the "Events" section, honoring the member-name/type filters and the
     * running item limit. Returns the updated running item count.
     */
    private int appendEventsSection(StringBuilder sb, Type type, String memberName,
                                    String memberType, int limit, int count, boolean useRussian)
    {
        if (!shouldIncludeMemberType(memberType, MEMBER_EVENT))
        {
            return count;
        }
        EList<Event> events = type.getEvents();
        if (events == null || events.isEmpty())
        {
            return count;
        }
        sb.append("## Events\n\n"); //$NON-NLS-1$
        for (Event event : events)
        {
            if (count >= limit)
                break;
            String eventName = useRussian ? event.getNameRu() : event.getName();
            if (memberNameMatches(eventName, event.getName(), event.getNameRu(), memberName))
            {
                appendEventDocumentation(sb, event, useRussian);
                count++;
            }
        }
        return count;
    }

    /**
     * Tells whether a member should be emitted given the optional member-name filter.
     * A {@code null} filter always matches; otherwise the filter is matched (case-insensitive,
     * partial) against the localized name and the explicit English/Russian names, in that order.
     *
     * @param localizedName the name already resolved for the requested language
     * @param enName the English name of the member
     * @param ruName the Russian name of the member
     * @param filter the optional member-name filter ({@code null} to accept all)
     * @return {@code true} when the member passes the filter
     */
    private boolean memberNameMatches(String localizedName, String enName, String ruName, String filter)
    {
        return filter == null || matchesMemberName(localizedName, filter) ||
            matchesMemberName(enName, filter) ||
            matchesMemberName(ruName, filter);
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
                java.util.Iterator<IV8Project> it = v8pm.getProjects().iterator();
                if (it.hasNext())
                {
                    return it.next().getVersion();
                }
            }
            return null;
        }

        try
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            IProject project = ctx.project();
            if (ctx.exists())
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

        sb.append("### ").append(name != null ? MarkdownUtils.escapeMarkdown(name) : UNKNOWN_LABEL); //$NON-NLS-1$
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

        sb.append("### ").append(name != null ? MarkdownUtils.escapeMarkdown(name) : UNKNOWN_LABEL); //$NON-NLS-1$
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
            List<String> typeNames = collectTypeNames(propTypes, useRussian);
            sb.append(String.join(" | ", typeNames)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Collects the localized display names of the given type items, in order,
     * skipping items whose chosen name is {@code null}.
     *
     * @param types the type items to render (must not be {@code null})
     * @param useRussian {@code true} to prefer the Russian name, {@code false} for English
     * @return the collected non-{@code null} type names, possibly empty
     */
    private static List<String> collectTypeNames(EList<TypeItem> types, boolean useRussian)
    {
        List<String> typeNames = new ArrayList<>();
        for (TypeItem typeItem : types)
        {
            String typeName = useRussian ? typeItem.getNameRu() : typeItem.getName();
            if (typeName != null)
            {
                typeNames.add(typeName);
            }
        }
        return typeNames;
    }

    /**
     * Appends event documentation.
     */
    private void appendEventDocumentation(StringBuilder sb, Event event, boolean useRussian)
    {
        String name = useRussian && event.getNameRu() != null ? event.getNameRu() : event.getName();
        String altName = useRussian ? event.getName() : event.getNameRu();

        sb.append("### ").append(name != null ? MarkdownUtils.escapeMarkdown(name) : UNKNOWN_LABEL); //$NON-NLS-1$
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
    public String getBuiltinFunctionDocumentation(String functionName, boolean useRussian)
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
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
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
            return ToolResult.error("Could not get method provider. Make sure EDT workspace is open.").toJson(); //$NON-NLS-1$
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

        sb.append("# ").append(displayName != null ? displayName : UNKNOWN_LABEL); //$NON-NLS-1$
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
