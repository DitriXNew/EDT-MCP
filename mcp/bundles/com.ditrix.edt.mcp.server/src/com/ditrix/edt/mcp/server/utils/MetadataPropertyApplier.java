// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Shared metadata property preparation and application. */
public final class MetadataPropertyApplier
{
    private MetadataPropertyApplier() { }

    @FunctionalInterface
    public interface TypeBuilder
    {
        MetadataTypeBuilder.Result build(JsonElement spec, Configuration config, MetadataScope scope,
            Version version, boolean extension, MetadataTypeBuilder.TypeTarget target);
    }

    /** Property/JSON key: the value of a property entry. */
    public static final String KEY_VALUE = "value";

    public static final String KEY_COMMON = "common";

    public static final String KEY_ROLES = "roles";

    /** A ScheduledJob's method-reference property (guarded by {@link MethodReferenceValidator}). */
    public static final String PROP_METHOD_NAME = "methodName";

    /** Error-message fragment between an FQN and its EClass name (e.g. "'X' is a Catalog"). */
    public static final String MSG_IS_A = "' is a ";

    /** Common prefix of every {@link #validateReferenceTarget} error. */
    public static final String MSG_REFERENCE_TARGET = "Reference target '";

    /** Common infix of every {@link #validateReferenceTarget} error, between the value and the property. */
    public static final String MSG_FOR_PROP = "' for '";

    /**
     * Applies a validated batch to its target inside the write transaction: the type writes first, then
     * every other change, in batch order. A type write is EDT's type change, which resets the properties
     * that depend on the type the way the designer does (see {@link TypeChangeDefaults}); applying it
     * first keeps a property the same call sets explicitly - quick choice, indexing, full-text search,
     * ... - from being undone by those resets. A repeated property is decided by its LAST write, and so
     * is a repeated type: only the last type write of each feature is applied, so the resets compare the
     * stored type with the type the batch ends with, never with one it merely passes through. A batch
     * therefore means its end state in whatever order it lists its properties (issue #700).
     * Package-visible for tests.
     *
     * @param target the object the batch is written to, inside {@code tx}
     * @param changes the prepared changes, in the caller's order
     * @param tx the write transaction
     * @return what the type writes reset besides the type; {@link TypeChangeDefaults.Reset#NONE} when
     *         they reset nothing
     */
    public static TypeChangeDefaults.Reset applyChanges(EObject target, List<PreparedChange> changes,
        IBmTransaction tx)
    {
        return applyChanges(target, changes, tx, null);
    }

    /**
     * {@link #applyChanges(EObject, List, IBmTransaction)}, naming {@code fqn} in a refusal. Before anything is
     * written, every change the adopted-object routing wrote as prepared is checked again on the object read
     * inside the transaction ({@link AdoptedPropertyRouter#requireStillWritten}): an extension state set to
     * None while the call waited would make EDT's {@code .mdo} writer drop the value, so the write refuses and
     * rolls back instead of reporting a value the file never gets (#709 / #710).
     *
     * @param target the object the batch is written to, inside {@code tx}
     * @param changes the prepared changes, in the caller's order
     * @param tx the write transaction
     * @param fqn the FQN the call addresses, for a refusal, or {@code null}
     * @return what the type writes reset besides the type
     */
    public static TypeChangeDefaults.Reset applyChanges(EObject target, List<PreparedChange> changes,
        IBmTransaction tx, String fqn)
    {
        for (PreparedChange change : changes)
        {
            if (!change.isRoutedWrite())
            {
                AdoptedPropertyRouter.requireStillWritten(target, change.feature(), change.bindingFeature(), fqn);
            }
        }
        Map<EStructuralFeature, PreparedChange> lastTypeWrites = new LinkedHashMap<>();
        for (PreparedChange change : changes)
        {
            if (change.isTypeChange())
            {
                lastTypeWrites.put(change.feature(), change);
            }
        }
        String valueError = memberValueTypeError(target, changes);
        if (valueError != null) throw new IllegalArgumentException(valueError);
        TypeChangeDefaults.Reset reset = TypeChangeDefaults.Reset.NONE;
        for (PreparedChange change : lastTypeWrites.values())
        {
            TypeChangeDefaults.Reset applied = change.applyTo(target, tx);
            if (applied != TypeChangeDefaults.Reset.NONE)
            {
                reset = applied;
            }
        }
        for (PreparedChange change : changes)
        {
            // The complete batch was checked above; templateType was initialized by the caller.
            if (!change.isTypeChange()
                && change.feature() != MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE_TYPE)
            {
                change.applyTo(target, tx);
            }
        }
        return reset;
    }

    public static String validateAndPrepare(IProject project, MetadataScope scope, Configuration config,
        Version version, MdObject target, List<JsonObject> properties, List<PreparedChange> changes,
        MdNameNormalizer.Report normReport, boolean isExtensionProject)
    {
        return validateAndPrepare(project, scope, config, version, target, properties, changes, normReport,
            isExtensionProject, MetadataPropertyApplier::buildTypeDescription);
    }

    public static String validateAndPrepare(IProject project, MetadataScope scope, Configuration config,
        Version version, MdObject target, List<JsonObject> properties, List<PreparedChange> changes,
        MdNameNormalizer.Report normReport, boolean isExtensionProject, TypeBuilder typeBuilder)
    {
        // A field list is judged on the object as it stands; a property in the same batch that changes
        // whether EDT offers a standard field the list names (a length, hierarchical, owners, ...) would
        // change that state under the entry (issue #740).
        String batchErr = fieldListBatchError(target, properties);
        if (batchErr != null)
        {
            return batchErr;
        }
        PrepareContext ctx = new PrepareContext(project, scope, config, version,
            declaredCodesAfterBatch(config, target, properties),
            MetadataTypeBuilder.TypeTarget.METADATA, typeBuilder);
        for (JsonObject prop : properties)
        {
            // The mdclass path has no <extInfo> (extInfo == null): findFeature then classifies only the
            // object's own features, so this stays byte-identical to the pre-extInfo behaviour.
            String pErr = prepare(ctx, target, null, prop, changes, normReport,
                isExtensionProject);
            if (pErr != null)
            {
                return pErr;
            }
        }
        String valueError = memberValueTypeError(target, changes);
        return valueError == null ? null : ToolResult.error(valueError).toJson();
    }

    /**
     * {@code create_metadata}'s seam (issue #930): validates and prepares {@code properties} for an object of
     * {@code prototype}'s kind by THIS tool's rules - {@link #validateAndPrepare}, the same names, value kinds,
     * allowed values and refusals - so a property create accepts at creation is the one this tool would set,
     * never a second vocabulary. The prepared changes are written with {@link #applyChanges} inside the
     * creating transaction.
     *
     * <p>{@code prototype} is a detached object of the kind being created, never attached to a model: the
     * preparation reads only its class and its own values, and every reference it prepares is captured by
     * BM id, re-fetched inside the transaction.</p>
     *
     * @return the refusal JSON of the first property that does not validate, or {@code null}
     */
    public static String prepareForCreate(IProject project, MetadataScope scope, Configuration config, Version version, // NOSONAR the modify preparation's context, passed through as is
        MdObject prototype, List<JsonObject> properties, List<PreparedChange> changes,
        MdNameNormalizer.Report normReport, boolean isExtensionProject)
    {
        return validateAndPrepare(project, scope,
            config, version, prototype, properties, changes, normReport, isExtensionProject);
    }

    /** Checks against the batch's final type, regardless of the JSON property order. */
    public static String memberValueTypeError(EObject target, List<PreparedChange> changes)
    {
        boolean hasFillValue = changes.stream().anyMatch(change ->
            change.scalarValue instanceof MetadataMemberValues.Plan plan && plan.value() != null);
        if (!hasFillValue) return null;
        TypeDescription effective = MetadataMemberValues.typeOf(target);
        for (PreparedChange change : changes)
            if (change.isTypeChange() && change.scalarValue instanceof TypeDescription)
                effective = (TypeDescription)change.scalarValue;
        for (PreparedChange change : changes)
        {
            if (change.scalarValue instanceof MetadataMemberValues.Plan plan)
            {
                String error = plan.typeRefusal(effective);
                if (error != null) return error;
            }
        }
        return null;
    }

    /**
     * Refuses a field list ({@code inputByString} / {@code dataLockFields}) that NAMES a standard field whose
     * offer for that list the same call changes, through a property of the same target that decides it -
     * Code / Description / Number by {@code codeLength} / {@code descriptionLength} / {@code numberLength},
     * Parent and IsFolder by {@code hierarchical}, a catalog's IsFolder by {@code hierarchyType} and its
     * Owner by {@code owners}, ActionPeriodIsBasic by {@code actionPeriodUse} (issue #740; EDT's rules,
     * judged by {@link FieldListSupport#offerChange}).
     *
     * <p>The list is checked against the fields EDT derived from the object AS IT STANDS, so an entry is
     * judged right exactly when its field's offer is the same before and after the call - and such a batch
     * shares the call: {@code codeLength} from 9 to 10 with [Code], another non-empty {@code owners} list with
     * [Owner], {@code codeLength:0} with [Description]. When the call would END the offer, the entry would be
     * written although the object no longer offers it, and nothing reliable catches that afterwards: EDT's
     * model validator never re-checks a standard field of input by string and accepts a stale
     * ActionPeriodIsBasic in the data-lock list; it marks the other stale data-lock entries (error 81), but
     * only after modify_metadata has reported success. That refusal says to leave the field out of the list
     * or not to change the property - a second call would not bring the field back. When the call would
     * START the offer, the entry would be refused as not offered, and the refusal gives the separate-call
     * recipe. Both name the deciding properties the call changes. For an exchange plan's data-lock Code and
     * Description, which EDT's editor offers whatever the length, both speak of what EDT's validator accepts
     * ({@link FieldListSupport.OfferChange#byValidator}).</p>
     *
     * <p>The batch's values are read with the parsers their own preparation uses, and a repeated property - a
     * deciding one or a list - is decided by its last write, as when the batch is applied ({@link #mainFlagIn}
     * reads the main flag the same way). A value whose shape or parse that preparation refuses is no end state:
     * the property reads as unchanged, and its own refusal answers the call - nothing is written either way.
     * Owner FQNs are read by their shape only: whether each resolves to an object that can own a catalog is
     * checked by {@link #prepareManyReference}, against the project's scope, which the guard does not take. So an
     * {@code owners} list of FQNs that do not resolve still counts as non-empty: on a catalog without owners
     * the guard answers first, with the separate-call recipe, and the owners refusal comes on the next call -
     * nothing is written either way. A deciding property the target does not have is left to its own "not
     * assignable" refusal, and an adopted target to the field list's own refusal (its lists cannot be set at
     * all). Package-visible for the headless test.</p>
     *
     * @param target the object being modified
     * @param properties the raw batch
     * @return the ready JSON error, or {@code null} when the batch is safe
     */
    public static String fieldListBatchError(EObject target, List<JsonObject> properties)
    {
        if (FieldListSupport.isAdopted(target))
        {
            return null;
        }
        // Keyed by feature: a repeated list, like a repeated deciding property, is decided by its last write.
        Map<EStructuralFeature, PropertyInfo> lists = new LinkedHashMap<>();
        Map<EStructuralFeature, JsonElement> values = new LinkedHashMap<>();
        Map<EStructuralFeature, Object> ends = new LinkedHashMap<>();
        for (JsonObject prop : properties)
        {
            String name = prop == null ? null : asString(prop.get("name")); //$NON-NLS-1$
            PropertyInfo info = name == null ? null : MetadataPropertyIntrospector.findFeature(target, name);
            if (info == null)
            {
                continue;
            }
            if (info.valueKind == MetadataPropertyIntrospector.ValueKind.FIELD_LIST)
            {
                lists.put(info.feature, info);
                values.put(info.feature, prop.get(KEY_VALUE));
            }
            else if (FieldListSupport.isDecidingProperty(info.feature))
            {
                Object end = batchEndValue(info, prop.get(KEY_VALUE));
                if (end != null)
                {
                    ends.put(info.feature, end);
                }
                else
                {
                    ends.remove(info.feature);
                }
            }
        }
        for (Map.Entry<EStructuralFeature, PropertyInfo> list : lists.entrySet())
        {
            FieldListSupport.OfferChange change = FieldListSupport.offerChange(target, list.getKey(),
                values.get(list.getKey()), ends);
            if (change != null)
            {
                return ToolResult.error(change.offeredNow ? takesAwayRefusal(list.getValue().name, change)
                    : bringsBackRefusal(list.getValue().name, change)).toJson();
            }
        }
        return null;
    }

    /**
     * The value a write of a deciding property leaves, read by the parser that property's own preparation
     * uses ({@link #prepare}), or {@code null} when that parser refuses it: an {@code Integer} for a length, a
     * {@code Boolean} for a flag, the literal's instance for an enum, the FQN strings of a list reference of
     * the shape {@link #prepareManyReference} accepts (whether they resolve is that preparation's own check;
     * the guard reads only whether the list is empty).
     */
    public static Object batchEndValue(PropertyInfo info, JsonElement raw)
    {
        switch (info.valueKind)
        {
            case INTEGER:
                return parseInteger(asString(raw));
            case BOOLEAN:
                return parseBoolean(asString(raw));
            case ENUM:
                return enumInstance(info.feature, asString(raw));
            case MANY_REFERENCE:
                return referenceFqns(raw);
            default:
                return null;
        }
    }

    /** The instance of the enum literal {@code value} names, resolved as {@link #prepareEnum} does, or {@code null}. */
    public static Object enumInstance(EStructuralFeature feature, String value)
    {
        EEnumLiteral literal = MetadataPropertyIntrospector.resolveEnumLiteral(feature, value);
        return literal == null ? null : literal.getInstance();
    }

