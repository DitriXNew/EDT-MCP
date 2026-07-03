/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeContentItem;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeUse;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.util.CommonAttributeUtil;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Writes a {@link CommonAttribute}'s content list (the {@code modify_metadata} CommonAttribute
 * {@code content[]} branch): adds an owner metadata object to the common attribute's content with a
 * given {@link CommonAttributeUse use}, updates the use of an owner already present, or removes an
 * owner from the content.
 *
 * <p>Each {@code content[]} entry is {@code {op?:"add"|"remove" (default add), metadata:"Catalog.X",
 * use?:"Use"|"DontUse"|"Auto"}}. The {@code use} token is mapped case-insensitively through the
 * {@link MdClassPackage.Literals#COMMON_ATTRIBUTE_CONTENT_ITEM__USE} EAttribute's {@link EEnum}
 * literals (NOT {@code CommonAttributeUse.valueOf}); an omitted {@code use} defaults to
 * {@link CommonAttributeUse#USE Use}.</p>
 *
 * <p>The whole payload is validated UP FRONT, BEFORE the write transaction (op recognized; a
 * metadata FQN required; the owner resolves via the shared bilingual
 * {@link MetadataNodeResolver#resolveExisting}; the owner's {@link EClass} passes
 * {@link CommonAttributeUtil#isCommonAttributeOwnerClass}; the {@code use} token is recognized), so a
 * shape / resolution error leaves NOTHING written. The mutation then runs entirely inside a single
 * {@link BmTransactions#write write} boundary on the re-fetched common attribute, with every owner
 * re-resolved from the IN-TRANSACTION configuration so {@code getMetadata() == owner} identity holds.
 * New content items come ONLY from the MdPlugin {@link IModelObjectFactory} (never {@code new} /
 * {@code EcoreUtil}). The writer does NOT force-export; the caller ({@code ModifyMetadataTool}) does
 * that once, outside any boundary, after the commit.</p>
 *
 * <p>The parse / map / validate helpers are pure (no model, no UI) so they are unit-testable; the
 * model-touching {@link #apply} runs on the UI thread and goes only through the BM boundary.</p>
 */
public final class CommonAttributeContentWriter
{
    private CommonAttributeContentWriter()
    {
        // Utility class
    }

    /** Content op tokens. */
    private static final String OP_ADD = "add"; //$NON-NLS-1$
    private static final String OP_REMOVE = "remove"; //$NON-NLS-1$

    // ---- result -------------------------------------------------------------------------------

    /**
     * The outcome of applying a {@code content[]} payload to a common attribute: either a JSON
     * {@code error} (an up-front validation / resolution failure means nothing was written) or the
     * per-kind counts of what was applied (owners added, use updated on an existing owner, owners
     * removed).
     */
    public static final class Result
    {
        /** Non-null when the write failed / was rejected: a ready JSON error to return verbatim. */
        public final String error;
        /** Number of owners added to the content. */
        public final int added;
        /** Number of existing owners whose {@code use} was updated. */
        public final int updated;
        /** Number of owners removed from the content. */
        public final int removed;

        private Result(String error, int added, int updated, int removed)
        {
            this.error = error;
            this.added = added;
            this.updated = updated;
            this.removed = removed;
        }

        static Result failed(String error)
        {
            return new Result(error, 0, 0, 0);
        }

        static Result ok(int added, int updated, int removed)
        {
            return new Result(null, added, updated, removed);
        }

        public boolean hasError()
        {
            return error != null;
        }
    }

    // ---- entry point --------------------------------------------------------------------------

    /**
     * Applies a {@code content[]} payload to the resolved {@link CommonAttribute}. Validates the whole
     * payload up front (op, required metadata FQN, owner resolution + owner-kind, use token) so a
     * malformed / unresolvable entry fails before ANY mutation, then mutates the model inside a single
     * {@link BmTransactions#write write} boundary on the re-fetched common attribute. Runs on the UI
     * thread; does NOT force-export (the caller does that once, outside any boundary).
     *
     * @param project the workspace project owning the common attribute
     * @param config the configuration (for bilingual owner resolution)
     * @param commonAttribute the resolved common attribute top object
     * @param content the parsed {@code content[]} entries (must not be empty)
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    public static Result apply(IProject project, Configuration config, CommonAttribute commonAttribute,
        List<JsonObject> content)
    {
        if (content == null || content.isEmpty())
        {
            return Result.failed(ToolResult.error("Nothing to apply: provide at least one 'content' " //$NON-NLS-1$
                + "entry, e.g. {\"metadata\":\"Catalog.Products\",\"use\":\"Use\"} to add an owner or " //$NON-NLS-1$
                + "{\"op\":\"remove\",\"metadata\":\"Catalog.Products\"} to remove one.").toJson()); //$NON-NLS-1$
        }

        // Validate + resolve every entry up front so nothing is mutated on a shape / resolution error.
        List<PlannedEntry> plan = new ArrayList<>();
        for (JsonObject entry : content)
        {
            EntryPlan resolved = planEntry(config, entry);
            if (resolved.error != null)
            {
                return Result.failed(resolved.error);
            }
            plan.add(resolved.entry);
        }

        Services services = Services.resolve(project);
        if (services.error != null)
        {
            return Result.failed(services.error);
        }

        long attributeBmId = ((IBmObject)commonAttribute).bmGetId();
        try
        {
            return BmTransactions.write(services.model, "ModifyCommonAttributeContent", (tx, pm) -> //$NON-NLS-1$
            {
                CommonAttribute inTx = (CommonAttribute)tx.getObjectById(attributeBmId);
                if (inTx == null)
                {
                    throw new ContentWriteException(ToolResult.error("The common attribute could not " //$NON-NLS-1$
                        + "be resolved inside the transaction.").toJson());
                }
                int added = 0;
                int updated = 0;
                int removed = 0;
                for (PlannedEntry planned : plan)
                {
                    MdObject owner = resolveOwnerInTx(tx, planned.ownerBmId);
                    if (owner == null)
                    {
                        throw new ContentWriteException(ToolResult.error("Owner '" + planned.fqn //$NON-NLS-1$
                            + "' could not be resolved inside the transaction.").toJson()); //$NON-NLS-1$
                    }
                    if (planned.remove)
                    {
                        if (removeOwner(inTx, owner) == 0)
                        {
                            throw new ContentWriteException(ToolResult.error("Owner '" + planned.fqn //$NON-NLS-1$
                                + "' is not in the content list of common attribute '" //$NON-NLS-1$
                                + inTx.getName() + "'; nothing to remove. Read the current content " //$NON-NLS-1$
                                + "with get_metadata_details on the common attribute FQN.").toJson()); //$NON-NLS-1$
                        }
                        removed++;
                    }
                    else
                    {
                        int[] delta = addOwner(inTx, owner, planned.use, services.factory, services.version);
                        added += delta[0];
                        updated += delta[1];
                    }
                }
                return Result.ok(added, updated, removed);
            });
        }
        catch (ContentWriteException e)
        {
            return Result.failed(e.getErrorJson());
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to modify common attribute content for " //$NON-NLS-1$
                + commonAttribute.getName(), e);
            return Result.failed(ToolResult.error("Failed to modify common attribute content: " //$NON-NLS-1$
                + e.getMessage()).toJson());
        }
    }

    // ---- mutation (inside the write boundary) -------------------------------------------------

    /**
     * Adds an owner to the content, or updates its {@code use} when already present. Scans
     * {@link CommonAttribute#getContent()} by {@link CommonAttributeContentItem#getMetadata()}
     * identity: a match whose {@code use} DIFFERS has its use set (an update); a match whose {@code use}
     * already equals the requested value is a no-op (idempotent re-add - counted as neither an add nor
     * an update); otherwise a fresh {@link CommonAttributeContentItem} is created via the MdPlugin
     * {@link IModelObjectFactory}, its metadata + use set, and it is appended (an add). MUST run inside
     * the write boundary.
     *
     * @return a two-slot delta {@code [added, updated]}
     */
    private static int[] addOwner(CommonAttribute commonAttribute, MdObject owner, CommonAttributeUse use,
        IModelObjectFactory factory, Version version)
    {
        CommonAttributeContentItem existing = findByOwner(commonAttribute, owner);
        if (existing != null)
        {
            if (existing.getUse() == use)
            {
                // Idempotent re-add: the owner is already listed with the requested use - nothing to do.
                return new int[] {0, 0};
            }
            existing.setUse(use);
            return new int[] {0, 1};
        }
        CommonAttributeContentItem item = (CommonAttributeContentItem)factory
            .create(MdClassPackage.Literals.COMMON_ATTRIBUTE_CONTENT_ITEM, version);
        if (item == null)
        {
            throw new ContentWriteException(ToolResult.error("The EDT factory cannot create a " //$NON-NLS-1$
                + "common attribute content item.").toJson());
        }
        item.setMetadata(owner);
        item.setUse(use);
        commonAttribute.getContent().add(item);
        return new int[] {1, 0};
    }

    /**
     * Removes the content item whose {@link CommonAttributeContentItem#getMetadata()} is the given
     * owner (by identity), if present. MUST run inside the write boundary. A {@code 0} return (owner
     * not in the content) is treated by the caller as a clean error - removing an owner that is not
     * listed is NOT a silent no-op - so the whole write rolls back and nothing is applied.
     *
     * @return {@code 1} when an item was removed, {@code 0} when the owner was not in the content
     */
    private static int removeOwner(CommonAttribute commonAttribute, MdObject owner)
    {
        CommonAttributeContentItem existing = findByOwner(commonAttribute, owner);
        if (existing == null)
        {
            return 0;
        }
        commonAttribute.getContent().remove(existing);
        return 1;
    }

    /** Finds the content item for the given owner by {@code getMetadata()} identity, or null. */
    private static CommonAttributeContentItem findByOwner(CommonAttribute commonAttribute, MdObject owner)
    {
        for (CommonAttributeContentItem item : commonAttribute.getContent())
        {
            if (item.getMetadata() == owner)
            {
                return item;
            }
        }
        return null;
    }

    /** Re-fetches a content owner inside the write transaction by its (stable, top-object) BM id. */
    private static MdObject resolveOwnerInTx(IBmTransaction tx, long ownerBmId)
    {
        Object obj = tx.getObjectById(ownerBmId);
        return obj instanceof MdObject ? (MdObject)obj : null;
    }

    // ---- up-front planning / validation (pure aside from the shared resolver) -----------------

    /** A validated + resolved entry ready to apply inside the tx. */
    private static final class PlannedEntry
    {
        final boolean remove;
        /** The original FQN as supplied (for error messages). */
        final String fqn;
        /** The owner's BM object id, re-fetched inside the tx by identity (a common-attribute owner is
         * always a TOP object, so its {@code bmGetId()} is stable across the read/write boundary). */
        final long ownerBmId;
        /** The mapped use (null for a remove entry). */
        final CommonAttributeUse use;

        PlannedEntry(boolean remove, String fqn, long ownerBmId, CommonAttributeUse use)
        {
            this.remove = remove;
            this.fqn = fqn;
            this.ownerBmId = ownerBmId;
            this.use = use;
        }
    }

    /** A planned entry OR a ready JSON error from up-front validation / resolution. */
    private static final class EntryPlan
    {
        final PlannedEntry entry;
        final String error;

        private EntryPlan(PlannedEntry entry, String error)
        {
            this.entry = entry;
            this.error = error;
        }

        static EntryPlan ok(PlannedEntry entry)
        {
            return new EntryPlan(entry, null);
        }

        static EntryPlan failed(String error)
        {
            return new EntryPlan(null, error);
        }
    }

    /**
     * Validates + resolves a single {@code content[]} entry up front (BEFORE the tx): the op is
     * recognized (default {@code add}), a metadata FQN is present, the owner resolves via the shared
     * bilingual {@link MetadataNodeResolver#resolveExisting}, the owner's {@link EClass} is a legal
     * common-attribute owner, and (for an add) the {@code use} token maps to a literal. Returns a ready
     * {@link PlannedEntry} or a ready JSON error.
     */
    private static EntryPlan planEntry(Configuration config, JsonObject entry)
    {
        String op = contentOp(entry);
        if (!OP_ADD.equals(op) && !OP_REMOVE.equals(op))
        {
            return EntryPlan.failed(ToolResult.error("Each 'content' entry 'op' must be 'add' or " //$NON-NLS-1$
                + "'remove' (default 'add'); got '" + op + "'.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String fqn = str(entry.get("metadata")); //$NON-NLS-1$
        if (fqn == null || fqn.isEmpty())
        {
            return EntryPlan.failed(ToolResult.error("Each 'content' entry needs a 'metadata' owner " //$NON-NLS-1$
                + "FQN, e.g. 'Catalog.Products' (or the Russian " //$NON-NLS-1$
                + "'Справочник.Товары').") //$NON-NLS-1$
                .toJson());
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        if (node == null || node.object == null)
        {
            return EntryPlan.failed(ownerNotFound(config, fqn));
        }
        MdObject owner = node.object;
        if (!CommonAttributeUtil.isCommonAttributeOwnerClass(owner.eClass()))
        {
            return EntryPlan.failed(ToolResult.error("'" + fqn + "' (" + owner.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ") cannot be an owner of a common attribute. Only objects that support common " //$NON-NLS-1$
                + "attributes (e.g. Catalog / Document / InformationRegister) are valid content " //$NON-NLS-1$
                + "owners.").toJson()); //$NON-NLS-1$
        }

        CommonAttributeUse use = null;
        if (!OP_REMOVE.equals(op))
        {
            JsonElement useEl = entry.get("use"); //$NON-NLS-1$
            String useToken = str(useEl);
            use = mapUse(useToken);
            if (use == null)
            {
                return EntryPlan.failed(ToolResult.error("The 'use' value '" + useToken //$NON-NLS-1$
                    + "' is not valid; use one of 'Use' / 'DontUse' / 'Auto' (case-insensitive); " //$NON-NLS-1$
                    + "default is 'Use'.").toJson()); //$NON-NLS-1$
            }
        }
        // A common-attribute owner is a TOP object; capture its stable BM id so it is re-fetched by
        // identity inside the write transaction (tx.getObjectById) rather than re-resolved by FQN from
        // a container walk - a top object's eContainer() does not reliably climb to the Configuration.
        long ownerBmId = ((IBmObject)owner).bmGetId();
        return EntryPlan.ok(new PlannedEntry(OP_REMOVE.equals(op), fqn, ownerBmId, use));
    }

    // ---- pure helpers (unit-testable) ---------------------------------------------------------

    /** Normalizes a content op token to {@code add} / {@code remove}; default {@code add}. */
    static String contentOp(JsonObject entry)
    {
        String op = str(entry.get("op")); //$NON-NLS-1$
        if (op == null || op.trim().isEmpty())
        {
            return OP_ADD;
        }
        return op.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps a {@code use} token to a {@link CommonAttributeUse} case-insensitively through the
     * {@link MdClassPackage.Literals#COMMON_ATTRIBUTE_CONTENT_ITEM__USE} EAttribute's {@link EEnum}
     * literals (matched against each literal's display name - {@code Use} / {@code DontUse} /
     * {@code Auto}, which is what both {@code getLiteral()} and {@code getName()} return for this
     * enum); NOT {@code CommonAttributeUse.valueOf}. An omitted / blank token defaults to
     * {@link CommonAttributeUse#USE Use}. Returns {@code null} for an unrecognized token so the caller
     * fails with an actionable error.
     *
     * @param token the {@code use} token (may be null / blank)
     * @return the mapped {@link CommonAttributeUse}, or {@code null} when unrecognized
     */
    static CommonAttributeUse mapUse(String token)
    {
        if (token == null || token.trim().isEmpty())
        {
            return CommonAttributeUse.USE;
        }
        String wanted = token.trim();
        EAttribute useAttr = MdClassPackage.Literals.COMMON_ATTRIBUTE_CONTENT_ITEM__USE;
        if (!(useAttr.getEAttributeType() instanceof EEnum))
        {
            return null;
        }
        EEnum useEnum = (EEnum)useAttr.getEAttributeType();
        for (EEnumLiteral literal : useEnum.getELiterals())
        {
            if (wanted.equalsIgnoreCase(literal.getLiteral()) || wanted.equalsIgnoreCase(literal.getName()))
            {
                return (CommonAttributeUse)literal.getInstance();
            }
        }
        return null;
    }

    // ---- error builders (actionable) ----------------------------------------------------------

    private static String ownerNotFound(Configuration config, String fqn)
    {
        String norm = MetadataTypeUtils.normalizeFqn(fqn);
        String[] parts = norm.split("\\."); //$NON-NLS-1$
        String suggestion = ""; //$NON-NLS-1$
        if (parts.length >= 2)
        {
            List<String> similar = MetadataTypeUtils.findSimilarObjects(config, parts[0], parts[1], 5);
            if (!similar.isEmpty())
            {
                suggestion = " Did you mean: " + String.join(", ", similar) + "?"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return ToolResult.error("Owner not found for content entry: '" + fqn + "'. Use a valid FQN " //$NON-NLS-1$ //$NON-NLS-2$
            + "(e.g. 'Catalog.Products'); check with get_metadata_objects." + suggestion).toJson(); //$NON-NLS-1$
    }

    // ---- small typed helpers ------------------------------------------------------------------

    private static String str(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    // ---- internals ----------------------------------------------------------------------------

    /** Resolves the {@link IBmModel}, the MdPlugin {@link IModelObjectFactory} and the platform
     * {@link Version} for a project, or carries a ready JSON error. */
    private static final class Services
    {
        final IBmModel model;
        final IModelObjectFactory factory;
        final Version version;
        final String error;

        private Services(IBmModel model, IModelObjectFactory factory, Version version, String error)
        {
            this.model = model;
            this.factory = factory;
            this.version = version;
            this.error = error;
        }

        static Services resolve(IProject project)
        {
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
            IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
            if (bmModelManager == null || factory == null || v8ProjectManager == null)
            {
                return new Services(null, null, null, ToolResult.error("The metadata write services " //$NON-NLS-1$
                    + "are not available (IBmModelManager / IModelObjectFactory / IV8ProjectManager). " //$NON-NLS-1$
                    + "Ensure the EDT workbench is fully started, then retry.").toJson()); //$NON-NLS-1$
            }
            IV8Project v8Project = v8ProjectManager.getProject(project);
            if (v8Project == null)
            {
                return new Services(null, null, null, ToolResult.error("No 1C project for: " //$NON-NLS-1$
                    + project.getName()).toJson());
            }
            IBmModel model = bmModelManager.getModel(project);
            if (model == null)
            {
                return new Services(null, null, null, ToolResult.error("BM model not available for " //$NON-NLS-1$
                    + "project: " + project.getName()).toJson()); //$NON-NLS-1$
            }
            return new Services(model, factory, v8Project.getVersion(), null);
        }
    }

    /**
     * Carries a ready JSON error out of a deep apply step (resolution / transaction failure) so the
     * top-level {@link #apply} returns it verbatim. Unchecked so it crosses the BM task boundary; the
     * message is a validated {@link ToolResult#error} JSON string.
     */
    static final class ContentWriteException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final transient String errorJson;

        ContentWriteException(String errorJson)
        {
            super(errorJson);
            this.errorJson = errorJson;
        }

        String getErrorJson()
        {
            return errorJson;
        }
    }
}
