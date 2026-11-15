/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * The other project of a cross-project clone (issue #933 PR-3). EDT pastes a top object into another project with the
 * DESTINATION's copier inside one destination transaction: every reference leaving the copied graph becomes a proxy
 * with the same address in the destination namespace ({@code AttachingModelObjectCopier.createProxy} /
 * {@code copyReferenceTypeDescription}), so a referenced object the destination lacks is a dangling proxy. One BM
 * transaction reads every active namespace by URI, so the source, the destination and an extension destination's base
 * configuration are all read in that same transaction.
 */
public final class MetadataCloneDestination
{
    /** The longest list of missing references a refusal message spells out; the structured list is complete. */
    static final int MESSAGE_LIMIT = 20;

    public final String projectName;
    public final Configuration configuration;
    public final boolean extension;
    /** The extension destination's base configuration; {@code null} for a configuration destination. */
    public final Configuration base;
    public final String baseProjectName;
    private final String sourceNamespace;
    private final String namespace;
    private final Function<URI, EObject> resolve;
    private final Predicate<String> folderExists;

    MetadataCloneDestination(String projectName, Configuration configuration, boolean extension, Configuration base,
        String baseProjectName, String sourceNamespace, String namespace, Function<URI, EObject> resolve,
        Predicate<String> folderExists)
    {
        this.projectName = projectName;
        this.configuration = configuration;
        this.extension = extension;
        this.base = base;
        this.baseProjectName = baseProjectName;
        this.sourceNamespace = sourceNamespace;
        this.namespace = namespace;
        this.resolve = resolve;
        this.folderExists = folderExists;
    }

    /**
     * Reads the destination in {@code tx}, which may belong to any of the involved models.
     *
     * @param folderExists whether a project-relative folder exists in the destination project
     */
    public static MetadataCloneDestination read(IBmTransaction tx, String projectName, String namespace,
        boolean extension, String baseProjectName, String baseNamespace, String sourceNamespace,
        Predicate<String> folderExists)
    {
        Function<URI, EObject> resolve = uri -> {
            try
            {
                return tx.getObjectByUri(uri);
            }
            catch (RuntimeException e)
            {
                // An address that cannot be resolved there is reported, not thrown: the caller decides.
                return null;
            }
        };
        Configuration configuration = configuration(resolve, namespace, projectName);
        Configuration base = extension ? configuration(resolve, baseNamespace, baseProjectName) : null;
        return new MetadataCloneDestination(projectName, configuration, extension, base, baseProjectName,
            sourceNamespace, namespace, resolve, folderExists);
    }

    /** The source project's configuration, read in the destination's transaction by its namespace. */
    public static Configuration source(IBmTransaction tx, String namespace, String projectName)
    {
        return configuration(tx::getObjectByUri, namespace, projectName);
    }

    private static Configuration configuration(Function<URI, EObject> resolve, String namespace, String projectName)
    {
        EObject root = namespace == null ? null : resolve.apply(BmUriUtil.createTopBmObjectUri(namespace,
            "Configuration")); //$NON-NLS-1$
        if (!(root instanceof Configuration))
        {
            throw new IllegalStateException("The configuration of project '" + projectName + "' is not loaded. "
                + "Wait for list_projects to report it ready, then preview again.");
        }
        return (Configuration)root;
    }

    /** The clone's name in this destination: newName, or the source name EDT keeps when it is free there. */
    public static String effectiveName(MdObject source, String newName)
    {
        return newName == null ? source.getName() : newName;
    }

    /**
     * Refuses a name the destination already uses for this kind, and, in an extension, a name its base configuration
     * uses for an object the extension has not adopted: an own extension object must not repeat a base name.
     */
    public void checkName(MdObject source, String newName)
    {
        String kind = source.eClass().getName();
        String name = effectiveName(source, newName);
        String address = kind + "." + name;
        String advice = newName == null ? "Pass a newName that is unused there." : "Choose another newName.";
        if (MetadataTypeUtils.findObject(configuration, kind, name) != null)
        {
            throw new MetadataClonePlan.Refusal(address + " already exists in project '" + projectName
                + "' (case-insensitive). " + advice);
        }
        if (base != null && MetadataTypeUtils.findObject(base, kind, name) != null)
        {
            throw new MetadataClonePlan.Refusal(address + " exists in '" + baseProjectName + "', the base configuration "
                + "of extension '" + projectName + "', and the extension has not adopted it: an own object of the "
                + "extension must not repeat a base name. " + advice + " To extend that base object instead, adopt it "
                + "with adopt_metadata_object.");
        }
    }

    /** Whether the destination already has the clone's object folder (relative to the destination project). */
    public boolean folderExists(String projectRelativeFolder)
    {
        return folderExists.test(projectRelativeFolder);
    }

    /**
     * Objects the copied graph references that the destination lacks, as BM addresses: a top object FQN, or
     * {@code TopFqn#/path} when the top object exists but the referenced member does not. Derived from every ordinary
     * reference of every copied node (types in type descriptions, owners, register records, form and rights targets
     * alike), not from a list of features. A reference the source itself cannot resolve is not this clone's loss
     * and is left out.
     */
    public List<String> missingReferences(MdObject source)
    {
        return missingReferences(source, sourceNamespace, namespace, resolve);
    }

    static List<String> missingReferences(MdObject source, String sourceNamespace, String namespace,
        Function<URI, EObject> resolve)
    {
        String sourceFqn = source.eClass().getName() + "." + source.getName();
        Map<String, EObject> graph = MetadataClonePlan.graph(source);
        Set<EObject> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        owned.addAll(graph.values());
        Set<String> missing = new TreeSet<>();
        for (EObject node : graph.values())
        {
            for (EReference reference : node.eClass().getEAllReferences())
            {
                if (reference.isContainment() || reference.isContainer() || reference.isDerived()
                    || reference.isTransient() || MetadataClonePlan.external(reference))
                {
                    continue;
                }
                Object value = node.eGet(reference, false);
                Iterable<?> targets = value instanceof Iterable<?> ? (Iterable<?>)value : Collections.singletonList(value);
                for (Object target : targets)
                {
                    if (!(target instanceof EObject) || owned.contains(target))
                    {
                        continue;
                    }
                    String label = missingLabel(EcoreUtil.getURI((EObject)target), sourceFqn, sourceNamespace,
                        namespace, resolve);
                    if (label != null)
                    {
                        missing.add(label);
                    }
                }
            }
        }
        return new ArrayList<>(missing);
    }

    private static String missingLabel(URI uri, String sourceFqn, String sourceNamespace, String namespace,
        Function<URI, EObject> resolve)
    {
        if (uri == null || !BmUriUtil.isBmUri(uri))
        {
            // Platform types, standard command groups and other non-model values exist everywhere.
            return null;
        }
        String top = BmUriUtil.extractTopObjectFqn(uri);
        if (top == null || top.equals(sourceFqn) || top.startsWith(sourceFqn + "."))
        {
            // The source's own members are copied with it.
            return null;
        }
        boolean member = !BmUriUtil.isTopObjectUri(uri);
        String path = member ? BmUriUtil.extractContainmentPath(uri) : null;
        if (resolve.apply(retarget(namespace, top, path)) != null)
        {
            return null;
        }
        String origin = BmUriUtil.extractNamespace(uri);
        if (resolve.apply(retarget(origin == null ? sourceNamespace : origin, top, path)) == null)
        {
            return null;
        }
        return member && resolve.apply(retarget(namespace, top, null)) != null ? top + "#" + path : top;
    }

    private static URI retarget(String namespace, String top, String path)
    {
        return path == null ? BmUriUtil.createTopBmObjectUri(namespace, top)
            : BmUriUtil.createContainedBmObjectUri(namespace, top, path);
    }

    /** The refusal for a non-empty {@link #missingReferences(MdObject)} result. */
    public MissingReferences missing(String sourceFqn, List<String> references)
    {
        StringBuilder text = new StringBuilder("the clone of ").append(sourceFqn).append(" would reference ")
            .append(references.size()).append(references.size() == 1 ? " object" : " objects")
            .append(" that project '").append(projectName).append("' does not have: ");
        text.append(String.join(", ", references.subList(0, Math.min(MESSAGE_LIMIT, references.size()))));
        if (references.size() > MESSAGE_LIMIT)
        {
            text.append(", and ").append(references.size() - MESSAGE_LIMIT).append(" more (see missingReferences)");
        }
        text.append(". Nothing was written. ");
        if (extension)
        {
            text.append("Adopt them into the extension first with adopt_metadata_object (projectName '")
                .append(baseProjectName).append("', extensionProjectName '").append(projectName)
                .append("'), or remove the references from the source, then preview again.");
        }
        else
        {
            text.append("Create them in project '").append(projectName).append("' first (create_metadata or "
                + "clone_metadata_object), or remove the references from the source, then preview again.");
        }
        return new MissingReferences(text.toString(), references);
    }

    /** Destination state the copy depends on: its kind, configuration root, same-kind names and the base names. */
    void appendFingerprint(StringBuilder content, MdObject source, List<String> missing)
    {
        String kind = source.eClass().getName();
        content.append("\ntarget=").append(projectName).append(":extension=").append(extension).append('\n');
        MetadataClonePlan.appendModel(content, Collections.singletonMap("targetConfiguration", configuration));
        for (MdObject sibling : MetadataTypeUtils.getObjects(configuration, kind))
        {
            content.append("target:").append(sibling.getName()).append(':').append(sibling.getUuid()).append('\n');
        }
        if (base != null)
        {
            content.append("base=").append(baseProjectName).append('\n');
            for (MdObject sibling : MetadataTypeUtils.getObjects(base, kind))
            {
                content.append("base:").append(sibling.getName()).append(':').append(sibling.getUuid()).append('\n');
            }
        }
        content.append("missing=").append(missing).append('\n');
    }

    /** A final refusal that also carries the complete list of missing references. */
    public static final class MissingReferences extends MetadataClonePlan.Refusal
    {
        private static final long serialVersionUID = 1L;

        public final transient List<String> references;

        public MissingReferences(String message, List<String> references)
        {
            super(message);
            this.references = List.copyOf(references);
        }
    }
}