    /**
     * The FQN strings of a list-reference value, or {@code null} unless it is a JSON array of non-empty
     * strings - the shape {@link #prepareManyReference} accepts.
     */
    public static List<String> referenceFqns(JsonElement raw)
    {
        if (raw == null || !raw.isJsonArray())
        {
            return null;
        }
        List<String> fqns = new ArrayList<>();
        for (JsonElement el : raw.getAsJsonArray())
        {
            String fqn = (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
            if (fqn == null || fqn.isEmpty())
            {
                return null;
            }
            fqns.add(fqn);
        }
        return fqns;
    }

    /**
     * The refusal of a batch that would START the offer of the field the list names: the list is judged
     * before the property changes, so the property goes first and the list into a separate call. For a field
     * only EDT's validator decides ({@link FieldListSupport.OfferChange#byValidator}) the reason is what the
     * validator accepts: EDT's editor offers that field whatever the property says.
     */
    public static String bringsBackRefusal(String list, FieldListSupport.OfferChange change)
    {
        String properties = quotedNames(change.properties, " and "); //$NON-NLS-1$
        String judged = change.byValidator ? "whether EDT's validator accepts that field depends on " //$NON-NLS-1$
            : "whether EDT offers that field depends on "; //$NON-NLS-1$
        return "'" + list + "' cannot be set in the same call as " + properties + " while it names " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + change.address + ": " + judged + properties //$NON-NLS-1$
            + ", and the list is checked against the object as it stands before the call. Set " + properties //$NON-NLS-1$
            + " first, then '" + list + "' in a separate call - or leave " + change.address //$NON-NLS-1$ //$NON-NLS-2$
            + " out of the list."; //$NON-NLS-1$
    }

    /**
     * The refusal of a batch that would END the offer of the field the list names: the list would be left
     * naming a field EDT no longer offers - or, for a field only EDT's validator decides
     * ({@link FieldListSupport.OfferChange#byValidator}), one the validator rejects although EDT's editor
     * still offers it - and a second call would not bring the field back: so leave it out, or keep the
     * property.
     */
    public static String takesAwayRefusal(String list, FieldListSupport.OfferChange change)
    {
        String properties = quotedNames(change.properties, " and "); //$NON-NLS-1$
        String why = change.byValidator
            ? "EDT's validator accepts that field now, but would reject it once this call has changed " + properties //$NON-NLS-1$
                + ", so the list would be left naming a field EDT's validator marks as an error" //$NON-NLS-1$
            : "EDT offers that field now, but would no longer offer it once this call has changed " + properties //$NON-NLS-1$
                + ", so the list would be left naming a field EDT does not offer"; //$NON-NLS-1$
        return "'" + list + "' cannot be set in the same call as " + properties + " while it names " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + change.address + ": " + why + ". Leave " + change.address + " out of the list, or do not change " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + quotedNames(change.properties, " or ") + "."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Property names quoted for a refusal: 'a', 'a' and 'b', 'a', 'b' and 'c' - {@code last} joins the last two. */
    public static String quotedNames(List<String> names, String last)
    {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.size(); i++)
        {
            if (i > 0)
            {
                text.append(i == names.size() - 1 ? last : ", "); //$NON-NLS-1$
            }
            text.append('\'').append(names.get(i)).append('\'');
        }
        return text.toString();
    }

    /**
     * The dynamic-list branch's pre-check verdict for an ALREADY-RESOLVED attribute - the body of
     * {@link #dynamicListRetypePreflight}'s read, package-private so a unit test can drive the
     * decision itself without an EDT context. Must run inside the read transaction: it resolves the
     * main table against the model.
     *
     * @param config the configuration the main table is resolved against
     * @param formModel the tx-bound form model (its metamodel decides whether a list is possible)
     * @param member the resolved form attribute, or {@code null} when it does not exist
     * @param mainTable the requested main-table FQN, or {@code null}
     * @return a ready JSON error, {@code ""} for "do not prompt", or {@code null} to ask
     */
    public static String dynamicListRetypeVerdict(Configuration config, Version version, EObject formModel, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        EObject member, String mainTable)
    {
        if (member == null)
        {
            return ""; //$NON-NLS-1$
        }
        // A form model that cannot represent a dynamic list at all refuses the conversion no matter
        // what the user answers - the write raised it from inside the transaction (issue #295 review).
        String unsupported = FormElementWriter.dynamicListUnsupportedError(formModel);
        if (unsupported != null)
        {
            return unsupported;
        }
        // The main table is resolved HERE, in the same read, because the write callback resolves it
        // too late: an FQN that names nothing would raise the destructive prompt first and answer the
        // resolution failure only after ALLOW (issue #295 review).
        String mainTableErr = FormElementWriter.mainTableResolutionError(config, mainTable);
        if (mainTableErr != null)
        {
            return mainTableErr;
        }
        if (FormElementWriter.isDynamicListAttribute(member))
        {
            return ""; //$NON-NLS-1$
        }
        String orphan = orphanColumnsError(member);
        if (orphan != null)
        {
            return orphan;
        }
        // LAST, because it only matters once a conversion is really going to happen: the value type
        // the conversion must build. Unbuildable = the write refuses whatever the user answers, and it
        // refuses only AFTER having set the ext-info classifier - so this belongs above the gate, and
        // the version is resolved before it (issue #295 review).
        return FormElementWriter.dynamicListTypeUnavailableError(version);
    }

    /**
     * The stranded-columns refusal for {@code member}, or {@code null} when it owns no columns. Single
     * owner of the wording, because TWO paths can retype a form attribute: the ordinary property path
     * and the dynamic-list branch, which swaps the value type for {@code DynamicList} without ever
     * building a {@code TypeDescription}. Issue #295.
     *
     * @param member the form member about to be retyped
     * @return a ready JSON error naming the columns, or {@code null}
     */
    public static String orphanColumnsError(EObject member)
    {
        List<String> columns = FormElementWriter.attributeColumnNames(member);
        if (columns.isEmpty())
        {
            return null;
        }
        return ToolResult.error("This form attribute still has " + columns.size() + " column(s) (" //$NON-NLS-1$ //$NON-NLS-2$
            + String.join(", ", columns) + "), which only a collection type can own. Delete the " //$NON-NLS-1$ //$NON-NLS-2$
            + "columns first with delete_metadata, or keep a collection type (ValueTable / " //$NON-NLS-1$
            + "ValueTree).").toJson(); //$NON-NLS-1$
    }

    public static final String PROP_HANDLER = "handler";

    /**
     * Immutable bundle of {@link #prepare}'s {@link Configuration} + {@link Version} parameters - the
     * model context every {@code ValueKind} branch resolves references / types against. Folded together
     * purely to bring {@link #prepare}'s parameter count under the 7-parameter threshold (S107); no
     * behaviour change. {@code project} is nullable: it is {@code null} on the form-member path (a form
     * member is never a ScheduledJob / EventSubscription, so {@link #validateMethodReference} never
     * dereferences it there) and only non-null on the mdclass path.
     */
    public static final class PrepareContext
    {
        public final IProject project;

        public final Configuration config;

        /** The ROOT an FQN resolves against: a configuration, or external-objects roots. */
        public final MetadataScope scope;

        public final Version version;

        /** Codes declared AFTER this batch; {@code null} when it changes no language code. */
        public final List<String> declaredAfterBatch;

        /**
         * What a {@code TYPE_DESCRIPTION} property prepared in this context will be attached to. It is
         * the CALL SITE that knows this (the form-member path vs the mdclass path), so the answer is
         * carried here rather than sniffed off the resolved feature - issue #295.
         */
        public final MetadataTypeBuilder.TypeTarget typeTarget;
        private final TypeBuilder typeBuilder;

        public PrepareContext(IProject project, MetadataScope scope, Configuration config, Version version,
            List<String> declaredAfterBatch)
        {
            this(project, scope, config, version, declaredAfterBatch,
                MetadataTypeBuilder.TypeTarget.METADATA, MetadataPropertyApplier::buildTypeDescription);
        }

        private PrepareContext(IProject project, MetadataScope scope, Configuration config, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            Version version,
            List<String> declaredAfterBatch, MetadataTypeBuilder.TypeTarget typeTarget, TypeBuilder typeBuilder)
        {
            this.project = project;
            this.scope = scope == null ? MetadataScope.ofConfiguration(config) : scope;
            this.config = config;
            this.version = version;
            this.declaredAfterBatch = declaredAfterBatch;
            this.typeTarget = typeTarget;
            this.typeBuilder = typeBuilder;
        }

        /**
         * The FORM-member variant. A form attribute is the one place the platform holds an in-memory
         * collection type (ValueTable / ValueTree), so this context admits those kinds (#295); on the
         * mdclass path only a defined type's own type does, through
         * {@link MetadataPropertyApplier#typeTargetForFeature} (#727).
         * {@code project} is {@code null} here, as the class doc explains.
         *
         * @param scope the resolution root the member belongs to
         * @param version the platform version
         * @return a context whose type target is a form attribute
         */
        public static PrepareContext forFormMember(MetadataScope scope, Version version)
        {
            return forFormMember(scope, version, MetadataPropertyApplier::buildTypeDescription);
        }

        public static PrepareContext forFormMember(MetadataScope scope, Version version, TypeBuilder typeBuilder)
        {
            MetadataScope effective = scope == null ? MetadataScope.ofConfiguration(null) : scope;
            return new PrepareContext(null, effective, effective.configuration(), version, null,
                MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE, typeBuilder);
        }
    }

    /**
     * The language codes the configuration will declare AFTER this batch is applied, or {@code null}
     * when the batch changes no language code (the caller then uses the model's current set).
     * Issue #298.
     *
     * <p>The set is the configuration's languages with the TARGET language's code REPLACED by the
     * one this batch assigns - not the union of old and new. A batch that renames a code
     * ({@code en} -&gt; {@code fr}) leaves no {@code en} behind, so a value written under {@code en}
     * in that same batch would be invisible and must be refused, exactly like any other undeclared
     * code. A batch that gives a NEW language its first code adds it, because the target's old code
     * is empty.
     *
     * <p>Safe by construction: the whole batch is prepared before anything is written, so if the
     * {@code languageCode} entry is itself rejected the call fails and nothing is applied.
     *
     * @param config the configuration
     * @param target the object being modified
     * @param properties the raw properties array (may be {@code null})
     * @return the post-batch codes, or {@code null} when this batch changes no language code
     */
    public static List<String> declaredCodesAfterBatch(Configuration config, MdObject target,
        List<JsonObject> properties)
    {
        if (config == null || !(target instanceof Language) || properties == null)
        {
            return null;
        }
        String newCode = null;
        for (JsonObject prop : properties)
        {
            if ("languageCode".equalsIgnoreCase(asString(prop.get("name")))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String value = asString(prop.get(KEY_VALUE));
                if (value != null && !value.isEmpty())
                {
                    newCode = value;
                }
            }
        }
        if (newCode == null)
        {
            return null;
        }
        List<String> codes = new ArrayList<>();
        boolean targetSeen = false;
        String defaultAfter = null;
        Language defaultLanguage = config.getDefaultLanguage();
        for (Language lang : config.getLanguages())
        {
            if (lang == null)
            {
                continue;
            }
            boolean isTarget = lang == target || lang.getName() != null
                && lang.getName().equals(((Language)target).getName());
            targetSeen |= isTarget;
            String code = isTarget ? newCode : lang.getLanguageCode();
            if (isDefaultLanguage(defaultLanguage, lang))
            {
                defaultAfter = code;
            }
            if (code != null && !code.isEmpty() && !codes.contains(code))
            {
                codes.add(code);
            }
        }
        if (!targetSeen && !codes.contains(newCode))
        {
            codes.add(newCode);
        }
        // The DEFAULT language's post-edit code goes FIRST: it is what a localized value with no
        // explicit 'language' must fall back to once the batch has renamed the old default code
        // away. Counting what is left cannot answer that - a second, untouched language leaves two
        // codes and neither of them is "the default" by position alone.
        if (defaultAfter != null && !defaultAfter.isEmpty() && codes.remove(defaultAfter))
        {
            codes.add(0, defaultAfter);
        }
        return codes;
    }

    /** Whether {@code lang} IS the configuration's default language (by identity, else by name). */
    public static boolean isDefaultLanguage(Language defaultLanguage, Language lang)
    {
        if (defaultLanguage == null || lang == null)
        {
            return false;
        }
        return defaultLanguage == lang
            || defaultLanguage.getName() != null && defaultLanguage.getName().equals(lang.getName());
    }

    /**
     * Validates one property against the introspected schema and, on success, appends a
     * {@link PreparedChange}. Returns a JSON error string on failure, or {@code null} on success.
     * Accepts any {@link EObject} so it serves both mdclass nodes and form members (the introspector
     * and the prepared change are EClass-driven, not mdclass-specific).
     *
     * <p>{@code extInfo} is the element's nested {@code <extInfo>} EObject (a form element's layout /
     * kind-specific sub-object, e.g. a UsualGroup's {@code UsualGroupExtInfo}) when the property may
     * live there, or {@code null} on the mdclass path (an mdclass object has no extInfo, so the
     * extInfo traversal is a no-op and this behaves exactly as before). A property found on the
     * extInfo carries {@code info.onExtInfo == true}; the {@link PreparedChange} is built against the
     * extInfo's feature, and the caller routes the {@code eSet} to the extInfo holder.</p>
     *
     * <p>{@code isExtensionProject} is threaded down only to append the extension-adopt hint (issue
     * #262) to an unresolved {@code TYPE_DESCRIPTION} reference; every other {@code ValueKind} ignores
     * it.</p>
     */
    public static String prepare(PrepareContext ctx, EObject target, EObject extInfo,
        JsonObject prop, List<PreparedChange> out, MdNameNormalizer.Report normReport,
        boolean isExtensionProject)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("Each entry in 'properties' needs a non-empty 'name'.").toJson(); //$NON-NLS-1$
        }
        if ("name".equalsIgnoreCase(name)) //$NON-NLS-1$
        {
            return ToolResult.error("Renaming via the 'name' property is not allowed here: use " //$NON-NLS-1$
                + "rename_metadata_object, which cascades the rename across BSL code, forms and " //$NON-NLS-1$
                + "metadata. modify_metadata only sets non-identity properties.").toJson(); //$NON-NLS-1$
        }
        String value = asString(prop.get(KEY_VALUE));

