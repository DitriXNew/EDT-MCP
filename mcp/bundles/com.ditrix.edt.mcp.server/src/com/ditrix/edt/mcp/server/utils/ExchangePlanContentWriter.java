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
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.AutoRegistrationChanges;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlanContentItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Writes an {@link ExchangePlan}'s content list (the {@code modify_metadata} ExchangePlan
 * {@code content[]} branch): adds a metadata object to the exchange plan's content with an optional
 * {@link AutoRegistrationChanges autoRecord} flag, updates the {@code autoRecord} of an object already
 * present, or removes an object from the content.
 *
 * <p>Each {@code content[]} entry is {@code {op?:"add"|"remove" (default add), metadata:"Catalog.X",
 * autoRecord?:"Allow"|"Deny"}}. The {@code autoRecord} token is mapped case-insensitively through the
 * {@link MdClassPackage.Literals#EXCHANGE_PLAN_CONTENT_ITEM__AUTO_RECORD} EAttribute's {@link EEnum}
 * literals (NOT {@code AutoRegistrationChanges.valueOf}). Unlike the CommonAttribute {@code use} flag,
 * {@code autoRecord} is OPTIONAL: an omitted / blank token leaves the value UNSET (the EMF / platform
 * default), while an unknown token is rejected up front (listing {@code Allow} / {@code Deny}).</p>
 *
 * <p>The whole payload is validated UP FRONT, BEFORE the write transaction (op recognized; a metadata
 * FQN required; the object resolves via the shared bilingual
 * {@link MetadataNodeResolver#resolveExisting}; for an add, when supplied, the {@code autoRecord} token
 * maps to a literal), so a shape / resolution error leaves NOTHING written. The mutation then runs
 * entirely inside a single {@link BmTransactions#write write} boundary on the re-fetched exchange plan,
 * with every content object re-fetched from the IN-TRANSACTION model by its captured BM id (a content
 * object is a TOP object, so {@code tx.getObjectById(bmGetId())} is used - NEVER {@code eContainer()},
 * which does not reliably climb to the {@code Configuration}). New content items come ONLY from the
 * MdPlugin {@link IModelObjectFactory} (never {@code new} / {@code EcoreUtil}). The writer does NOT
 * force-export; the caller ({@code ModifyMetadataTool}) does that once, outside any boundary, after the
 * commit.</p>
 *
 * <p>The parse / map / validate helpers are pure (no model, no UI) so they are unit-testable; the
 * model-touching {@link #apply} runs on the UI thread and goes only through the BM boundary.</p>
 */
public final class ExchangePlanContentWriter
{
    private ExchangePlanContentWriter()
    {
        // Utility class
    }

    /** Content op tokens. */
    private static final String OP_ADD = "add"; //$NON-NLS-1$
    private static final String OP_REMOVE = "remove"; //$NON-NLS-1$

    // ---- result -------------------------------------------------------------------------------

    /**
     * The outcome of applying a {@code content[]} payload to an exchange plan: either a JSON
     * {@code error} (an up-front validation / resolution failure means nothing was written) or the
     * per-kind counts of what was applied (objects added, {@code autoRecord} updated on an existing
     * object, objects removed).
     */
    public static final class Result
    {
        /** Non-null when the write failed / was rejected: a ready JSON error to return verbatim. */
        public final String error;
        /** Number of objects added to the content. */
        public final int added;
        /** Number of existing objects whose {@code autoRecord} was updated. */
        public final int updated;
        /** Number of objects removed from the content. */
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
     * Applies a {@code content[]} payload to the resolved {@link ExchangePlan}. Validates the whole
     * payload up front (op, required metadata FQN, object resolution, and - when supplied - the
     * {@code autoRecord} token) so a malformed / unresolvable entry fails before ANY mutation, then
     * mutates the model inside a single {@link BmTransactions#write write} boundary on the re-fetched
     * exchange plan. Runs on the UI thread; does NOT force-export (the caller does that once, outside
     * any boundary).
     *
     * @param project the workspace project owning the exchange plan
     * @param config the configuration (for bilingual object resolution)
     * @param exchangePlan the resolved exchange plan top object
     * @param content the parsed {@code content[]} entries (must not be empty)
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    public static Result apply(IProject project, Configuration config, ExchangePlan exchangePlan,
        List<JsonObject> content)
    {
        if (content == null || content.isEmpty())
        {
            return Result.failed(ToolResult.error("Nothing to apply: provide at least one 'content' " //$NON-NLS-1$
                + "entry, e.g. {\"metadata\":\"Catalog.Products\",\"autoRecord\":\"Allow\"} to add an " //$NON-NLS-1$
                + "object or {\"op\":\"remove\",\"metadata\":\"Catalog.Products\"} to remove one.") //$NON-NLS-1$
                .toJson());
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

        long exchangePlanBmId = ((IBmObject)exchangePlan).bmGetId();
        try
        {
            return BmTransactions.write(services.model, "ModifyExchangePlanContent", (tx, pm) -> //$NON-NLS-1$
            {
                ExchangePlan inTx = (ExchangePlan)tx.getObjectById(exchangePlanBmId);
                if (inTx == null)
                {
                    throw new ContentWriteException(ToolResult.error("The exchange plan could not be " //$NON-NLS-1$
                        + "resolved inside the transaction.").toJson());
                }
                int added = 0;
                int updated = 0;
                int removed = 0;
                for (PlannedEntry planned : plan)
                {
                    MdObject mdObject = resolveObjectInTx(tx, planned.objectBmId);
                    if (mdObject == null)
                    {
                        throw new ContentWriteException(ToolResult.error("Object '" + planned.fqn //$NON-NLS-1$
                            + "' could not be resolved inside the transaction.").toJson()); //$NON-NLS-1$
                    }
                    if (planned.remove)
                    {
                        if (removeObject(inTx, mdObject) == 0)
                        {
                            throw new ContentWriteException(ToolResult.error("Object '" + planned.fqn //$NON-NLS-1$
                                + "' is not in the content list of exchange plan '" //$NON-NLS-1$
                                + inTx.getName() + "'; nothing to remove. Read the current content " //$NON-NLS-1$
                                + "with get_metadata_details on the exchange plan FQN.").toJson()); //$NON-NLS-1$
                        }
                        removed++;
                    }
                    else
                    {
                        int[] delta = addObject(inTx, mdObject, planned.autoRecord, services.factory,
                            services.version);
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
            Activator.logError("Failed to modify exchange plan content for " //$NON-NLS-1$
                + exchangePlan.getName(), e);
            return Result.failed(ToolResult.error("Failed to modify exchange plan content: " //$NON-NLS-1$
                + e.getMessage()).toJson());
        }
    }

    // ---- mutation (inside the write boundary) -------------------------------------------------

    /**
     * Adds an object to the content, or updates its {@code autoRecord} when already present. Scans
     * {@link ExchangePlan#getContent()} by {@link ExchangePlanContentItem#getMdObject()} identity: a
     * match is a no-op when no {@code autoRecord} token was supplied, or when the supplied value already
     * equals the current value (idempotent re-add - counted as neither an add nor an update); a match
     * whose {@code autoRecord} DIFFERS from a supplied value has it set (an update); otherwise a fresh
     * {@link ExchangePlanContentItem} is created via the MdPlugin {@link IModelObjectFactory}, its
     * object set (and {@code autoRecord} set only when a token was supplied - an omitted token leaves
     * the EMF default), and it is appended (an add). MUST run inside the write boundary.
     *
     * @param autoRecord the mapped {@link AutoRegistrationChanges}, or {@code null} when the token was
     *            omitted (leave / keep the current value)
     * @return a two-slot delta {@code [added, updated]}
     */
    private static int[] addObject(ExchangePlan exchangePlan, MdObject mdObject,
        AutoRegistrationChanges autoRecord, IModelObjectFactory factory, Version version)
    {
        ExchangePlanContentItem existing = findByObject(exchangePlan, mdObject);
        if (existing != null)
        {
            if (autoRecord == null || existing.getAutoRecord() == autoRecord)
            {
                // Idempotent re-add: no autoRecord requested, or the object already has the requested
                // value - nothing to do.
                return new int[] {0, 0};
            }
            existing.setAutoRecord(autoRecord);
            return new int[] {0, 1};
        }
        ExchangePlanContentItem item = (ExchangePlanContentItem)factory
            .create(MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM, version);
        if (item == null)
        {
            throw new ContentWriteException(ToolResult.error("The EDT factory cannot create an " //$NON-NLS-1$
                + "exchange plan content item.").toJson());
        }
        item.setMdObject(mdObject);
        if (autoRecord != null)
        {
            item.setAutoRecord(autoRecord);
        }
        exchangePlan.getContent().add(item);
        return new int[] {1, 0};
    }

    /**
     * Removes the content item whose {@link ExchangePlanContentItem#getMdObject()} is the given object
     * (by identity), if present. MUST run inside the write boundary. A {@code 0} return (object not in
     * the content) is treated by the caller as a clean error - removing an object that is not listed is
     * NOT a silent no-op - so the whole write rolls back and nothing is applied.
     *
     * @return {@code 1} when an item was removed, {@code 0} when the object was not in the content
     */
    private static int removeObject(ExchangePlan exchangePlan, MdObject mdObject)
    {
        ExchangePlanContentItem existing = findByObject(exchangePlan, mdObject);
        if (existing == null)
        {
            return 0;
        }
        exchangePlan.getContent().remove(existing);
        return 1;
    }

    /** Finds the content item for the given object by {@code getMdObject()} identity, or null. */
    private static ExchangePlanContentItem findByObject(ExchangePlan exchangePlan, MdObject mdObject)
    {
        for (ExchangePlanContentItem item : exchangePlan.getContent())
        {
            if (item.getMdObject() == mdObject)
            {
                return item;
            }
        }
        return null;
    }

    /** Re-fetches a content object inside the write transaction by its (stable, top-object) BM id. */
    private static MdObject resolveObjectInTx(IBmTransaction tx, long objectBmId)
    {
        Object obj = tx.getObjectById(objectBmId);
        return obj instanceof MdObject ? (MdObject)obj : null;
    }

    // ---- up-front planning / validation (pure aside from the shared resolver) -----------------

    /** A validated + resolved entry ready to apply inside the tx. */
    private static final class PlannedEntry
    {
        final boolean remove;
        /** The original FQN as supplied (for error messages). */
        final String fqn;
        /** The object's BM object id, re-fetched inside the tx by identity (an exchange plan content
         * object is always a TOP object, so its {@code bmGetId()} is stable across the read/write
         * boundary). */
        final long objectBmId;
        /** The mapped {@code autoRecord} (null for a remove entry, or for an add with the token
         * omitted - meaning leave the EMF default). */
        final AutoRegistrationChanges autoRecord;

        PlannedEntry(boolean remove, String fqn, long objectBmId, AutoRegistrationChanges autoRecord)
        {
            this.remove = remove;
            this.fqn = fqn;
            this.objectBmId = objectBmId;
            this.autoRecord = autoRecord;
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
     * recognized (default {@code add}), a metadata FQN is present, the object resolves via the shared
     * bilingual {@link MetadataNodeResolver#resolveExisting}, and (for an add, when supplied) the
     * {@code autoRecord} token maps to a literal. Returns a ready {@link PlannedEntry} or a ready JSON
     * error.
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
            return EntryPlan.failed(ToolResult.error("Each 'content' entry needs a 'metadata' object " //$NON-NLS-1$
                + "FQN, e.g. 'Catalog.Products' (or the Russian " //$NON-NLS-1$
                + "'Справочник.Товары').") //$NON-NLS-1$
                .toJson());
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        if (node == null || node.object == null)
        {
            return EntryPlan.failed(objectNotFound(config, fqn));
        }
        MdObject mdObject = node.object;

        AutoRegistrationChanges autoRecord = null;
        if (!OP_REMOVE.equals(op))
        {
            JsonElement autoRecordEl = entry.get("autoRecord"); //$NON-NLS-1$
            String autoRecordToken = str(autoRecordEl);
            if (!isAutoRecordOmitted(autoRecordToken))
            {
                autoRecord = mapAutoRecord(autoRecordToken);
                if (autoRecord == null)
                {
                    return EntryPlan.failed(ToolResult.error("The 'autoRecord' value '" //$NON-NLS-1$
                        + autoRecordToken + "' is not valid; use one of 'Allow' / 'Deny' " //$NON-NLS-1$
                        + "(case-insensitive). Omit 'autoRecord' to leave the platform default.") //$NON-NLS-1$
                        .toJson());
                }
            }
        }
        // An exchange plan content object is a TOP object; capture its stable BM id so it is re-fetched
        // by identity inside the write transaction (tx.getObjectById) rather than re-resolved by FQN
        // from a container walk - a top object's eContainer() does not reliably climb to the
        // Configuration.
        long objectBmId = ((IBmObject)mdObject).bmGetId();
        return EntryPlan.ok(new PlannedEntry(OP_REMOVE.equals(op), fqn, objectBmId, autoRecord));
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
     * Whether an {@code autoRecord} token is omitted (null / blank). Unlike the CommonAttribute
     * {@code use} flag, an omitted {@code autoRecord} does NOT map to a default literal: the caller
     * leaves the value UNSET (the EMF / platform default). Kept separate from {@link #mapAutoRecord} so
     * an omitted token ("leave unset") is distinguished from an unknown token ("reject").
     *
     * @param token the {@code autoRecord} token (may be null / blank)
     * @return {@code true} when the token is null / blank
     */
    static boolean isAutoRecordOmitted(String token)
    {
        return token == null || token.trim().isEmpty();
    }

    /**
     * Maps a NON-omitted {@code autoRecord} token to an {@link AutoRegistrationChanges} case-insensitively
     * through the {@link MdClassPackage.Literals#EXCHANGE_PLAN_CONTENT_ITEM__AUTO_RECORD} EAttribute's
     * {@link EEnum} literals (matched against each literal's display name - {@code Allow} / {@code Deny},
     * which is what both {@code getLiteral()} and {@code getName()} return for this enum); NOT
     * {@code AutoRegistrationChanges.valueOf}. Returns {@code null} for an unrecognized token so the
     * caller fails with an actionable error. Callers must first check {@link #isAutoRecordOmitted} - an
     * omitted token also returns {@code null} here, but MEANS "leave unset", not "reject".
     *
     * @param token the {@code autoRecord} token (should be non-blank; see {@link #isAutoRecordOmitted})
     * @return the mapped {@link AutoRegistrationChanges}, or {@code null} when unrecognized / omitted
     */
    static AutoRegistrationChanges mapAutoRecord(String token)
    {
        if (isAutoRecordOmitted(token))
        {
            return null;
        }
        String wanted = token.trim();
        EAttribute autoRecordAttr = MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM__AUTO_RECORD;
        if (!(autoRecordAttr.getEAttributeType() instanceof EEnum))
        {
            return null;
        }
        EEnum autoRecordEnum = (EEnum)autoRecordAttr.getEAttributeType();
        for (EEnumLiteral literal : autoRecordEnum.getELiterals())
        {
            if (wanted.equalsIgnoreCase(literal.getLiteral()) || wanted.equalsIgnoreCase(literal.getName()))
            {
                return (AutoRegistrationChanges)literal.getInstance();
            }
        }
        return null;
    }

    // ---- error builders (actionable) ----------------------------------------------------------

    private static String objectNotFound(Configuration config, String fqn)
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
        return ToolResult.error("Object not found for content entry: '" + fqn + "'. Use a valid FQN " //$NON-NLS-1$ //$NON-NLS-2$
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
