/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.event.IEventBroker;
import com._1c.g5.v8.dt.core.model.IModelObjectCollectionRuntimeOrderSorter;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.util.DbViewUtil;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractRoleDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.ObjectRight;
import com._1c.g5.v8.dt.rights.model.ObjectRights;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightsFactory;
import com._1c.g5.v8.dt.rights.model.Rls;
import com._1c.g5.v8.dt.rights.model.RoleDescription;
import com._1c.g5.v8.dt.rights.model.RestrictionTemplate;
import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;
import com._1c.g5.v8.dt.rights.tasks.AddRightValuesTask;
import com._1c.g5.v8.dt.rights.tasks.AddRlsTask;
import com._1c.g5.v8.dt.rights.tasks.AddRlsTemplateTask;
import com._1c.g5.v8.dt.rights.tasks.DeleteRlsTemplateTask;
import com._1c.g5.v8.dt.rights.tasks.EditRlsTask;
import com._1c.g5.v8.dt.rights.tasks.EditRlsTemplateTask;
import com._1c.g5.v8.dt.rights.tasks.SetIndependentRightsOfChildObjectsTask;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Writes a {@link Role}'s access rights (the {@code modify_metadata} Role branch): per-object right
 * VALUES, per-object Row-Level-Security (RLS) restriction conditions with optional per-field
 * resolution, RLS restriction TEMPLATES, and the three role properties.
 *
 * <p>The mutation is performed through the EDT-native BM tasks ({@code AddRightValuesTask},
 * {@code AddRlsTask} / {@code EditRlsTask}, {@code AddRlsTemplateTask} / {@code EditRlsTemplateTask}
 * / {@code DeleteRlsTemplateTask}, {@code SetIndependentRightsOfChildObjectsTask}) which each open
 * their own BM transaction ({@code bmModel.execute(task)}), never a hand-rolled model edit. The
 * caller ({@code ModifyMetadataTool}) then force-exports BOTH the {@code Role.<Name>} FQN AND the
 * {@link RoleDescription}'s OWN top-object FQN (carried out of the apply as {@link Result#rightsFqn}),
 * OUTSIDE any boundary, after every task has run: the rights matrix lives in its OWN BM resource
 * ({@code Rights.rights}), so exporting only the role FQN would drain {@code Role.mdo} but never
 * {@code Rights.rights}.</p>
 *
 * <p>The concrete rights matrix lives on {@link RoleDescription} (a subtype of the mdclass
 * {@link AbstractRoleDescription} the bare {@code Role.getRights()} may hold). Every rights task
 * requires the {@link RoleDescription} to already exist, so {@link #ensureRoleDescription} seeds one
 * in a {@link BmTransactions#write write} boundary before the tasks run when the role has none - and
 * REGISTERS it as a BM top object in that same boundary (see {@link #attachRoleDescription}); a
 * description that is only referenced and never attached fails the very next commit with
 * {@code Failed to persist reference value ...RoleDescriptionImpl@<hash>} (issue #452).</p>
 *
 * <p>The value / name / payload helpers are pure (no model, no UI) so they are unit-testable; the
 * model-touching apply methods run on the UI thread and go only through the BM boundary.</p>
 *
 * <p><b>Resolution-atomic {@code rights[]}; otherwise best effort.</b> Every {@code rights[]} entry is
 * fully resolved - object, right, value and RLS fields - before the first commit, and before a missing
 * role description is bootstrapped. A refusal from that resolution phase therefore writes nothing at
 * all. Applying the completed plan is still not one platform transaction: a rights task can fail
 * mid-batch, and {@code templates[]} / {@code roleProperties} resolve or run only after the bootstrap
 * and the rights plan has been applied. Those later failures can leave earlier entries committed; the
 * refusal reports the counts that had already been applied and tells the caller how to reconcile the
 * changed model.</p>
 */
public final class RoleRightsWriter
{
    private RoleRightsWriter()
    {
        // Utility class
    }

    /** Tri-state right value tokens accepted from the wire. */
    private static final String VAL_SET = "set"; //$NON-NLS-1$
    private static final String VAL_UNSET = "unset"; //$NON-NLS-1$
    private static final String VAL_PROVIDED = "provided"; //$NON-NLS-1$

    /** Template op tokens. */
    private static final String OP_ADD = "add"; //$NON-NLS-1$
    private static final String OP_EDIT = "edit"; //$NON-NLS-1$
    private static final String OP_DELETE = "delete"; //$NON-NLS-1$

    /** Wire (JSON payload) keys read from an entry - centralized so the apply and validate paths agree. */
    private static final String KEY_OBJECT = "object"; //$NON-NLS-1$
    private static final String KEY_RIGHT = "right"; //$NON-NLS-1$
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_CONDITION = "condition"; //$NON-NLS-1$

    // ---- result -------------------------------------------------------------------------------

    /**
     * The outcome of applying a role payload: either a JSON {@code error} or the per-section counts of
     * what was applied. An up-front validation error or any {@code rights[]} resolution refusal means
     * nothing was written. A later task, {@code templates[]} or {@code roleProperties} failure may
     * leave earlier entries applied; a failed result then carries their real counts and an error that
     * describes how to reconcile the changed model (see the class javadoc).
     */
    public static final class Result
    {
        /** Non-null when the write failed / was rejected: a ready JSON error to return verbatim. */
        public final String error;
        /**
         * Number of right-entry VALUES applied. On success their optional RLS tasks also completed;
         * on failure the last counted entry may be the value that committed immediately before its
         * RLS task failed.
         */
        public final int rights;
        /** Number of template operations applied. */
        public final int templates;
        /** Number of role-property booleans applied. */
        public final int roleProperties;
        /**
         * The {@link RoleDescription}'s OWN BM top-object FQN - the {@code Rights.rights} resource the
         * caller force-exports in addition to {@code Role.<Name>}. Produced by
         * {@link ITopObjectFqnGenerator} inside the write boundary that registered the description, so
         * it is available even for a description this call has just created (which has no readable
         * {@code bmGetFqn()} yet).
         *
         * <p>It is a NAME, and only a name. It answers "under what FQN should the export be
         * submitted", never "did this call write" - {@link #rightsModelWritten} answers that, and the
         * two are independent in BOTH directions. A refusal raised before the first task ran carries
         * a perfectly good FQN on a call that wrote nothing; a generator that could not answer for an
         * ALREADY-registered description leaves {@code null} on a call whose tasks then wrote plenty.
         * Gating the export on this field conflated the two and skipped the drain - and with it the
         * {@code WriteScope} declaration of issue #408 - for the second case.</p>
         *
         * <p>{@code null} therefore means only that no rights-resource FQN could be named: the
         * bootstrap never ran, it refused and restored what it found, or the generator produced
         * nothing (or threw) for a description that was already registered. The caller then exports
         * the role FQN alone and reports {@code persisted:false}.</p>
         */
        public final String rightsFqn;

        /**
         * Whether this call COMMITTED at least one write to the role's rights model - the bootstrap
         * attaching a fresh {@code Rights.rights} resource, or any right / RLS / template /
         * role-property task that ran to completion before the failure.
         *
         * <p>This, not {@link #rightsFqn}, is what a refusal is gated on. A refusal is not proof that
         * nothing happened: although every {@code rights[]} reference is resolved before the
         * bootstrap, entries are still applied one at a time, and templates / role properties run
         * later. A task failure can therefore refuse with earlier work already committed. The
         * force-export drains that work to disk AND records the project in the call's
         * {@code WriteScope} (issue #408), so a call that wrote without exporting would be declaring
         * that it changed nothing.</p>
         *
         * <p>Recorded only AFTER the transaction that made the write has returned: work that rolled
         * back is not work this call owes anyone a drain of.</p>
         */
        public final boolean rightsModelWritten;

        private Result(String error, int rights, int templates, int roleProperties, String rightsFqn, // NOSONAR cohesive per-call outcome; a holder would not improve clarity
            boolean rightsModelWritten)
        {
            this.error = error;
            this.rights = rights;
            this.templates = templates;
            this.roleProperties = roleProperties;
            this.rightsFqn = rightsFqn;
            this.rightsModelWritten = rightsModelWritten;
        }

        /** A refusal raised before anything could be written: nothing to name, nothing to drain. */
        static Result failed(String error)
        {
            return new Result(error, 0, 0, 0, null, false);
        }

        /**
         * A failed result that reports both what the call had already applied and whether any rights
         * model write committed, so the caller can explain the partial change, drain it and declare it
         * instead of leaving the model and the disk disagreeing. See {@link #rightsFqn} and
         * {@link #rightsModelWritten}.
         *
         * @param error the ready JSON error to return verbatim
         * @param rights number of committed right-entry values before the failure
         * @param templates number of completed template operations before the failure
         * @param roleProperties number of completed role-property changes before the failure
         * @param rightsFqn the FQN of the rights resource this call can name, or {@code null}
         * @param rightsModelWritten whether a write of this call had already committed
         * @return the failed result
         */
        static Result failed(String error, int rights, int templates, int roleProperties,
            String rightsFqn, boolean rightsModelWritten)
        {
            return new Result(error, rights, templates, roleProperties, rightsFqn,
                rightsModelWritten);
        }

        static Result ok(int rights, int templates, int roleProperties, String rightsFqn,
            boolean rightsModelWritten)
        {
            return new Result(null, rights, templates, roleProperties, rightsFqn, rightsModelWritten);
        }

        public boolean hasError()
        {
            return error != null;
        }
    }

    // ---- entry point --------------------------------------------------------------------------

    /**
     * Applies a role payload ({@code rights[]} / {@code templates[]} / {@code roleProperties}) to the
     * resolved {@link Role}. Runs on the UI thread (model resolution) and mutates the model only
     * through the BM tasks and the {@link #ensureRoleDescription} write boundary. Does NOT force-export
     * (the caller does that once, outside any boundary).
     *
     * @param project the workspace project owning the role
     * @param config the configuration (for bilingual object resolution)
     * @param role the resolved role top object
     * @param rights the parsed {@code rights[]} entries (may be empty)
     * @param templates the parsed {@code templates[]} entries (may be empty)
     * @param roleProperties the parsed {@code roleProperties} object, or {@code null}
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    public static Result apply(IProject project, Configuration config, Role role,
        List<JsonObject> rights, List<JsonObject> templates, JsonObject roleProperties)
    {
        if (rights.isEmpty() && templates.isEmpty() && roleProperties == null)
        {
            return Result.failed(ToolResult.error("Nothing to apply to the role: provide at least one " //$NON-NLS-1$
                + "of 'rights' (per-object right values / RLS), 'templates' (RLS restriction " //$NON-NLS-1$
                + "templates) or 'roleProperties'.").toJson()); //$NON-NLS-1$
        }

        String validationError = validatePayload(rights, templates, roleProperties);
        if (validationError != null)
        {
            return Result.failed(validationError);
        }

        IBmModelManagerHolder holder = IBmModelManagerHolder.resolve(project);
        if (holder.error != null)
        {
            return Result.failed(holder.error);
        }

        // Every service the apply needs is resolved HERE, once, on the calling thread - never inside a
        // BM transaction and never at a second resolution site. The FQN generator joined this block for
        // issue #452: the RoleDescription bootstrap must register the description as a BM top object,
        // and the canonical FQN for that registration comes from the generator alone.
        IRightInfosService rightInfos = Activator.getDefault().getRightInfosService();
        IEventBroker eventBroker = Activator.getDefault().getEventBroker();
        IModelObjectCollectionRuntimeOrderSorter sorter = Activator.getDefault().getCollectionOrderSorter();
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (rightInfos == null || eventBroker == null || sorter == null || fqnGenerator == null)
        {
            return Result.failed(ToolResult.error("The role-rights services are not available " //$NON-NLS-1$
                + "(IRightInfosService / IEventBroker / collection order sorter / top-object FQN " //$NON-NLS-1$
                + "generator). Ensure the EDT workbench is fully started, then retry.").toJson()); //$NON-NLS-1$
        }

        long roleBmId = ((IBmObject)role).bmGetId();

        Context ctx = new Context(project, config, holder.model, role, roleBmId, rightInfos, eventBroker,
            sorter);
        return applyResolved(ctx, fqnGenerator, rights, templates, roleProperties);
    }

    /**
     * The apply proper, with the payload already validated and every EDT service already resolved.
     *
     * <p>Package-visible on purpose: {@link #apply} reaches into {@link Activator} for its services
     * and therefore cannot run headless, while THIS is where the two things a refusal has to report
     * are decided - the rights FQN and whether anything was written. Both were previously
     * unreachable by a unit test, so a test could only hand-build the outcome DTO and would stay
     * green while the real path stopped reporting either.</p>
     *
     * @param ctx the resolved per-call context (also the ledger of what this call has committed)
     * @param fqnGenerator the top-object FQN generator
     * @param rights the parsed {@code rights[]} entries (may be empty)
     * @param templates the parsed {@code templates[]} entries (may be empty)
     * @param roleProperties the parsed {@code roleProperties} object, or {@code null}
     * @return a {@link Result} - check {@link Result#hasError()} first
     */
    static Result applyResolved(Context ctx, ITopObjectFqnGenerator fqnGenerator,
        List<JsonObject> rights, List<JsonObject> templates, JsonObject roleProperties)
    {
        // The role's Name is captured ONCE, here, inside a read boundary. Both places that need it -
        // the log line and the caller-facing refusal - sit in the catch blocks below, which run
        // AFTER the last BM task has finished, where role.getName() would be an EMF feature read on
        // the bare calling thread with no transaction open at all.
        String roleName = BmTransactions.read(ctx.model, "ReadRoleName", (tx, pm) -> ctx.role.getName()); //$NON-NLS-1$
        // Assigned by the bootstrap inside the try and read from BOTH catch blocks: when the
        // bootstrap could name the rights resource, every refusal raised after it still owes the
        // caller that name to export under (see Result#rightsFqn). WHETHER to export is a separate
        // question, answered by ctx.hasWritten() - see Result#rightsModelWritten.
        String rightsFqn = null;
        try
        {
            // Resolve the ENTIRE rights payload before the bootstrap commits anything. Neither the
            // right-info lookup nor the DB-view RLS field pool depends on RoleDescription, so a
            // resolution refusal can leave the role exactly as the call found it. Template resolution
            // genuinely needs the description and therefore remains after the bootstrap.
            List<RightPlan> rightsPlan = planRights(ctx, rights);

            // A RoleDescription must exist AND be registered as a BM top object before ANY rights task
            // runs (the tasks downcast Role.getRights() to RoleDescription without auto-creating it,
            // and a merely-referenced description fails the next commit - issue #452). Seed and attach
            // one in a write boundary when the role has none, then re-resolve the role so the tasks see
            // the concrete description. Its OWN FQN is carried out for the caller's force-export.
            // Inside the try so its RoleWriteException returns a clean single-level error.
            Bootstrap bootstrap = ensureRoleDescription(ctx, fqnGenerator);
            rightsFqn = bootstrap.fqn;

            applyRights(ctx, rightsPlan);
            applyTemplates(ctx, templates);
            applyRoleProperties(ctx, roleProperties);
            return Result.ok(ctx.appliedRights, ctx.appliedTemplates, ctx.appliedRoleProperties,
                rightsFqn, ctx.hasWritten());
        }
        catch (RoleWriteException e)
        {
            String error = reportPartialApplication(e.getErrorJson(), ctx.appliedRights,
                ctx.appliedTemplates, ctx.appliedRoleProperties);
            return Result.failed(error, ctx.appliedRights, ctx.appliedTemplates,
                ctx.appliedRoleProperties, rightsFqn, ctx.hasWritten());
        }
        catch (RuntimeException e)
        {
            // Any other runtime failure from the EDT tasks (an NPE, a DbView derived-data timing
            // failure, a task-internal failure surfaced by bmModel.execute) must still leave the tool
            // as a clean structured ToolResult error rather than escape to the JSON-RPC layer. The
            // message itself is built by applyFailure - a pure helper, because apply() resolves EDT
            // services and so cannot be reached headless, and the scrubbing it performs has to stay
            // pinnable by a unit test.
            Activator.logError("Failed to apply role rights to " + roleName, e); //$NON-NLS-1$
            String error = reportPartialApplication(applyFailure(roleName, e), ctx.appliedRights,
                ctx.appliedTemplates, ctx.appliedRoleProperties);
            return Result.failed(error, ctx.appliedRights, ctx.appliedTemplates,
                ctx.appliedRoleProperties, rightsFqn, ctx.hasWritten());
        }
    }

    // ---- rights[] -----------------------------------------------------------------------------

    /** Everything the apply phase needs for one fully resolved {@code rights[]} entry. */
    private static final class RightPlan
    {
        final EObject target;
        final Right right;
        final RightValue value;
        final String rls;
        final Collection<DbViewFieldDef> rlsFields;

        RightPlan(EObject target, Right right, RightValue value, String rls,
            Collection<DbViewFieldDef> rlsFields)
        {
            this.target = target;
            this.right = right;
            this.value = value;
            this.rls = rls;
            this.rlsFields = rlsFields;
        }
    }

    /**
     * Resolves every {@code rights[]} entry before any write: the target object, bilingual
     * {@link Right}, parsed value, and (when an RLS condition is present) its bilingual DB-view field
     * collection. The returned plan contains task inputs only and this method commits nothing.
     *
     * @param ctx the resolved per-call context
     * @param rights the validated {@code rights[]} payload
     * @return one fully resolved plan item per payload entry
     * @throws RoleWriteException when an object, right or requested RLS field does not resolve
     */
    private static List<RightPlan> planRights(Context ctx, List<JsonObject> rights)
    {
        List<RightPlan> plan = new ArrayList<>(rights.size());
        for (JsonObject entry : rights)
        {
            String objectFqn = str(entry.get(KEY_OBJECT));
            MdObject targetMd = resolveObject(ctx.config, objectFqn);
            if (targetMd == null)
            {
                throw new RoleWriteException(objectNotFound(ctx.config, objectFqn));
            }
            EObject target = targetMd;

            String rightName = str(entry.get(KEY_RIGHT));
            // The right is resolved by walking rightInfos.getRights(object) - a model read - so it runs
            // inside a read boundary; only the resolved Right handle (a task input) escapes.
            Right right = BmTransactions.read(ctx.model, "ResolveRight", //$NON-NLS-1$
                (tx, pm) -> resolveRight(ctx.rightInfos, target, rightName));
            if (right == null)
            {
                throw new RoleWriteException(BmTransactions.read(ctx.model, "RightNotFound", //$NON-NLS-1$
                    (tx, pm) -> rightNotFound(ctx.rightInfos, target, rightName, objectFqn)));
            }

            RightValue value = parseRightValue(entry.get(KEY_VALUE));
            String rls = emptyToNull(str(entry.get("rls"))); //$NON-NLS-1$
            Collection<DbViewFieldDef> fields = Collections.emptyList();
            if (rls != null)
            {
                List<String> fieldNames = strList(entry.get("rlsFields")); //$NON-NLS-1$
                // DbViewUtil.getRlsFields reads DB-view derived data - a model read - so resolve inside
                // a read boundary; only the resolved field collection (a task input) escapes.
                fields = BmTransactions.read(ctx.model, "ResolveRlsFields", //$NON-NLS-1$
                    (tx, pm) -> resolveRlsFields(targetMd, fieldNames));
                if (fields == null)
                {
                    throw new RoleWriteException(BmTransactions.read(ctx.model, "RlsFieldsNotFound", //$NON-NLS-1$
                        (tx, pm) -> rlsFieldsNotFound(targetMd, fieldNames)));
                }
            }
            plan.add(new RightPlan(target, right, value, rls, fields));
        }
        return plan;
    }

    /**
     * Applies a fully resolved rights plan. For each entry the value task runs first, followed by the
     * Add/Edit RLS task when the plan carries a condition; each task is its own
     * {@code bmModel.execute}.
     *
     * <p><b>The applied count is value tasks COMMITTED, not cells written.</b>
     * {@code AddRightValuesTask} goes through {@code RightsModelUtil.changeObjectRight}, which does
     * not author an {@code ObjectRight} whose value equals {@code getDefaultRightValue(object, role)}
     * - a cell that only restates the default is pruned. The role-wide flags decide that default
     * ({@code setForNewObjects} for a top object, {@code setForAttributesByDefault} for an attribute /
     * tabular section / dimension / resource), and a role bootstrapped by
     * {@link #attachRoleDescription} has BOTH {@code false}, so on such a role a top-object entry
     * whose value is the default lands nowhere while this method still counts it. What actually
     * landed is read back with {@code get_metadata_details}; the count says what the payload asked
     * for. Note also the order inside a single {@link #apply}: this method runs BEFORE
     * {@link #applyRoleProperties}, so a flag sent in the same call does not change the default the
     * cells were measured against - send it in an earlier call.</p>
     */
    private static void applyRights(Context ctx, List<RightPlan> rights)
    {
        for (RightPlan entry : rights)
        {
            setRightValue(ctx, entry.target, entry.right, entry.value);
            // Count the value as soon as its own task commits. The optional RLS task intentionally
            // follows it and can fail; retaining this count is how that residual non-atomic window
            // reports the right value that really landed.
            ctx.appliedRights++;
            if (entry.rls != null)
            {
                applyRls(ctx, entry);
            }
        }
    }

    /** Runs {@code AddRightValuesTask} for a single object + right + value. */
    private static void setRightValue(Context ctx, EObject target, Right right, RightValue value)
    {
        Map<Right, RightValue> rightToValue = new LinkedHashMap<>();
        rightToValue.put(right, value);
        Map<EObject, Map<Right, RightValue>> objectToRights = new LinkedHashMap<>();
        objectToRights.put(target, rightToValue);
        Map<Role, Map<EObject, Map<Right, RightValue>>> roleMap = new LinkedHashMap<>();
        roleMap.put(ctx.role, objectToRights);

        IBmTask<?> task = AddRightValuesTask.create(roleMap, ctx.project, ctx.eventBroker, ctx.sorter);
        ctx.execute(task);
    }

    /**
     * Applies the already-resolved RLS restriction for one object + right. The existing-RLS Add/Edit
     * decision runs INSIDE a {@link BmTransactions#read read} boundary that re-resolves the role by BM
     * id; only the resolved handles the task consumes escape the boundary.
     */
    private static void applyRls(Context ctx, RightPlan entry)
    {
        // Resolve the role description and the Add/Edit decision INSIDE a read boundary (re-fetching the
        // role by bm id), so getRights()/getRestrictionsByCondition() are never walked on the bare
        // calling thread. Deliberately keep this RlsPlan in the APPLY phase: an earlier entry in the
        // same batch can add or edit the RLS state on which this Add-vs-Edit decision depends.
        RlsPlan plan = BmTransactions.read(ctx.model, "ResolveRlsPlan", (tx, pm) -> //$NON-NLS-1$
        {
            RoleDescription roleDescription = roleDescriptionInTx(tx, ctx.roleBmId);
            Rls existing = findExistingRls(roleDescription, entry.target, entry.right);
            return new RlsPlan(roleDescription, existing);
        });

        IBmTask<?> task;
        if (plan.existing != null)
        {
            task = EditRlsTask.create(plan.existing, entry.rlsFields, entry.rls, ctx.project,
                ctx.eventBroker);
        }
        else
        {
            task = AddRlsTask.create(plan.roleDescription, entry.target, entry.right, entry.rlsFields,
                entry.rls, ctx.project, ctx.eventBroker, ctx.sorter);
        }
        ctx.execute(task);
    }

    /** The RoleDescription + optional existing Rls resolved for one RLS entry (both task inputs). */
    private static final class RlsPlan
    {
        final RoleDescription roleDescription;
        final Rls existing;

        RlsPlan(RoleDescription roleDescription, Rls existing)
        {
            this.roleDescription = roleDescription;
            this.existing = existing;
        }
    }

    /**
     * Finds an existing {@link Rls} for the given object + right on the role description, or null. MUST
     * be called inside a read boundary (it walks EMF containment references).
     */
    private static Rls findExistingRls(RoleDescription roleDescription, EObject target, Right right)
    {
        if (roleDescription == null)
        {
            return null;
        }
        ObjectRights objectRights =
            RightsModelUtil.filterObjectRightsByEObjectFastly(target, roleDescription);
        if (objectRights == null)
        {
            return null;
        }
        for (ObjectRight objectRight : objectRights.getRights())
        {
            if (RightsModelUtil.isSameRights(right, objectRight.getRight())
                && !objectRight.getRestrictionsByCondition().isEmpty())
            {
                return objectRight.getRestrictionsByCondition().get(0);
            }
        }
        return null;
    }

    // ---- templates[] --------------------------------------------------------------------------

    /**
     * Applies every {@code templates[]} entry: {@code add} / {@code edit} / {@code delete} an RLS
     * restriction template on the role description, each via its own BM task.
     */
    private static void applyTemplates(Context ctx, List<JsonObject> templates)
    {
        if (templates.isEmpty())
        {
            return;
        }
        for (JsonObject entry : templates)
        {
            String op = templateOp(entry);
            String name = str(entry.get(KEY_NAME));
            String condition = str(entry.get(KEY_CONDITION));
            // Resolve the role description (and, for edit/delete, the named template) INSIDE a read
            // boundary that re-fetches by bm id, so the template collection is never walked outside a
            // boundary; only the handles the task consumes escape.
            IBmTask<?> task = BmTransactions.read(ctx.model, "ResolveTemplateTask", (tx, pm) -> //$NON-NLS-1$
            {
                RoleDescription roleDescription = roleDescriptionInTx(tx, ctx.roleBmId);
                return buildTemplateTask(ctx, roleDescription, op, name, condition);
            });
            ctx.execute(task);
            ctx.appliedTemplates++;
        }
    }

    /**
     * Builds the add / edit / delete template task for one entry (validated already). MUST be called
     * inside a read boundary: it walks {@code roleDescription.getTemplates()} for edit / delete.
     */
    private static IBmTask<?> buildTemplateTask(Context ctx, RoleDescription roleDescription, String op,
        String name, String condition)
    {
        if (OP_ADD.equals(op))
        {
            return AddRlsTemplateTask.create(roleDescription, name, condition, ctx.project,
                ctx.eventBroker);
        }
        RestrictionTemplate template = findTemplate(roleDescription, name);
        if (template == null)
        {
            throw new RoleWriteException(templateNotFound(roleDescription, name));
        }
        if (OP_DELETE.equals(op))
        {
            return DeleteRlsTemplateTask.create(roleDescription, template, ctx.project, ctx.eventBroker);
        }
        return EditRlsTemplateTask.create(template, name, condition, ctx.project, ctx.eventBroker);
    }

    /**
     * Finds a named restriction template on the role description (case-insensitive), or null. MUST be
     * called inside a read boundary (it walks {@code getTemplates()}).
     */
    private static RestrictionTemplate findTemplate(RoleDescription roleDescription, String name)
    {
        if (roleDescription == null || name == null)
        {
            return null;
        }
        for (RestrictionTemplate template : roleDescription.getTemplates())
        {
            if (name.equalsIgnoreCase(template.getName()))
            {
                return template;
            }
        }
        return null;
    }

    // ---- roleProperties -----------------------------------------------------------------------

    /**
     * Applies the three role properties. {@code independentRightsOfChildObjects} goes through the
     * EDT-native {@code SetIndependentRightsOfChildObjectsTask}; the two "for new objects" /
     * "for attributes by default" flags use the direct {@link RoleDescription} boolean setters (the
     * E4 fallback) inside a write boundary, because their native tasks
     * ({@code SetSetRightsForNewObjectsTask} / {@code SetSetRightsForAttributesByDefaultTask}) require
     * extra constructor dependencies ({@code IBmEmfIndexManager} / {@code IQualifiedNameProvider} /
     * per-EClass right suppliers) that are not wired here; the flags themselves are the whole change.
     */
    private static void applyRoleProperties(Context ctx, JsonObject roleProperties)
    {
        if (roleProperties == null)
        {
            return;
        }
        Boolean setForNewObjects = boolProp(roleProperties, "setForNewObjects"); //$NON-NLS-1$
        Boolean setForAttributesByDefault = boolProp(roleProperties, "setForAttributesByDefault"); //$NON-NLS-1$
        Boolean independentRights = boolProp(roleProperties, "independentRightsOfChildObjects"); //$NON-NLS-1$

        if (independentRights != null)
        {
            // Resolve the role description by bm id inside a read boundary; the task then re-opens its
            // own write tx. No EMF feature is read on the bare calling thread.
            RoleDescription roleDescription = BmTransactions.read(ctx.model, "ReadRoleDescription", //$NON-NLS-1$
                (tx, pm) -> roleDescriptionInTx(tx, ctx.roleBmId));
            ctx.execute(
                SetIndependentRightsOfChildObjectsTask.create(roleDescription, independentRights));
            ctx.appliedRoleProperties++;
        }
        if (setForNewObjects != null)
        {
            setBooleanRoleProperty(ctx, RolePropertyKind.FOR_NEW_OBJECTS, setForNewObjects);
            ctx.appliedRoleProperties++;
        }
        if (setForAttributesByDefault != null)
        {
            setBooleanRoleProperty(ctx, RolePropertyKind.FOR_ATTRIBUTES_BY_DEFAULT,
                setForAttributesByDefault);
            ctx.appliedRoleProperties++;
        }
    }

    /** The two role flags set via the direct RoleDescription setter (E4 fallback). */
    private enum RolePropertyKind
    {
        FOR_NEW_OBJECTS, FOR_ATTRIBUTES_BY_DEFAULT
    }

    /**
     * Sets one boolean role flag via the direct {@link RoleDescription} setter inside a write boundary
     * (the E4 fallback - see {@link #applyRoleProperties}). The role description is re-fetched by bmId
     * inside the transaction.
     */
    private static void setBooleanRoleProperty(Context ctx, RolePropertyKind kind, boolean value)
    {
        BmTransactions.<Void>write(ctx.model, "SetRoleProperty", (tx, pm) -> //$NON-NLS-1$
        {
            Role inTx = (Role)tx.getObjectById(ctx.roleBmId);
            if (inTx == null || !(inTx.getRights() instanceof RoleDescription))
            {
                throw new RoleWriteException(ToolResult.error("The role description could not be " //$NON-NLS-1$
                    + "resolved inside the transaction.").toJson());
            }
            RoleDescription roleDescription = (RoleDescription)inTx.getRights();
            if (kind == RolePropertyKind.FOR_NEW_OBJECTS)
            {
                roleDescription.setSetForNewObjects(value);
            }
            else
            {
                roleDescription.setSetForAttributesByDefault(value);
            }
            return null;
        });
        // Not routed through Context.execute: this one is our OWN write boundary rather than a
        // platform task, but it commits exactly the same kind of change and owes the same drain.
        ctx.recordWrite();
    }

    // ---- role description bootstrap ------------------------------------------------------------

    /** What the RoleDescription bootstrap ended up with: a name to export under, and a fact. */
    static final class Bootstrap
    {
        /** The description's own top-object FQN, or {@code null} when none could be produced. */
        final String fqn;
        /** Whether the bootstrap ATTACHED a fresh description, i.e. whether it wrote. */
        final boolean wrote;

        Bootstrap(String fqn, boolean wrote)
        {
            this.fqn = fqn;
            this.wrote = wrote;
        }
    }

    /**
     * Ensures the role has a concrete {@link RoleDescription} (the rights tasks downcast
     * {@code Role.getRights()} to it and do not auto-create it) and that the description is a
     * REGISTERED BM top object, then reports that description's own top-object FQN and whether a
     * fresh one had to be attached.
     *
     * <p>The two halves are reported separately because they are independent. The reuse branch
     * writes nothing yet may fail to produce an FQN; the fresh branch writes and always produces
     * one. Answering "did this call write" with "is the FQN non-null" gets BOTH cases wrong - see
     * {@link Result#rightsModelWritten}.</p>
     *
     * <p>Whether a fresh description was attached is decided INSIDE the boundary (the role now
     * points somewhere other than it did) but recorded by the caller OUTSIDE it, once the
     * transaction has returned: a write that rolled back is not one this call owes a drain of.</p>
     *
     * @param ctx the per-call context (its model and role bm id)
     * @param fqnGenerator the top-object FQN generator, resolved by the caller on the calling thread
     * @return the bootstrap outcome; {@link Bootstrap#fqn} is {@code null} when an already-registered
     *     description's FQN could not be produced (the caller then exports only the role FQN and
     *     reports {@code persisted:false})
     */
    static Bootstrap ensureRoleDescription(Context ctx, ITopObjectFqnGenerator fqnGenerator)
    {
        Bootstrap bootstrap = BmTransactions.<Bootstrap>write(ctx.model, "EnsureRoleDescription", //$NON-NLS-1$
            (tx, pm) ->
            {
                Role inTx = (Role)tx.getObjectById(ctx.roleBmId);
                if (inTx == null)
                {
                    throw new RoleWriteException(ToolResult.error("The role could not be resolved " //$NON-NLS-1$
                        + "inside the transaction.").toJson());
                }
                AbstractRoleDescription before = inTx.getRights();
                String fqn = attachRoleDescription(tx, inTx, fqnGenerator);
                // Every branch that refuses restores the reference and THROWS, so a returned
                // reference that changed can only be the fresh description this bootstrap attached.
                return new Bootstrap(fqn, inTx.getRights() != before);
            });
        if (bootstrap.wrote)
        {
            ctx.recordWrite();
        }
        return bootstrap;
    }

    /**
     * Gives the role a concrete {@link RoleDescription} that is a REGISTERED BM top object, and returns
     * that description's own top-object FQN. Runs INSIDE the caller's write transaction; touches no
     * service and no model beyond the role handed to it, so it is unit-testable.
     *
     * <p><b>Why the attach exists at all (issue #452).</b> {@code Role.rights} is a {@code refers}
     * (non-containment) reference, and {@link RoleDescription}'s impl is an
     * {@link IBmObject} in its OWN BM resource. A description that is only
     * created by {@link RightsFactory} and assigned to the role has no BM namespace, so the
     * transaction's reference factory cannot build a persistable reference to it and the FIRST commit
     * that carries it dies with {@code Failed to persist reference value
     * ...RoleDescriptionImpl@<hash>}. Registering it with
     * {@link IBmTransaction#attachTopObject(IBmObject, String) attachTopObject} in the SAME transaction
     * that sets the reference is the cure - the same shape {@code XdtoWriter.resolvePackageContent} and
     * {@code FormElementWriter.createCommonFormContent} already use for their content objects.</p>
     *
     * <p><b>The order is load-bearing, not cosmetic.</b> The reference is pointed at the description
     * FIRST, the FQN is generated SECOND, the attach happens THIRD. Generating the FQN before the
     * reference is set makes {@code attachTopObject} <i>appear</i> to succeed - no exception, the
     * reference reads back fine within the model - while never durably registering the object under
     * that FQN; that exact regression is written up in {@code XdtoWriter.resolvePackageContent}.</p>
     *
     * <p><b>The FQN comes from the generator, never from the object and never from the name.</b>
     * {@code bmGetFqn()} is not called here and must not be reintroduced: it throws
     * {@code BmAssertionException} on an object the same transaction has just attached, which would
     * turn already-applied work into a reported failure. The measured registry string
     * {@code Role.<Name>.Rights} is this generator's OUTPUT, not an input - do not concatenate it.</p>
     *
     * <p><b>The reuse guard is "BM has attached it AND it is a top object"</b> - not
     * {@code instanceof RoleDescription} alone, and not {@code bmIsTop()} alone: a bare
     * {@link AbstractRoleDescription}, and a concrete description BM never registered, both need a
     * fresh, attached one. {@code bmIsTransient()} is asked FIRST, and it is not decoration - it is
     * the only half of the predicate that can see the #452 state. (Measured on EDT 2026.2,
     * {@code com._1c.g5.v8.bm.core.BmObject}: {@code bmIsTop()} is
     * {@code bmIsTransient() ? eContainer() == null : isFullTopObjectId(id)}, and {@code Role.rights}
     * is declared {@code refers} - a NON-containment reference - so {@code setRights} never gives the
     * description an {@code eContainer}. A description that was created and assigned but never
     * attached therefore answers {@code bmIsTop() == true}, which is exactly the unpersistable value
     * this bootstrap exists to replace; trusting {@code bmIsTop()} alone would hand it straight back
     * to the next commit.)</p>
     *
     * <p><b>An FQN collision is refused, never resolved.</b> The disk importer registers exactly this
     * FQN too, and stale {@code .Rights} entries outlive their roles. Adopting the incumbent would
     * silently rewire the role's matrix to an object we did not create; detaching it would destroy an
     * existing matrix. Refusing does neither. What the refusal may SAY is bounded by what was
     * observed: {@code tx.getTopObjectByFqn(fqn) != null} shows the name is taken, and nothing more -
     * it does not tell a stale leftover apart from a live registration this role's reference has not
     * resolved to yet, so the message offers {@code clean_project} as the remedy for the stale case
     * instead of asserting that the case IS stale. For the same reason it names no OWNER for the
     * incumbent: the reading taken shows that the name is taken, never whose it is, and the likeliest
     * occupant is this role's OWN {@code .Rights} entry rather than a foreign object.</p>
     *
     * @param tx the active write transaction
     * @param inTx the role, re-fetched inside {@code tx}
     * @param fqnGenerator the top-object FQN generator
     * @return the description's own top-object FQN, or {@code null} when an ALREADY-registered
     *     description's FQN could not be produced - whether the generator returned nothing OR threw
     *     (nothing was mutated on that branch, so the write stands and only the rights-resource export
     *     is lost); a FRESH description that cannot be given an FQN is refused instead, because there
     *     the reference has already been repointed and leaving it is the unpersistable #452 state
     */
    static String attachRoleDescription(IBmTransaction tx, Role inTx, ITopObjectFqnGenerator fqnGenerator)
    {
        AbstractRoleDescription current = inTx.getRights();
        if (current instanceof RoleDescription && current instanceof IBmObject
            && !((IBmObject)current).bmIsTransient() && ((IBmObject)current).bmIsTop())
        {
            // Already registered: the role's reference ALREADY points at it, so the generator sees the
            // same owner -> property link the fresh branch builds and reproduces the same FQN.
            //
            // A generator failure on THIS branch costs only the export, never the write - and that has
            // to hold for a THROW exactly as it holds for an empty return. The md delegate reports an
            // unresolvable owner by throwing, and an unconverted throw would leave this method, leave
            // ensureRoleDescription, and land in apply()'s generic RuntimeException catch, which
            // refuses the whole call - before applyRights / applyTemplates / applyRoleProperties have
            // run - on the path EVERY role with an existing rights model takes. Degrade to null: the
            // caller then exports the role FQN alone and reports persisted:false.
            try
            {
                return emptyToNull(fqnGenerator.generateExternalPropertyFqn(inTx,
                    MdClassPackage.Literals.ROLE__RIGHTS));
            }
            catch (RuntimeException e)
            {
                Activator.logError("Could not generate the rights model FQN of the already-registered " //$NON-NLS-1$
                    + "rights model of role " + inTx.getName() //$NON-NLS-1$
                    + "; the rights write stands, only its Rights.rights export is skipped", e); //$NON-NLS-1$
                return null;
            }
        }

        AbstractRoleDescription previous = current;
        RoleDescription roleDescription = RightsFactory.eINSTANCE.createRoleDescription();
        // (1) point the reference at the fresh description...
        inTx.setRights(roleDescription);

        String fqn;
        try
        {
            // (2) ...THEN derive its canonical FQN from the owner. The md delegate reports an
            // unresolvable owner by throwing, and that text embeds the owner's toString() - a second
            // identity leak - so the throw is converted here, not propagated.
            fqn = emptyToNull(fqnGenerator.generateExternalPropertyFqn(inTx,
                MdClassPackage.Literals.ROLE__RIGHTS));
        }
        catch (RuntimeException e)
        {
            inTx.setRights(previous);
            throw new RoleWriteException(rightsFqnUnavailable(inTx, e));
        }
        if (fqn == null)
        {
            inTx.setRights(previous);
            throw new RoleWriteException(rightsFqnUnavailable(inTx, null));
        }

        // The incumbent can never be OUR fresh description (that one is unattached) - and that is the
        // ONLY thing established here. What IS registered under the FQN stays unknown: a stale
        // leftover, a foreign object, or this role's own live '.Rights' entry its transient reference
        // has not resolved to. Refuse without naming which: adopting the incumbent would silently
        // change what the role grants, detaching it would discard a matrix we did not author.
        IBmObject incumbent = tx.getTopObjectByFqn(fqn);
        if (incumbent != null)
        {
            inTx.setRights(previous);
            throw new RoleWriteException(ToolResult.error("The rights model of role '" //$NON-NLS-1$
                + inTx.getName() + "' cannot be created: '" + fqn + "' is already registered in " //$NON-NLS-1$ //$NON-NLS-2$
                + "this project's model, so this role's rights model cannot be attached under it. " //$NON-NLS-1$
                + "This call did not determine why that FQN is taken: if the " //$NON-NLS-1$
                + "registration is stale (a rights model whose role no longer exists on disk), run " //$NON-NLS-1$
                + "clean_project on the project and retry the same call; otherwise re-read the role " //$NON-NLS-1$
                + "with get_metadata_details first.").toJson()); //$NON-NLS-1$
        }

        // (3) ...and only now register it. A role whose reference was set but whose attach did not
        // happen must never survive this method - every failure above restores the previous reference.
        tx.attachTopObject((IBmObject)roleDescription, fqn);
        return fqn;
    }

    /**
     * The refusal used when the rights-model FQN cannot be produced for a role. Any platform text
     * carried by {@code cause} is scrubbed of EMF implementation identities first: the md delegate
     * reports an unresolvable owner by embedding its {@code toString()}, and an
     * {@code ...Impl@<hash>} tells the caller nothing while the simple type name is the diagnosis.
     */
    private static String rightsFqnUnavailable(Role inTx, RuntimeException cause)
    {
        String detail = cause == null ? "the generator produced no FQN" //$NON-NLS-1$
            : PlatformFailures.withoutObjectIdentity(PlatformFailures.describe(cause));
        return ToolResult.error("Could not generate the rights model FQN for role '" + inTx.getName() //$NON-NLS-1$
            + "' (" + detail + "), so its rights model was not created and nothing was written. The " //$NON-NLS-1$ //$NON-NLS-2$
            + "role is most likely not resolvable in the configuration tree: re-read it with " //$NON-NLS-1$
            + "get_metadata_details, then retry.").toJson(); //$NON-NLS-1$
    }

    /**
     * The refusal used when a rights task fails in a way the writer does not recognise - the
     * catch-all of {@link #apply}. It is a separate helper for one reason: {@code apply} resolves EDT
     * services and cannot run headless, while THIS text is the one place the #452 commit failure
     * ("Failed to persist reference value ...RoleDescriptionImpl@&lt;hash&gt;") reaches a caller, so
     * the scrubbing it performs has to be pinnable by a unit test.
     *
     * <p>The platform text is scrubbed of EMF implementation identities on the way out: the hash is
     * worthless to a caller who cannot inspect that heap, while the simple type name IS the
     * diagnosis, so both halves are asserted - no identity, and the type name still present.</p>
     *
     * @param roleName the role's Name, so the caller can tell which call failed
     * @param cause the platform failure (its message may sit on a nested status)
     * @return a ready JSON error
     */
    static String applyFailure(String roleName, RuntimeException cause)
    {
        return ToolResult.error("Failed to apply role rights to " + roleName + ": " //$NON-NLS-1$ //$NON-NLS-2$
            + PlatformFailures.withoutObjectIdentity(PlatformFailures.describe(cause))
            + ". Re-read the role with get_metadata_details to see what landed, then retry; if the " //$NON-NLS-1$
            + "failure repeats, run clean_project on the project and retry.").toJson(); //$NON-NLS-1$
    }

    /**
     * Adds the completed per-section counts and an explicit reconciliation warning to a ready error
     * JSON after a partial apply. Pure and package-visible so its defensive response-shape contract is
     * unit-testable without EDT services.
     *
     * <p>Only an explicit {@code success:false} object with a string {@code error} is changed, and
     * only when at least one count is non-zero. A success, malformed JSON, malformed error object or a
     * zero-count refusal passes through byte-for-byte.</p>
     *
     * @param errorJson the ready error JSON
     * @param rights committed right-entry values
     * @param templates completed template operations
     * @param roleProperties completed role-property changes
     * @return the enriched error, or {@code errorJson} unchanged when it is not eligible
     */
    static String reportPartialApplication(String errorJson, int rights, int templates,
        int roleProperties)
    {
        if (errorJson == null || (rights == 0 && templates == 0 && roleProperties == 0))
        {
            return errorJson;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(errorJson);
            if (!parsed.isJsonObject())
            {
                return errorJson;
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement success = object.get("success"); //$NON-NLS-1$
            JsonElement error = object.get(McpKeys.ERROR);
            if (success == null || !success.isJsonPrimitive()
                || !success.getAsJsonPrimitive().isBoolean() || success.getAsBoolean()
                || error == null || !error.isJsonPrimitive()
                || !error.getAsJsonPrimitive().isString())
            {
                return errorJson;
            }

            JsonObject applied = new JsonObject();
            applied.addProperty("rights", rights); //$NON-NLS-1$
            applied.addProperty("templates", templates); //$NON-NLS-1$
            applied.addProperty("roleProperties", roleProperties); //$NON-NLS-1$
            object.add("applied", applied); //$NON-NLS-1$
            object.addProperty(McpKeys.ERROR, error.getAsString()
                + " Before it failed, this call had already applied rights=" + rights //$NON-NLS-1$
                + ", templates=" + templates + ", roleProperties=" + roleProperties //$NON-NLS-1$ //$NON-NLS-2$
                + "; the model was changed, so re-read the role with get_metadata_details, then " //$NON-NLS-1$
                + "undo or complete the remaining changes by hand."); //$NON-NLS-1$
            return GsonProvider.toJson(object);
        }
        catch (RuntimeException e)
        {
            return errorJson;
        }
    }

    /** {@code null} for a null or empty string, the string itself otherwise. */
    private static String emptyToNull(String value)
    {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Resolves the current concrete {@link RoleDescription} INSIDE an active read boundary (re-fetching
     * the role by bmId from the supplied transaction). Never null once {@link #ensureRoleDescription}
     * has run. The caller must wrap this in {@link BmTransactions#read} so no EMF feature is read on the
     * bare calling thread.
     */
    private static RoleDescription roleDescriptionInTx(IBmTransaction tx, long roleBmId)
    {
        Role inTx = (Role)tx.getObjectById(roleBmId);
        if (inTx != null && inTx.getRights() instanceof RoleDescription)
        {
            return (RoleDescription)inTx.getRights();
        }
        return null;
    }

    // ---- resolution (bilingual) ---------------------------------------------------------------

    /** Resolves an object FQN (bilingual type token) to its metadata top object, or null. */
    static MdObject resolveObject(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String norm = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, norm);
        return node != null ? node.object : null;
    }

    /**
     * Resolves a {@link Right} valid for the object by its bilingual name (English {@code getName()} or
     * Russian {@code getNameRu()}, case-insensitive), or null when no right matches.
     */
    static Right resolveRight(IRightInfosService rightInfos, EObject object, String rightName)
    {
        if (rightName == null || rightName.isEmpty())
        {
            return null;
        }
        for (Right right : rightInfos.getRights(object))
        {
            if (namesMatch(rightName, right.getName(), right.getNameRu()))
            {
                return right;
            }
        }
        return null;
    }

    /**
     * Resolves the {@link DbViewFieldDef} collection an RLS applies to. An empty / omitted
     * {@code fieldNames} yields {@link Collections#emptyList()} = a whole-object restriction. A
     * non-empty list is matched bilingually against the object's RLS field pool
     * ({@link DbViewUtil#getRlsFields}); returns {@code null} when any requested field is unknown (so
     * the caller can fail with an actionable error) rather than silently dropping it.
     */
    static Collection<DbViewFieldDef> resolveRlsFields(MdObject mdObject, List<String> fieldNames)
    {
        if (fieldNames == null || fieldNames.isEmpty())
        {
            return Collections.emptyList();
        }
        List<DbViewFieldDef> pool = DbViewUtil.getRlsFields(mdObject);
        List<DbViewFieldDef> resolved = new ArrayList<>();
        for (String wanted : fieldNames)
        {
            DbViewFieldDef match = matchField(pool, wanted);
            if (match == null)
            {
                return null;
            }
            resolved.add(match);
        }
        return resolved;
    }

    /** Matches a field by its bilingual name in the pool, or null. */
    private static DbViewFieldDef matchField(List<DbViewFieldDef> pool, String wanted)
    {
        if (pool == null || wanted == null)
        {
            return null;
        }
        for (DbViewFieldDef field : pool)
        {
            if (namesMatch(wanted, field.getName(), field.getNameRu()))
            {
                return field;
            }
        }
        return null;
    }

    // ---- pure helpers (unit-testable) ---------------------------------------------------------

    /**
     * Parses a right value token to a {@link RightValue}: {@code "set"} / boolean {@code true} to
     * {@link RightValue#SET}, {@code "unset"} / boolean {@code false} to {@link RightValue#UNSET},
     * {@code "provided"} to {@link RightValue#PROVIDED}. An omitted / null value defaults to
     * {@link RightValue#SET}. Returns {@link RightValue#SET} for an unrecognized token only after
     * {@link #validateRightValue} has rejected it, so callers validate first.
     *
     * @param value the JSON value (string token or boolean), may be null
     * @return the resolved {@link RightValue} (never null)
     */
    static RightValue parseRightValue(JsonElement value)
    {
        if (value == null || value.isJsonNull())
        {
            return RightValue.SET;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean())
        {
            return value.getAsBoolean() ? RightValue.SET : RightValue.UNSET;
        }
        String token = str(value);
        if (token == null)
        {
            return RightValue.SET;
        }
        switch (token.trim().toLowerCase(Locale.ROOT))
        {
            case VAL_UNSET:
                return RightValue.UNSET;
            case VAL_PROVIDED:
                return RightValue.PROVIDED;
            case VAL_SET:
            default:
                return RightValue.SET;
        }
    }

    /**
     * Whether a right-value token is recognizable: a boolean, or one of {@code set} / {@code unset} /
     * {@code provided} (case-insensitive), or absent (defaults to {@code set}). Pure.
     */
    static boolean isValidRightValue(JsonElement value)
    {
        if (value == null || value.isJsonNull())
        {
            return true;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean())
        {
            return true;
        }
        String token = str(value);
        if (token == null)
        {
            return false;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        return VAL_SET.equals(t) || VAL_UNSET.equals(t) || VAL_PROVIDED.equals(t);
    }

    /**
     * Bilingual case-insensitive name match: whether {@code wanted} equals the English {@code enName}
     * or the Russian {@code ruName} (either may be null). Pure - the core of the bilingual right / field
     * resolution.
     */
    static boolean namesMatch(String wanted, String enName, String ruName)
    {
        if (wanted == null)
        {
            return false;
        }
        return wanted.equalsIgnoreCase(enName) || wanted.equalsIgnoreCase(ruName);
    }

    /** Normalizes a template op token to {@code add} / {@code edit} / {@code delete}; default {@code add}. */
    static String templateOp(JsonObject entry)
    {
        String op = str(entry.get("op")); //$NON-NLS-1$
        if (op == null || op.trim().isEmpty())
        {
            return OP_ADD;
        }
        return op.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Validates the whole role payload BEFORE any write (fail fast, no partial mutation). Returns the
     * first JSON error, or null when every entry is well-formed. Pure (reads only the JSON).
     *
     * @param rights the {@code rights[]} entries
     * @param templates the {@code templates[]} entries
     * @param roleProperties the {@code roleProperties} object, or null
     * @return a ready JSON error, or null when valid
     */
    static String validatePayload(List<JsonObject> rights, List<JsonObject> templates,
        JsonObject roleProperties)
    {
        for (JsonObject entry : rights)
        {
            String err = validateRightsEntry(entry);
            if (err != null)
            {
                return err;
            }
        }
        for (JsonObject entry : templates)
        {
            String err = validateTemplateEntry(entry);
            if (err != null)
            {
                return err;
            }
        }
        return validateRoleProperties(roleProperties);
    }

    /** Validates a single {@code rights[]} entry (object + right required, value recognizable). */
    static String validateRightsEntry(JsonObject entry)
    {
        if (str(entry.get(KEY_OBJECT)) == null || str(entry.get(KEY_OBJECT)).isEmpty())
        {
            return ToolResult.error("Each 'rights' entry needs an 'object' FQN, e.g. " //$NON-NLS-1$
                + "'Catalog.Products' (or the Russian 'Справочник.Товары').").toJson(); //$NON-NLS-1$
        }
        if (str(entry.get(KEY_RIGHT)) == null || str(entry.get(KEY_RIGHT)).isEmpty())
        {
            return ToolResult.error("Each 'rights' entry needs a 'right' name, e.g. 'Read' / " //$NON-NLS-1$
                + "'Update' (or the Russian 'Чтение' / 'Изменение').").toJson(); //$NON-NLS-1$
        }
        if (!isValidRightValue(entry.get(KEY_VALUE)))
        {
            return ToolResult.error("The right 'value' must be 'set' / 'unset' / 'provided' or a " //$NON-NLS-1$
                + "boolean (true = set, false = unset); default is 'set'.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /** Validates a single {@code templates[]} entry (op valid; name required; condition for add/edit). */
    static String validateTemplateEntry(JsonObject entry)
    {
        String op = templateOp(entry);
        if (!OP_ADD.equals(op) && !OP_EDIT.equals(op) && !OP_DELETE.equals(op))
        {
            return ToolResult.error("Each 'templates' entry 'op' must be 'add', 'edit' or 'delete' " //$NON-NLS-1$
                + "(default 'add'); got '" + op + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (str(entry.get(KEY_NAME)) == null || str(entry.get(KEY_NAME)).isEmpty())
        {
            return ToolResult.error("Each 'templates' entry needs a 'name'.").toJson(); //$NON-NLS-1$
        }
        if ((OP_ADD.equals(op) || OP_EDIT.equals(op))
            && (str(entry.get(KEY_CONDITION)) == null || str(entry.get(KEY_CONDITION)).isEmpty()))
        {
            return ToolResult.error("A 'templates' " + op + " entry needs a 'condition' (the RLS " //$NON-NLS-1$ //$NON-NLS-2$
                + "restriction text).").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /** Validates the {@code roleProperties} object: each supplied flag must be a boolean. */
    static String validateRoleProperties(JsonObject roleProperties)
    {
        if (roleProperties == null)
        {
            return null;
        }
        for (String key : new String[] {"setForNewObjects", "setForAttributesByDefault", //$NON-NLS-1$ //$NON-NLS-2$
            "independentRightsOfChildObjects"}) //$NON-NLS-1$
        {
            JsonElement el = roleProperties.get(key);
            if (el != null && !el.isJsonNull()
                && !(el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()))
            {
                return ToolResult.error("roleProperties." + key + " must be a boolean (true / false).") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
        }
        return null;
    }

    // ---- error builders (actionable) ----------------------------------------------------------

    private static String objectNotFound(Configuration config, String fqn)
    {
        List<String> similar = similarObjects(config, fqn);
        String suggestion = similar.isEmpty() ? "" //$NON-NLS-1$
            : " Did you mean: " + String.join(", ", similar) + "?"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return ToolResult.error("Object not found for right entry: '" + fqn + "'. Use a valid FQN " //$NON-NLS-1$ //$NON-NLS-2$
            + "(e.g. 'Catalog.Products'); check with get_metadata_objects." + suggestion).toJson(); //$NON-NLS-1$
    }

    /** Best-effort "did you mean" suggestions from the FQN's type; empty when it cannot be parsed. */
    private static List<String> similarObjects(Configuration config, String fqn)
    {
        String norm = MetadataTypeUtils.normalizeFqn(fqn);
        String[] parts = norm.split("\\."); //$NON-NLS-1$
        if (parts.length >= 2)
        {
            return MetadataTypeUtils.findSimilarObjects(config, parts[0], parts[1], 5);
        }
        return Collections.emptyList();
    }

    private static String rightNotFound(IRightInfosService rightInfos, EObject object, String rightName,
        String objectFqn)
    {
        List<String> valid = new ArrayList<>();
        for (Right right : rightInfos.getRights(object))
        {
            valid.add(rightLabel(right));
        }
        Collections.sort(valid);
        return ToolResult.error("Right '" + rightName + "' is not valid for '" + objectFqn //$NON-NLS-1$ //$NON-NLS-2$
            + "'. Valid rights: " + String.join(", ", valid) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String rlsFieldsNotFound(MdObject mdObject, List<String> fieldNames)
    {
        List<String> pool = new ArrayList<>();
        for (DbViewFieldDef field : DbViewUtil.getRlsFields(mdObject))
        {
            pool.add(fieldLabel(field));
        }
        Collections.sort(pool);
        return ToolResult.error("One or more RLS fields " + fieldNames + " are not available on '" //$NON-NLS-1$ //$NON-NLS-2$
            + mdObject.getName() + "'. Available RLS fields: " + String.join(", ", pool) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Omit 'rlsFields' for a whole-object restriction.").toJson(); //$NON-NLS-1$
    }

    private static String templateNotFound(RoleDescription roleDescription, String name)
    {
        List<String> existing = new ArrayList<>();
        if (roleDescription != null)
        {
            for (RestrictionTemplate template : roleDescription.getTemplates())
            {
                existing.add(template.getName());
            }
        }
        Collections.sort(existing);
        String have = existing.isEmpty() ? "the role has no templates" //$NON-NLS-1$
            : "existing templates: " + String.join(", ", existing); //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("RLS template '" + name + "' not found (" + have //$NON-NLS-1$ //$NON-NLS-2$
            + "). Use op 'add' to create it.").toJson(); //$NON-NLS-1$
    }

    /** Bilingual label for a {@link Right} ("English / Русский" when both, else whichever is set). */
    private static String rightLabel(Right right)
    {
        return dualLabel(right.getName(), right.getNameRu());
    }

    private static String fieldLabel(DbViewFieldDef field)
    {
        return dualLabel(field.getName(), field.getNameRu());
    }

    private static String dualLabel(String en, String ru)
    {
        if (en != null && ru != null && !en.equals(ru))
        {
            return en + " / " + ru; //$NON-NLS-1$
        }
        return en != null ? en : ru;
    }

    // ---- small typed helpers ------------------------------------------------------------------

    private static String str(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    /** Reads a JSON array element into a list of non-empty strings; empty list when absent / not an array. */
    private static List<String> strList(JsonElement el)
    {
        List<String> out = new ArrayList<>();
        if (el != null && el.isJsonArray())
        {
            el.getAsJsonArray().forEach(item ->
            {
                String s = str(item);
                if (s != null && !s.trim().isEmpty())
                {
                    out.add(s.trim());
                }
            });
        }
        return out;
    }

    /** Reads a boolean property, or null when absent / not a boolean (tri-state: only supplied flags apply). */
    private static Boolean boolProp(JsonObject obj, String key)
    {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean())
        {
            return Boolean.valueOf(el.getAsBoolean());
        }
        return null; // NOSONAR tri-state: null means "flag not supplied"; only supplied flags are applied
    }

    // ---- internals ----------------------------------------------------------------------------


    /**
     * Per-call context: the resolved services and role handle threaded through the apply, plus the
     * ledger of what this call has already COMMITTED.
     *
     * <p>The ledger is kept here, and every model write goes through {@link #execute} or
     * {@link #recordWrite}, for the reason issue #408 settled for the export contract as a whole:
     * declaring the write is made a side effect OF the write, so a new task cannot be added and the
     * declaration forgotten. Package-visible so a unit test can drive {@link #applyResolved} with a
     * mocked model.</p>
     */
    static final class Context
    {
        final IProject project;
        final Configuration config;
        final IBmModel model;
        final Role role;
        final long roleBmId;
        final IRightInfosService rightInfos;
        final IEventBroker eventBroker;
        final IModelObjectCollectionRuntimeOrderSorter sorter;

        /** Set the moment a write of this call RETURNS from the model, i.e. has committed. */
        private boolean written;

        /**
         * Committed payload units. Rights count after the value task (before optional RLS); templates
         * and properties count after their complete entry task returns.
         */
        private int appliedRights;
        private int appliedTemplates;
        private int appliedRoleProperties;

        Context(IProject project, Configuration config, IBmModel model, Role role, long roleBmId, // NOSONAR cohesive per-call context; a holder would not improve clarity
            IRightInfosService rightInfos, IEventBroker eventBroker,
            IModelObjectCollectionRuntimeOrderSorter sorter)
        {
            this.project = project;
            this.config = config;
            this.model = model;
            this.role = role;
            this.roleBmId = roleBmId;
            this.rightInfos = rightInfos;
            this.eventBroker = eventBroker;
            this.sorter = sorter;
        }

        /**
         * Runs a platform rights task and records the commit it just made. The recording happens
         * AFTER the model returns, so a task that threw (and rolled back) is not counted.
         *
         * @param task the EDT rights task to execute
         */
        void execute(IBmTask<?> task)
        {
            model.execute(task);
            written = true;
            WriteScope.recordWrite(project);
        }

        /** Records a commit made outside {@link #execute} (the bootstrap attach, a direct setter). */
        void recordWrite()
        {
            written = true;
            WriteScope.recordWrite(project);
        }

        /** Whether this call has committed at least one write to the role's rights model. */
        boolean hasWritten()
        {
            return written;
        }
    }

    /** Resolves the {@link IBmModel} for the project, or carries a ready JSON error. */
    private static final class IBmModelManagerHolder
    {
        final IBmModel model;
        final String error;

        private IBmModelManagerHolder(IBmModel model, String error)
        {
            this.model = model;
            this.error = error;
        }

        static IBmModelManagerHolder resolve(IProject project)
        {
            com._1c.g5.v8.dt.core.platform.IBmModelManager manager =
                Activator.getDefault().getBmModelManager();
            if (manager == null)
            {
                return new IBmModelManagerHolder(null,
                    ToolResult.error("IBmModelManager not available").toJson()); //$NON-NLS-1$
            }
            IBmModel model = manager.getModel(project);
            if (model == null)
            {
                return new IBmModelManagerHolder(null,
                    ToolResult.error("BM model not available for project: " + project.getName()).toJson()); //$NON-NLS-1$
            }
            return new IBmModelManagerHolder(model, null);
        }
    }

    /**
     * Carries a ready JSON error out of a deep apply step (resolution / transaction failure) so the
     * top-level {@link #apply} returns it verbatim. Unchecked so it crosses the BM task boundary; the
     * message is a validated {@link ToolResult#error} JSON string.
     */
    static final class RoleWriteException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final transient String errorJson;

        RoleWriteException(String errorJson)
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
