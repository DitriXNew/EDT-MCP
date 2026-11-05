/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.common.StringUtils;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.picture.PictureExtension;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.FormPicture;
import com._1c.g5.v8.dt.form.pictures.PictureFqnParts;
import com._1c.g5.v8.dt.form.pictures.PictureFqnUtil;
import com._1c.g5.v8.dt.htmldocument.model.HtmlDocumentPage;
import com._1c.g5.v8.dt.mcore.HelpPage;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceInfo;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceScanResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Detached preview and native-copy postconditions. Model-taking methods require a BM boundary. */
public final class MetadataClonePlan
{
    public static final Set<String> SUPPORTED = Set.of("Catalog", "Document", "Report", "DataProcessor");
    private static final String EXTERNAL_PROPERTY = "http://www.1c.ru/v8/dt/common/ExternalProperty";

    public final String sourceFqn;
    public final String fingerprint;
    public final JsonArray incoming;
    public final boolean referenceScanComplete;
    public final Map<String, String> files;
    public final List<String> topFqns;
    public final String folder;
    public final String sourceFile;
    public final String uuid;
    public final int modelObjects;

    private MetadataClonePlan(String sourceFqn, String fingerprint, JsonArray incoming,
        boolean referenceScanComplete, Map<String, String> files, List<String> topFqns,
        String folder, String sourceFile, String uuid, int modelObjects)
    {
        this.sourceFqn = sourceFqn;
        this.fingerprint = fingerprint;
        this.incoming = incoming;
        this.referenceScanComplete = referenceScanComplete;
        this.files = Collections.unmodifiableMap(new TreeMap<>(files));
        this.topFqns = List.copyOf(topFqns);
        this.folder = folder;
        this.sourceFile = sourceFile;
        this.uuid = uuid;
        this.modelObjects = modelObjects;
    }

    public String hash()
    {
        return ContentHash.of(fingerprint);
    }

