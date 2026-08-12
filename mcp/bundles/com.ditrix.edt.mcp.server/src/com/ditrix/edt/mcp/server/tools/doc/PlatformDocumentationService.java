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

    /**
     * Description user-data key naming the platform TYPE that documents a metadata TYPE SET
     * ({@code CatalogObject} -> {@code CatalogObjectCatalogName}). Mirrors
     * {@code com._1c.g5.v8.dt.platform.type.MdTypeSetLoader.CONTAINS_TYPE}; the constant itself is
     * not bound because its package is not among this bundle's imports, while the KEY is the stable
     * contract the provider publishes on every metadata type-set description. Issue #355.
     */
    private static final String USER_DATA_CONTAINS_TYPE = "containsType"; //$NON-NLS-1$

    /** Description user-data key carrying the Russian name of a platform type / type set. */
    private static final String USER_DATA_RU_NAME = "ru_name"; //$NON-NLS-1$

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

        // Find type by iterating through all type descriptions. The index re-checks each name it is
        // about to PRINT by actually resolving it, so the banner cannot advertise a name that then
        // fails - the loop issue #355 is about.
        IEObjectProvider provider = typeProvider;
        PlatformNameIndex index =
            new PlatformNameIndex(typeName, candidate -> resolvesAsType(provider, candidate));
        DocumentedType foundType = findType(typeProvider, typeName, index);

        // If not found, show available types
        if (foundType == null)
        {
            return buildTypeNotFoundBanner(typeName, index);
        }

        // Build documentation from resolved Type
        return buildTypeDocumentation(foundType, version, memberName, memberType, limit, useRussian,
            detailed);
    }

    /**
     * The soft banner for a type lookup that resolved nothing. Three different failures, three
     * different answers - telling a caller a name does not exist when the platform KNOWS it is a
     * different, wrong answer, and it is what sent agents round a loop of equivalent retries in
     * issue #355. The mirror-image mistake matters just as much: declaring "the platform documents
     * nothing for it" over a set whose target we merely failed to reach states a fact about the
     * platform that is not in evidence, and buries a condition a retry could clear.
     *
     * @param typeName the name that was looked up
     * @param index the names the scan collected
     * @return the rendered banner
     */
    private String buildTypeNotFoundBanner(String typeName, PlatformNameIndex index)
    {
        switch (index.missReason())
        {
        case DOCUMENTS_NOTHING:
            return index.buildNotFoundBanner("No documentation for type set: ", typeName, "types", //$NON-NLS-1$ //$NON-NLS-2$
                "A TYPE SET unions other types and declares no members of its own, so the platform " //$NON-NLS-1$
                    + "documents nothing for it. Ask for one of the type sets it unions " //$NON-NLS-1$
                    + "(CatalogRef, DocumentRef, EnumRef, ...) or for the concrete platform type."); //$NON-NLS-1$
        case TARGET_UNRESOLVED:
            return index.buildNotFoundBanner("Documentation unavailable for type set: ", typeName, //$NON-NLS-1$
                "types", //$NON-NLS-1$
                "The platform publishes this type set and names the generic type that documents it, " //$NON-NLS-1$
                    + "but that type could not be resolved in this platform version's model - which " //$NON-NLS-1$
                    + "usually means the model is not fully loaded yet. Retry; if it persists, ask " //$NON-NLS-1$
                    + "for the concrete platform type instead."); //$NON-NLS-1$
        default:
            return index.buildNotFoundBanner("Type not found: ", typeName, "types", //$NON-NLS-1$ //$NON-NLS-2$
                "Names are matched exactly (case-insensitive) against the platform type names, in " //$NON-NLS-1$
                    + "English or Russian. An object of your own configuration is not a platform " //$NON-NLS-1$
                    + "type - use get_metadata_details for that; for a global built-in function " //$NON-NLS-1$
                    + "pass category='builtin'."); //$NON-NLS-1$
        }
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
     * A resolved documentation target: the platform {@link Type} whose members are rendered and,
     * when the caller named a metadata TYPE SET, how it was reached.
     */
    private static final class DocumentedType
    {
        /** The type the documentation is built from. */
        final Type type;

        /** The type set the caller named ({@code "CatalogObject / СправочникОбъект"}), or {@code null}. */
        final String typeSetLabel;

        /**
         * The name the SYNTAX HELPER is asked with. For a type set that is the set's name, because
         * the help documents it under the set ({@code CatalogObject.<Catalog name>}) rather than
         * under the generic type the model resolves to. {@code null} => use the type's own name.
         */
        final String helpName;

        DocumentedType(Type type, String typeSetLabel, String helpName)
        {
            this.type = type;
            this.typeSetLabel = typeSetLabel;
            this.helpName = helpName;
        }
    }

    /**
     * Iterates the provider's descriptions looking for {@code typeName} (case-insensitive, matching
     * either the full qualified name or its last segment), feeding every name it does not match into
     * {@code index} for the not-found banner.
     *
     * @param index collects the RESOLVABLE names seen, for the not-found banner
     * @return the resolved documentation target, or {@code null} when not found
     */
    private DocumentedType findType(IEObjectProvider typeProvider, String typeName, PlatformNameIndex index)
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
            String name = lastSegment != null ? lastSegment : fullName;

            // Check if this is the type we're looking for (case-insensitive, check both full and last segment) // NOSONAR explanatory comment, not commented-out code
            boolean matches = fullName.equalsIgnoreCase(typeName) || name.equalsIgnoreCase(typeName);

            if (!isDocumentable(typeProvider, desc))
            {
                // Deliberately NOT offered as an available name: it answers nothing. When it IS what
                // was asked for, say so precisely instead of "not found" (issue #355) - and split
                // the two ways a set can get here, because they are not the same news. A set that
                // names NO target documents nothing and never will; a set that names one the
                // provider does not currently hold is a model that may simply not be loaded yet,
                // and telling that caller "the platform documents nothing for it" would be a claim
                // about the platform made from our own failure to find something. The bilingual
                // trap sits right here: containsType lives on the ENGLISH description, so a Russian
                // one is silent about it either way - namesNoTarget is what separates "read it, it
                // names nothing" from "never got to read it".
                if (matches)
                {
                    if (namesNoTarget(typeProvider, desc))
                    {
                        index.markDocumentsNothing();
                    }
                    else
                    {
                        index.markTargetUnresolved();
                    }
                }
                continue;
            }
            if (matches)
            {
                DocumentedType resolved = resolveDocumentedType(typeProvider, desc, name);
                if (resolved != null)
                {
                    return resolved;
                }
                // Matched and yet resolved nothing. Whatever the reason, this name does not answer,
                // so it must not come back as an "available" one - that is the loop, exactly.
                //
                // A TYPE SET that got this far passed the cheap "its target is registered" test and
                // still failed to resolve - the registered-vs-resolved distinction again, one level
                // up. Answering a plain "Type not found" for a name the platform demonstrably knows
                // is the wrong diagnosis; so is "the platform documents nothing for it", because it
                // named a target and we simply could not reach it. It gets its own answer.
                if (McorePackage.Literals.TYPE_SET.equals(desc.getEClass()))
                {
                    index.markTargetUnresolved();
                }
                continue;
            }
            index.accept(name);
        }
        return null;
    }

    /**
     * Whether {@code name} really answers a type lookup - the same resolution the tool would run for
     * it, so a name that passes this is a name the caller can copy out of the banner and query.
     * Applied only to the handful of names about to be printed.
     *
     * @param provider the type provider
     * @param name the candidate name, exactly as it would be passed back
     * @return {@code true} when the lookup resolves
     */
    private boolean resolvesAsType(IEObjectProvider provider, String name)
    {
        IEObjectDescription desc = provider.getEObjectDescription(name);
        return desc != null && resolveDocumentedType(provider, desc, name) != null;
    }

    /**
     * Whether a description names something this tool can render documentation for. Only a TYPE SET
     * with no generic type behind it is refused - a set that unions other types and declares no
     * members of its own ({@code AnyRef} / {@code ЛюбаяСсылка}). Everything else is accepted,
     * including any description whose {@code EClass} an EDT version reports differently: a wrong
     * "documentable" costs one honest "not found", while a wrong refusal would silently hide the
     * whole vocabulary.
     *
     * @param provider the provider the description came from
     * @param desc the provider description
     * @return {@code true} when the name can answer a documentation lookup
     */
    private static boolean isDocumentable(IEObjectProvider provider, IEObjectDescription desc)
    {
        if (!McorePackage.Literals.TYPE_SET.equals(desc.getEClass()))
        {
            return true;
        }
        return !documentedTypeDescriptions(provider, desc).isEmpty();
    }

    /**
     * The descriptions of the generic types that document a TYPE SET - the ones
     * {@link #containedTypeName} names, looked UP in the provider so the set is advertised only when
     * its target is really there.
     *
     * <p>A LIST, in declaration order, because {@code containsType} is a comma-separated field:
     * returning only the first registered candidate would report a set as undocumented whenever
     * that one fails to resolve while a later one would have. Callers that only need a yes/no take
     * the emptiness; the caller that renders takes the first that actually RESOLVES.
     *
     * <p>Checking the name is non-blank is not enough: it is the platform's own table, and a version
     * that renamed or dropped a generic type would have the set listed as available and then fail on
     * query - the very loop this change removes. The check stays a map lookup plus an EClass test, so
     * it costs nothing; deciding by an actual {@code EcoreUtil.resolve} is what would be unaffordable
     * (thousands of platform resources loaded on the UI thread for one not-found answer), and is not
     * needed - the provider registers a type only after its resource parsed at init.
     *
     * @param provider the provider the description came from
     * @param desc the type-set description
     * @return the target descriptions in declaration order, empty when the set documents nothing
     */
    private static List<IEObjectDescription> documentedTypeDescriptions(IEObjectProvider provider,
        IEObjectDescription desc)
    {
        String contains = containedTypeName(provider, desc);
        if (contains == null)
        {
            return List.of();
        }
        List<IEObjectDescription> targets = new ArrayList<>();
        for (String candidate : contains.split(",")) //$NON-NLS-1$
        {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            IEObjectDescription target = provider.getEObjectDescription(trimmed);
            if (target != null && !McorePackage.Literals.TYPE_SET.equals(target.getEClass()))
            {
                targets.add(target);
            }
        }
        return targets;
    }

    /**
     * The name of the generic platform type that documents a TYPE SET, or {@code null} when the
     * platform documents none (a set that only unions other sets, e.g. {@code AnyRef}).
     *
     * <p>The provider registers a set under BOTH its names against the SAME resource URI, but hangs
     * the metadata user data on the ENGLISH description only - the Russian one gets the plain
     * script-variant map. Read straight, that made {@code CatalogObject} resolve while
     * {@code СправочникОбъект} did not, which is exactly half of issue #355. The URI fragment carries
     * the English name ({@code .../mdTypeSets#/CatalogObject}), so the Russian description finds its
     * sibling through the provider instead of through a hand-maintained name table.
     *
     * @param provider the provider the description came from
     * @param desc the type-set description
     * @return the documented type's name, or {@code null}
     */
    private static String containedTypeName(IEObjectProvider provider, IEObjectDescription desc)
    {
        String contains = desc.getUserData(USER_DATA_CONTAINS_TYPE);
        if (contains != null && !contains.isBlank())
        {
            return contains;
        }
        IEObjectDescription sibling = englishSibling(provider, desc);
        contains = sibling != null ? sibling.getUserData(USER_DATA_CONTAINS_TYPE) : null;
        return contains != null && !contains.isBlank() ? contains : null;
    }

    /**
     * The description a type set is registered under with its ENGLISH name, reached from the URI
     * fragment both spellings share. {@code null} when this description already IS the English one,
     * or when the provider publishes no such fragment.
     *
     * @param provider the provider the description came from
     * @param desc the type-set description
     * @return the English sibling description, or {@code null}
     */
    private static IEObjectDescription englishSibling(IEObjectProvider provider, IEObjectDescription desc)
    {
        String name = englishName(desc);
        if (name == null || name.equals(desc.getName().getLastSegment()))
        {
            return null;
        }
        return provider.getEObjectDescription(name);
    }

    /**
     * The ENGLISH name a type-set description's URI fragment carries
     * ({@code .../mdTypeSets#/CatalogObject}), or {@code null} when there is no usable fragment.
     *
     * @param desc the type-set description
     * @return the English name, or {@code null}
     */
    private static String englishName(IEObjectDescription desc)
    {
        org.eclipse.emf.common.util.URI uri = desc.getEObjectURI();
        String fragment = uri != null ? uri.fragment() : null;
        if (fragment == null || fragment.isEmpty())
        {
            return null;
        }
        String name = fragment.startsWith("/") ? fragment.substring(1) : fragment; //$NON-NLS-1$
        return name.isEmpty() ? null : name;
    }

    /**
     * Whether the absence of a {@code containsType} is a statement about the PLATFORM rather than
     * about our own reach - i.e. whether the description that carries the field was actually read.
     *
     * <p>The field lives on the ENGLISH description only. So a Russian one that named no target may
     * mean the set documents nothing ({@code ЛюбаяСсылка}), or merely that its English sibling was
     * out of reach - a missing URI fragment, a provider that does not currently hold it. Those two
     * deserve opposite answers ("never retry" vs "retry"), and telling them apart is only possible
     * here, where it is known WHY the lookup came back empty.
     *
     * @param provider the provider the description came from
     * @param desc the type-set description
     * @return {@code true} when the authoritative description was read and simply named no target
     */
    private static boolean namesNoTarget(IEObjectProvider provider, IEObjectDescription desc)
    {
        if (containedTypeName(provider, desc) != null)
        {
            return false;
        }
        if (desc.getUserData(USER_DATA_CONTAINS_TYPE) != null)
        {
            // It carries the field itself (blank), so it IS the authoritative word on the matter.
            return true;
        }
        String name = englishName(desc);
        if (name == null)
        {
            return false;
        }
        // A fragment naming THIS description means it already is the English one - nothing to
        // fetch, and its silence is the platform's own answer.
        return name.equals(desc.getName().getLastSegment()) || provider.getEObjectDescription(name) != null;
    }

    /**
     * Resolves a matched description to the {@link Type} whose members are rendered.
     *
     * <p>Straightforward for a platform type (Array, ValueTable). A METADATA TYPE SET
     * ({@code CatalogObject} / {@code СправочникОбъект}, {@code DocumentRef}, {@code EnumRef}, ...)
     * is a {@code TypeSet} rather than a {@code Type} and carries no members at all, so the old
     * lookup rejected it and reported "not found" for a name it had itself just listed as available
     * (issue #355). The platform keeps the common API of such a set on a GENERIC type -
     * {@code CatalogObject} -> {@code CatalogObjectCatalogName}, the {@code Записать()} /
     * {@code ПолучитьОбъект()} members - and names that type in the description's user data, so one
     * more lookup in the SAME provider reaches it. No proxy games and no resource set: exactly the
     * mechanism that already resolves {@code ValueTable}.
     *
     * @param provider the provider the description came from (used for the second lookup)
     * @param desc the matched description
     * @param matchedName the name as the caller spelled it, for the rendered type-set label
     * @return the documentation target, or {@code null} when nothing resolves
     */
    private DocumentedType resolveDocumentedType(IEObjectProvider provider, IEObjectDescription desc,
        String matchedName)
    {
        Type direct = resolveDescriptionAsType(desc);
        if (direct != null)
        {
            return new DocumentedType(direct, null, null);
        }
        // Every candidate gets a turn: the first REGISTERED one is not necessarily the first that
        // resolves, and stopping at it would report a set as undocumented while a later candidate
        // would have answered.
        for (IEObjectDescription target : documentedTypeDescriptions(provider, desc))
        {
            Type resolved = resolveDescriptionAsType(target);
            if (resolved != null)
            {
                return new DocumentedType(resolved, typeSetLabel(provider, desc, matchedName), matchedName);
            }
        }
        return null;
    }

    /**
     * The bilingual label of the type set the caller named. The English description carries the
     * Russian name in its user data; the Russian one carries no counterpart, so its English half is
     * taken from the sibling the two share a URI with. Nothing is invented for a name the provider
     * does not publish.
     *
     * @param provider the provider the description came from
     * @param desc the matched type-set description
     * @param matchedName the name as it was matched
     * @return the label to render
     */
    private static String typeSetLabel(IEObjectProvider provider, IEObjectDescription desc,
        String matchedName)
    {
        String altName = desc.getUserData(USER_DATA_RU_NAME);
        if (altName == null || altName.isBlank())
        {
            IEObjectDescription sibling = englishSibling(provider, desc);
            altName = sibling != null ? sibling.getName().getLastSegment() : null;
        }
        if (altName == null || altName.isBlank() || altName.equalsIgnoreCase(matchedName))
        {
            return matchedName;
        }
        return matchedName + " / " + altName; //$NON-NLS-1$
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
     * Builds markdown documentation for a Type.
     */
    private String buildTypeDocumentation(DocumentedType documented, Version version, String memberName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                                           String memberType, int limit, boolean useRussian,
                                           boolean detailed)
    {
        Type type = documented.type;
        StringBuilder sb = new StringBuilder();
        // The syntax helper carries what the model does not: the prose, and the return value of
        // methods the model records none for. Unavailable => every lookup is null and the output is
        // exactly the model-only one. Read ONLY for 'detailed': the concise rendering drops every
        // description anyway, and each lookup walks the doc tree and parses a page on the UI thread -
        // at limit 200 that is hundreds of page loads whose result is then thrown away. Issue #299.
        PlatformHelpService help = detailed ? new PlatformHelpService(version, useRussian ? "ru" : "en") //$NON-NLS-1$ //$NON-NLS-2$
            : PlatformHelpService.disabled();
        String typeName = helpNameFor(documented, type, help);

        appendTypeHeader(sb, type, useRussian);
        appendTypeSetLine(sb, documented.typeSetLabel);
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
     * The name the SYNTAX HELPER is asked with, for the type's own description and for every
     * member's.
     *
     * <p>A metadata TYPE SET is normally documented under the SET
     * ({@code CatalogObject.<Catalog name>}), not under the generic type the model resolved to
     * ({@code CatalogObjectCatalogName}) - so the set's name comes first. But not every set has a
     * page: {@code Characteristic} has none while its generic type
     * ({@code CharacteristicsDescription}) does, and committing to the set's name unconditionally
     * silently dropped every description for it. So the set's name is used only when the helper
     * actually documents it, and otherwise the resolved type answers as it always did. Issue #355.
     *
     * @param documented the resolved lookup target
     * @param type the resolved platform type
     * @param help the syntax-helper reader (a disabled one documents nothing, so CONCISE - which
     *            reads no descriptions at all - simply keeps the model name)
     * @return the name to ask the helper with
     */
    private static String helpNameFor(DocumentedType documented, Type type, PlatformHelpService help)
    {
        String modelName = type.getName() != null ? type.getName() : type.getNameRu();
        if (documented.helpName == null)
        {
            return modelName;
        }
        return help.documents(documented.helpName) ? documented.helpName : modelName;
    }

    /**
     * Names the TYPE SET the caller asked for, when the documentation was reached through one. Says
     * plainly which name was resolved and what the rendered members are, so a caller is never left
     * wondering why it asked for {@code СправочникОбъект} and got {@code CatalogObjectCatalogName}.
     *
     * @param sb the output buffer
     * @param typeSetLabel the set's bilingual label, or {@code null} when the type was named directly
     */
    private static void appendTypeSetLine(StringBuilder sb, String typeSetLabel)
    {
        if (typeSetLabel == null)
        {
            return;
        }
        sb.append("**Type set:** ").append(MarkdownUtils.escapeMarkdown(typeSetLabel)) //$NON-NLS-1$
            .append(" - the members below are the ones every type in this set carries.\n\n"); //$NON-NLS-1$
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

        // Search for the function. As on the type branch, each name the banner is about to print is
        // re-checked by actually resolving it: the METHOD provider hands out proxies too, and a name
        // that cannot resolve must not be advertised as available.
        ResourceSet proxyContext = resourceSet;
        PlatformNameIndex index = new PlatformNameIndex(functionName,
            candidate -> resolvesAsBuiltin(methodProvider, candidate, proxyContext));
        Method foundMethod = findBuiltinMethod(methodProvider, functionName, resourceSet, index);

        // If not found, show available methods
        if (foundMethod == null)
        {
            return index.buildNotFoundBanner("Built-in function not found: ", functionName, //$NON-NLS-1$
                "global methods", //$NON-NLS-1$
                "Names are matched exactly (case-insensitive), in English or Russian. A method of a " //$NON-NLS-1$
                    + "platform TYPE is not a global function - pass category='type' with the type's " //$NON-NLS-1$
                    + "name and memberName instead."); //$NON-NLS-1$
        }

        // Build documentation for the found method
        return buildBuiltinMethodDocumentation(foundMethod, useRussian);
    }

    /**
     * Whether {@code name} really answers a built-in lookup - the same resolution the tool would run
     * for it. Applied only to the handful of names about to be printed.
     *
     * @param provider the global-method provider
     * @param name the candidate name, exactly as it would be passed back
     * @param resourceSet resource set used to resolve the proxy (may be {@code null})
     * @return {@code true} when the lookup resolves
     */
    private boolean resolvesAsBuiltin(IEObjectProvider provider, String name, ResourceSet resourceSet)
    {
        IEObjectDescription desc = provider.getEObjectDescription(name);
        return desc != null && resolveDescriptionAsMethod(desc, resourceSet) != null;
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
     * (case-insensitive, by last segment), feeding the names it sees into {@code index} for the
     * not-found banner.
     *
     * @param resourceSet resource set used to resolve a matched proxy (may be {@code null})
     * @param index collects the names seen, for the not-found banner
     * @return the resolved (non-proxy) {@link Method}, or {@code null} when not found
     */
    private Method findBuiltinMethod(IEObjectProvider methodProvider, String functionName,
                                     ResourceSet resourceSet, PlatformNameIndex index)
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

            // Check if this is the function we're looking for (case-insensitive) // NOSONAR explanatory comment, not commented-out code
            if (methodName.equalsIgnoreCase(functionName))
            {
                Method resolvedMethod = resolveDescriptionAsMethod(desc, resourceSet);
                if (resolvedMethod != null)
                {
                    return resolvedMethod;
                }
                // Matched and resolved nothing: never offer the caller its own query back.
                continue;
            }
            index.accept(methodName);
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
