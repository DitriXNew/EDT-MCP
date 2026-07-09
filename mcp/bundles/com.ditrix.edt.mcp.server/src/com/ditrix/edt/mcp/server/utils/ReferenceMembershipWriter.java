/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicRegister;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogOwner;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Writes a top object's PLAIN reference-membership list (the {@code modify_metadata}
 * {@code content[]} branch for the kinds whose {@code content[]} is a bare list of references to other
 * top objects, with no wrapper item and no per-entry flag):
 * <ul>
 * <li>{@link Catalog#getOwners() Catalog.owners} - the catalog's owner objects ({@link CatalogOwner});</li>
 * <li>{@link Document#getRegisterRecords() Document.registerRecords} - the document's register records /
 * движения ({@link BasicRegister});</li>
 * <li>{@link Subsystem#getContent() Subsystem.content} - the objects that belong to the subsystem (any
 * top-level configuration object except a {@link Subsystem}, which nests via
 * {@link Subsystem#getSubsystems()}).</li>
 * </ul>
 * A single generic algorithm serves every list; the kinds only differ by the {@link Kind} descriptor
 * (which {@link EList} the top object exposes, which membership predicate a resolved object must satisfy,
 * and the English label / hint used in messages). Any {@code use} / {@code autoRecord} field on an entry
 * is IGNORED for these kinds - the list is plain references.
 *
 * <p>Each {@code content[]} entry is {@code {op?:"add"|"remove" (default add), metadata:"<FQN>"}}. The
 * whole payload is validated UP FRONT, BEFORE the write transaction (op recognized; a metadata FQN
 * required; the reference resolves via the shared bilingual
 * {@link MetadataNodeResolver#resolveExisting}; the resolved object implements the kind's required
 * reference interface), so a shape / resolution error leaves NOTHING written. The mutation then runs
 * entirely inside a single {@link BmTransactions#write write} boundary on the re-fetched top object,
 * with the top object AND every member re-fetched from the transaction by their (stable, top-object)
 * {@code bmGetId()} - NEVER via {@code eContainer} (the v1 live bug). Adds are idempotent (a member
 * already present by identity is a no-op, {@code added} stays {@code 0}); a remove of a member that is
 * not in the list is a clean error that rolls the whole write back (never a silent no-op). The writer
 * does NOT force-export; the caller ({@code ModifyMetadataTool}) does that once, outside any boundary,
 * after the commit.</p>
 *
 * <p>The parse / validate helpers are pure (no model, no UI) so they are unit-testable; the
 * model-touching {@link #apply} runs on the UI thread and goes only through the BM boundary.</p>
 */
public final class ReferenceMembershipWriter
{
    private ReferenceMembershipWriter()
    {
        // Utility class
    }

    /** Content op tokens. */
    private static final String OP_ADD = "add"; //$NON-NLS-1$
    private static final String OP_REMOVE = "remove"; //$NON-NLS-1$

    // ---- kinds --------------------------------------------------------------------------------

    /**
     * The plain reference-membership lists this writer serves. Each carries the {@link EList}
     * accessor on the owning top object, the {@link Predicate} a resolved member must satisfy, and the
     * English label / hint used in validation and error messages. The list element type is
     * {@link MdObject} ({@link CatalogOwner}, {@link BasicRegister} and every legal subsystem-content
     * object are {@code MdObject}s), so a single generic mutation works over any list.
     */
    public enum Kind
    {
        /** {@link Catalog#getOwners()} - the catalog's owner objects. */
        CATALOG_OWNERS(top -> asMdObjectList(((Catalog)top).getOwners()), CatalogOwner.class::isInstance, "owner", //$NON-NLS-1$
            "must be a catalog owner (a Catalog / ChartOfCharacteristicTypes / ChartOfAccounts / " //$NON-NLS-1$
                + "ChartOfCalculationTypes / ExchangePlan)"), //$NON-NLS-1$

        /** {@link Document#getRegisterRecords()} - the document's register records (движения). */
        DOCUMENT_REGISTER_RECORDS(top -> asMdObjectList(((Document)top).getRegisterRecords()),
            BasicRegister.class::isInstance, "register record", //$NON-NLS-1$
            "must be a register (InformationRegister / AccumulationRegister / AccountingRegister / " //$NON-NLS-1$
                + "CalculationRegister)"), //$NON-NLS-1$

        /**
         * {@link Subsystem#getContent()} - the top-level configuration objects that belong to the
         * subsystem. A member may be any object from the broad {@link #SUBSYSTEM_CONTENT_MEMBER_CLASSES}
         * allow-list, but NEVER a {@link Subsystem} (a nested subsystem is a child of
         * {@link Subsystem#getSubsystems()}, not a content member) - that exclusion is a hard rule
         * independent of the allow-list.
         */
        SUBSYSTEM_CONTENT(top -> asMdObjectList(((Subsystem)top).getContent()),
            ReferenceMembershipWriter::isSubsystemContentMember, "content object", //$NON-NLS-1$
            "must be a top-level configuration object that can belong to a subsystem (a Constant / " //$NON-NLS-1$
                + "Catalog / Document / Enum / Report / DataProcessor / register / CommonModule / Role / " //$NON-NLS-1$
                + "etc.), not a Subsystem (a nested subsystem is added through the parent subsystem's own " //$NON-NLS-1$
                + "Subsystems list, not its content)"); //$NON-NLS-1$

        /** Accessor of the top object's plain reference list (returns the LIVE {@link EList}). */
        private final Function<MdObject, EList<MdObject>> listAccessor;
        /** The predicate a resolved member must satisfy to be a legal element of this list. */
        private final Predicate<Object> memberPredicate;
        /** A short English noun for a member of this list (e.g. {@code owner}), for messages. */
        private final String memberLabel;
        /** A full English "must be a ..." clause naming the valid member kinds, for reject messages. */
        private final String requiredKindHint;

        Kind(Function<MdObject, EList<MdObject>> listAccessor, Predicate<Object> memberPredicate,
            String memberLabel, String requiredKindHint)
        {
            this.listAccessor = listAccessor;
            this.memberPredicate = memberPredicate;
            this.memberLabel = memberLabel;
            this.requiredKindHint = requiredKindHint;
        }

        /** The live reference {@link EList} on the given (in-transaction) top object. */
        EList<MdObject> list(MdObject top)
        {
            return listAccessor.apply(top);
        }

        /**
         * Whether the given resolved object is a legal member of this list (satisfies the kind's
         * membership predicate). This is the up-front reject predicate: a non-matching object
         * (e.g. an {@code Enum} FQN for {@link #CATALOG_OWNERS}, or a {@link Subsystem} for
         * {@link #SUBSYSTEM_CONTENT}) is rejected before any mutation. A {@code null} candidate is never
         * a member (every predicate here is null-safe).
         *
         * @param candidate the resolved object (may be {@code null})
         * @return {@code true} when {@code candidate} satisfies the kind's membership predicate
         */
        boolean accepts(Object candidate)
        {
            return memberPredicate.test(candidate);
        }

        /** The English noun for a member of this list (e.g. {@code owner}), for messages and tests. */
        String memberLabel()
        {
            return memberLabel;
        }

        /**
         * The full English "must be a ..." clause naming the valid member kinds, as embedded in an
         * up-front reject message. Exposed for messages and tests (so the corrected wording - which
         * lists only the real {@link CatalogOwner} kinds and must NOT name a Document / Task /
         * BusinessProcess - can be pinned).
         */
        String requiredKindHint()
        {
            return requiredKindHint;
        }

        /**
         * Views a concrete EMF reference list ({@code EList<CatalogOwner>} / {@code EList<BasicRegister>})
         * as an {@code EList<MdObject>} so a single generic mutation serves both. The cast is safe: the
         * element types {@link CatalogOwner} / {@link BasicRegister} are {@link MdObject}s, and every
         * object appended has already been validated to implement the list's required interface
         * (so no illegal element is ever added to the underlying EMF feature).
         */
        @SuppressWarnings("unchecked")
        private static EList<MdObject> asMdObjectList(EList<? extends MdObject> list)
        {
            return (EList<MdObject>)list;
        }
    }

    // ---- result -------------------------------------------------------------------------------

    /**
     * The outcome of applying a plain reference-membership {@code content[]} payload: either a JSON
     * {@code error} (an up-front validation / resolution failure means nothing was written) or the
     * counts of what was applied. {@link #updated} is ALWAYS {@code 0} for these kinds (the list has no
     * per-member flag to update), and is present only so the caller's success payload shape matches the
     * other {@code content[]} kinds.
     */
    public static final class Result
    {
        /** Non-null when the write failed / was rejected: a ready JSON error to return verbatim. */
        public final String error;
        /** Number of references added to the list. */
        public final int added;
        /** Always {@code 0} - a plain reference list has no per-member flag to update. */
        public final int updated;
        /** Number of references removed from the list. */
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

        static Result ok(int added, int removed)
        {
            return new Result(null, added, 0, removed);
        }

        public boolean hasError()
        {
            return error != null;
        }
    }

    // ---- entry point --------------------------------------------------------------------------

    /**
     * Applies a plain reference-membership {@code content[]} payload to the resolved top object.
     * Validates the whole payload up front (op, required metadata FQN, reference resolution + reference
     * kind) so a malformed / unresolvable / wrong-kind entry fails before ANY mutation, then mutates the
     * model inside a single {@link BmTransactions#write write} boundary on the re-fetched top object.
     * Runs on the UI thread; does NOT force-export (the caller does that once, outside any boundary).
     *
     * @param project the workspace project owning the top object
     * @param config the configuration (for bilingual reference resolution)
     * @param top the resolved top object (a {@link Catalog} for {@link Kind#CATALOG_OWNERS}, a
     *            {@link Document} for {@link Kind#DOCUMENT_REGISTER_RECORDS}, a {@link Subsystem} for
     *            {@link Kind#SUBSYSTEM_CONTENT})
     * @param content the parsed {@code content[]} entries (must not be empty)
     * @param kind which plain reference list to write
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    public static Result apply(IProject project, Configuration config, MdObject top, List<JsonObject> content,
        Kind kind)
    {
        if (content == null || content.isEmpty())
        {
            return Result.failed(ToolResult.error("Nothing to apply: provide at least one 'content' " //$NON-NLS-1$
                + "entry, e.g. {\"metadata\":\"Catalog.Products\"} to add a " + kind.memberLabel //$NON-NLS-1$
                + " or {\"op\":\"remove\",\"metadata\":\"Catalog.Products\"} to remove one.").toJson()); //$NON-NLS-1$
        }

        // Validate + resolve every entry up front so nothing is mutated on a shape / resolution error.
        List<PlannedEntry> plan = new ArrayList<>(); // NOSONAR plan is read inside the write lambda (capture)
        for (JsonObject entry : content)
        {
            EntryPlan resolved = planEntry(config, entry, kind);
            if (resolved.error != null)
            {
                return Result.failed(resolved.error);
            }
            plan.add(resolved.entry);
        }

        IBmModel model = resolveModel(project);
        if (model == null)
        {
            return Result.failed(ToolResult.error("The metadata write services are not available " //$NON-NLS-1$
                + "(IBmModelManager). Ensure the EDT workbench is fully started, then retry.").toJson()); //$NON-NLS-1$
        }

        long topBmId = ((IBmObject)top).bmGetId();
        try
        {
            return BmTransactions.write(model, "ModifyReferenceMembership", (tx, pm) -> //$NON-NLS-1$
            {
                Object inTxTop = tx.getObjectById(topBmId);
                if (!(inTxTop instanceof MdObject))
                {
                    throw new ContentWriteException(ToolResult.error("The metadata object could not " //$NON-NLS-1$
                        + "be resolved inside the transaction.").toJson());
                }
                MdObject topInTx = (MdObject)inTxTop;
                int added = 0;
                int removed = 0;
                for (PlannedEntry planned : plan)
                {
                    int[] delta = applyPlannedEntry(kind, tx, topInTx, planned);
                    added += delta[0];
                    removed += delta[1];
                }
                return Result.ok(added, removed);
            });
        }
        catch (ContentWriteException e)
        {
            return Result.failed(e.getErrorJson());
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to modify " + kind.memberLabel + " list for " //$NON-NLS-1$ //$NON-NLS-2$
                + top.getName(), e);
            return Result.failed(ToolResult.error("Failed to modify the " + kind.memberLabel //$NON-NLS-1$
                + " list: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    // ---- mutation (inside the write boundary) -------------------------------------------------

    /**
     * Applies a single validated {@link PlannedEntry} to the in-transaction top object: re-fetches the
     * referenced member by its stable BM id, then either removes it from the kind's list (a remove that
     * finds nothing is a clean error that rolls the whole write back) or appends it idempotently via
     * {@link #addRef}. MUST run inside the write boundary.
     *
     * @return a two-slot delta {@code [added, removed]}
     */
    private static int[] applyPlannedEntry(Kind kind, IBmTransaction tx, MdObject topInTx,
        PlannedEntry planned)
    {
        Object refObj = tx.getObjectById(planned.refBmId);
        if (!(refObj instanceof MdObject))
        {
            throw new ContentWriteException(ToolResult.error("The " + kind.memberLabel //$NON-NLS-1$
                + " '" + planned.fqn //$NON-NLS-1$
                + "' could not be resolved inside the transaction.").toJson()); //$NON-NLS-1$
        }
        MdObject ref = (MdObject)refObj;
        if (planned.remove)
        {
            if (!removeRef(kind, topInTx, ref))
            {
                throw new ContentWriteException(ToolResult.error("The " + kind.memberLabel //$NON-NLS-1$
                    + " '" + planned.fqn + "' is not in the " + kind.memberLabel //$NON-NLS-1$ //$NON-NLS-2$
                    + " list of '" + topInTx.getName() //$NON-NLS-1$
                    + "'; nothing to remove. Read the current list with " //$NON-NLS-1$
                    + "get_metadata_details on the object FQN.").toJson()); //$NON-NLS-1$
            }
            return new int[] {0, 1};
        }
        if (addRef(kind, topInTx, ref))
        {
            return new int[] {1, 0};
        }
        return new int[] {0, 0};
    }

    /**
     * Appends a reference to the kind's list if it is not already present by identity (idempotent). MUST
     * run inside the write boundary.
     *
     * @return {@code true} when the reference was appended, {@code false} when it was already present
     */
    private static boolean addRef(Kind kind, MdObject top, MdObject ref)
    {
        EList<MdObject> list = kind.list(top);
        if (containsByIdentity(list, ref))
        {
            // Idempotent re-add: the reference is already in the list - nothing to do.
            return false;
        }
        list.add(ref);
        return true;
    }

    /**
     * Removes a reference from the kind's list by identity, if present. MUST run inside the write
     * boundary. A {@code false} return (reference not in the list) is treated by the caller as a clean
     * error - removing a reference that is not listed is NOT a silent no-op - so the whole write rolls
     * back and nothing is applied.
     *
     * @return {@code true} when a reference was removed, {@code false} when it was not in the list
     */
    private static boolean removeRef(Kind kind, MdObject top, MdObject ref)
    {
        EList<MdObject> list = kind.list(top);
        MdObject existing = findByIdentity(list, ref);
        if (existing == null)
        {
            return false;
        }
        list.remove(existing);
        return true;
    }

    /** Whether the list already contains the given reference by object identity. */
    static boolean containsByIdentity(EList<MdObject> list, MdObject ref)
    {
        return findByIdentity(list, ref) != null;
    }

    /** The element of the list that is the given reference by object identity, or {@code null}. */
    private static MdObject findByIdentity(EList<MdObject> list, MdObject ref)
    {
        for (MdObject element : list)
        {
            if (element == ref)
            {
                return element;
            }
        }
        return null;
    }

    // ---- up-front planning / validation (pure aside from the shared resolver) -----------------

    /** A validated + resolved entry ready to apply inside the tx. */
    private static final class PlannedEntry
    {
        final boolean remove;
        /** The original FQN as supplied (for error messages). */
        final String fqn;
        /** The reference's BM object id, re-fetched inside the tx by identity (a member of one of these
         * lists is always a TOP object, so its {@code bmGetId()} is stable across the read/write
         * boundary). */
        final long refBmId;

        PlannedEntry(boolean remove, String fqn, long refBmId)
        {
            this.remove = remove;
            this.fqn = fqn;
            this.refBmId = refBmId;
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
     * recognized (default {@code add}), a metadata FQN is present, the reference resolves via the shared
     * bilingual {@link MetadataNodeResolver#resolveExisting}, and the resolved object satisfies the
     * kind's membership predicate. Any {@code use} / {@code autoRecord} field is ignored for
     * these kinds. Returns a ready {@link PlannedEntry} or a ready JSON error.
     */
    private static EntryPlan planEntry(Configuration config, JsonObject entry, Kind kind)
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
            return EntryPlan.failed(ToolResult.error("Each 'content' entry needs a 'metadata' " //$NON-NLS-1$
                + kind.memberLabel + " FQN, e.g. 'Catalog.Products' (or the Russian " //$NON-NLS-1$
                + "'Справочник.Товары').").toJson()); //$NON-NLS-1$
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        if (node == null || node.object == null)
        {
            return EntryPlan.failed(refNotFound(config, fqn, kind));
        }
        MdObject ref = node.object;
        if (!kind.accepts(ref))
        {
            return EntryPlan.failed(ToolResult.error("'" + fqn + "' (" + ref.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + ") " + kind.requiredKindHint + ".").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // A member of these lists is a TOP object; capture its stable BM id so it is re-fetched by
        // identity inside the write transaction (tx.getObjectById) rather than re-resolved by FQN from a
        // container walk - a top object's eContainer() does not reliably climb to the Configuration.
        long refBmId = ((IBmObject)ref).bmGetId();
        return EntryPlan.ok(new PlannedEntry(OP_REMOVE.equals(op), fqn, refBmId));
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
     * The top-level configuration object kinds that may be a member of a {@link Subsystem}'s content, as
     * an immutable set of {@link MdClassPackage.Literals} EClasses matched by identity. This is a broad,
     * documented allow-list of the top-level objects a subsystem may reference; the headless server must
     * NOT depend on EDT's UI-bundle subsystem picker, so the allowed kinds are enumerated explicitly
     * (mirroring the sibling {@link ExchangePlanContentWriter} precedent). It is deliberately extensible:
     * a missing valid kind is a trivial one-line follow-up. {@link MdClassPackage.Literals#SUBSYSTEM
     * Subsystem} is intentionally ABSENT - a nested subsystem is a child of
     * {@link Subsystem#getSubsystems()}, not a content member - but that exclusion is ALSO enforced as a
     * hard rule in {@link #isSubsystemContentMember} so it holds even if this set were ever changed.
     */
    private static final Set<EClass> SUBSYSTEM_CONTENT_MEMBER_CLASSES = Set.of(
        MdClassPackage.Literals.CONSTANT,
        MdClassPackage.Literals.CATALOG,
        MdClassPackage.Literals.DOCUMENT,
        MdClassPackage.Literals.DOCUMENT_JOURNAL,
        MdClassPackage.Literals.ENUM,
        MdClassPackage.Literals.REPORT,
        MdClassPackage.Literals.DATA_PROCESSOR,
        MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES,
        MdClassPackage.Literals.CHART_OF_ACCOUNTS,
        MdClassPackage.Literals.CHART_OF_CALCULATION_TYPES,
        MdClassPackage.Literals.INFORMATION_REGISTER,
        MdClassPackage.Literals.ACCUMULATION_REGISTER,
        MdClassPackage.Literals.ACCOUNTING_REGISTER,
        MdClassPackage.Literals.CALCULATION_REGISTER,
        MdClassPackage.Literals.BUSINESS_PROCESS,
        MdClassPackage.Literals.TASK,
        MdClassPackage.Literals.EXCHANGE_PLAN,
        MdClassPackage.Literals.COMMON_MODULE,
        MdClassPackage.Literals.COMMON_FORM,
        MdClassPackage.Literals.COMMON_COMMAND,
        MdClassPackage.Literals.COMMON_ATTRIBUTE,
        MdClassPackage.Literals.COMMON_PICTURE,
        MdClassPackage.Literals.COMMON_TEMPLATE,
        MdClassPackage.Literals.WEB_SERVICE,
        MdClassPackage.Literals.HTTP_SERVICE,
        MdClassPackage.Literals.WS_REFERENCE,
        MdClassPackage.Literals.FILTER_CRITERION,
        MdClassPackage.Literals.SETTINGS_STORAGE,
        MdClassPackage.Literals.SEQUENCE,
        MdClassPackage.Literals.SCHEDULED_JOB,
        MdClassPackage.Literals.FUNCTIONAL_OPTION,
        MdClassPackage.Literals.DEFINED_TYPE,
        MdClassPackage.Literals.ROLE,
        MdClassPackage.Literals.SESSION_PARAMETER,
        MdClassPackage.Literals.EVENT_SUBSCRIPTION);

    /**
     * Whether the given resolved object is a legal member of a {@link Subsystem}'s content: it is an
     * {@link EObject} whose {@link EClass} is in the {@link #SUBSYSTEM_CONTENT_MEMBER_CLASSES} allow-list
     * AND it is NOT a {@link Subsystem}. The {@link Subsystem}-self exclusion is a HARD rule applied
     * independently of the allow-list (a nested subsystem is added via the parent's
     * {@link Subsystem#getSubsystems()} list, never its content). Pure, null-safe and side-effect-free so
     * it serves as the up-front reject predicate for {@link Kind#SUBSYSTEM_CONTENT}, symmetric with the
     * sibling kinds' {@code instanceof} guards.
     *
     * @param candidate the resolved object (may be {@code null})
     * @return {@code true} when {@code candidate} is an allow-listed non-Subsystem content object
     */
    static boolean isSubsystemContentMember(Object candidate)
    {
        // Hard exclusion of Subsystem is independent of the allow-list (which already omits it).
        if (!(candidate instanceof EObject) || candidate instanceof Subsystem)
        {
            return false;
        }
        // The backing Set.of(...) is null-hostile, but a live EObject's eClass() is never null.
        return SUBSYSTEM_CONTENT_MEMBER_CLASSES.contains(((EObject)candidate).eClass());
    }

    // ---- error builders (actionable) ----------------------------------------------------------

    private static String refNotFound(Configuration config, String fqn, Kind kind)
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
        return ToolResult.error("The " + kind.memberLabel + " was not found for content entry: '" //$NON-NLS-1$ //$NON-NLS-2$
            + fqn + "'. Use a valid FQN (e.g. 'Catalog.Products'); check with " //$NON-NLS-1$
            + "get_metadata_objects." + suggestion).toJson(); //$NON-NLS-1$
    }

    // ---- small typed helpers ------------------------------------------------------------------

    private static String str(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    /** Resolves the {@link IBmModel} for a project, or {@code null} when the services are unavailable. */
    private static IBmModel resolveModel(IProject project)
    {
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return null;
        }
        return bmModelManager.getModel(project);
    }

    // ---- internals ----------------------------------------------------------------------------

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