        // Guard (scoped to exactly two type+property combos - a no-op for everything else, including
        // every property on the form-member path where ctx.project is null): a ScheduledJob's
        // methodName / an EventSubscription's handler must reference an EXISTING, Exported, Server-side
        // CommonModule method BEFORE it is accepted, so binding a job/subscription at a function that
        // does not exist yet fails HERE with an actionable error instead of only at update_database
        // ("no such function"). An empty value is left to the pre-existing generic-STRING policy below
        // (requireValueError: modify_metadata never "clears" a property on an empty value) rather than
        // being reported as a method-reference failure.
        String methodRefErr = validateMethodReference(ctx.project, ctx.config, target, name, value);
        if (methodRefErr != null)
        {
            return methodRefErr;
        }
        // A VALID reference is re-written to its canonical stored form (English CommonModule prefix;
        // resolved module casing) so a tolerated variant like 'Calc.Add' / 'ОбщийМодуль.Calc.Add'
        // never serializes verbatim into the model where the platform's own resolution would miss it.
        value = canonicalMethodReference(ctx.config, target, name, value);

        // findFeature classifies ONLY the matched feature and skips the current-value rendering
        // (eGet + proxy + type rendering) that the full introspect() performs for EVERY assignable
        // feature - prepare() never reads currentValue, and this runs on the UI thread per property.
        // A direct feature of `target` wins; only when it has none does a matching feature on the
        // element's `extInfo` win (info.onExtInfo). On the mdclass path extInfo is null - a no-op.
        PropertyInfo info = MetadataPropertyIntrospector.findFeature(target, extInfo, name);
        if (info == null)
        {
            // The "available properties" hint covers the extInfo layout props too (the now-extended
            // assignable set): its EClass comes from the live extInfo instance, or - when the slot is
            // empty - is derived reflectively; null on the mdclass path (member-only, unchanged).
            EClass extInfoEClass = extInfo != null ? extInfo.eClass()
                : FormElementWriter.resolveExtInfoEClass(target);
            return nonAssignablePropertyError(target, name, extInfoEClass, null);
        }