    /** Validate before entering the workbench, including malformed JSON that JsonUtils would drop. */
    public static String requestError(String fqn, String newName, boolean confirm, String expectedHash,
        String rawTransfer)
    {
        String normalized = fqn == null ? "" : MetadataTypeUtils.normalizeFqn(fqn);
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 2 || !SUPPORTED.contains(parts[0]) || parts[1].isBlank())
        {
            return "Unsupported source '" + fqn + "'. Pass one top object: Catalog.Name, Document.Name, "
                + "Report.Name or DataProcessor.Name in a plain configuration project.";
        }
        if (newName != null && (newName.isBlank() || !StringUtils.isValidName(newName)))
        {
            return "Invalid newName '" + newName + "'. Pass a 1C identifier starting with a letter or "
                + "underscore, followed by letters, digits or underscores; omit newName for EDT's free name.";
        }
        if (confirm && (expectedHash == null || expectedHash.isBlank()))
        {
            return "expectedHash is required with confirm=true. Call clone_metadata_object without confirm "
                + "first, then repeat the same newName and transfer selection with its contentHash.";
        }
        if (rawTransfer != null)
        {
            try
            {
                JsonElement parsed = JsonParser.parseString(rawTransfer);
                if (!parsed.isJsonArray())
                {
                    return "transfer must be an array of {kind, owner}; use the owners from a fresh preview.";
                }
                Set<String> selected = new LinkedHashSet<>();
                for (JsonElement element : parsed.getAsJsonArray())
                {
                    if (!element.isJsonObject())
                    {
                        return "Each transfer must be {kind, owner}; use the owners from a fresh preview.";
                    }
                    JsonObject entry = element.getAsJsonObject();
                    if (!entry.keySet().equals(Set.of("kind", "owner")) || !string(entry, "kind")
                        || !string(entry, "owner"))
                    {
                        return "Each transfer requires exactly the string fields kind and owner; use a fresh preview.";
                    }
                    String kind = entry.get("kind").getAsString();
                    if (!Set.of("subsystem", "roleRights").contains(kind))
                    {
                        return "Transfer kind '" + kind + "' is reported only. Select subsystem or roleRights; "
                            + "replicate other relations separately in EDT.";
                    }
                    String key = kind + ":" + MetadataTypeUtils.normalizeFqn(entry.get("owner").getAsString())
                        .toLowerCase(Locale.ROOT);
                    if (!selected.add(key))
                    {
                        return "Duplicate transfer '" + key + "'. Select each owner once.";
                    }
                }
            }
            catch (RuntimeException e)
            {
                return "Invalid transfer JSON. Pass an array of {kind, owner} from a fresh preview.";
            }
        }
        return null;
    }

    private static boolean string(JsonObject entry, String key)
    {
        JsonElement value = entry.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            && !value.getAsString().isBlank();
    }

    public static MdObject source(Configuration config, String fqn)
    {
        MdObject object = MetadataNodeResolver.resolveAddress(MetadataScope.ofConfiguration(config),
            MetadataTypeUtils.normalizeFqn(fqn), false);
        if (object == null)
        {
            throw new IllegalArgumentException("Object not found: '" + fqn
                + "'. Use get_metadata_objects; the name must be a programmatic identifier, not a synonym.");
        }
        if (!SUPPORTED.contains(object.eClass().getName()))
        {
            throw new IllegalArgumentException("Unsupported source '" + fqn
                + "'. Use Catalog, Document, Report or DataProcessor.");
        }
        return object;
    }

    public static EReference collection(Configuration config, MdObject source)
    {
        for (EReference ref : config.eClass().getEAllReferences())
        {
            if (ref.isMany() && !ref.isDerived() && ref.getEReferenceType() == source.eClass())
            {
                return ref;
            }
        }
        throw new IllegalArgumentException("The source has no configuration collection. Use a plain configuration project.");
    }

    public static void checkName(Configuration config, MdObject source, String newName)
    {
        if (newName != null && MetadataTypeUtils.findObject(config, source.eClass().getName(), newName) != null)
        {
            throw new IllegalArgumentException("newName '" + newName + "' is already taken in "
                + source.eClass().getName() + " (case-insensitive). Choose an unused identifier or omit newName.");
        }
    }

    /** Source, disk bytes, destination names and selected owners are hashed in the same read. */
    public static MetadataClonePlan capture(IProject project, IBmModel model, Configuration config,
        MdObject source, String newName, List<JsonObject> transfer, IProjectFileSystemSupport fs,
        boolean scanReferences)
    {
        checkName(config, source, newName);
        String fqn = source.eClass().getName() + "." + source.getName();
        Map<String, EObject> graph = graph(source);
        StringBuilder content = new StringBuilder(fqn).append('\n').append(newName).append('\n');
        appendModel(content, graph);
        appendModel(content, Collections.singletonMap("configuration", config));
        IFile rootFile = fs.getFile(source);
        requireFile(rootFile, "source metadata");
        if (newName != null && rootFile.getParent().getParent()
            .getFolder(new org.eclipse.core.runtime.Path(newName)).exists())
        {
            throw new IllegalArgumentException("Destination folder for newName '" + newName
                + "' already exists. Inspect its files and choose an unused name; clone never overwrites a folder.");
        }
        Map<String, String> files = ownedFileHashes(graph, fs, rootFile);
        content.append(files).append('\n');
        List<String> topFqns = collectTopFqns(graph);
        for (MdObject sibling : MetadataTypeUtils.getObjects(config, source.eClass().getName()))
        {
            content.append(sibling.getName()).append(':').append(sibling.getUuid()).append('\n');
        }
        JsonArray relations = transferRelations(config, source);
        content.append(relations).append('\n');
        for (Role role : config.getRoles())
        {
            content.append(role.getName()).append(':').append(role.getUuid());
            if (role.getRights() instanceof RoleDescription)
            {
                RoleDescription rights = (RoleDescription)role.getRights();
                content.append(':').append(rights.isSetForNewObjects()).append(':')
                    .append(rights.isSetForAttributesByDefault()).append(':')
                    .append(rights.isIndependentRightsOfChildObjects());
            }
            content.append('\n');
        }
        List<String> selected = new ArrayList<>();
        for (JsonObject entry : transfer)
        {
            String kind = entry.get("kind").getAsString();
            String ownerFqn = MetadataTypeUtils.normalizeFqn(entry.get("owner").getAsString());
            JsonObject relation = findRelation(relations, kind, ownerFqn);
            if (relation == null)
            {
                throw new IllegalArgumentException("Transfer '" + kind + "' owner '" + ownerFqn
                    + "' does not reference the source. Use an eligible owner from a fresh preview.");
            }
            MdObject owner = MetadataNodeResolver.resolveAddress(MetadataScope.ofConfiguration(config), ownerFqn, false);
            String refusal = VendorSupportGuard.refusalFor(owner, MetadataScope.ofConfiguration(config),
                ownerFqn, ownerFqn, "modified");
            if (refusal != null)
            {
                throw new IllegalArgumentException(refusal);
            }
            StringBuilder ownerContent = new StringBuilder();
            appendModel(ownerContent, graph(owner));
            selected.add(kind + ":" + relation.get("owner").getAsString() + ":" + ownerContent);
        }
        Collections.sort(selected);
        content.append(selected);
        boolean complete = false;
        if (scanReferences)
        {
            ReferenceScanResult scan = new MetadataReferenceService().collectReferencesForObjectStrict(
                project, model, (IBmObject)source, Integer.MAX_VALUE / 100);
            complete = scan.complete;
            for (ReferenceInfo ref : scan.refs)
            {
                if (isOwnedReference(ref, fqn, graph,
                    rootFile.getFullPath().removeLastSegments(1).toPortableString()))
                {
                    continue;
                }
                JsonObject relation = new JsonObject();
                String kind = "metadata";
                if (ref.sourceObject != null)
                {
                    String type = ref.sourceObject.bmGetTopObject().eClass().getName();
                    kind = Character.toLowerCase(type.charAt(0)) + type.substring(1);
                }
                relation.addProperty("kind", ref.isBslReference ? "code" : kind);
                relation.addProperty("owner", ref.sourcePath);
                relation.addProperty("category", ref.category);
                relation.addProperty("feature", ref.feature);
                relation.addProperty("line", ref.line);
                relation.addProperty("transferSupport", "reportedOnly");
                relations.add(relation);
            }
        }
        if (!files.equals(ownedFileHashes(graph, fs, rootFile)))
        {
            throw new IllegalArgumentException("Source files changed while previewing '" + fqn
                + "'. Wait for export to finish, then preview again.");
        }
        List<JsonElement> grouped = new ArrayList<>();
        relations.forEach(grouped::add);
        grouped.sort(java.util.Comparator.comparing(item -> item.getAsJsonObject().get("kind").getAsString()));
        JsonArray incoming = new JsonArray();
        grouped.forEach(incoming::add);
        return new MetadataClonePlan(fqn, content.toString(), incoming, complete, files, topFqns,
            rootFile.getParent().getProjectRelativePath().toPortableString(),
            rootFile.getProjectRelativePath().toPortableString(), String.valueOf(source.getUuid()),
            graph.size());
    }

    private static boolean sourcePath(String path, String folder)
    {
        if (path == null)
        {
            return false;
        }
        String portable = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String canonical = folder.toLowerCase(Locale.ROOT);
        if (portable.contains("/src/"))
        {
            return portable.contains(canonical + "/");
        }
        String sourceRelative = canonical.substring(canonical.indexOf("/src/") + 5);
        return portable.startsWith("src/" + sourceRelative + "/") || portable.startsWith(sourceRelative + "/");
    }

    /** Incoming relations have owners outside the copied graph, including derived field nodes. */
    static boolean isOwnedReference(ReferenceInfo ref, String sourceFqn, Map<String, EObject> graph, String folder)
    {
        Set<EObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EObject owner = ref.sourceObject; owner != null && visited.add(owner); owner = owner.eContainer())
        {
            if (graph.containsValue(owner))
            {
                return true;
            }
            if (owner instanceof IBmObject && ((IBmObject)owner).bmIsTop()
                && !((IBmObject)owner).bmIsTransient()
                && ownedAddress(((IBmObject)owner).bmGetFqn(), sourceFqn))
            {
                return true;
            }
        }
        if (ref.isBslReference && ref.identityPath != null && ref.identityPath.replace('\\', '/').contains("/src/"))
        {
            return sourcePath(ref.identityPath, folder);
        }
        return sourcePath(ref.sourcePath, folder) || sourcePath(ref.identityPath, folder)
            || (!ref.isBslReference && (ownedAddress(ref.sourcePath, sourceFqn)
                || ownedAddress(ref.identityPath, sourceFqn)));
    }

    private static boolean ownedAddress(String display, String sourceFqn)
    {
        if (display == null)
        {
            return false;
        }
        int separator = display.indexOf(" - ");
        int member = display.indexOf(" / ");
        if (member >= 0 && (separator < 0 || member < separator))
        {
            separator = member;
        }
        String owner = MetadataTypeUtils.normalizeFqn(separator < 0 ? display : display.substring(0, separator));
        return owner.equalsIgnoreCase(sourceFqn)
            || owner.regionMatches(true, 0, sourceFqn + ".", 0, sourceFqn.length() + 1);
    }

    /** Stable serialization used by postconditions and revision checks, never EObject.toString(). */
    public static String modelFingerprint(EObject object)
    {
        StringBuilder text = new StringBuilder();
        appendModel(text, graph(object));
        return text.toString();
    }

    /** Persisted containment plus EDT-annotated external models; never follows ordinary references. */
    public static Map<String, EObject> graph(EObject root)
    {
        Map<String, EObject> nodes = new LinkedHashMap<>();
        walk(root, "", nodes, Collections.newSetFromMap(new IdentityHashMap<>()));
        return nodes;
    }

    private static void walk(EObject object, String path, Map<String, EObject> nodes, Set<EObject> seen)
    {
        if (object == null || object.eIsProxy() || !seen.add(object))
        {
            return;
        }
        nodes.put(path, object);
        Map<String, Integer> indexes = new java.util.HashMap<>();
        for (EObject child : PersistedContents.of(object))
        {
            EReference feature = child.eContainmentFeature();
            if (feature == null || "producedTypes".equals(feature.getName()) || "extension".equals(feature.getName()))
            {
                continue;
            }
            int index = indexes.merge(feature.getName(), 1, Integer::sum) - 1;
            walk(child, path + "/" + feature.getName() + (feature.isMany() ? "/" + index : ""), nodes, seen);
        }
        for (EReference ref : object.eClass().getEAllReferences())
        {
            if (!external(ref) || ref.isContainment() || ref.isDerived() || !modeledExternal(object, ref))
            {
                continue;
            }
            Object value = object.eGet(ref);
            if (value instanceof Collection<?>)
            {
                int index = 0;
                for (Object child : (Collection<?>)value)
                {
                    if (child instanceof EObject)
                    {
                        walk((EObject)child, path + "/" + ref.getName() + "/" + index++, nodes, seen);
                    }
                }
            }
            else if (value instanceof EObject)
            {
                walk((EObject)value, path + "/" + ref.getName(), nodes, seen);
            }
        }
    }

    public static boolean external(EReference ref)
    {
        return ref.getEAnnotation(EXTERNAL_PROPERTY) != null;
    }

    /** Native paste clones these models; other external properties (including BSL ASTs) are files. */
    private static boolean modeledExternal(EObject owner, EReference ref)
    {
        if (com._1c.g5.v8.dt.md.MdUtil.isSuppressSettingsFeature(ref))
        {
            return false;
        }
        String type = ref.getEReferenceType().getName();
        return Set.of("AbstractForm", "AbstractStyle", "AbstractRoleDescription", "AbstractAdditionalIndexes")
            .contains(type) || (owner instanceof BasicTemplate && "template".equals(ref.getName())
                && ((BasicTemplate)owner).getTemplateType() == TemplateType.HTML_DOCUMENT);
    }

    private static void appendModel(StringBuilder text, Map<String, EObject> graph)
    {
        for (Map.Entry<String, EObject> entry : graph.entrySet())
        {
            EObject object = entry.getValue();
            text.append(entry.getKey()).append(':').append(object.eClass().getName()).append('{');
            for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
            {
                // External proxies exist even without files; owned models and file hashes cover their content.
                if (feature.isDerived() || feature.isTransient()
                    || (feature instanceof EReference && external((EReference)feature)))
                {
                    continue;
                }
                text.append(feature.getName()).append('=');
                appendValue(text, object.eGet(feature, false));
                text.append(';');
            }
            text.append("}\n");
        }
    }

    private static void appendValue(StringBuilder text, Object value)
    {
        if (value instanceof EObject)
        {
            text.append(EcoreUtil.getURI((EObject)value));
        }
        else if (value instanceof Iterable<?>)
        {
            text.append('[');
            for (Object item : (Iterable<?>)value)
            {
                appendValue(text, item);
                text.append(',');
            }
            text.append(']');
        }
        else
        {
            String scalar = String.valueOf(value);
            text.append(scalar.length()).append(':').append(scalar);
        }
    }

    public static List<String> collectTopFqns(Map<String, EObject> graph)
    {
        Set<String> fqns = new LinkedHashSet<>();
        for (EObject object : graph.values())
        {
            if (object instanceof IBmObject && ((IBmObject)object).bmIsTop()
                && !((IBmObject)object).bmIsTransient())
            {
                fqns.add(((IBmObject)object).bmGetFqn());
            }
        }
        return new ArrayList<>(fqns);
    }

    /** Authored object rows only: role default flags do not mean an authored row exists. */
    public static JsonArray transferRelations(Configuration config, MdObject source)
    {
        JsonArray result = new JsonArray();
        for (Subsystem root : config.getSubsystems())
        {
            subsystemRelations(root, source, result);
        }
        for (Role role : config.getRoles())
        {
            if (role.getRights() instanceof RoleDescription)
            {
                for (ObjectRights row : ((RoleDescription)role.getRights()).getRights())
                {
                    if (row.getObject() == source)
                    {
                        result.add(relation("roleRights", "Role." + role.getName()));
                        break;
                    }
                }
            }
        }
        return result;
    }

    private static void subsystemRelations(Subsystem subsystem, MdObject source, JsonArray result)
    {
        if (subsystem.getContent().contains(source))
        {
            result.add(relation("subsystem", SubsystemUtils.lineage(subsystem).chainFqn()));
        }
        for (Subsystem child : subsystem.getSubsystems())
        {
            subsystemRelations(child, source, result);
        }
    }

    private static JsonObject relation(String kind, String owner)
    {
        JsonObject result = new JsonObject();
        result.addProperty("kind", kind);
        result.addProperty("owner", owner);
        result.addProperty("transferSupport", "supported");
        return result;
    }

    private static JsonObject findRelation(JsonArray relations, String kind, String owner)
    {
        for (JsonElement item : relations)
        {
            JsonObject relation = item.getAsJsonObject();
            if (kind.equals(relation.get("kind").getAsString())
                && owner.equalsIgnoreCase(relation.get("owner").getAsString()))
            {
                return relation;
            }
        }
        return null;
    }

    public static List<String> transfer(Configuration config, MdObject source, MdObject clone,
        List<JsonObject> selections, ITopObjectFqnGenerator generator)
    {
        List<String> dirty = new ArrayList<>();
        Map<String, EObject> originals = graph(source);
        Map<String, EObject> copies = graph(clone);
        for (JsonObject selection : selections)
        {
            String ownerFqn = MetadataTypeUtils.normalizeFqn(selection.get("owner").getAsString());
            MdObject owner = MetadataNodeResolver.resolveAddress(MetadataScope.ofConfiguration(config), ownerFqn, false);
            if ("subsystem".equals(selection.get("kind").getAsString()))
            {
                ((Subsystem)owner).getContent().add(clone);
            }
            else
            {
                RoleDescription description = (RoleDescription)((Role)owner).getRights();
                copyRightsRow(description, source, clone, originals, copies);
                dirty.add(generator.generateExternalPropertyFqn(owner,
                    com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.ROLE__RIGHTS));
            }
            dirty.add(((IBmObject)owner).bmGetTopObject().bmGetFqn());
        }
        return dirty;
    }

    public static void copyRightsRow(RoleDescription role, EObject source, EObject clone,
        Map<String, EObject> originals, Map<String, EObject> copies)
    {
        for (ObjectRights row : new ArrayList<>(role.getRights()))
        {
            if (row.getObject() == source)
            {
                Map<EObject, EObject> remapped = new IdentityHashMap<>();
                for (Map.Entry<String, EObject> entry : originals.entrySet())
                {
                    EObject counterpart = copies.get(entry.getKey());
                    if (counterpart != null)
                    {
                        remapped.put(entry.getValue(), counterpart);
                    }
                }
                EcoreUtil.Copier copier = new EcoreUtil.Copier()
                {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public EObject get(Object key)
                    {
                        EObject mapped = remapped.get(key);
                        return mapped == null ? super.get(key) : mapped;
                    }
                };
                ObjectRights copied = (ObjectRights)copier.copy(row);
                copier.copyReferences();
                copied.setObject(clone);
                for (ObjectRight right : copied.getRights())
                {
                    for (Rls rls : right.getRestrictionsByCondition())
                    {
                        if (!rls.getFields().isEmpty())
                        {
                            List<DbViewFieldDef> fields = new ArrayList<>();
                            for (DbViewFieldDef field : rls.getFields())
                            {
                                org.eclipse.emf.common.util.URI uri = EcoreUtil.getURI(field);
                                DbViewFieldDef proxy = (DbViewFieldDef)EcoreUtil.create(field.eClass());
                                org.eclipse.emf.common.util.URI replacement = retargetRlsFieldUri(uri,
                                    ((IBmObject)source).bmGetFqn(), ((IBmObject)clone).bmGetFqn(),
                                    ((IBmObject)clone).bmGetEngine().getId());
                                ((org.eclipse.emf.ecore.InternalEObject)proxy).eSetProxyURI(replacement);
                                fields.add(proxy);
                            }
                            rls.getFields().clear();
                            rls.getFields().addAll(fields);
                        }
                    }
                }
                role.getRights().removeIf(existing -> existing.getObject() == clone);
                role.getRights().add(copied);
                return;
            }
        }
        throw new IllegalArgumentException("The selected role no longer has a source rights row. Run preview again.");
    }

    /** Like native field copying, keep the generated field path and change its owning top FQN. */
    public static org.eclipse.emf.common.util.URI retargetRlsFieldUri(org.eclipse.emf.common.util.URI uri,
        String sourceFqn, String cloneFqn, String namespace)
    {
        if (!BmUriUtil.isBmUri(uri))
        {
            throw new IllegalArgumentException("Cannot map an unresolved RLS field to the clone. "
                + "Open the source rights in EDT and resolve its fields before previewing again.");
        }
        String owner = BmUriUtil.extractTopObjectFqn(uri);
        if (!(sourceFqn.equals(owner) || owner.startsWith(sourceFqn + ".")))
        {
            throw new IllegalArgumentException("An RLS field belongs to '" + owner
                + "', outside the cloned source. Inspect the selected role in EDT before retrying.");
        }
        return BmUriUtil.createContainedBmObjectUri(namespace, cloneFqn + owner.substring(sourceFqn.length()),
            BmUriUtil.extractContainmentPath(uri));
    }

    /** Native-owned resources keyed by model-node/feature address, independent of physical names. */
    public static Map<String, IFile> ownedResources(Map<String, EObject> graph, IProjectFileSystemSupport fs)
    {
        return resourcePaths(graph, fs, true);
    }

    /** Destination paths are planned before commit/export; existence is checked only after the barrier. */
    public static Map<String, IFile> destinationResources(Map<String, EObject> graph, IProjectFileSystemSupport fs)
    {
        return resourcePaths(graph, fs, false);
    }

    private static Map<String, IFile> resourcePaths(Map<String, EObject> graph, IProjectFileSystemSupport fs,
        boolean source)
    {
        Map<String, IFile> result = new LinkedHashMap<>();
        for (Map.Entry<String, EObject> entry : graph.entrySet())
        {
            EObject node = entry.getValue();
            if (entry.getKey().isEmpty() || (node instanceof IBmObject && ((IBmObject)node).bmIsTop()))
            {
                addFile(result, entry.getKey() + "@model", fs.getFile(node));
            }
            if (node instanceof HelpPage || node instanceof HtmlDocumentPage)
            {
                addFile(result, entry.getKey() + "@file", fs.getFile(node));
            }
            if (node instanceof FormPicture && PictureExtension.getType(node) != null)
            {
                IFile file = fs.getFile(node);
                if (file == null)
                {
                    String fqn = PictureFqnUtil.buildFormPictureBlobFqn((FormPicture)node);
                    if (fqn == null)
                    {
                        throw new IllegalArgumentException("Cannot identify an owned form picture. "
                            + "Open the source form in EDT and repair its picture before cloning.");
                    }
                    PictureFqnParts parts = PictureFqnUtil.extractFormPictureBlobFqnParts(fqn);
                    IFile form = fs.getFile(((IBmObject)node).bmGetTopObject());
                    String items = StringUtils.capitalize(FormPackage.Literals.FORM_ITEM_CONTAINER__ITEMS.getName());
                    file = form.getProject().getFile(form.getProjectRelativePath().removeLastSegments(1)
                        .append(items).append(parts.getItem()).append(parts.getFeature())
                        .addFileExtension(PictureExtension.getType(node)));
                }
                addFile(result, entry.getKey() + "@picture", file);
            }
            for (EReference ref : node.eClass().getEAllReferences())
            {
                if (!external(ref) || ref.isDerived()
                    || com._1c.g5.v8.dt.md.MdUtil.isSuppressSettingsFeature(ref))
                {
                    continue;
                }
                addFile(result, entry.getKey() + "@" + ref.getName(),
                    source ? externalFile(node, ref, fs) : fs.getFile(node, ref));
            }
        }
        return result;
    }

    private static IFile externalFile(EObject owner, EReference ref, IProjectFileSystemSupport fs)
    {
        IFile file = fs.getFile(owner, ref);
        if (file != null && file.exists())
        {
            return file;
        }
        Object value = owner.eGet(ref, false);
        if (value instanceof EObject && hasExternalContent((EObject)value))
        {
            requireFile(file, "external property '" + ref.getName() + "'");
        }
        // Native paste copies ordinary external properties only when their source file exists.
        return null;
    }

    private static boolean hasExternalContent(EObject object)
    {
        if (object.eIsProxy())
        {
            return false;
        }
        if (object instanceof com._1c.g5.v8.dt.bsl.model.Module)
        {
            org.eclipse.xtext.nodemodel.INode node = org.eclipse.xtext.nodemodel.util.NodeModelUtils.getNode(object);
            // ModuleType/owner identify an empty module too; only syntax or authored AST children are content.
            return (node != null && !node.getText().isBlank()) || !PersistedContents.of(object).isEmpty();
        }
        for (EStructuralFeature feature : object.eClass().getEAllStructuralFeatures())
        {
            if (!feature.isDerived() && !feature.isTransient() && object.eIsSet(feature))
            {
                return true;
            }
        }
        return false;
    }

    private static void addFile(Map<String, IFile> files, String key, IFile file)
    {
        if (file != null)
        {
            files.put(key, file);
        }
    }

    private static Map<String, String> ownedFileHashes(Map<String, EObject> graph,
        IProjectFileSystemSupport fs, IFile root)
    {
        Map<String, String> result = new TreeMap<>();
        for (IFile file : ownedResources(graph, fs).values())
        {
            if (!root.getParent().getProjectRelativePath().isPrefixOf(file.getProjectRelativePath()))
            {
                throw new IllegalArgumentException("Owned resource '" + file.getProjectRelativePath()
                    + "' is outside the object's folder. Use EDT native paste for this source.");
            }
            requireFile(file, "owned resource");
            try (InputStream input = file.getContents(true))
            {
                result.put(file.getProjectRelativePath().makeRelativeTo(root.getParent().getProjectRelativePath())
                    .toPortableString(), bytesHash(input.readAllBytes()));
            }
            catch (Exception e)
            {
                throw new IllegalStateException("Cannot read '" + file.getProjectRelativePath()
                    + "'. Save and refresh the source, then preview again.", e);
            }
        }
        return result;
    }

    public static String bytesHash(byte[] bytes)
    {
        try
        {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (java.security.NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static void requireFile(IFile file, String what)
    {
        if (file == null || !file.exists())
        {
            throw new IllegalArgumentException("Missing " + what + " file '" + file
                + "'. Save/export and refresh the source before previewing again.");
        }
    }

    /** Explicit textual occurrences only; dynamic names are outside this report's scope. */
    public static JsonArray textReferences(String path, String text, String sourceFqn)
    {
        String[] parts = sourceFqn.split("\\.", 2);
        Set<String> aliases = MetadataTypeUtils.typeAliases(parts[0]);
        List<String> tokens = new ArrayList<>();
        for (String alias : aliases)
        {
            tokens.add(Pattern.quote(alias));
        }
        Pattern reference = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:" + String.join("|", tokens)
            + ")\\s*\\.\\s*" + Pattern.quote(parts[1]) + "(?![\\p{L}\\p{N}_])");
        JsonArray result = new JsonArray();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++)
        {
            if (reference.matcher(lines[i]).find())
            {
                JsonObject hit = new JsonObject();
                hit.addProperty("file", path);
                hit.addProperty("line", i + 1);
                result.add(hit);
            }
        }
        return result;
    }
}
