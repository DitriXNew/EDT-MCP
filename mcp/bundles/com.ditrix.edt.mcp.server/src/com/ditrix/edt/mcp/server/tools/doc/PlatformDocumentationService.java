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
    public String getTypeDocumentation(String typeName, String memberName, String memberType, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                                        String projectName, int limit, boolean useRussian,
                                        boolean detailed)
    {
        AtomicReference<String> resultRef = new AtomicReference<>();

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getTypeDocumentationInternal(typeName, memberName, memberType,
                                                              projectName, limit, useRussian, detailed);
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
    private String getTypeDocumentationInternal(String typeName, String memberName, String memberType, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                                                 String projectName, int limit, boolean useRussian,
                                                 boolean detailed)
    {
        // Get version for type provider
        Version version = getProjectVersion(projectName);
        if (version == null)
        {
            version = Version.LATEST;
        }

        // Note: For platform types like Array, ValueTable, the types are
        // directly available from IEObjectDescription without needing project ResourceSet.

        IEObjectProvider typeProvider = selectTypeProvider(version);
        if (typeProvider == null)
        {
            return ToolResult.error("Could not get type provider. Make sure EDT workspace is open.").toJson(); //$NON-NLS-1$
        }

        // Find type by iterating through all type descriptions
        List<String> availableTypes = new ArrayList<>();
        Type foundType = findType(typeProvider, typeName, availableTypes);

        // If not found, show available types
        if (foundType == null)
        {
            return buildNotFoundBanner("Type not found: ", typeName, "types", availableTypes); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Build documentation from resolved Type
        return buildTypeDocumentation(foundType, version, memberName, memberType, limit, useRussian,
            detailed);
    }

    /**
     * Selects the type provider for the given version: prefers TYPE (platform types like Array,
     * ValueTable) and falls back to TYPE_ITEM when the TYPE provider is empty (some EDT versions).
     *
     * @return the selected provider, or {@code null} when neither is available
     */
    private IEObjectProvider selectTypeProvider(Version version)
    {
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
        return typeProvider;
    }

    /**
     * Iterates the provider's descriptions looking for {@code typeName} (case-insensitive, matching
     * either the full qualified name or its last segment), collecting up to the first 30 names into
     * {@code availableTypes} for the not-found banner.
     *
     * @param availableTypes out-param populated with up to 30 candidate names, in iteration order
     * @return the resolved (non-proxy) {@link Type}, or {@code null} when not found
     */
    private Type findType(IEObjectProvider typeProvider, String typeName, List<String> availableTypes)
    {
        Iterable<IEObjectDescription> descriptions = typeProvider.getEObjectDescriptions(null);
        if (descriptions == null)
        {
            return null;
        }
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

            // Check if this is the type we're looking for (case-insensitive, check both full and last segment) // NOSONAR explanatory comment, not commented-out code
            if (fullName.equalsIgnoreCase(typeName) ||
                (lastSegment != null && lastSegment.equalsIgnoreCase(typeName)))
            {
                Type resolvedType = resolveDescriptionAsType(desc);
                if (resolvedType != null)
                {
                    return resolvedType;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a matched description to a non-proxy {@link Type}, attempting EcoreUtil proxy
     * resolution via a temporary resource set when needed (errors are logged, not thrown).
     *
     * @return the resolved {@link Type}, or {@code null} when the object is not a Type or stays a proxy
     */
    private Type resolveDescriptionAsType(IEObjectDescription desc)
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
                return (Type) resolved;
            }
        }
        return null;
    }

    /**
     * Builds the soft "not found" banner: an {@code "Error: <subject><name>"} line followed by the
     * collected available items (or an empty-provider note / "more available" hint). The exact text
     * matches the previous inline builders so {@link #isNotFoundBanner} still recognises it.
     *
     * @param subject the not-found phrase incl. trailing separator (e.g. {@code "Type not found: "})
     * @param name the looked-up name appended after {@code subject}
     * @param itemsLabel the plural noun used in the "Available &lt;itemsLabel&gt; (first N)" heading
     * @param available the collected candidate names
     * @return the rendered banner string
     */
    private String buildNotFoundBanner(String subject, String name, String itemsLabel, List<String> available)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Error: ").append(subject).append(name).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("Available ").append(itemsLabel).append(" (first ").append(available.size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String item : available)
        {
            sb.append("- ").append(item).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (available.isEmpty())
        {
            sb.append("(no ").append(itemsLabel).append(" found - provider may be empty)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (available.size() >= 30)
        {
            sb.append("... (more available)\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Builds markdown documentation for a Type.
     */
    private String buildTypeDocumentation(Type type, Version version, String memberName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                                           String memberType, int limit, boolean useRussian,
                                           boolean detailed)
    {
        StringBuilder sb = new StringBuilder();
        // The syntax helper carries what the model does not: the prose, and the return value of
        // methods the model records none for. Unavailable => every lookup is null and the output is
        // exactly the model-only one. Read ONLY for 'detailed': the concise rendering drops every
        // description anyway, and each lookup walks the doc tree and parses a page on the UI thread -
        // at limit 200 that is hundreds of page loads whose result is then thrown away. Issue #299.
        PlatformHelpService help = detailed ? new PlatformHelpService(version, useRussian ? "ru" : "en") //$NON-NLS-1$ //$NON-NLS-2$
            : PlatformHelpService.disabled();
        String typeName = type.getName() != null ? type.getName() : type.getNameRu();

        appendTypeHeader(sb, type, useRussian);
        appendDescription(sb, help.typeDescription(typeName));
        appendTypeInfo(sb, type);
        appendCollectionElementTypes(sb, type, useRussian);

        int count = 0;
        // A system enumeration's VALUES are what a caller comes for; they are also the only thing it
        // has - it is not constructible, so the "Constructors" section below is skipped for it
        // (rendering an empty constructor under "Created by New: No" was self-contradicting). #299
        if (type.isSysEnum())
        {
            count = appendSysEnumValuesSection(sb, type, version, memberName, memberType, limit, count,
                useRussian);
        }
        else
        {
            count = appendConstructorsSection(sb, type, memberType, limit, count, useRussian);
        }

        // Get context def for methods and properties
        ContextDef contextDef = type.getContextDef();
        if (contextDef != null)
        {
            count = appendMethodsSection(sb, contextDef, memberName, memberType, limit, count,
                useRussian, help, typeName);
            count = appendPropertiesSection(sb, contextDef, memberName, memberType, limit, count,
                useRussian, help, typeName);
        }

        count = appendEventsSection(sb, type, memberName, memberType, limit, count, useRussian);

        if (count >= limit)
        {
            sb.append("\n*Results limited to ").append(limit).append(" items.*\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }


    /**
     * Appends a documentation paragraph read from the syntax helper, when there is one. A blank or
     * absent text renders nothing at all, so an EDT without the platform documentation produces the
     * same output as before. Issue #299.
     */
    private static void appendDescription(StringBuilder sb, String description)
    {
        if (description == null || description.isBlank())
        {
            return;
        }
        sb.append(MarkdownUtils.escapeMarkdown(description.trim())).append("\n\n"); //$NON-NLS-1$
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
     * Appends the "Values" section of a SYSTEM ENUMERATION - the thing a caller actually needs from
     * such a type and the one thing the type itself does not carry. Issue #299.
     *
     * <p>A system enumeration is modelled as TWO types: the one named after the enumeration (what a
     * caller asks for, and what a variable is typed as) and a companion "manager" type whose
     * PROPERTIES are the values. Only the second one holds them, and it is reachable through the
     * GLOBAL CONTEXT property that carries the enumeration's name - the very thing BSL resolves when
     * it sees {@code DateFractions.Date}. When that lookup fails the section is simply omitted: a
     * missing section is better than a wrong one.
     *
     * @return the updated running item count
     */
    private int appendSysEnumValuesSection(StringBuilder sb, Type type, Version version, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String memberName, String memberType, int limit, int count, boolean useRussian)
    {
        if (!shouldIncludeMemberType(memberType, MEMBER_PROPERTY) && !MEMBER_ALL.equals(memberType))
        {
            return count;
        }
        List<Property> values;
        try
        {
            values = findSysEnumValues(type, version);
        }
        catch (Exception e) // NOSONAR the values are an enrichment: an unresolvable proxy must not fail the whole lookup
        {
            // Without this the javadoc above ("the section is simply omitted") was a promise the
            // code did not keep: the exception reached the outer handler and turned the WHOLE type
            // lookup into an error.
            Activator.logInfo("System enumeration values could not be read: " + e); //$NON-NLS-1$
            return count;
        }
        if (values.isEmpty())
        {
            return count;
        }
        sb.append("## Values\n\n"); //$NON-NLS-1$
        String enumName = useRussian && type.getNameRu() != null ? type.getNameRu() : type.getName();
        for (Property value : values)
        {
            if (count >= limit)
            {
                break;
            }
            String valueName = useRussian && value.getNameRu() != null ? value.getNameRu() : value.getName();
            if (!memberNameMatches(valueName, value.getName(), value.getNameRu(), memberName))
            {
                continue;
            }
            String altName = useRussian ? value.getName() : value.getNameRu();
            sb.append("- `").append(enumName != null ? enumName : UNKNOWN_LABEL).append('.') //$NON-NLS-1$
                .append(valueName != null ? valueName : UNKNOWN_LABEL).append('`'); //$NON-NLS-1$
            if (altName != null && !altName.equals(valueName))
            {
                // The alternate identifier names the enumeration in the OTHER language too:
                // a Russian rendering reads 'ЧастиДаты.Дата / DateFractions.Date', not the
                // Russian enumeration name glued to the English value - which would be a
                // hybrid that exists in neither language.
                String altEnumName = useRussian ? type.getName() : type.getNameRu();
                sb.append(" / `").append(altEnumName != null ? altEnumName : UNKNOWN_LABEL) //$NON-NLS-1$
                    .append('.').append(altName).append('`'); //$NON-NLS-1$
            }
            sb.append('\n');
            count++;
        }
        sb.append('\n');
        return count;
    }


    /**
     * The values of a system enumeration: the properties of the companion type reached through the
     * global-context property named after the enumeration. Returns an empty list when the model does
     * not expose it (never {@code null}) - the caller then omits the section. Issue #299.
     */
    private List<Property> findSysEnumValues(Type type, Version version)
    {
        String enName = type.getName();
        String ruName = type.getNameRu();
        if (enName == null && ruName == null)
        {
            return List.of();
        }
        IEObjectProvider propertyProvider =
            IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.PROPERTY, version);
        if (propertyProvider == null)
        {
            return List.of();
        }
        Iterable<IEObjectDescription> descriptions = propertyProvider.getEObjectDescriptions(null);
        if (descriptions == null)
        {
            return List.of();
        }
        // The provider hands out PROXIES. Resolve them in the resource set the ALREADY-RESOLVED type
        // lives in: that one holds the platform resources of THIS type's version, whereas a fresh,
        // empty one resolves nothing. This is the whole reason the values looked absent.
        //
        // Deliberately no fallback to "any project's resource set": in a workspace holding projects
        // on DIFFERENT platform versions that would resolve this version's proxies against another
        // version's resources - wrong values are worse than none, so a type with no resource set
        // simply reports no values.
        ResourceSet resourceSet = type.eResource() != null ? type.eResource().getResourceSet() : null;
        if (resourceSet == null)
        {
            return List.of();
        }
        for (IEObjectDescription desc : descriptions)
        {
            String lastSegment = desc.getName().getLastSegment();
            if (lastSegment == null
                || !lastSegment.equalsIgnoreCase(enName) && !lastSegment.equalsIgnoreCase(ruName))
            {
                continue;
            }
            List<Property> values = valuesOfEnumAccessProperty(desc, resourceSet);
            if (!values.isEmpty())
            {
                return values;
            }
        }
        return List.of();
    }

    /**
     * Resolves a global-context property description to the properties of the type it is typed at -
     * for an enumeration-access property those ARE the enumeration's values. Empty when the
     * description does not resolve or carries no such type.
     */
    private List<Property> valuesOfEnumAccessProperty(IEObjectDescription desc, ResourceSet resourceSet)
    {
        EObject resolved = desc.getEObjectOrProxy();
        if (resolved != null && resolved.eIsProxy() && resourceSet != null)
        {
            resolved = EcoreUtil.resolve(resolved, resourceSet);
        }
        if (!(resolved instanceof Property) || resolved.eIsProxy())
        {
            return List.of();
        }
        for (TypeItem typeItem : ((Property)resolved).getTypes())
        {
            if (!(typeItem instanceof Type))
            {
                continue;
            }
            ContextDef holder = ((Type)typeItem).getContextDef();
            if (holder == null)
            {
                continue;
            }
            EList<Property> properties = holder.allProperties();
            if (properties != null && !properties.isEmpty())
            {
                return new ArrayList<>(properties);
            }
        }
        return List.of();
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
    private int appendMethodsSection(StringBuilder sb, ContextDef contextDef, String memberName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                                     String memberType, int limit, int count, boolean useRussian,
                                     PlatformHelpService help, String typeName)
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
                appendMethodDocumentation(sb, method, useRussian, help, typeName);
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
    private int appendPropertiesSection(StringBuilder sb, ContextDef contextDef, String memberName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                                        String memberType, int limit, int count, boolean useRussian,
                                        PlatformHelpService help, String typeName)
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
                appendPropertyDocumentation(sb, prop, useRussian, help, typeName);
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
            return firstProjectVersion();
        }

        try
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            if (ctx.exists())
            {
                return versionForContext(ctx);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project version", e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns the platform version of the first available project, or {@code null} when
     * there is none. Used as the fallback when no project name is supplied.
     */
    private Version firstProjectVersion()
    {
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

    /**
     * Resolves the platform version for an existing project context by walking
     * {@code IProject -> IDtProject -> IV8Project}, returning {@code null} when any link in
     * the chain is unavailable.
     */
    private Version versionForContext(ProjectContext ctx)
    {
        IProject project = ctx.project();
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
    private void appendMethodDocumentation(StringBuilder sb, Method method, boolean useRussian,
        PlatformHelpService help, String typeName)
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
        appendDescription(sb, help.memberDescription(typeName, method.getName()));

        // Parameter sets (overloads) - use getParamSet() not getParamSets()
        EList<ParamSet> paramSets = method.getParamSet();
        appendMethodParamSets(sb, paramSets, useRussian);

        // What the method returns, from BOTH sources. The model carries the TYPE but records none at
        // all for some methods; the syntax helper carries the platform's own wording, which also
        // says what the value MEANS. Whichever exists is rendered - and when only the documentation
        // has it, that is stated, so a caller can tell a modelled type from a documented sentence.
        // A method the documentation describes as a procedure legitimately yields neither. #299
        EList<TypeItem> retValTypes = method.getRetValType();
        String documentedReturn = help.methodReturnValue(typeName, method.getName());
        if (retValTypes != null && !retValTypes.isEmpty())
        {
            sb.append("\n**Returns:** ").append(joinTypeNames(retValTypes, useRussian)); //$NON-NLS-1$
            if (documentedReturn != null)
            {
                sb.append(" - ").append(MarkdownUtils.escapeMarkdown(documentedReturn)); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        else if (documentedReturn != null)
        {
            sb.append("\n**Returns (from the platform documentation):** ") //$NON-NLS-1$
                .append(MarkdownUtils.escapeMarkdown(documentedReturn)).append("\n"); //$NON-NLS-1$
        }

        sb.append("\n"); //$NON-NLS-1$
    }

    /**
     * Appends the parameter sets (overloads) of a method, prefixing each with an
     * "Overload N" heading when more than one set is present.
     */
    private void appendMethodParamSets(StringBuilder sb, EList<ParamSet> paramSets, boolean useRussian)
    {
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
    }

    /**
     * Joins the localized names of the given type items with " | ", skipping items whose
     * name is {@code null}.
     */
    private String joinTypeNames(EList<TypeItem> typeItems, boolean useRussian)
    {
        List<String> typeNames = new ArrayList<>();
        for (TypeItem typeItem : typeItems)
        {
            String typeName = useRussian ? typeItem.getNameRu() : typeItem.getName();
            if (typeName != null)
            {
                typeNames.add(typeName);
            }
        }
        return String.join(" | ", typeNames); //$NON-NLS-1$
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
    private void appendPropertyDocumentation(StringBuilder sb, Property prop, boolean useRussian,
        PlatformHelpService help, String typeName)
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
        appendDescription(sb, help.memberDescription(typeName, prop.getName()));

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

        ResourceSet resourceSet = findAnyProjectResourceSet();

        // Search for the function
        List<String> availableMethods = new ArrayList<>();
        Method foundMethod = findBuiltinMethod(methodProvider, functionName, resourceSet, availableMethods);

        // If not found, show available methods
        if (foundMethod == null)
        {
            return buildBuiltinNotFoundBanner(functionName, availableMethods);
        }

        // Build documentation for the found method
        return buildBuiltinMethodDocumentation(foundMethod, useRussian);
    }

    /**
     * Finds the first non-{@code null} project {@link ResourceSet} (used to resolve proxies),
     * iterating the open V8 projects. Returns {@code null} when no provider / project yields one.
     */
    private ResourceSet findAnyProjectResourceSet()
    {
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
        return resourceSet;
    }

    /**
     * Iterates the provider's descriptions looking for a global method named {@code functionName}
     * (case-insensitive, by last segment), collecting up to the first 30 names into
     * {@code availableMethods} for the not-found banner.
     *
     * @param resourceSet resource set used to resolve a matched proxy (may be {@code null})
     * @param availableMethods out-param populated with up to 30 candidate names, in iteration order
     * @return the resolved (non-proxy) {@link Method}, or {@code null} when not found
     */
    private Method findBuiltinMethod(IEObjectProvider methodProvider, String functionName,
                                     ResourceSet resourceSet, List<String> availableMethods)
    {
        Iterable<IEObjectDescription> descriptions = methodProvider.getEObjectDescriptions(null);
        if (descriptions == null)
        {
            return null;
        }
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

            // Check if this is the function we're looking for (case-insensitive) // NOSONAR explanatory comment, not commented-out code
            if (methodName.equalsIgnoreCase(functionName))
            {
                Method resolvedMethod = resolveDescriptionAsMethod(desc, resourceSet);
                if (resolvedMethod != null)
                {
                    return resolvedMethod;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a matched description to a non-proxy {@link Method}, preferring the given
     * {@code resourceSet} and otherwise a temporary one for proxy resolution.
     *
     * @return the resolved {@link Method}, or {@code null} when the object is absent, not a Method,
     *         or stays a proxy
     */
    private Method resolveDescriptionAsMethod(IEObjectDescription desc, ResourceSet resourceSet)
    {
        EObject resolved = desc.getEObjectOrProxy();
        if (resolved == null)
        {
            return null;
        }
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
            return (Method) resolved;
        }
        return null;
    }

    /**
     * Builds the built-in-function not-found banner. Mirrors the previous inline text exactly: the
     * heading reads "Available global methods" while the empty-provider note reads "(no methods
     * found ...)", so it cannot reuse {@link #buildNotFoundBanner} (single label).
     *
     * @param functionName the looked-up function name
     * @param available the collected candidate global-method names
     * @return the rendered banner string
     */
    private String buildBuiltinNotFoundBanner(String functionName, List<String> available)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Error: Built-in function not found: ").append(functionName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("Available global methods (first ").append(available.size()).append("):\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String availMethod : available)
        {
            sb.append("- ").append(availMethod).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (available.isEmpty())
        {
            sb.append("(no methods found - provider may be empty)\n"); //$NON-NLS-1$
        }
        else if (available.size() >= 30)
        {
            sb.append("... (more available)\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Builds markdown documentation for a built-in method.
     */
    private String buildBuiltinMethodDocumentation(Method method, boolean useRussian)
    {
        StringBuilder sb = new StringBuilder();

        appendBuiltinMethodHeader(sb, method, useRussian);

        // Parameter sets (overloads)
        EList<ParamSet> paramSets = method.getParamSet();
        appendBuiltinParamSets(sb, paramSets, useRussian);

        // Return type
        EList<TypeItem> retValTypes = method.getRetValType();
        if (retValTypes != null && !retValTypes.isEmpty())
        {
            sb.append("## Return Type\n\n"); //$NON-NLS-1$
            sb.append("**Returns:** ").append(joinTypeNames(retValTypes, useRussian)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    /**
     * Appends the title, category and return/procedure flag header of a built-in method.
     */
    private void appendBuiltinMethodHeader(StringBuilder sb, Method method, boolean useRussian)
    {
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
    }

    /**
     * Appends the "Parameters" section of a built-in method, rendering one block per
     * overload (with an "Overload N" heading when there are several) or a "No parameters"
     * note when the method has none.
     */
    private void appendBuiltinParamSets(StringBuilder sb, EList<ParamSet> paramSets, boolean useRussian)
    {
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
    }
}