        switch (info.valueKind)
        {
            case LOCALIZED_STRING:
                return prepareLocalized(ctx, name, value, prop, info, out, normReport);
            case ENUM:
                if (target instanceof BasicTemplate
                    && info.feature == MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE_TYPE)
                {
                    TemplateType type = TemplateContentSupport.resolveType(value);
                    String typeError = TemplateContentSupport.unsupportedRetypeError(type, value);
                    if (typeError != null && (type == null || type != ((BasicTemplate)target).getTemplateType()))
                    {
                        return typeError;
                    }
                    out.add(PreparedChange.scalar(info.feature, type));
                    return null;
                }
                return prepareEnum(name, value, info, out);
            case MANY_ENUM:
                return prepareManyEnum(name, prop, info, out);
            case BOOLEAN:
                return prepareBoolean(name, value, info, out);
            case INTEGER:
                return prepareInteger(name, value, info, out);
            case LONG:
                return prepareLong(name, value, info, out);
            case TYPE_DESCRIPTION:
                return prepareTypeDescription(ctx, target, name, prop, info, out, isExtensionProject);
            case REFERENCE:
                return prepareReference(ctx, target, name, value, info, out);
            case MANY_REFERENCE:
                return prepareManyReference(ctx.scope, name, prop, info, out);
            case FORM_CHOICE_VALUES:
                try
                {
                    FormChoiceParameters.Plan values = FormChoiceParameters.prepare(ctx.scope, target,
                        info.feature.getName(), prop.get(KEY_VALUE));
                    out.add(PreparedChange.formChoiceValues(info.feature, values));
                    return null;
                }
                catch (IllegalArgumentException e)
                {
                    return ToolResult.error(e.getMessage()).toJson();
                }
            case MEMBER_VALUES:
                try
                {
                    MetadataMemberValues.Plan values = MetadataMemberValues.prepare(ctx.scope, target,
                        info.feature.getName(), prop.get(KEY_VALUE));
                    out.add(PreparedChange.memberValues(info.feature, values));
                    return null;
                }
                catch (IllegalArgumentException e)
                {
                    return ToolResult.error(e.getMessage()).toJson();
                }
            case MCORE_VALUE_LIST:
                return prepareMcoreValueList(ctx.scope, name, prop, info, out);
            case STYLE_VALUE:
                return prepareStyleValue(ctx, name, prop, target, info, out);
            case PICTURE:
                return preparePicture(ctx, name, prop, info, out);
            case QNAME:
                return prepareQName(name, prop, info, out);
            case ADJUSTABLE_BOOLEAN:
                return prepareAdjustableBoolean(ctx.config, name, value, prop, info, out);
            case COLOR:
            case FONT:
                return prepareAppearance(ctx, target, name, prop, info, out);
            case USE_ALWAYS:
                return prepareUseAlways(target, prop, info, out);
            case FIELD_LIST:
                return prepareFieldList(target, name, prop, info, out);
            case EXCLUDED_COMMANDS:
                ExcludedCommandsSupport.Plan plan = ExcludedCommandsSupport.plan(target, prop.get(KEY_VALUE));
                if (plan.error() != null)
                {
                    return ToolResult.error(plan.error()).toJson();
                }
                out.add(PreparedChange.excludedCommands(info.feature, plan));
                return null;
            case STRING:
            default:
                return prepareString(name, value, info, out, normReport);
        }
    }

    /** Builds the shared actionable error for a property outside an EObject's assignable surface. */
    public static String nonAssignablePropertyError(EObject target, String name, EClass extInfoEClass,
        String additionalHint)
    {
        String discovery = additionalHint == null
            ? "Use get_metadata_details with assignable:true for kinds + allowed values." //$NON-NLS-1$
            : additionalHint;
        String targetLabel = additionalHint == null ? target.eClass().getName()
            : "the form content root (" + target.eClass().getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        String propertiesLabel = additionalHint == null ? "Assignable properties: " //$NON-NLS-1$
            : "Form content root assignable properties: "; //$NON-NLS-1$
        return ToolResult.error("Property '" + name + "' is not assignable on " //$NON-NLS-1$ //$NON-NLS-2$
            + targetLabel + ". " + propertiesLabel //$NON-NLS-1$
            + String.join(", ", MetadataPropertyIntrospector.assignableNames(target, extInfoEClass)) //$NON-NLS-1$
            + ". " + discovery).toJson(); //$NON-NLS-1$
    }

    /**
     * Dispatches the maintainer-requested method-reference guard (a job/subscription must be bound to a
     * method that already EXISTS, is EXPORTED and lives in a SERVER module) to {@link
     * MethodReferenceValidator}, scoped to exactly the two type+property combos it covers. Every other
     * target/property - including any property on the form-member path, where {@code project} is
     * {@code null} - returns {@code null} immediately without touching {@code project} / {@code config}.
     * An empty value is NOT validated here (it falls through to the pre-existing generic-STRING policy,
     * which itself rejects an empty value rather than "clearing" the property). Package-visible (takes
     * the plain {@code project}/{@code config} pair rather than the private {@link PrepareContext}) so
     * the dispatch is unit-testable headlessly, mirroring {@link #formTypeExtInfoComboError}.
     */
    public static String validateMethodReference(IProject project, Configuration config, EObject target, String name,
        String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        if (target instanceof ScheduledJob && PROP_METHOD_NAME.equalsIgnoreCase(name))
        {
            return MethodReferenceValidator.validate(project, config, value, PROP_METHOD_NAME,
                "'CommonModule.ModuleName.MethodName'", "CommonModule.Calc.Add"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (target instanceof EventSubscription && PROP_HANDLER.equalsIgnoreCase(name))
        {
            return MethodReferenceValidator.validate(project, config, value, PROP_HANDLER,
                "'CommonModule.ModuleName.MethodName'", "CommonModule.Calc.Add"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Canonicalizes an ALREADY-VALIDATED method reference for the two guarded combos (see
     * {@link #validateMethodReference}): both a ScheduledJob's {@code methodName} and an
     * EventSubscription's {@code handler} store {@code CommonModule.Module.Method}, with the RESOLVED
     * module's exact metadata name. Any other target/property - or a defensive resolution failure -
     * returns the value unchanged.
     */
    public static String canonicalMethodReference(Configuration config, EObject target, String name, String value)
    {
        if (value == null || value.isEmpty())
        {
            return value;
        }
        String canonical = null;
        if ((target instanceof ScheduledJob && PROP_METHOD_NAME.equalsIgnoreCase(name))
            || (target instanceof EventSubscription && PROP_HANDLER.equalsIgnoreCase(name)))
        {
            canonical = MethodReferenceValidator.canonicalReference(config, value);
        }
        return canonical != null ? canonical : value;
    }

    /**
     * Validates an {@code ENUM} property value against the feature's literals and, on success,
     * appends the prepared scalar change to {@code out}. Returns a JSON error listing the allowed
     * values on failure, or {@code null} on success. Extracted verbatim from {@link #prepare}.
     */
    public static String prepareEnum(String name, String value, PropertyInfo info,
        List<PreparedChange> out)
    {
        EEnumLiteral literal = MetadataPropertyIntrospector.resolveEnumLiteral(info.feature, value);
        if (literal == null)
        {
            return ToolResult.error("'" + value + "' is not a valid value for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Allowed: " + String.join(", ", info.allowedValues) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.add(PreparedChange.scalar(info.feature, literal.getInstance()));
        return null;
    }

    /**
     * Validates a {@code MANY_ENUM} property and queues a whole-list replacement. A JSON array of
     * literal strings is canonical; a bare string is accepted as a one-element replacement. Every
     * literal is resolved by the same case-insensitive resolver as scalar {@code ENUM}.
     */
    public static String prepareManyEnum(String name, JsonObject prop, PropertyInfo info,
        List<PreparedChange> out)
    {
        JsonElement raw = prop.get(KEY_VALUE);
        List<JsonElement> elements = new ArrayList<>();
        boolean arrayInput = raw != null && raw.isJsonArray();
        if (arrayInput)
        {
            for (JsonElement element : raw.getAsJsonArray())
            {
                elements.add(element);
            }
        }
        else if (raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString())
        {
            elements.add(raw);
        }
        else
        {
            return invalidManyEnumShape(name, raw, -1);
        }

        List<Object> values = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++)
        {
            JsonElement element = elements.get(i);
            if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString())
            {
                return invalidManyEnumShape(name, element, i);
            }
            String value = element.getAsString();
            EEnumLiteral literal = MetadataPropertyIntrospector.resolveEnumLiteral(info.feature, value);
            if (literal == null)
            {
                String offender = arrayInput ? "Element at index " + i + " ('" + value + "')" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    : "'" + value + "'"; //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.error(offender + " is not a valid value for '" + name //$NON-NLS-1$
                    + "'. Allowed: " + String.join(", ", info.allowedValues) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            values.add(literal.getInstance());
        }
        out.add(PreparedChange.manyEnum(info.feature, values));
        return null;
    }

    /** A shape refusal that quotes the bad JSON and states both accepted replacement forms. */
    public static String invalidManyEnumShape(String name, JsonElement badValue, int index)
    {
        String location = index >= 0 ? " at index " + index : ""; //$NON-NLS-1$ //$NON-NLS-2$
        String rendered = badValue == null ? "missing" : badValue.toString(); //$NON-NLS-1$
        return ToolResult.error("Invalid value" + location + " for '" + name + "': " + rendered //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ". Expected a JSON array of enum literal strings, e.g. [\"PersonalComputer\"], " //$NON-NLS-1$
            + "or a bare enum literal string as shorthand for a one-element replacement.").toJson(); //$NON-NLS-1$
    }

    /**
     * Validates a {@code BOOLEAN} property value and, on success, appends the prepared scalar
     * change to {@code out}. Returns a JSON error on a non-boolean value, or {@code null} on
     * success. Extracted verbatim from {@link #prepare}.
     */
    public static String prepareBoolean(String name, String value, PropertyInfo info,
        List<PreparedChange> out)
    {
        Boolean b = parseBoolean(value);
        if (b == null)
        {
            return ToolResult.error("'" + value + "' is not a valid boolean for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Use true or false.").toJson(); //$NON-NLS-1$
        }
        out.add(PreparedChange.scalar(info.feature, b));
        return null;
    }

    /**
     * Validates an {@code ADJUSTABLE_BOOLEAN} property value and, on success, appends the prepared
     * change to {@code out}. A plain boolean addresses the nested {@code common} flag and keeps the
     * role values (issue #382); {@code {common?, roles?}} also edits the role values (issue #719).
     *
     * @param config the configuration the role names resolve in, may be {@code null}
     * @param name the property name (for the error text)
     * @param value the raw wire value as a string ({@code null} for an object)
     * @param prop the property entry
     * @param info the introspected property
     * @param out the prepared-change sink
     * @return a JSON error, or {@code null} on success
     */
    public static String prepareAdjustableBoolean(Configuration config, String name, String value,
        JsonObject prop, PropertyInfo info, List<PreparedChange> out)
    {
        JsonElement raw = prop.get(KEY_VALUE);
        if (raw != null && raw.isJsonObject())
        {
            return prepareAdjustableBooleanObject(config, name, raw.getAsJsonObject(), info, out);
        }
        Boolean b = parseBoolean(value);
        if (b == null)
        {
            return ToolResult.error("'" + (value != null ? value : String.valueOf(raw)) //$NON-NLS-1$
                + "' is not a valid boolean for '" + name //$NON-NLS-1$
                + "'. Use true or false, or {common?: bool, roles?: {'Role.<Name>': true|false|'default'}} " //$NON-NLS-1$
                + "to set per-role values.").toJson(); //$NON-NLS-1$
        }
        out.add(PreparedChange.adjustableBoolean(info.feature, b.booleanValue()));
        return null;
    }

    /**
     * The object form of an {@code ADJUSTABLE_BOOLEAN}: {@code common} optional, {@code roles} a map of
     * role (by {@code Role.<Name>} or bare Name) to {@code true} / {@code false} / {@code 'default'}
     * (drops the role's value). Every role is resolved here, before anything is written.
     */
    public static String prepareAdjustableBooleanObject(Configuration config, String name, JsonObject value,
        PropertyInfo info, List<PreparedChange> out)
    {
        String shape = "{common?: true|false, roles?: {'Role.<Name>': true|false|'default'}}"; //$NON-NLS-1$
        for (String key : value.keySet())
        {
            if (!KEY_COMMON.equals(key) && !KEY_ROLES.equals(key))
            {
                return ToolResult.error("'" + name + "' has no member '" + key + "'. Use " + shape + ".") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .toJson();
            }
        }
        Boolean common = null;
        if (value.has(KEY_COMMON))
        {
            common = AdjustableBooleanSupport.booleanValue(value.get(KEY_COMMON));
            if (common == null)
            {
                return ToolResult.error("'" + name + "'.common must be true or false, got " //$NON-NLS-1$ //$NON-NLS-2$
                    + value.get(KEY_COMMON) + ".").toJson(); //$NON-NLS-1$
            }
        }
        Map<Role, Boolean> roles = new LinkedHashMap<>();
        if (value.has(KEY_ROLES))
        {
            JsonElement rolesEl = value.get(KEY_ROLES);
            if (!rolesEl.isJsonObject())
            {
                return ToolResult.error("'" + name + "'.roles must be an object keyed by role, e.g. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "{'Role.Manager': false}; got " + rolesEl + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            for (Map.Entry<String, JsonElement> entry : rolesEl.getAsJsonObject().entrySet())
            {
                Role role = AdjustableBooleanSupport.resolveRole(config, entry.getKey(), true);
                if (role == null)
                {
                    return ToolResult.error("'" + name + "'.roles: '" + entry.getKey() //$NON-NLS-1$ //$NON-NLS-2$
                        + "' is not a role of the configuration. Name it as 'Role.<Name>' or '<Name>' by its " //$NON-NLS-1$
                        + "programmatic Name; get_metadata_objects with metadataType 'Role' lists them.") //$NON-NLS-1$
                        .toJson();
                }
                if (roles.containsKey(role))
                {
                    return ToolResult.error("'" + name + "'.roles names the role '" + role.getName() //$NON-NLS-1$ //$NON-NLS-2$
                        + "' twice (as '" + entry.getKey() + "' too). Give each role once.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                JsonElement roleValue = entry.getValue();
                Boolean state = AdjustableBooleanSupport.isDefaultToken(roleValue) ? null
                    : AdjustableBooleanSupport.booleanValue(roleValue);
                if (state == null && !AdjustableBooleanSupport.isDefaultToken(roleValue))
                {
                    return ToolResult.error("'" + name + "'.roles['" + entry.getKey() //$NON-NLS-1$ //$NON-NLS-2$
                        + "'] must be true, false or 'default' (drops the role's value), got " + roleValue //$NON-NLS-1$
                        + ".").toJson(); //$NON-NLS-1$
                }
                roles.put(role, state);
            }
        }
        if (common == null && roles.isEmpty())
        {
            return ToolResult.error("'" + name + "' changes nothing: give 'common' and/or 'roles' - " + shape //$NON-NLS-1$ //$NON-NLS-2$
                + ". A null role value is dropped in transit; use 'default' to drop a role's value.").toJson(); //$NON-NLS-1$
        }
        out.add(PreparedChange.adjustableBoolean(info.feature, new AdjustableBooleanEdit(common, roles)));
        return null;
    }

    /**
     * Validates a form attribute's {@code useAlways} checkboxes (issue #661) against the platform's data
     * tree and EDT's checkbox rule, before anything is written, and appends the prepared change.
     */
    public static String prepareUseAlways(EObject target, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out)
    {
        if (!(target instanceof FormAttribute))
        {
            return ToolResult.error("'" + UseAlwaysSupport.PROPERTY + "' is set on a form attribute " //$NON-NLS-1$ //$NON-NLS-2$
                + "('...Form.<Form>.Attribute.<Name>').").toJson(); //$NON-NLS-1$
        }
        FormAttribute attribute = (FormAttribute)target;
        List<UseAlwaysSupport.Request> requests = new ArrayList<>();
        String err = UseAlwaysSupport.parse(attribute.getName(), prop.get(KEY_VALUE), requests);
        List<UseAlwaysSupport.Plan> plans = new ArrayList<>();
        if (err == null)
        {
            err = UseAlwaysSupport.plan(formOf(attribute), attribute, requests, plans);
        }
        if (err != null)
        {
            return ToolResult.error(err).toJson();
        }
        out.add(PreparedChange.useAlways(info.feature, plans));
        return null;
    }

    /**
     * Validates a field list ({@code inputByString} / {@code dataLockFields}, issue #740) against the
     * fields EDT offers for it on the object as it stands, and queues a whole-list replacement carrying
     * field ids only - the derived fields are rebuilt by EDT between now and the write, a field id is
     * not.
     */
    public static String prepareFieldList(EObject target, String name, JsonObject prop, PropertyInfo info,
        List<PreparedChange> out)
    {
        List<String> fieldIds = new ArrayList<>();
        String refusal = FieldListSupport.resolve(target, info.feature, name, prop.get(KEY_VALUE), fieldIds);
        if (refusal != null)
        {
            return ToolResult.error(refusal).toJson();
        }
        out.add(PreparedChange.fieldList(info.feature, fieldIds));
        return null;
    }

    /** The form content model holding {@code member}, or {@code null} when it is detached. */
    public static EObject formOf(EObject member)
    {
        EObject current = member;
        while (current != null && !(current instanceof Form))
        {
            current = current.eContainer();
        }
        return current;
    }

    /**
     * Validates an {@code INTEGER} property value and, on success, appends the prepared scalar
     * change to {@code out}. Returns a JSON error on a non-integer value, or {@code null} on
     * success. Extracted verbatim from {@link #prepare}.
     */
    public static String prepareInteger(String name, String value, PropertyInfo info,
        List<PreparedChange> out)
    {
        Integer i = parseInteger(value);
        if (i == null)
        {
            return ToolResult.error("'" + value + "' is not a valid integer for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                + "'.").toJson(); //$NON-NLS-1$
        }
        out.add(PreparedChange.scalar(info.feature, i));
        return null;
    }

    /**
     * Validates a {@code LONG} property value and, on success, appends the prepared scalar change as
     * a {@link Long}. Returns an actionable JSON error when the value is fractional or outside the
     * signed 64-bit range, or {@code null} on success.
     */
    public static String prepareLong(String name, String value, PropertyInfo info,
        List<PreparedChange> out)
    {
        Long l = parseLong(value);
        if (l == null)
        {
            return ToolResult.error("'" + value + "' is not a valid 64-bit integer for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Use a whole number from " + Long.MIN_VALUE + " to " + Long.MAX_VALUE + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.add(PreparedChange.scalar(info.feature, l));
        return null;
    }

    /**
     * Validates a plain {@code STRING} property (the default value kind) and, on success, appends
     * the prepared scalar change (with the yo-normalization applied) to {@code out}. Returns a
     * JSON error on a missing value, or {@code null} on success. Extracted verbatim from
     * {@link #prepare}.
     */
    public static String prepareString(String name, String value, PropertyInfo info,
        List<PreparedChange> out, MdNameNormalizer.Report normReport)
    {
        if (value == null || value.isEmpty())
        {
            return requireValueError(name);
        }
        out.add(PreparedChange.scalar(info.feature,
            normalizeStringPropertyValue(name, value, normReport)));
        return null;
    }

    /**
     * Validates a {@code LOCALIZED_STRING} property (resolving its synonym language code) and, on
     * success, appends the prepared localized change to {@code out}. Returns a JSON error on failure,
     * or {@code null} on success. Read-only: it only builds and queues the change (no model mutation).
     */
    public static String prepareLocalized(PrepareContext ctx, String name, String value, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out, MdNameNormalizer.Report normReport)
    {
        if (value == null || value.isEmpty())
        {
            return requireValueError(name);
        }
        String code;
        try
        {
            // Validate against what the configuration will declare AFTER this batch: an edit that
            // sets a Language's 'languageCode' and a localized value under it must not reject its own
            // second half, and one that RENAMES a code must not accept the code it removes.
            code = ctx.scope.resolveSynonymLanguage(value,
                asString(prop.get("language")), "'" + name + "'", ctx.declaredAfterBatch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        out.add(PreparedChange.localized(info.feature, code, normReport.apply(name, value)));
        return null;
    }

    /**
     * Validates a {@code TYPE_DESCRIPTION} property (building the type description for the resolved
     * platform version) and, on success, appends the prepared scalar change to {@code out}. Returns a
     * JSON error on failure, or {@code null} on success. Read-only: it only builds and queues the
     * change (no model mutation). {@code isExtensionProject} is forwarded to
     * {@link MetadataTypeBuilder#build(JsonElement, Configuration, MetadataScope, Version, boolean,
     * MetadataTypeBuilder.TypeTarget)} so an unresolved reference target's error can append the
     * extension-adopt hint (issue #262); {@code ctx.typeTarget} rides along so the in-memory collection
     * kinds are admitted on a form attribute and refused on a stored metadata feature (issue #295),
     * with the feature-level exceptions of {@link #typeTargetForFeature}. A refusal of a defined type's
     * own type names that defined type ({@link #definedTypeOwner}).
     */
    public static String prepareTypeDescription(PrepareContext ctx, EObject target, String name,
        JsonObject prop, PropertyInfo info, List<PreparedChange> out, boolean isExtensionProject)
    {
        if (ctx.version == null)
        {
            return ToolResult.error("Cannot resolve the platform version needed to build a " //$NON-NLS-1$
                + "type for '" + name + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MetadataTypeBuilder.TypeTarget typeTarget = typeTargetForFeature(ctx.typeTarget, info.feature);
        MetadataTypeBuilder.Result tr = ctx.typeBuilder.build(prop.get(KEY_VALUE), ctx.config,
            ctx.scope, ctx.version, isExtensionProject, typeTarget);
        if (tr.error != null)
        {
            String message = "Invalid 'type' for '" + name + "'" + definedTypeOwner(typeTarget, target) //$NON-NLS-1$ //$NON-NLS-2$
                + ": " + tr.error; //$NON-NLS-1$
            if (tr.platformFailure)
            {
                // Not the caller's spec: the platform failed, so it reaches the log as an ERROR with a stack.
                Activator.logError("Error building the type for '" + name + "'", tr.asException(message)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return ToolResult.error(message).toJson();
        }
        FormValueTypePolicy.Problem problem = info.feature == target.eClass().getEStructuralFeature("valueType") //$NON-NLS-1$
            ? FormValueTypePolicy.check(target, tr.typeDescription) : null;
        if (problem != null)
        {
            return ToolResult.error(problem.message()).toJson();
        }
        String offerError = genericReferenceOfferError(typeTarget, target, info.feature, name, tr.typeDescription);
        if (offerError != null)
        {
            return ToolResult.error(offerError).toJson();
        }
        out.add(PreparedChange.typeDescription(info.feature, tr.typeDescription));
        return null;
    }

    /**
     * EDT's verdict on a generic reference type ({@code {kind:'DocumentRef'}}, issue #966) for a stored
     * feature or a session parameter: EDT's type editor of that very feature decides
     * ({@link GenericReferenceTypeOffer}). A form member is judged by the form type picker
     * ({@link FormValueTypePolicy}) and a defined type by its own category, so neither is asked twice.
     *
     * @return the refusal message, or {@code null} when the type may be written
     */
    static String genericReferenceOfferError(MetadataTypeBuilder.TypeTarget typeTarget, EObject target,
        EStructuralFeature feature, String name, TypeDescription built)
    {
        if (typeTarget != MetadataTypeBuilder.TypeTarget.METADATA
            && typeTarget != MetadataTypeBuilder.TypeTarget.SESSION_PARAMETER)
        {
            return null;
        }
        GenericReferenceTypeOffer.Problem offer = GenericReferenceTypeOffer.check(target, feature, built);
        if (offer == null)
        {
            return null;
        }
        String message = "Invalid 'type' for '" + name + "': " + offer.message(); //$NON-NLS-1$ //$NON-NLS-2$
        if (offer.platformFailure())
        {
            Activator.logError("Error checking the type for '" + name + "'", new IllegalStateException(message)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return message;
    }

    /** Platform dependency seam; preparation and its guards remain shared with the live writer. */
    public static MetadataTypeBuilder.Result buildTypeDescription(JsonElement spec, Configuration config,
        MetadataScope scope, Version version, boolean extension, MetadataTypeBuilder.TypeTarget target)
    {
        return MetadataTypeBuilder.build(spec, config, scope, version, extension, target);
    }

    /**
     * Adds the feature-level exceptions on top of the call site's form-vs-mdclass target: an event
     * subscription's source (#543), a session parameter's type (#646) and a defined type's own type,
     * which takes what EDT's DefinedType type editor offers (#727).
     */
    public static MetadataTypeBuilder.TypeTarget typeTargetForFeature(
        MetadataTypeBuilder.TypeTarget contextTarget, EStructuralFeature feature)
    {
        if (contextTarget != MetadataTypeBuilder.TypeTarget.METADATA)
        {
            return contextTarget;
        }
        if (feature == MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE)
        {
            return MetadataTypeBuilder.TypeTarget.EVENT_SOURCE;
        }
        if (feature == MdClassPackage.Literals.SESSION_PARAMETER__TYPE)
        {
            return MetadataTypeBuilder.TypeTarget.SESSION_PARAMETER;
        }
        if (feature == MdClassPackage.Literals.DEFINED_TYPE__TYPE)
        {
            return MetadataTypeBuilder.TypeTarget.DEFINED_TYPE;
        }
        return contextTarget;
    }

    /**
     * The clause naming the defined type a {@code type} refusal is about - {@code " of 'DefinedType.X'"} -
     * for a {@link MetadataTypeBuilder.TypeTarget#DEFINED_TYPE} target (issue #727), else empty: the
     * other targets' refusals keep their wording.
     */
    public static String definedTypeOwner(MetadataTypeBuilder.TypeTarget typeTarget, EObject target)
    {
        String address = typeTarget == MetadataTypeBuilder.TypeTarget.DEFINED_TYPE && target instanceof MdObject
            ? MetadataNodeResolver.resolvableAddressOf((MdObject)target) : null;
        return address == null ? "" : " of '" + address + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Validates a single-valued {@code REFERENCE} property (resolving and type-checking its FQN target)
     * and, on success, appends the prepared reference change to {@code out}. Returns a JSON error on
     * failure, or {@code null} on success. Read-only: it only builds and queues the change (no model
     * mutation). {@code owner} is the element the property is being set on (e.g. the DataProcessor a
     * {@code defaultForm} is set on) - passed to {@link #resolveReferenceTarget} so a bare short form
     * Name (no dots) can resolve against the owner's OWN {@code getForms()} collection (issue #262).
     *
     * <p>A command's {@code group} additionally admits a platform STANDARD command group by its bare
     * name - see {@link #queueStandardCommandGroup} (issue #508).</p>
     */
    public static String prepareReference(PrepareContext ctx, EObject owner, String name, String value,
        PropertyInfo info, List<PreparedChange> out)
    {
        // The catalogue is fetched only for a command-group reference, so no other property pays a
        // platform lookup for it.
        return prepareReferenceWith(MetadataPropertyIntrospector.isCommandGroupReference(info.feature)
            ? StandardCommandGroupResolver.providerFor(ctx.version) : null,
            ctx.scope, owner, name, value, info, out);
    }

    /**
     * The whole single-reference path with the platform command-group catalogue supplied rather than
     * fetched, so a headless test can drive it end to end - including the ORDER of its two resolution
     * attempts, which is what makes a standard group reachable at all.
     *
     * @param groupCatalogue the platform command-group catalogue, {@code null} for every reference
     *     that is not a command group and when the platform supplied none
     * @param scope the resolution root an FQN resolves against
     * @param owner the element the property is being set on, or {@code null}
     * @param name the property name
     * @param value the reference value as supplied by the caller
     * @param info the resolved property
     * @param out collects the prepared change
     * @return a JSON error, or {@code null} on success
     */
    public static String prepareReferenceWith(IEObjectProvider groupCatalogue, MetadataScope scope, // NOSONAR the parameter list is the reference path's own inputs; folding it would hide the injected catalogue
        EObject owner, String name, String value, PropertyInfo info, List<PreparedChange> out)
    {
        if (value == null || value.isEmpty())
        {
            return requireValueError(name);
        }
        if (queueStandardCommandGroup(info.feature, value, groupCatalogue, out))
        {
            return null;
        }
        MdObject targetMd = resolveReferenceTarget(scope, owner, value);
        String vErr = validateReferenceTarget(name, info.feature, targetMd, value, groupCatalogue, owner);
        if (vErr != null)
        {
            return vErr;
        }
        out.add(PreparedChange.reference(info.feature, ((IBmObject)targetMd).bmGetId()));
        return null;
    }

    /**
     * Queues a platform STANDARD command group ({@code ActionsPanelTools},
     * {@code NavigationPanelSeeAlso}, ... - English or Russian identifier, any case) as a SCALAR set
     * of the platform's own proxy, and answers whether it did (issue #508).
     *
     * <p>It is a SCALAR and not a {@link PreparedChange#reference}: a standard group is not an
     * {@code MdObject} and carries no BM id, so there is nothing to re-fetch inside the write
     * transaction - the proxy itself is the persistable value, and the exporter spells it back out as
     * the group's English name.</p>
     *
     * <p>Answers {@code false} - leaving the caller on the unchanged FQN path - for every reference
     * that is not a command group, for a DOTTED value (that is the {@code CommandGroup.<Name>}
     * address space), when the platform supplied no catalogue, and for an identifier the catalogue
     * does not know.</p>
     *
     * <p>A bare identifier that answers {@code false} is NOT short-circuited: it goes on through
     * {@link #resolveReferenceTarget} like any other value, which for a dotless value includes the
     * owner's-own-form probe ({@code owner instanceof MdObject && no dot}). That probe cannot match
     * here - {@code group} is declared only on commands, and a command owns no forms - so the value
     * arrives at the shared not-found refusal, which names both accepted forms. The probe is passed
     * through rather than skipped: this branch adds an address space, it does not remove one.</p>
     *
     * <p>Package-visible so a headless test can drive the branch with an injected catalogue; the live
     * path takes one from the versioned platform registry.</p>
     */
    public static boolean queueStandardCommandGroup(EStructuralFeature feature, String value,
        IEObjectProvider catalogue, List<PreparedChange> out)
    {
        if (catalogue == null || value == null || value.indexOf('.') >= 0
            || !MetadataPropertyIntrospector.isCommandGroupReference(feature))
        {
            return false;
        }
        EObject group = StandardCommandGroupResolver.resolve(catalogue, value).group;
        if (group == null)
        {
            return false;
        }
        out.add(PreparedChange.scalar(feature, group));
        return true;
    }

    /**
     * Validates a {@code MANY_REFERENCE} property (the value must be a JSON array of non-empty FQNs,
     * each resolving and type-checking) and, on success, appends the prepared list-reference change to
     * {@code out}. Returns a JSON error on failure, or {@code null} on success. Read-only: it only
     * builds and queues the change (no model mutation).
     */
    public static String prepareManyReference(MetadataScope scope, String name, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out)
    {
        JsonElement raw = prop.get(KEY_VALUE);
        if (raw == null || !raw.isJsonArray())
        {
            return ToolResult.error("'" + name + "' is a list reference: provide 'value' as an " //$NON-NLS-1$ //$NON-NLS-2$
                + "array of FQNs, e.g. [\"Catalog.Products\", \"Document.Order\"].").toJson(); //$NON-NLS-1$
        }
        List<Long> ids = new ArrayList<>();
        for (JsonElement el : raw.getAsJsonArray())
        {
            String fqn = (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
            if (fqn == null || fqn.isEmpty())
            {
                return ToolResult.error("Each entry of the '" + name + "' list must be a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "non-empty FQN string.").toJson(); //$NON-NLS-1$
            }
            MdObject t = resolveReferenceTarget(scope, null, fqn);
            String vErr = validateReferenceTarget(name, info.feature, t, fqn);
            if (vErr != null)
            {
                return vErr;
            }
            ids.add(((IBmObject)t).bmGetId());
        }
        out.add(PreparedChange.manyReference(info.feature, ids));
        return null;
    }

    /**
     * Validates a {@code MCORE_VALUE_LIST} property and queues an ordered replacement. Configuration
     * XDTO-package targets are reduced to BM ids here; only ids and namespace strings cross into the
     * write phase, where the actual ReferenceValue/StringValue objects are created.
     */
    public static String prepareMcoreValueList(MetadataScope scope, String name, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out)
    {
        McoreValueListPreparation prepared = buildMcoreValueListValue(name, prop.get(KEY_VALUE), scope);
        if (prepared.error != null)
        {
            return prepared.error;
        }
        out.add(PreparedChange.mcoreValueList(info.feature, prepared.values));
        return null;
    }

    /**
     * Parses and reduces an mcore Value list to its transaction-safe shape. Package-visible so
     * headless tests can pin refusal wording and the no-live-reference boundary.
     */
    public static McoreValueListPreparation buildMcoreValueListValue(String propertyName, JsonElement raw,
        MetadataScope scope)
    {
        McoreValueListBuilder.Result built = McoreValueListBuilder.build(raw, scope);
        if (built.error != null)
        {
            return McoreValueListPreparation.failed(ToolResult.error("Invalid mcore Value list for " //$NON-NLS-1$
                + "property '" + propertyName + "': " + built.error).toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return prepareResolvedMcoreValueList(built.items);
    }

    /** Converts resolved entries to strings/BM ids without retaining any live XDTO-package object. */
    public static McoreValueListPreparation prepareResolvedMcoreValueList(
        List<McoreValueListBuilder.Item> items)
    {
        List<McoreValuePreparation> reduced = new ArrayList<>();
        for (McoreValueListBuilder.Item item : items)
        {
            if (item.referenceTarget != null)
            {
                reduced.add(McoreValuePreparation.reference(
                    ((IBmObject)item.referenceTarget).bmGetId()));
            }
            else
            {
                reduced.add(McoreValuePreparation.namespace(item.namespaceUri));
            }
        }
        return McoreValueListPreparation.ok(reduced);
    }

    /**
     * Validates a {@code STYLE_VALUE} property (building the StyleItem Color / Font value) and, on
     * success, appends the prepared style-value change to {@code out} (which also keeps the sibling
     * {@code type} feature consistent with the value). Returns a JSON error on failure, or {@code null}
     * on success. Read-only: it only builds and queues the change (no model mutation).
     */
    public static String prepareStyleValue(PrepareContext ctx, String name, JsonObject prop,
        EObject target, PropertyInfo info, List<PreparedChange> out)
    {
        StyleValueBuilder.Result sv = StyleValueBuilder.build(prop.get(KEY_VALUE),
            StyleValueBuilder.forConfiguration(ctx.config, AppearanceValueBuilder.colorCatalogue(ctx.version)));
        if (sv.error != null)
        {
            return ToolResult.error("Invalid StyleItem '" + name + "': " + sv.error).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The style item's `type` (Color / Font) is kept consistent with the value it holds, so
        // the change sets both the `value` and the sibling `type` feature in one shot.
        EStructuralFeature typeFeature = target.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        out.add(PreparedChange.styleValue(info.feature, typeFeature, sv.value, sv.type));
        return null;
    }

    /**
     * Validates and resolves a contained mcore Picture value. A standard-picture proxy is safe to
     * queue directly; a live CommonPicture crosses the prepare/write boundary only by BM id. The
     * PictureRef itself is created and attached inside the caller's existing write transaction.
     */
    public static String preparePicture(PrepareContext ctx, String name, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out)
    {
        JsonElement raw = prop.get(KEY_VALUE);
        if (isMissingOrEmptyString(raw))
        {
            return requireValueError(name);
        }
        PicturePreparation prepared = buildPictureValue(name, raw, ctx.scope, ctx.version);
        if (prepared.error != null)
        {
            return prepared.error;
        }
        out.add(PreparedChange.picture(info.feature, prepared.platformPictureProxy,
            prepared.commonPictureBmId));
        return null;
    }

    /**
     * Validates a contained {@code COLOR} / {@code FONT} value (a form item's {@code textColor},
     * {@code titleFont}, ... - issue #660) and queues it. Read-only: the value is built detached and a
     * configuration style / palette item crosses into the write transaction only by its BM id.
     */
    public static String prepareAppearance(PrepareContext ctx, EObject target, String name,
        JsonObject prop, PropertyInfo info, List<PreparedChange> out)
    {
        // The designer offers configuration style / palette items only on an ordinary owner: a style
        // item or palette color of its own, and an external object (whose configuration lives in
        // another project), take platform values only.
        Configuration configuration = target instanceof MdObject || ctx.scope.isExternalObjects()
            ? null : ctx.config;
        boolean color = info.valueKind == MetadataPropertyIntrospector.ValueKind.COLOR;
        return prepareAppearanceWith(color, name, prop.get(KEY_VALUE), configuration,
            color ? AppearanceValueBuilder.colorCatalogue(ctx.version)
                : AppearanceValueBuilder.fontCatalogue(ctx.version),
            info.feature, out);
    }

    /**
     * The {@link #prepareAppearance} body with the configuration and the platform catalogue
     * supplied, so a headless test can drive parse, refusal and the queued change end to end.
     *
     * @param color {@code true} for a COLOR property, {@code false} for a FONT one
     * @param name the property name
     * @param raw the property value as supplied
     * @param configuration the configuration a named value may reference, or {@code null}
     * @param catalogue the platform colour / font catalogue, may be {@code null}
     * @param feature the property's feature
     * @param out collects the prepared change
     * @return a JSON error, or {@code null} on success
     */
    public static String prepareAppearanceWith(boolean color, String name, JsonElement raw, // NOSONAR the parameter list is the injected inputs of one property
        Configuration configuration, IEObjectProvider catalogue,
        EStructuralFeature feature, List<PreparedChange> out)
    {
        AppearanceValueBuilder.Result built = color
            ? AppearanceValueBuilder.buildColor(raw, configuration, catalogue)
            : AppearanceValueBuilder.buildFont(raw, configuration, catalogue);
        if (built.error != null)
        {
            return ToolResult.error("Invalid " + (color ? "color" : "font") + " for property '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + name + "': " + built.error).toJson(); //$NON-NLS-1$
        }
        Long itemBmId = built.configurationItem instanceof IBmObject
            ? Long.valueOf(((IBmObject)built.configurationItem).bmGetId()) : null;
        out.add(PreparedChange.appearance(feature, built.value, itemBmId));
        return null;
    }

    /**
     * Builds and wraps a PictureValueBuilder result in the tool's ToolResult error contract. Kept
     * package-visible so the headless unit test can pin the exact refusal wording without a BM model.
     */
    public static PicturePreparation buildPictureValue(String name, JsonElement raw,
        MetadataScope scope, Version version)
    {
        PictureValueBuilder.Result built = PictureValueBuilder.build(raw, scope, version);
        if (built.error != null)
        {
            return PicturePreparation.failed(ToolResult.error(
                "Invalid picture for property '" + name + "': " + built.error).toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return prepareResolvedPicture(built.picture);
    }

    /**
     * Converts a resolved picture target to the transaction-safe prepared shape. Package-visible so
     * a headless test can prove that a CommonPicture is reduced to its BM id, never retained live.
     */
    public static PicturePreparation prepareResolvedPicture(EObject picture)
    {
        if (picture instanceof CommonPicture)
        {
            return PicturePreparation.common(((IBmObject)picture).bmGetId());
        }
        return PicturePreparation.standard(picture);
    }

    /**
     * Validates either supported QName wire form and queues a detached mcore QName for attachment in
     * the existing write transaction. Both members/sides are required and must be non-empty.
     */
    public static String prepareQName(String name, JsonObject prop, PropertyInfo info,
        List<PreparedChange> out)
    {
        JsonElement raw = prop.get(KEY_VALUE);
        if (isMissingOrEmptyString(raw))
        {
            return requireValueError(name);
        }
        ContainedValuePreparation prepared = buildQNameValue(name, raw);
        if (prepared.error != null)
        {
            return prepared.error;
        }
        out.add(PreparedChange.scalar(info.feature, prepared.value));
        return null;
    }

    /**
     * Parses a QName without touching the model. Package-visible for exact refusal-path unit tests.
     */
    public static ContainedValuePreparation buildQNameValue(String propertyName, JsonElement raw)
    {
        String name;
        String nsUri;
        if (raw != null && !raw.isJsonNull() && raw.isJsonObject())
        {
            JsonObject object = raw.getAsJsonObject();
            if (object.size() != 2 || !object.has("name") || !object.has("nsUri")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return invalidQName(propertyName, raw,
                    "the object form requires exactly the 'name' and 'nsUri' members"); //$NON-NLS-1$
            }
            name = strictString(object.get("name")); //$NON-NLS-1$
            nsUri = strictString(object.get("nsUri")); //$NON-NLS-1$
            if (isBlank(name) || isBlank(nsUri))
            {
                return invalidQName(propertyName, raw,
                    "the object form requires non-empty string members 'name' and 'nsUri'"); //$NON-NLS-1$
            }
        }
        else if (raw != null && !raw.isJsonNull() && raw.isJsonPrimitive()
            && raw.getAsJsonPrimitive().isString())
        {
            String compact = raw.getAsString();
            int close = compact.indexOf('}');
            if (!compact.startsWith("{") || close <= 1 || close == compact.length() - 1) //$NON-NLS-1$
            {
                return invalidQName(propertyName, raw,
                    "the compact form must be '{nsUri}name' with a non-empty namespace URI and name"); //$NON-NLS-1$
            }
            nsUri = compact.substring(1, close);
            name = compact.substring(close + 1);
            if (isBlank(name) || isBlank(nsUri))
            {
                return invalidQName(propertyName, raw,
                    "the compact form must be '{nsUri}name' with a non-empty namespace URI and name"); //$NON-NLS-1$
            }
        }
        else
        {
            return invalidQName(propertyName, raw,
                "the value is neither a QName object nor a compact string"); //$NON-NLS-1$
        }

        QName qname = McoreFactory.eINSTANCE.createQName();
        qname.setName(name);
        qname.setNsUri(nsUri);
        return ContainedValuePreparation.ok(qname);
    }

    public static ContainedValuePreparation invalidQName(String propertyName, JsonElement raw,
        String reason)
    {
        String value = raw == null || raw.isJsonNull() ? "null" : raw.toString(); //$NON-NLS-1$
        return ContainedValuePreparation.failed(ToolResult.error("Invalid QName value for property '" //$NON-NLS-1$
            + propertyName + "': " + value + "; " + reason + ". Use either " //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"name\":\"string\",\"nsUri\":\"http://www.w3.org/2001/XMLSchema\"} or " //$NON-NLS-1$
            + "\"{http://www.w3.org/2001/XMLSchema}string\".").toJson()); //$NON-NLS-1$
    }

    public static boolean isMissingOrEmptyString(JsonElement raw)
    {
        return raw == null || raw.isJsonNull() || raw.isJsonPrimitive()
            && raw.getAsJsonPrimitive().isString() && raw.getAsString().isEmpty();
    }

    public static String strictString(JsonElement raw)
    {
        return raw != null && !raw.isJsonNull() && raw.isJsonPrimitive()
            && raw.getAsJsonPrimitive().isString() ? raw.getAsString() : null;
    }

    public static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Applies the yo-to-ye normalization to a free STRING property value with a deliberately
     * NARROW scope: only the {@code comment} property is normalized (it is presentation text
     * checked by the same EDT validator, 1C standard #std474, as names and synonyms). Every
     * other free STRING feature can be identifier-like — e.g. {@code XDTOPackage.namespace}
     * is a URI — where a silent yo-to-ye rewrite would corrupt the value, so the caller's
     * text is kept verbatim. LOCALIZED_STRING values are normalized separately (see the
     * LOCALIZED_STRING branch of {@code prepare}).
     *
     * @param name the property name as supplied by the caller
     * @param value the non-empty property value
     * @param normReport the normalization report (honors the {@code normalizeYo} toggle)
     * @return the value to assign — normalized for {@code comment}, verbatim otherwise
     */
    public static String normalizeStringPropertyValue(String name, String value,
        MdNameNormalizer.Report normReport)
    {
        return "comment".equalsIgnoreCase(name) ? normReport.apply(name, value) : value; //$NON-NLS-1$
    }

    public static MdObject resolveReferenceTarget(Configuration config, EObject owner, String fqn)
    {
        return resolveReferenceTarget(MetadataScope.ofConfiguration(config), owner, fqn);
    }

    /**
     * The {@link #resolveReferenceTarget(Configuration, EObject, String)} variant that resolves
     * against whichever ROOT the project has, so a reference between two objects of an
     * external-objects project resolves there and not in the base configuration (issue #309).
     *
     * @param scope the resolution root
     * @param owner the element the property is being set on, or {@code null}
     * @param fqn the reference value as supplied by the caller
     * @return the resolved metadata object, or {@code null} when nothing resolves
     */
    public static MdObject resolveReferenceTarget(MetadataScope scope, EObject owner, String fqn)
    {
        String norm = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode n = MetadataNodeResolver.resolveExisting(scope, norm);
        if (n != null)
        {
            return n.object;
        }
        String formPath = FormElementWriter.parseFormPath(norm);
        if (formPath != null)
        {
            MdObject form = FormStructureReader.resolveMdForm(scope, formPath);
            if (form != null)
            {
                return form;
            }
        }
        if (owner instanceof MdObject && norm.indexOf('.') < 0)
        {
            MdObject ownForm = FormStructureReader.findOwnedForm((MdObject)owner, norm);
            if (ownForm != null)
            {
                return ownForm;
            }
        }
        return null;
    }

    public static String validateReferenceTarget(String prop, EStructuralFeature feature,
        MdObject target, String fqn)
    {
        return validateReferenceTarget(prop, feature, target, fqn, null);
    }

    /**
     * The {@link #validateReferenceTarget(String, EStructuralFeature, MdObject, String)} variant that
     * also carries the platform command-group catalogue, so a {@code group} refusal can name the
     * standard groups the caller may use instead (issue #508). {@code groupCatalogue} is {@code null}
     * for every other reference, and on a command group when the platform supplied none - the refusal
     * then still names both accepted forms.
     *
     * @param prop the property name
     * @param feature the reference feature
     * @param target the resolved target, or {@code null} when nothing resolved
     * @param fqn the reference value as supplied by the caller
     * @param groupCatalogue the platform command-group catalogue, may be {@code null}
     * @return a JSON error, or {@code null} when the target is usable
     */
    public static String validateReferenceTarget(String prop, EStructuralFeature feature,
        MdObject target, String fqn, IEObjectProvider groupCatalogue)
    {
        return validateReferenceTarget(prop, feature, target, fqn, groupCatalogue, null);
    }

    /**
     * The full single-reference check, with the object the property is being set on. Besides a top
     * object, a form and a template, it admits the owner's OWN Field for a {@code refers Field}
     * property (an external data source Table's {@code presentationField} / {@code parentField},
     * issue #448) with the owner check in {@link TableFieldReferences}. A list reference
     * passes no owner, so a Field never passes there.
     *
     * @param prop the property name
     * @param feature the reference feature
     * @param target the resolved target, or {@code null} when nothing resolved
     * @param fqn the reference value as supplied by the caller
     * @param groupCatalogue the platform command-group catalogue, may be {@code null}
     * @param owner the element the property is being set on, or {@code null}
     * @return a JSON error, or {@code null} when the target is usable
     */
    public static String validateReferenceTarget(String prop, EStructuralFeature feature, // NOSONAR the owner joins the existing reference inputs
        MdObject target, String fqn, IEObjectProvider groupCatalogue, EObject owner)
    {
        if (target == null)
        {
            return ToolResult.error(MSG_REFERENCE_TARGET + fqn + MSG_FOR_PROP + prop + "' was not found. " //$NON-NLS-1$
                + referenceNotFoundHint(feature, groupCatalogue)).toJson();
        }
        if (!(target instanceof IBmObject))
        {
            return ToolResult.error(MSG_REFERENCE_TARGET + fqn + MSG_FOR_PROP + prop + "' must be a " //$NON-NLS-1$
                + "top-level object; references to members are not supported.").toJson(); //$NON-NLS-1$
        }
        boolean isForm = MdClassPackage.Literals.BASIC_FORM.isSuperTypeOf(target.eClass());
        boolean isTemplate = target instanceof BasicTemplate;
        boolean isField = TableFieldReferences.isFieldReference(feature, target);
        if (!isForm && !isTemplate && !isField && !((IBmObject)target).bmIsTop())
        {
            return ToolResult.error(MSG_REFERENCE_TARGET + fqn + MSG_FOR_PROP + prop + "' must be a " //$NON-NLS-1$
                + "top-level object; references to members are not supported (forms and templates " //$NON-NLS-1$
                + "are the supported member references because BM can re-fetch both by id).").toJson(); //$NON-NLS-1$
        }
        String fieldRefusal = isField ? TableFieldReferences.refusal(prop, owner, target, fqn) : null;
        if (fieldRefusal != null)
        {
            return ToolResult.error(fieldRefusal).toJson();
        }
        EClass targetType = ((EReference)feature).getEReferenceType();
        if (targetType != null && !targetType.isSuperTypeOf(target.eClass()))
        {
            return ToolResult.error("'" + fqn + MSG_IS_A + target.eClass().getName() + " but '" + prop //$NON-NLS-1$ //$NON-NLS-2$
                + "' requires a " + targetType.getName() + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * The "how to address this reference" hint appended to a not-found error: a {@code group} feature
     * (declared against the mcore {@code CommandGroup} interface - see
     * {@link MetadataPropertyIntrospector} class doc) gets ONE merged hint naming BOTH forms it
     * accepts - the {@code CommandGroup.<Name>} FQN of a configuration group and the bare name of a
     * platform STANDARD group - plus the standard groups themselves, in both identifiers, because the
     * Russian one is discoverable nowhere else (issue #508). Every other reference feature gets the
     * generic FQN hint.
     */
    public static String referenceNotFoundHint(EStructuralFeature feature,
        IEObjectProvider groupCatalogue)
    {
        if (MetadataPropertyIntrospector.isCommandGroupReference(feature))
        {
            return StandardCommandGroupResolver.addressingHint(groupCatalogue);
        }
        return "Use a valid FQN (e.g. 'Catalog.Products'); check with get_metadata_objects."; //$NON-NLS-1$
    }

    /**
     * Error for a missing / empty {@code value}: this tool never clears a property on an omitted
     * value (a clear must be explicit), matching the former set_metadata_property's "empty = not
     * provided" guard.
     */
    public static String requireValueError(String name)
    {
        return ToolResult.error("Property '" + name + "' needs a non-empty 'value'. modify_metadata " //$NON-NLS-1$ //$NON-NLS-2$
            + "does not clear a property on an empty value.").toJson(); //$NON-NLS-1$
    }

    /**
     * The STYLE_VALUE-only binding of a {@link PreparedChange}: the sibling {@code type} feature
     * (Color / Font) and the {@link StyleElementType} to set on it alongside the value, so the style
     * item's type stays consistent with the value it holds. A parameter-object that keeps the
     * {@link PreparedChange} constructor below the 7-parameter bar; {@code null} for every non-style
     * change. Carries no behaviour.
     */
    public static final class StyleBinding
    {
        public final EStructuralFeature typeFeature;
        public final StyleElementType type;

        StyleBinding(EStructuralFeature typeFeature, StyleElementType type)
        {
            this.typeFeature = typeFeature;
            this.type = type;
        }
    }

    /**
     * Transaction-safe prepared Picture target. A successful result carries EITHER a platform proxy
     * or a CommonPicture BM id; it never retains a live CommonPicture across the write boundary.
     * Package-visible so the headless tests can pin that invariant and the exact refusal wording.
     */
    public static final class PicturePreparation
    {
        public final EObject platformPictureProxy;

        public final Long commonPictureBmId;

        public final String error;

        private PicturePreparation(EObject platformPictureProxy, Long commonPictureBmId,
            String error)
        {
            this.platformPictureProxy = platformPictureProxy;
            this.commonPictureBmId = commonPictureBmId;
            this.error = error;
        }

        public static PicturePreparation standard(EObject platformPictureProxy)
        {
            return new PicturePreparation(platformPictureProxy, null, null);
        }

        public static PicturePreparation common(long commonPictureBmId)
        {
            return new PicturePreparation(null, Long.valueOf(commonPictureBmId), null);
        }

        public static PicturePreparation failed(String error)
        {
            return new PicturePreparation(null, null, error);
        }
    }

    /**
     * A detached QName produced during validation, or a ready ToolResult error JSON. Exactly one field
     * is non-null. Package-visible so headless tests can pin QName refusal wording without constructing
     * the private PreparedChange or entering a BM transaction.
     */
    public static final class ContainedValuePreparation
    {
        public final EObject value;

        public final String error;

        private ContainedValuePreparation(EObject value, String error)
        {
            this.value = value;
            this.error = error;
        }

        public static ContainedValuePreparation ok(EObject value)
        {
            return new ContainedValuePreparation(value, null);
        }

        public static ContainedValuePreparation failed(String error)
        {
            return new ContainedValuePreparation(null, error);
        }
    }

    /** One transaction-safe mcore Value-list entry: either a namespace string or a reference BM id. */
    public static final class McoreValuePreparation
    {
        public final String namespaceUri;

        public final Long referenceBmId;

        private McoreValuePreparation(String namespaceUri, Long referenceBmId)
        {
            this.namespaceUri = namespaceUri;
            this.referenceBmId = referenceBmId;
        }

        public static McoreValuePreparation namespace(String namespaceUri)
        {
            return new McoreValuePreparation(namespaceUri, null);
        }

        public static McoreValuePreparation reference(long bmId)
        {
            return new McoreValuePreparation(null, Long.valueOf(bmId));
        }
    }

    /** An ordered, transaction-safe mcore Value list, or a ready ToolResult error JSON. */
    public static final class McoreValueListPreparation
    {
        public final List<McoreValuePreparation> values;

        public final String error;

        private McoreValueListPreparation(List<McoreValuePreparation> values, String error)
        {
            this.values = values;
            this.error = error;
        }

        public static McoreValueListPreparation ok(List<McoreValuePreparation> values)
        {
            return new McoreValueListPreparation(
                java.util.Collections.unmodifiableList(new ArrayList<>(values)), null);
        }

        public static McoreValueListPreparation failed(String error)
        {
            return new McoreValueListPreparation(null, error);
        }
    }

    /**
     * The object form of an {@code AdjustableBoolean} change (issue #719): an optional {@code common}
     * and role values, {@code null} dropping a role's value. Roles are kept by BM id so the write
     * re-fetches them in its own transaction.
     */
    public static final class AdjustableBooleanEdit
    {
        /** The new {@code common}, or {@code null} to keep the stored one. */
        public final Boolean common;
        /** Role -&gt; value as resolved by the preparation, in request order. */
        public final Map<Role, Boolean> roles;
        public final Map<Long, Boolean> roleIds;
        public final Map<Long, String> roleNames;

        public AdjustableBooleanEdit(Boolean common, Map<Role, Boolean> roles)
        {
            this.common = common;
            if (HostKind.current() == HostKind.HEADLESS)
            {
                Map<Long, Boolean> ids = new LinkedHashMap<>();
                Map<Long, String> names = new LinkedHashMap<>();
                roles.forEach((role, value) -> {
                    long id = ((IBmObject)role).bmGetId();
                    ids.put(id, value);
                    names.put(id, role.getName());
                });
                this.roles = Map.of();
                this.roleIds = java.util.Collections.unmodifiableMap(ids);
                this.roleNames = java.util.Collections.unmodifiableMap(names);
            }
            else
            {
                this.roles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(roles));
                this.roleIds = Map.of();
                this.roleNames = Map.of();
            }
        }

        /** The role edits with every role re-fetched in {@code tx} (as resolved when {@code tx} is null). */
        public Map<Role, Boolean> rolesIn(IBmTransaction tx)
        {
            Map<Role, Boolean> result = new LinkedHashMap<>();
            for (Map.Entry<Long, Boolean> entry : roleIds.entrySet())
            {
                if (tx == null) throw new IllegalStateException("Role flags require the active write transaction");
                Object current = tx.getObjectById(entry.getKey());
                if (!(current instanceof Role role))
                    throw new IllegalStateException("Role." + roleNames.get(entry.getKey()) + " is no longer in the transaction");
                result.put(role, entry.getValue());
            }
            for (Map.Entry<Role, Boolean> e : roles.entrySet())
            {
                Role role = e.getKey();
                if (tx != null && role instanceof IBmObject)
                {
                    Object inTx = tx.getObjectById(((IBmObject)role).bmGetId());
                    if (!(inTx instanceof Role))
                    {
                        throw new IllegalStateException("Role." + role.getName() //$NON-NLS-1$
                            + " is no longer in the transaction"); //$NON-NLS-1$
                    }
                    role = (Role)inTx;
                }
                result.put(role, e.getValue());
            }
            return result;
        }
    }

    /** A validated, coerced change ready to apply to the re-fetched target inside the write tx. */
    public static final class PreparedChange
    {
        private enum Kind
        {
            /** An adopted defined type's type, written to its type extension (#709); the value is the plan. */
            TYPE_EXTENSION,
            /** An adopted event subscription's source, extended (#710); the value is the plan. */
            EXTENDED_SOURCE,
            SCALAR, LOCALIZED, REFERENCE, MANY_REFERENCE, MANY_ENUM, MCORE_VALUE_LIST, STYLE_VALUE,
            CONTAINED, ADJUSTABLE_BOOLEAN,
            /** A form attribute's {@code useAlways} checkboxes; the value is the list of plans. */
            USE_ALWAYS,
            /** A Field/Table dataPath validated by the same planner as creation (#728). */
            FORM_DATA_PATH,
            /** A field list (issue #740); the value is the list of field ids. */
            FIELD_LIST, EXCLUDED_COMMANDS, MEMBER_VALUES, FORM_CHOICE_VALUES
        }

        /**
         * Creates a CONTAINED change's value inside the write transaction, from the object its
         * reference BM id re-fetches there ({@code null} when the change names none).
         */
        @FunctionalInterface
        public interface ContainedValue
        {
            /** @return the detached value to set, or {@code null} to unset the feature */
            EObject create(EObject referencedInTx);
        }

        public final EStructuralFeature feature;
        public final Kind kind;
        public final Object scalarValue;
        public final String localizedLanguage;
        public final String localizedValue;
        /**
         * For REFERENCE/MANY_REFERENCE: target bmIds. For a CommonPicture: its one target bmId.
         */
        public final List<Long> referenceBmIds;
        /** For a STYLE_VALUE: the sibling `type` feature + StyleElementType; {@code null} otherwise. */
        public final StyleBinding styleBinding;
        /**
         * {@code true} for a {@code TYPE_DESCRIPTION} change (the object's / attribute's {@code type} /
         * form-attribute {@code valueType}). This is the destructive case the consent gate prompts on:
         * retyping data can drop stored values on a database update. Every benign change is {@code false}.
         */
        public final boolean typeChange;

        private PreparedChange(EStructuralFeature feature, Kind kind, Object scalarValue, // NOSONAR cohesive discriminated-union payload, one field per Kind (already reduced by StyleBinding); a further param-object would fragment it
            String language, String localizedValue, List<Long> referenceBmIds,
            StyleBinding styleBinding, boolean typeChange)
        {
            this.feature = feature;
            this.kind = kind;
            this.scalarValue = scalarValue;
            this.localizedLanguage = language;
            this.localizedValue = localizedValue;
            this.referenceBmIds = referenceBmIds;
            this.styleBinding = styleBinding;
            this.typeChange = typeChange;
        }

        public static PreparedChange memberValues(EStructuralFeature feature, MetadataMemberValues.Plan value)
        {
            return new PreparedChange(feature, Kind.MEMBER_VALUES, value, null, null, null, null, false);
        }

        public static PreparedChange formChoiceValues(EStructuralFeature feature, FormChoiceParameters.Plan value)
        {
            return new PreparedChange(feature, Kind.FORM_CHOICE_VALUES, value, null, null, null, null, false);
        }

        public static PreparedChange scalar(EStructuralFeature feature, Object value)
        {
            return new PreparedChange(feature, Kind.SCALAR, value, null, null, null, null, false);
        }

        /**
         * An {@code ADJUSTABLE_BOOLEAN} change: the boolean addresses the CONTAINED object's
         * {@code common} flag, so the applier must NOT {@code eSet} the feature itself - that would
         * replace the contained object and silently discard its {@code for} overrides (issue #382).
         */
        public static PreparedChange adjustableBoolean(EStructuralFeature feature, boolean common)
        {
            return new PreparedChange(feature, Kind.ADJUSTABLE_BOOLEAN, Boolean.valueOf(common),
                null, null, null, null, false);
        }

        /** The object form of an {@code ADJUSTABLE_BOOLEAN} change: {@code common} and role values (#719). */
        public static PreparedChange adjustableBoolean(EStructuralFeature feature, AdjustableBooleanEdit edit)
        {
            return new PreparedChange(feature, Kind.ADJUSTABLE_BOOLEAN, edit, null, null, null, null, false);
        }

        /** A form item's contained data path, planned without changing the model. */
        public static PreparedChange formDataPath(EStructuralFeature feature, FormElementWriter.DataPathPlan plan)
        {
            return new PreparedChange(feature, Kind.FORM_DATA_PATH, plan, null, null, null, null, false);
        }

        /** A form attribute's {@code useAlways} change: the validated per-path plans (#661). */
        public static PreparedChange useAlways(EStructuralFeature feature, List<UseAlwaysSupport.Plan> plans)
        {
            return new PreparedChange(feature, Kind.USE_ALWAYS, java.util.Collections.unmodifiableList(
                new ArrayList<>(plans)), null, null, null, null, false);
        }

        /** A validated standard-command replacement carrying canonical names only. */
        public static PreparedChange excludedCommands(EStructuralFeature feature, ExcludedCommandsSupport.Plan plan)
        {
            return new PreparedChange(feature, Kind.EXCLUDED_COMMANDS, plan, null, null, null, null, false);
        }

        /**
         * A field list's whole-list replacement (issue #740): only the field ids cross into the write,
         * where {@link FieldListSupport#apply} turns them into the proxies EDT stores.
         */
        public static PreparedChange fieldList(EStructuralFeature feature, List<String> fieldIds)
        {
            return new PreparedChange(feature, Kind.FIELD_LIST, java.util.Collections.unmodifiableList(
                new ArrayList<>(fieldIds)), null, null, null, null, false);
        }

        /**
         * A {@code TYPE_DESCRIPTION} change: a scalar set of a freshly-built (detached) type description,
         * flagged {@link #typeChange} so the caller can route it through the destructive-consent gate
         * (retyping data is the destructive case a plain property edit is not).
         */
        public static PreparedChange typeDescription(EStructuralFeature feature, Object typeDescription)
        {
            if (HostKind.current() == HostKind.HEADLESS && typeDescription instanceof TypeDescription value)
                typeDescription = MetadataTypeBuilder.snapshotTypeDescription(value);
            return new PreparedChange(feature, Kind.SCALAR, typeDescription, null, null, null, null, true);
        }

        /**
         * An adopted defined type's {@code type}, written to its type extension as the router planned it
         * (issue #709). Still a type change: the batch applies its last one, and the consent gate asks.
         */
        public static PreparedChange typeExtension(EStructuralFeature feature, AdoptedPropertyRouter.TypePlan plan)
        {
            return new PreparedChange(feature, Kind.TYPE_EXTENSION, plan, null, null, null, null, true);
        }

        /**
         * An adopted event subscription's {@code source}, extended as the router planned it (issue #710).
         * Still a type change: the batch applies its last one, and the consent gate asks.
         */
        public static PreparedChange extendedSource(EStructuralFeature feature, AdoptedPropertyRouter.SourcePlan plan)
        {
            return new PreparedChange(feature, Kind.EXTENDED_SOURCE, plan, null, null, null, null, true);
        }

        public static PreparedChange localized(EStructuralFeature feature, String language, String value)
        {
            return new PreparedChange(feature, Kind.LOCALIZED, null, language, value, null, null, false);
        }

        public static PreparedChange reference(EStructuralFeature feature, long targetBmId)
        {
            return new PreparedChange(feature, Kind.REFERENCE, null, null, null,
                java.util.Collections.singletonList(targetBmId), null, false);
        }

        public static PreparedChange manyReference(EStructuralFeature feature, List<Long> targetBmIds)
        {
            return new PreparedChange(feature, Kind.MANY_REFERENCE, null, null, null, targetBmIds,
                null, false);
        }

        /** A detached list of enum instances that replaces a many-valued EAttribute in the write tx. */
        public static PreparedChange manyEnum(EStructuralFeature feature, List<Object> values)
        {
            return new PreparedChange(feature, Kind.MANY_ENUM,
                java.util.Collections.unmodifiableList(new ArrayList<>(values)), null, null, null,
                null, false);
        }

        /**
         * A StyleItem value change: the freshly-built mcore {@link Value} ({@code styleValue}) is a
         * detached containment object, so it is set directly on the re-fetched style item inside the
         * tx (like the TYPE_DESCRIPTION scalar). The sibling {@code typeFeature} (Color / Font) is set
         * to {@code type} in the same change so the style item's type stays consistent with its value.
         */
        public static PreparedChange styleValue(EStructuralFeature valueFeature, EStructuralFeature typeFeature,
            Value styleValue, StyleElementType type)
        {
            return new PreparedChange(valueFeature, Kind.STYLE_VALUE, styleValue, null, null, null,
                new StyleBinding(typeFeature, type), false);
        }

        /**
         * A contained value that may point at a configuration object: the object crosses the
         * prepare/write boundary only as {@code referenceBmId}, and {@code factory} builds the value
         * in {@link #applyTo} from the object re-fetched inside the write transaction.
         */
        public static PreparedChange contained(EStructuralFeature feature, Long referenceBmId,
            ContainedValue factory)
        {
            List<Long> ids = referenceBmId == null ? null
                : java.util.Collections.singletonList(referenceBmId);
            return new PreparedChange(feature, Kind.CONTAINED, factory, null, null, ids, null, false);
        }

        /**
         * A picture change: a safe platform proxy, or one CommonPicture BM id. The PictureRef is
         * built only in applyTo.
         */
        public static PreparedChange picture(EStructuralFeature feature, EObject platformPictureProxy,
            Long commonPictureBmId)
        {
            return contained(feature, commonPictureBmId, picture -> {
                EObject pictureRef = EcoreUtil.create(McorePackage.Literals.PICTURE_REF);
                pictureRef.eSet(McorePackage.Literals.PICTURE_REF__PICTURE,
                    picture != null ? picture : platformPictureProxy);
                return pictureRef;
            });
        }

        /**
         * A contained colour / font change (issue #660): {@code value} is the detached Color / Font
         * (holding no live object), or {@code null} to CLEAR the property (the automatic value).
         * {@code itemBmId} names the configuration StyleItem / PaletteColor a ColorRef / FontRef is
         * bound to, inside the write transaction.
         */
        public static PreparedChange appearance(EStructuralFeature feature, EObject value, Long itemBmId)
        {
            return contained(feature, itemBmId, item -> {
                if (value != null && item != null)
                {
                    AppearanceValueBuilder.bind(value, item);
                }
                return value;
            });
        }

        /** An ordered replacement list carrying only namespace strings and reference BM ids. */
        public static PreparedChange mcoreValueList(EStructuralFeature feature,
            List<McoreValuePreparation> values)
        {
            return new PreparedChange(feature, Kind.MCORE_VALUE_LIST, values, null, null, null,
                null, false);
        }

        public String featureName()
        {
            return kind == Kind.USE_ALWAYS ? UseAlwaysSupport.PROPERTY : feature.getName();
        }

        /**
         * Whether this change is a write the adopted-object routing planned (a type extension, an extended
         * source), which checks the object again itself; every other change is written as prepared.
         */
        public boolean isRoutedWrite()
        {
            return kind == Kind.TYPE_EXTENSION || kind == Kind.EXTENDED_SOURCE;
        }

        /** Whether this change retypes data (a {@code TYPE_DESCRIPTION} / form {@code valueType} set). */
        public boolean isTypeChange()
        {
            return typeChange;
        }

        /** The value this change would write - for a type change, the built {@code TypeDescription}. */
        public Object value()
        {
            return scalarValue;
        }

        public boolean isLocalized()
        {
            return kind == Kind.LOCALIZED;
        }

        /** The second feature a style value writes - the style item's {@code type} - else {@code null}. */
        public EStructuralFeature bindingFeature()
        {
            return styleBinding == null ? null : styleBinding.typeFeature;
        }

        /**
         * What a routed write of an adopted object did (issues #709 / #710), once it ran; {@code null} for
         * any other change or before the write.
         */
        public String routedNote()
        {
            AdoptedPropertyRouter.Outcome outcome = null;
            if (kind == Kind.TYPE_EXTENSION)
            {
                outcome = ((AdoptedPropertyRouter.TypePlan)scalarValue).outcome();
            }
            else if (kind == Kind.EXTENDED_SOURCE)
            {
                outcome = ((AdoptedPropertyRouter.SourcePlan)scalarValue).outcome();
            }
            return outcome == null ? null : outcome.note();
        }

        /** The consent preview's warning of a routed write (issues #709 / #710), else {@code null}. */
        public String routedConsentWarning()
        {
            if (kind == Kind.TYPE_EXTENSION)
            {
                return ((AdoptedPropertyRouter.TypePlan)scalarValue).consentWarning();
            }
            return kind == Kind.EXTENDED_SOURCE ? ((AdoptedPropertyRouter.SourcePlan)scalarValue).consentWarning()
                : null;
        }

        public EStructuralFeature feature()
        {
            return feature;
        }

        /** The locale a {@code LOCALIZED} change was written under; {@code null} for other kinds. */
        public String language()
        {
            return localizedLanguage;
        }

        /**
         * Writes this change to {@code target} inside {@code tx}. A type write is EDT's type change: on an
         * owner EDT resets type-dependent properties for, and when the type really changes, those
         * properties are reset as the designer resets them (issue #700, {@link TypeChangeDefaults}).
         *
         * @return what a type write reset besides the type; {@link TypeChangeDefaults.Reset#NONE} for
         *         every other change
         */
        @SuppressWarnings("unchecked")
        public TypeChangeDefaults.Reset applyTo(EObject target, IBmTransaction tx)
        {
            switch (kind)
            {
                case TYPE_EXTENSION:
                {
                    // The extension block, never the native type: EDT's .mdo writer keeps an adopted
                    // defined type's type there (issue #709).
                    AdoptedPropertyRouter.applyTypeExtension(target, (AdoptedPropertyRouter.TypePlan)scalarValue, tx);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case EXTENDED_SOURCE:
                {
                    // The base sources plus the additions, and the state Extended - or back to None
                    // (issue #710).
                    AdoptedPropertyRouter.applySource(target, (AdoptedPropertyRouter.SourcePlan)scalarValue, tx);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case LOCALIZED:
                {
                    Object map = target.eGet(feature);
                    if (!(map instanceof EMap))
                    {
                        throw new IllegalStateException("Localized feature '" + feature.getName() //$NON-NLS-1$
                            + "' is not a map"); //$NON-NLS-1$
                    }
                    ((EMap<String, String>)map).put(localizedLanguage, localizedValue);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case REFERENCE:
                {
                    // BM normalizes the target to its in-tx counterpart by bmId on set.
                    target.eSet(feature, requireInTx(tx, referenceBmIds.get(0)));
                    return TypeChangeDefaults.Reset.NONE;
                }
                case MANY_REFERENCE:
                {
                    // Replace the whole list (a plain, non-containment cross-reference list, so add()
                    // only links the target - it does not reparent it).
                    EList<EObject> list = (EList<EObject>)target.eGet(feature);
                    list.clear();
                    for (Long id : referenceBmIds)
                    {
                        list.add(requireInTx(tx, id));
                    }
                    return TypeChangeDefaults.Reset.NONE;
                }
                case MANY_ENUM:
                {
                    // Replace the whole attribute list; every element was resolved to this feature's
                    // enum instance during preparation, before the write transaction opened.
                    EList<Object> list = (EList<Object>)target.eGet(feature);
                    list.clear();
                    list.addAll((List<Object>)scalarValue);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case MCORE_VALUE_LIST:
                {
                    // Replace the whole containment list. ReferenceValue wrappers are created only
                    // here, after re-fetching their XDTO-package target in this transaction.
                    EList<Value> list = (EList<Value>)target.eGet(feature);
                    list.clear();
                    for (McoreValuePreparation prepared :
                        (List<McoreValuePreparation>)scalarValue)
                    {
                        if (prepared.referenceBmId != null)
                        {
                            ReferenceValue reference = McoreFactory.eINSTANCE.createReferenceValue();
                            reference.setValue(requireInTx(tx, prepared.referenceBmId.longValue()));
                            list.add(reference);
                        }
                        else
                        {
                            StringValue string = McoreFactory.eINSTANCE.createStringValue();
                            string.setValue(prepared.namespaceUri);
                            list.add(string);
                        }
                    }
                    return TypeChangeDefaults.Reset.NONE;
                }
                case STYLE_VALUE:
                {
                    // Keep the style item's `type` consistent with the value it now holds (Color / Font),
                    // then set the freshly-built (detached) mcore Value as its containment `value`.
                    if (styleBinding != null && styleBinding.typeFeature != null
                        && styleBinding.type != null)
                    {
                        target.eSet(styleBinding.typeFeature, styleBinding.type);
                    }
                    target.eSet(feature, scalarValue);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case CONTAINED:
                {
                    // The referenced object is re-fetched by bmId so no live object from the
                    // prepare transaction crosses this boundary; null unsets ('auto').
                    EObject referenced = referenceBmIds == null ? null
                        : requireInTx(tx, referenceBmIds.get(0));
                    EObject value = ((ContainedValue)scalarValue).create(referenced);
                    if (value == null)
                    {
                        target.eUnset(feature);
                    }
                    else
                    {
                        target.eSet(feature, value);
                    }
                    return TypeChangeDefaults.Reset.NONE;
                }
                case ADJUSTABLE_BOOLEAN:
                {
                    // Reuse the contained object and rewrite only `common`, so the sibling `for`
                    // overrides survive; create one only when the slot is genuinely empty. A plain
                    // eSet here would replace the object and lose them (issue #382).
                    AdjustableBooleanEdit edit = scalarValue instanceof AdjustableBooleanEdit
                        ? (AdjustableBooleanEdit)scalarValue : null;
                    Object current = target.eGet(feature);
                    boolean common = edit == null ? Boolean.TRUE.equals(scalarValue)
                        : edit.common != null ? edit.common.booleanValue()
                        // No 'common' asked: keep the stored one; an empty slot gets the designer's true.
                        : !(current instanceof AdjustableBoolean) || ((AdjustableBoolean)current).isCommon();
                    if (!FormElementWriter.setAdjustableBooleanFeature(target, feature.getName(), common))
                    {
                        throw new IllegalStateException("Cannot set '" + feature.getName() //$NON-NLS-1$
                            + "': its AdjustableBoolean type cannot be instantiated"); //$NON-NLS-1$
                    }
                    if (edit != null && (!edit.roles.isEmpty() || !edit.roleIds.isEmpty()))
                    {
                        Object flag = target.eGet(feature);
                        if (!(flag instanceof AdjustableBoolean))
                        {
                            throw new IllegalStateException("Cannot set the role values of '" //$NON-NLS-1$
                                + feature.getName() + "': it is not an AdjustableBoolean"); //$NON-NLS-1$
                        }
                        AdjustableBooleanSupport.applyRoleEdits((AdjustableBoolean)flag, edit.rolesIn(tx));
                    }
                    return TypeChangeDefaults.Reset.NONE;
                }
                case FORM_DATA_PATH:
                {
                    FormElementWriter.applyDataPathChange(formOf(target), target,
                        (FormElementWriter.DataPathPlan)scalarValue);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case USE_ALWAYS:
                {
                    if (!(target instanceof FormAttribute))
                    {
                        throw new IllegalStateException("'useAlways' is set on a form attribute only"); //$NON-NLS-1$
                    }
                    UseAlwaysSupport.apply(formOf(target), (FormAttribute)target,
                        (List<UseAlwaysSupport.Plan>)scalarValue);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case EXCLUDED_COMMANDS:
                    ((ExcludedCommandsSupport.Plan)scalarValue).apply(target, feature);
                    return TypeChangeDefaults.Reset.NONE;
                case MEMBER_VALUES:
                    ((MetadataMemberValues.Plan)scalarValue).apply(target, feature, tx);
                    return TypeChangeDefaults.Reset.NONE;
                case FORM_CHOICE_VALUES:
                    ((FormChoiceParameters.Plan)scalarValue).apply(target, tx);
                    return TypeChangeDefaults.Reset.NONE;
                case FIELD_LIST:
                {
                    // Proxies by field id on the re-fetched top object, through change notifications
                    // (no eSetDeliver bracket - see FieldListSupport.replace).
                    FieldListSupport.apply(target, feature, (List<String>)scalarValue);
                    return TypeChangeDefaults.Reset.NONE;
                }
                case SCALAR:
                default:
                    if (typeChange && scalarValue instanceof TypeDescription)
                    {
                        TypeDescription value = (TypeDescription)scalarValue;
                        if (HostKind.current() == HostKind.HEADLESS)
                            value = MetadataTypeBuilder.typesInTransaction(value, tx);
                        return TypeChangeDefaults.setType(target, feature, value, tx);
                    }
                    target.eSet(feature, scalarValue);
                    return TypeChangeDefaults.Reset.NONE;
            }
        }

        /** Re-fetches a reference target inside the tx, failing clearly if it has gone (rolls back). */
        public static EObject requireInTx(IBmTransaction tx, long bmId)
        {
            EObject t = (EObject)tx.getObjectById(bmId);
            if (t == null)
            {
                throw new IllegalStateException("Reference target is no longer in the transaction"); //$NON-NLS-1$
            }
            return t;
        }
    }

    public static String asString(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    public static Boolean parseBoolean(String value)
    {
        if (value == null)
        {
            return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
    }

    public static Integer parseInteger(String value)
    {
        try
        {
            return JsonUtils.extractStrictInt(java.util.Collections.singletonMap("value", value), //$NON-NLS-1$
                "value", 0, Integer.MIN_VALUE, Integer.MAX_VALUE); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    public static Long parseLong(String value)
    {
        return JsonUtils.parseExactDecimalLong(value);
    }
}
