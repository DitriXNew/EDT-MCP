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
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.util.DbViewUtil;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractRoleDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
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
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
 * {@link RoleDescription}'s OWN top-object FQN (see {@link #resolveRightsDescriptionFqn}), OUTSIDE any
 * boundary, after every task has run: the rights matrix lives in its OWN BM resource
 * ({@code Rights.rights}), so exporting only the role FQN would drain {@code Role.mdo} but never
 * {@code Rights.rights}.</p>
 *
 * <p>The concrete rights matrix lives on {@link RoleDescription} (a subtype of the mdclass
 * {@link AbstractRoleDescription} the bare {@code Role.getRights()} may hold). Every rights task
 * requires the {@link RoleDescription} to already exist, so {@link #ensureRoleDescription} seeds one
 * in a {@link BmTransactions#write write} boundary before the tasks run when the role has none.</p>
 *
 * <p>The value / name / payload helpers are pure (no model, no UI) so they are unit-testable; the
 * model-touching apply methods run on the UI thread and go only through the BM boundary.</p>
 *
 * <p><b>Best-effort, non-atomic apply.</b> The payload is validated up front for shape errors (fail
 * fast, nothing written), but each entry is applied through its own BM task ({@code bmModel.execute}
 * per right value / RLS / template / role property), so there is no single rollback across entries. A
 * mid-run RESOLUTION failure (e.g. an {@code edit} / {@code delete} template whose name is not found,
 * or an unknown RLS field) fails the operation AFTER earlier entries have already committed - those
 * earlier entries stay applied. Order entries so any risky reference (edit/delete templates, RLS
 * fields) is validated by the caller before the batch, or accept that a partial mutation may remain
 * on failure.</p>
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
     * what was applied. An up-front VALIDATION error means nothing was written; a mid-run RESOLUTION /
     * task error may leave earlier entries already applied (the apply is best-effort, non-atomic - see
     * the class javadoc).
     */
    public static final class Result
    {
        /** Non-null when the write failed / was rejected: a ready JSON error to return verbatim. */
        public final String error;
        /** Number of right entries applied (value + optional RLS). */
        public final int rights;
        /** Number of template operations applied. */
        public final int templates;
        /** Number of role-property booleans applied. */
        public final int roleProperties;

        private Result(String error, int rights, int templates, int roleProperties)
        {
            this.error = error;
            this.rights = rights;
            this.templates = templates;
            this.roleProperties = roleProperties;
        }

        static Result failed(String error)
        {
            return new Result(error, 0, 0, 0);
        }

        static Result ok(int rights, int templates, int roleProperties)
        {
            return new Result(null, rights, templates, roleProperties);
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

        IRightInfosService rightInfos = Activator.getDefault().getRightInfosService();
        IEventBroker eventBroker = Activator.getDefault().getEventBroker();
        IModelObjectCollectionRuntimeOrderSorter sorter = Activator.getDefault().getCollectionOrderSorter();
        if (rightInfos == null || eventBroker == null || sorter == null)
        {
            return Result.failed(ToolResult.error("The role-rights services are not available " //$NON-NLS-1$
                + "(IRightInfosService / IEventBroker / collection order sorter). Ensure the EDT " //$NON-NLS-1$
                + "workbench is fully started, then retry.").toJson()); //$NON-NLS-1$
        }

        long roleBmId = ((IBmObject)role).bmGetId();

        Context ctx = new Context(project, config, holder.model, role, roleBmId, rightInfos, eventBroker,
            sorter);
        try
        {
            // A RoleDescription must exist before ANY rights task runs (the tasks downcast
            // Role.getRights() to RoleDescription without auto-creating it). Seed one in a write
            // boundary when the role has none, then re-resolve the role so the tasks see the concrete
            // description. Inside the try so its RoleWriteException returns a clean single-level error.
            ensureRoleDescription(holder.model, roleBmId);

            int appliedRights = applyRights(ctx, rights);
            int appliedTemplates = applyTemplates(ctx, templates);
            int appliedProps = applyRoleProperties(ctx, roleProperties);
            return Result.ok(appliedRights, appliedTemplates, appliedProps);
        }
        catch (RoleWriteException e)
        {
            return Result.failed(e.getErrorJson());
        }
        catch (RuntimeException e)
        {
            // Any other runtime failure from the EDT tasks (an NPE, a DbView derived-data timing
            // failure, a task-internal failure surfaced by bmModel.execute) must still leave the tool
            // as a clean structured ToolResult error rather than escape to the JSON-RPC layer.
            Activator.logError("Failed to apply role rights to " + role.getName(), e); //$NON-NLS-1$
            return Result.failed(ToolResult.error("Failed to apply role rights: " //$NON-NLS-1$
                + e.getMessage()).toJson());
        }
    }

    // ---- rights[] -----------------------------------------------------------------------------

    /**
     * Applies every {@code rights[]} entry: resolves the object + the {@link Right} (bilingually), sets
     * the value via {@code AddRightValuesTask}, and, when an RLS condition is present, resolves the
     * field pool via {@link DbViewUtil} and runs {@code AddRlsTask} (or {@code EditRlsTask} when an Rls
     * already exists for that object + right). Each task is its own {@code bmModel.execute}.
     */
    private static int applyRights(Context ctx, List<JsonObject> rights)
    {
        int applied = 0;
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
            setRightValue(ctx, target, right, value);

            String rls = str(entry.get("rls")); //$NON-NLS-1$
            if (rls != null && !rls.isEmpty())
            {
                applyRls(ctx, targetMd, target, right, entry, rls);
            }
            applied++;
        }
        return applied;
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
        ctx.model.execute(task);
    }

    /**
     * Applies the RLS restriction condition for one object + right: resolves the field collection
     * (empty {@code rlsFields} = whole-object restriction), then runs {@code EditRlsTask} when an Rls
     * already exists for that object + right, else {@code AddRlsTask}. Every model read (the field pool
     * and the Add / Edit decision) runs INSIDE a {@link BmTransactions#read read} boundary that
     * re-resolves handles by bm id; only the resolved handles the task consumes escape the boundary.
     */
    private static void applyRls(Context ctx, MdObject targetMd, EObject target, Right right,
        JsonObject entry, String rls)
    {
        List<String> fieldNames = strList(entry.get("rlsFields")); //$NON-NLS-1$
        // DbViewUtil.getRlsFields reads DB-view derived data - a model read - so resolve inside a read
        // boundary; only the resolved DbViewFieldDef collection (a task input) escapes.
        Collection<DbViewFieldDef> fields = BmTransactions.read(ctx.model, "ResolveRlsFields", //$NON-NLS-1$
            (tx, pm) -> resolveRlsFields(targetMd, fieldNames));
        if (fields == null)
        {
            throw new RoleWriteException(BmTransactions.read(ctx.model, "RlsFieldsNotFound", //$NON-NLS-1$
                (tx, pm) -> rlsFieldsNotFound(targetMd, fieldNames)));
        }

        // Resolve the role description and the Add/Edit decision INSIDE a read boundary (re-fetching the
        // role by bm id), so getRights()/getRestrictionsByCondition() are never walked on the bare
        // calling thread. The plan carries only the handles the Add/Edit task consumes.
        RlsPlan plan = BmTransactions.read(ctx.model, "ResolveRlsPlan", (tx, pm) -> //$NON-NLS-1$
        {
            RoleDescription roleDescription = roleDescriptionInTx(tx, ctx.roleBmId);
            Rls existing = findExistingRls(roleDescription, target, right);
            return new RlsPlan(roleDescription, existing);
        });

        IBmTask<?> task;
        if (plan.existing != null)
        {
            task = EditRlsTask.create(plan.existing, fields, rls, ctx.project, ctx.eventBroker);
        }
        else
        {
            task = AddRlsTask.create(plan.roleDescription, target, right, fields, rls, ctx.project,
                ctx.eventBroker, ctx.sorter);
        }
        ctx.model.execute(task);
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
    private static int applyTemplates(Context ctx, List<JsonObject> templates)
    {
        if (templates.isEmpty())
        {
            return 0;
        }
        int applied = 0;
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
            ctx.model.execute(task);
            applied++;
        }
        return applied;
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
    private static int applyRoleProperties(Context ctx, JsonObject roleProperties)
    {
        if (roleProperties == null)
        {
            return 0;
        }
        Boolean setForNewObjects = boolProp(roleProperties, "setForNewObjects"); //$NON-NLS-1$
        Boolean setForAttributesByDefault = boolProp(roleProperties, "setForAttributesByDefault"); //$NON-NLS-1$
        Boolean independentRights = boolProp(roleProperties, "independentRightsOfChildObjects"); //$NON-NLS-1$

        int applied = 0;
        if (independentRights != null)
        {
            // Resolve the role description by bm id inside a read boundary; the task then re-opens its
            // own write tx. No EMF feature is read on the bare calling thread.
            RoleDescription roleDescription = BmTransactions.read(ctx.model, "ReadRoleDescription", //$NON-NLS-1$
                (tx, pm) -> roleDescriptionInTx(tx, ctx.roleBmId));
            ctx.model.execute(
                SetIndependentRightsOfChildObjectsTask.create(roleDescription, independentRights));
            applied++;
        }
        if (setForNewObjects != null)
        {
            setBooleanRoleProperty(ctx, RolePropertyKind.FOR_NEW_OBJECTS, setForNewObjects);
            applied++;
        }
        if (setForAttributesByDefault != null)
        {
            setBooleanRoleProperty(ctx, RolePropertyKind.FOR_ATTRIBUTES_BY_DEFAULT,
                setForAttributesByDefault);
            applied++;
        }
        return applied;
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
    }

    // ---- role description bootstrap ------------------------------------------------------------

    /**
     * Ensures the role has a concrete {@link RoleDescription} (the rights tasks downcast
     * {@code Role.getRights()} to it and do not auto-create it). When the role has none - a null or a
     * bare {@link AbstractRoleDescription} - a fresh {@link RoleDescription} is created via
     * {@link RightsFactory} and set on the re-fetched role inside a write boundary.
     */
    static void ensureRoleDescription(IBmModel model, long roleBmId)
    {
        BmTransactions.<Void>write(model, "EnsureRoleDescription", (tx, pm) -> //$NON-NLS-1$
        {
            Role inTx = (Role)tx.getObjectById(roleBmId);
            if (inTx == null)
            {
                throw new RoleWriteException(ToolResult.error("The role could not be resolved inside " //$NON-NLS-1$
                    + "the transaction.").toJson());
            }
            AbstractRoleDescription current = inTx.getRights();
            if (!(current instanceof RoleDescription))
            {
                RoleDescription roleDescription = RightsFactory.eINSTANCE.createRoleDescription();
                inTx.setRights(roleDescription);
            }
            return null;
        });
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

    // ---- rights-resource persistence ----------------------------------------------------------

    /**
     * Resolves the OWN BM top-object FQN of the role's {@link RoleDescription} - the access matrix that
     * backs the sibling {@code Rights.rights} file - so the caller can force-export it to disk.
     * <p>
     * The rights matrix is NOT a child of the {@code Role} top object: {@code RoleDescription} is a
     * {@link com._1c.g5.v8.bm.core.IBmObject} in its OWN BM resource (its impl extends
     * {@code com._1c.g5.v8.bm.core.BmObject}), serialized by its OWN EClass-keyed exporter
     * ({@code com._1c.g5.v8.dt.internal.rights.resource.RightsExporter}, which
     * {@code supports(ROLE_DESCRIPTION)}) into {@code Rights.rights}. Force-exporting only the
     * {@code Role.<Name>} FQN therefore drains the {@code Role.mdo} resource but NEVER the
     * {@code Rights.rights} resource, so a role-rights change stays in memory and is invisible on disk.
     * Passing THIS FQN to {@link BmTransactions#forceExportToDisk} runs the rights exporter and writes
     * the {@code .rights} file.
     * <p>
     * Runs on the calling (UI) thread; resolves the FQN INSIDE a read boundary (re-fetching the role by
     * bmId), so no EMF/BM feature is read on the bare thread. Must be called AFTER {@link #apply} (which
     * ensures the description exists). Returns {@code null} when the role has no concrete description or
     * the description is not a resolvable top BM object (best-effort: the caller then exports only the
     * role FQN).
     *
     * @param project the workspace project owning the role
     * @param role the resolved role top object
     * @return the {@code Rights.rights} resource's own top-object FQN, or {@code null} when unresolvable
     */
    public static String resolveRightsDescriptionFqn(IProject project, Role role)
    {
        if (role == null)
        {
            return null;
        }
        IBmModelManagerHolder holder = IBmModelManagerHolder.resolve(project);
        if (holder.error != null)
        {
            return null;
        }
        long roleBmId = ((IBmObject)role).bmGetId();
        return BmTransactions.read(holder.model, "ResolveRightsFqn", (tx, pm) -> //$NON-NLS-1$
        {
            RoleDescription roleDescription = roleDescriptionInTx(tx, roleBmId);
            if (!(roleDescription instanceof IBmObject))
            {
                return null;
            }
            IBmObject rightsBm = (IBmObject)roleDescription;
            IBmObject top = rightsBm.bmIsTop() ? rightsBm : rightsBm.bmGetTopObject();
            return top != null ? top.bmGetFqn() : null;
        });
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


    /** Immutable per-call context: the resolved services and role handle threaded through the apply. */
    private static final class Context
    {
        final IProject project;
        final Configuration config;
        final IBmModel model;
        final Role role;
        final long roleBmId;
        final IRightInfosService rightInfos;
        final IEventBroker eventBroker;
        final IModelObjectCollectionRuntimeOrderSorter sorter;

        Context(IProject project, Configuration config, IBmModel model, Role role, long roleBmId,
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
