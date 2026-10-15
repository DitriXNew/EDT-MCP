/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.mcore.BinaryQualifiers;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.md.extension.IMdPropertyTypeProvider;
import com._1c.g5.v8.dt.md.extension.MdExtensionTypeUtil;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.ConfigurationExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.DefinedTypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.EventSubscriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionPackage;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdClassExtensionTypePackage;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyType;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeDescriptionInfoWithTypeInfo;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeInfo;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * What a write of a property of an object an extension ADOPTED from its base configuration does to the
 * extension's {@code .mdo}, decided before anything is written (issues #709 / #710).
 *
 * <p><b>Why.</b> EDT keeps a property of an adopted object in the extension's {@code .mdo} only when the
 * extension marks it: EDT's {@code .mdo} writer for an extension
 * ({@code com._1c.g5.v8.dt.internal.md.extension.resource.MdExtXmlFormatter.shouldSaveFeature}) drops the
 * value of a property whose same-named state in the object's extension block is unset or None, and the
 * write still answers that the save task ran. {@link #wouldDrop} mirrors that predicate exactly, and a write
 * it answers {@code true} for is refused - the file would not change. Two properties are not refused but
 * written the way EDT's own editors write them:</p>
 * <ul>
 * <li>an adopted defined type's {@code type} (#709): from runtime 8.3.18 its state is multi-state - the
 * adopter leaves the native type EMPTY and keeps the composition in the extension block's
 * {@code typeExtension}, each type with its own state. The write ADDS types there as {@code Extended},
 * keeps every base type and every type the extension checks as it is, and replaces only the types an
 * earlier call (or EDT) added. EDT's type-extension editor offers {@code Extended} for a defined type only
 * from 8.3.20 ({@code TypeDescriptionExtensionLevel}: CHECKED_ONLY below), and refuses some compositions
 * ({@code TypeExtensionTreeValidator}); both are mirrored, so a composition EDT's editor would refuse is
 * never written;</li>
 * <li>an adopted event subscription's {@code source} (#710): from 8.3.17 it is extensible - the native list
 * becomes the base sources plus the requested ones and the state {@code Extended}, through EDT's own
 * {@link IMdAdoptedPropertyAccess#setValue} with {@code EXTENSIBLE} (what EDT's adopter calls to extend a
 * value); a call that adds nothing returns the state to None, so the source follows the base again.</li>
 * </ul>
 * <p>Every other type of an adopted object is refused: from 8.3.18 EDT changes it only through its type
 * extension, and below that an extension can only check it; the refusal says which. The platform version
 * that decides all of this is the extension project's RUNTIME version (its
 * {@code Runtime-Version}), the one EDT's adopter, derived data, property access and type editor use - not
 * its compatibility mode. An object the extension owns ({@code NATIVE}) is never touched by any of it.</p>
 *
 * <p>Facts read from the bytecode of EDT 2026.1.2 (md.extension 4.2.1, metadata.extension 5.0.1,
 * md.ui.extension 5.0.101) and 2026.2.1 (4.2.2 / 5.0.2 / 5.0.102), identical on both apart from
 * constant-pool indices: the formatter predicate and its one exemption (an extension root's synonym); the
 * property-type delegates of every runtime version; the type-extension level, the states the type-extension
 * tree offers and its validator; the adopter of a defined type and of an event subscription; the dialog
 * commit, which leaves the type extension UNSET when nothing is selected. Base values are read in a read
 * transaction of the BASE project's model; only names and qualifier values cross into the extension's
 * write transaction.</p>
 */
public final class AdoptedPropertyRouter
{
    /** The defined type's type feature (#709). */
    private static final EStructuralFeature DEFINED_TYPE_TYPE = MdClassPackage.Literals.DEFINED_TYPE__TYPE;

    /** The event subscription's source feature (#710). */
    private static final EStructuralFeature SUBSCRIPTION_SOURCE = MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE;

    /** The type names whose qualifiers a type description carries, one qualifier set each. */
    private static final String STRING = "String"; //$NON-NLS-1$
    private static final String NUMBER = "Number"; //$NON-NLS-1$
    private static final String DATE = "Date"; //$NON-NLS-1$
    private static final String BINARY = "BinaryData"; //$NON-NLS-1$

    private static final String NOTHING_CHANGED = " Nothing was changed."; //$NON-NLS-1$
    private static final String LIST_PROJECTS_READY = "retry once list_projects reports it ready"; //$NON-NLS-1$

    private AdoptedPropertyRouter()
    {
        // Utility class
    }

    // ================================ EDT's .mdo writer ================================

    /**
     * Whether EDT's {@code .mdo} writer for an extension would leave {@code feature} of {@code object} out of
     * the file - {@code MdExtXmlFormatter.shouldSaveFeature} answering {@code false} before it asks the
     * generic rule. The predicate, verbatim: an {@link MdObject} whose belonging is
     * {@link ObjectBelonging#ADOPTED}, with an extension block whose class has a feature of EXACTLY the same
     * name (case-sensitive; {@code toolTip} does not find {@code tooltip}) typed
     * {@link MdPropertyState}, that is not the synonym of a {@link ConfigurationExtension} (an extension
     * root's synonym is always written), whose state is unset or {@link MdPropertyState#NONE}.
     *
     * @param object the object written, may be {@code null}
     * @param feature the native feature written, may be {@code null}
     * @return {@code true} when the value would not reach the file
     */
    public static boolean wouldDrop(EObject object, EStructuralFeature feature)
    {
        if (!(object instanceof MdObject) || feature == null)
        {
            return false;
        }
        MdObject md = (MdObject)object;
        if (md.getObjectBelonging() != ObjectBelonging.ADOPTED)
        {
            return false;
        }
        ObjectExtension extension = md.getExtension();
        if (extension == null)
        {
            return false;
        }
        EStructuralFeature state = extension.eClass().getEStructuralFeature(feature.getName());
        if (state == null || state.getEType() != MdClassExtensionTypePackage.Literals.MD_PROPERTY_STATE)
        {
            return false;
        }
        if (extension instanceof ConfigurationExtension
            && state == MdClassExtensionPackage.Literals.MD_OBJECT_EXTENSION__SYNONYM)
        {
            return false;
        }
        Object value = extension.eGet(state);
        return value == null || value == MdPropertyState.NONE;
    }

    /**
     * The state the extension block of {@code object} holds for {@code feature}: the value of the
     * same-named {@link MdPropertyState} feature of the block.
     *
     * @param object the object, may be {@code null}
     * @param feature the native feature, may be {@code null}
     * @return the state, or {@code null} when there is no block, no such state or it is unset
     */
    public static MdPropertyState stateOf(EObject object, EStructuralFeature feature)
    {
        if (!(object instanceof MdObject) || feature == null)
        {
            return null;
        }
        ObjectExtension extension = ((MdObject)object).getExtension();
        if (extension == null)
        {
            return null;
        }
        EStructuralFeature state = extension.eClass().getEStructuralFeature(feature.getName());
        if (state == null || state.getEType() != MdClassExtensionTypePackage.Literals.MD_PROPERTY_STATE)
        {
            return null;
        }
        Object value = extension.eGet(state);
        return value instanceof MdPropertyState ? (MdPropertyState)value : null;
    }

    // ================================ EDT's type-extension level ================================

    /**
     * What EDT's type-extension editor lets an extension do with the types of an adopted object's type -
     * the mirror of {@code TypeDescriptionExtensionLevel.getExtensionLevel} (md.ui.extension, an unexported
     * package), decided by the class of the object that owns the type and the runtime version.
     */
    enum Level
    {
        /** One state for the whole type (accounting flags; external-source fields below 8.3.24). */
        ONE_STATUS_ONLY,
        /** Types can only be checked against the base (defined types, charts, common attributes below 8.3.20). */
        CHECKED_ONLY,
        /** Types can be added ({@code Extended}). */
        CAN_EXTEND
    }

    /**
     * The type-extension level of an owner class at a runtime version, by the class itself (EDT compares
     * the EClass by identity, so a subclass does not count).
     *
     * @param owner the class of the object that owns the type, may be {@code null}
     * @param version the extension project's runtime version
     * @return the level
     */
    static Level levelOf(EClass owner, Version version)
    {
        if (owner == MdClassPackage.Literals.ACCOUNTING_FLAG
            || owner == MdClassPackage.Literals.EXT_DIMENSION_ACCOUNTING_FLAG)
        {
            return Level.ONE_STATUS_ONLY;
        }
        if (owner == MdClassPackage.Literals.FIELD || owner == MdClassPackage.Literals.DIMENSION
            || owner == MdClassPackage.Literals.RESOURCE || owner == MdClassPackage.Literals.FUNCTION)
        {
            return version.isLessThan(Version.V8_3_24) ? Level.ONE_STATUS_ONLY : Level.CAN_EXTEND;
        }
        if (owner == MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES
            || owner == MdClassPackage.Literals.DEFINED_TYPE || owner == MdClassPackage.Literals.COMMON_ATTRIBUTE)
        {
            return version.isLessThan(Version.V8_3_20) ? Level.CHECKED_ONLY : Level.CAN_EXTEND;
        }
        return Level.CAN_EXTEND;
    }

    // ================================ the routing ================================

    /** One prepared change as the router sees it. */
    public static final class Change
    {
        private final EStructuralFeature feature;
        private final Object value;
        private final EStructuralFeature binding;

        /**
         * @param feature the feature the change writes
         * @param value the value it writes - the built {@link TypeDescription} of a type change
         * @param binding a second feature the same change writes (a style item's {@code type}), or {@code null}
         */
        public Change(EStructuralFeature feature, Object value, EStructuralFeature binding)
        {
            this.feature = feature;
            this.value = value;
            this.binding = binding;
        }
    }

    /** What one call writes, and where. */
    public static final class Request
    {
        private final MdObject target;
        private final String fqn;
        private final IProject project;
        private final MetadataScope scope;
        private final Configuration config;
        private final Version version;
        private final List<Change> changes;

        /**
         * @param target the object the batch is written to
         * @param fqn the FQN the call addresses, for the messages
         * @param project the project the object belongs to (the extension)
         * @param scope its resolution root
         * @param config its configuration
         * @param version its runtime version, {@code null} when EDT does not know it yet
         * @param changes the prepared batch, in order
         */
        public Request(MdObject target, String fqn, IProject project, MetadataScope scope, // NOSONAR the request is the whole of what a call writes and where
            Configuration config, Version version, List<Change> changes)
        {
            this.target = target;
            this.fqn = fqn;
            this.project = project;
            this.scope = scope;
            this.config = config;
            this.version = version;
            this.changes = changes;
        }
    }

    /** What the router decided for one change. */
    public static final class Decision
    {
        /** The kinds of decision. */
        public enum Kind
        {
            /** Written as prepared. */
            PLAIN,
            /** Not written: it changes nothing, or a later write of the same type supersedes it. */
            DROP,
            /** Written to the adopted defined type's type extension. */
            TYPE_EXTENSION,
            /** Written as the adopted event subscription's extended source. */
            EXTENDED_SOURCE
        }

        private static final Decision PLAIN = new Decision(Kind.PLAIN, null);
        private static final Decision DROP = new Decision(Kind.DROP, null);

        private final Kind kind;
        private final Object plan;

        private Decision(Kind kind, Object plan)
        {
            this.kind = kind;
            this.plan = plan;
        }

        /** @return the kind */
        public Kind kind()
        {
            return kind;
        }

        /** @return the plan of a {@link Kind#TYPE_EXTENSION} decision, else {@code null} */
        public TypePlan typePlan()
        {
            return plan instanceof TypePlan ? (TypePlan)plan : null;
        }

        /** @return the plan of an {@link Kind#EXTENDED_SOURCE} decision, else {@code null} */
        public SourcePlan sourcePlan()
        {
            return plan instanceof SourcePlan ? (SourcePlan)plan : null;
        }
    }

    /** The router's answer for a batch: a refusal, or one decision per change and the notes of unchanged ones. */
    public static final class Routing
    {
        private final String refusal;
        private final List<Decision> decisions;
        private final List<String> notes;
        /** The call and the platform the routing asked, to read the base again before the write. */
        private final Request request;
        private final Environment env;
        /** The base value each planned write was built from, by the feature it writes. */
        private final Map<EStructuralFeature, BaseRead> bases;

        private Routing(String refusal, List<Decision> decisions, List<String> notes)
        {
            this(refusal, decisions, notes, null, null, Map.of());
        }

        private Routing(String refusal, List<Decision> decisions, List<String> notes, Request request, // NOSONAR the routing keeps what its base re-check needs
            Environment env, Map<EStructuralFeature, BaseRead> bases)
        {
            this.refusal = refusal;
            this.decisions = decisions;
            this.notes = notes;
            this.request = request;
            this.env = env;
            this.bases = bases;
        }

        /**
         * Reads the base value of every planned write ({@link Decision.Kind#TYPE_EXTENSION},
         * {@link Decision.Kind#EXTENDED_SOURCE}) again, in a read transaction of the BASE project's model, and
         * refuses the call when it is not the one the plan was built from: the base configuration is another
         * project, edited independently, and a plan composes the extension's value from its base types or
         * sources, so a plan built from a base value that changed meanwhile - while the call waited for
         * consent - would write a value that drops what the base added. The caller asks right before the write
         * transaction: no transaction spans two projects' models, so this is the last point where the base can
         * be read before the extension is written.
         *
         * @return the ready {@link ToolResult#error} JSON when a base value changed or can no longer be read,
         *     else {@code null}; {@code null} too for a batch with no planned write (nothing is read then)
         */
        public String baseChangedRefusal()
        {
            if (request == null || bases.isEmpty())
            {
                return null;
            }
            String base = env.baseProjectName(request.project);
            for (Map.Entry<EStructuralFeature, BaseRead> planned : bases.entrySet())
            {
                EStructuralFeature feature = planned.getKey();
                BaseRead now = env.readBase(request.project, request.target, feature);
                String text = baseChanged(planned.getValue(), now, feature.getName(), request.fqn, base);
                if (text != null)
                {
                    return ToolResult.error(text).toJson();
                }
            }
            return null;
        }

        /** @return whether the batch is refused; nothing may be written then */
        public boolean isRefused()
        {
            return refusal != null;
        }

        /** @return the ready {@link ToolResult#error} JSON of a refused batch, else {@code null} */
        public String refusal()
        {
            return refusal;
        }

        /**
         * @param index the position of the change in the batch
         * @return the decision for it
         */
        public Decision decision(int index)
        {
            return decisions.get(index);
        }

        /** @return what the confirmation says about changes that change nothing; never {@code null} */
        public List<String> notes()
        {
            return notes;
        }
    }

    /**
     * Decides every change of a batch, through EDT's services: see {@link #route(Request, Environment)}.
     *
     * @param request the batch
     * @return the routing
     */
    public static Routing route(Request request)
    {
        return route(request, PLATFORM);
    }

    /**
     * Decides every change of a batch written to {@code request.target}. A target that is not adopted gets
     * {@link Decision.Kind#PLAIN} everywhere. On an adopted target: an adopted defined type's {@code type} and
     * an adopted event subscription's {@code source} are routed (only the LAST write of each counts, as when
     * a batch is applied; an earlier one is dropped); any other type is refused; any other property
     * {@link #wouldDrop} answers {@code true} for - a style value's {@code type} binding included - is refused;
     * everything else is written as prepared. The first refusal wins, and nothing is written then. A routing
     * that plans a write keeps the base value each plan was built from, for {@link Routing#baseChangedRefusal};
     * the write itself checks again what the routing judged on the object ({@link #applyTypeExtension},
     * {@link #applySource}, {@link #requireStillWritten}).
     *
     * @param request the batch
     * @param env the platform calls
     * @return the routing
     */
    static Routing route(Request request, Environment env)
    {
        List<Change> changes = request.changes;
        List<Decision> decisions = new ArrayList<>(Collections.nCopies(changes.size(), Decision.PLAIN));
        List<String> notes = new ArrayList<>();
        if (!ExtensionServices.isAdopted(request.target))
        {
            return new Routing(null, decisions, notes);
        }
        Map<EStructuralFeature, Integer> lastWrite = new HashMap<>();
        for (int i = 0; i < changes.size(); i++)
        {
            lastWrite.put(changes.get(i).feature, Integer.valueOf(i));
        }
        Map<EStructuralFeature, BaseRead> bases = new LinkedHashMap<>();
        for (int i = 0; i < changes.size(); i++)
        {
            Change change = changes.get(i);
            EStructuralFeature feature = change.feature;
            if (feature == DEFINED_TYPE_TYPE || feature == SUBSCRIPTION_SOURCE)
            {
                if (lastWrite.get(feature).intValue() != i)
                {
                    decisions.set(i, Decision.DROP);
                    continue;
                }
                Routed routed = feature == DEFINED_TYPE_TYPE ? routeDefinedType(request, change, env)
                    : routeSource(request, change, env);
                if (routed.refusal != null)
                {
                    return new Routing(ToolResult.error(routed.refusal).toJson(), decisions, notes);
                }
                decisions.set(i, routed.decision);
                if (routed.note != null)
                {
                    notes.add(routed.note);
                }
                if (routed.base != null)
                {
                    bases.put(feature, routed.base);
                }
            }
            else if (MdExtensionTypeUtil.isTypeDescriptionFeature(feature))
            {
                return new Routing(ToolResult.error(otherTypeRefusal(request, feature, env)).toJson(), decisions,
                    notes);
            }
            else if (wouldDrop(request.target, feature) || wouldDrop(request.target, change.binding))
            {
                EStructuralFeature dropped = wouldDrop(request.target, feature) ? feature : change.binding;
                return new Routing(ToolResult.error(droppedRefusal(request.target, dropped, request.fqn,
                    request.project, request.version, env)).toJson(), decisions, notes);
            }
        }
        return new Routing(null, decisions, notes, request, env, bases);
    }

    /**
     * The refusal of a write EDT's {@code .mdo} writer would drop, for the writers that bypass the generic
     * property batch (a membership {@code content} payload, a predefined item of an adopted owner).
     *
     * @param target the object written
     * @param feature the native feature the write changes
     * @param fqn the FQN of the object, for the message
     * @param project the project the object belongs to
     * @param version the project's runtime version, or {@code null}
     * @return the ready {@link ToolResult#error} JSON, or {@code null} when EDT would write it
     */
    public static String refusalIfDropped(MdObject target, EStructuralFeature feature, String fqn,
        IProject project, Version version)
    {
        return refusalIfDropped(target, feature, fqn, project, version, PLATFORM);
    }

    /** {@link #refusalIfDropped(MdObject, EStructuralFeature, String, IProject, Version)} against {@code env}. */
    static String refusalIfDropped(MdObject target, EStructuralFeature feature, String fqn, IProject project,
        Version version, Environment env)
    {
        if (feature == null || !wouldDrop(target, feature))
        {
            return null;
        }
        return ToolResult.error(droppedRefusal(target, feature, fqn, project, version, env)).toJson();
    }

    /** A decision with its note, or a refusal text; a plan also keeps the base value it was built from. */
    private static final class Routed
    {
        private final Decision decision;
        private final String note;
        private final String refusal;
        private final BaseRead base;

        private Routed(Decision decision, String note, String refusal, BaseRead base)
        {
            this.decision = decision;
            this.note = note;
            this.refusal = refusal;
            this.base = base;
        }

        static Routed refuse(String refusal)
        {
            return new Routed(null, null, refusal, null);
        }

        static Routed unchanged(String note)
        {
            return new Routed(Decision.DROP, note, null, null);
        }

        static Routed plan(Decision decision, BaseRead base)
        {
            return new Routed(decision, null, null, base);
        }
    }

    // ================================ #709: an adopted defined type's type ================================

    private static Routed routeDefinedType(Request request, Change change, Environment env)
    {
        MdObject target = request.target;
        String fqn = request.fqn;
        Version version = request.version;
        String base = env.baseProjectName(request.project);
        if (version == null)
        {
            return Routed.refuse(unknownVersionRefusal("type", fqn, base)); //$NON-NLS-1$
        }
        ObjectExtension extension = target.getExtension();
        EStructuralFeature typeExtensionFeature = extension instanceof DefinedTypeExtension
            ? MdExtensionTypeUtil.getTypeExtensionFeature(extension) : null;
        if (typeExtensionFeature == null)
        {
            return Routed.refuse(noBlockRefusal("type", fqn, base)); //$NON-NLS-1$
        }
        Collection<MdPropertyType> types = env.propertyTypes(target, change.feature, version);
        if (types == null)
        {
            return Routed.refuse(serviceRefusal("type", fqn, "IMdPropertyTypeProvider", base)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!types.contains(MdPropertyType.MULTI_STATE) || levelOf(target.eClass(), version) != Level.CAN_EXTEND)
        {
            return Routed.refuse("'type' of " + fqn + " was not written: " + fqn + " is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "configuration" + named(base) + ", and EDT lets an extension for platform " + version //$NON-NLS-1$ //$NON-NLS-2$
                + " only check a defined type's types against the base, not add any; adding types needs " //$NON-NLS-1$
                + "platform 8.3.20 or later (the extension project's platform version). Change the type in the " //$NON-NLS-1$
                + "base configuration" + named(base) + "." + NOTHING_CHANGED); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!(change.value instanceof TypeDescription))
        {
            return Routed.refuse("'type' of " + fqn + " was not written: the value is not a type." //$NON-NLS-1$ //$NON-NLS-2$
                + NOTHING_CHANGED);
        }
        TypeDescription requested = (TypeDescription)change.value;
        BaseRead baseRead = env.readBase(request.project, target, change.feature);
        String baseRefusal = baseRefusal(baseRead, "type", fqn, base); //$NON-NLS-1$
        if (baseRefusal != null)
        {
            return Routed.refuse(baseRefusal);
        }
        TypeInfos infos = env.typeInfos(target, change.feature);
        if (infos == null)
        {
            return Routed.refuse("'type' of " + fqn + " was not written: " + fqn + " is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "configuration" + named(base) + ", and EDT's type information for its type, which decides which " //$NON-NLS-1$ //$NON-NLS-2$
                + "types EDT's type-extension editor offers, is not available; " + LIST_PROJECTS_READY + "." //$NON-NLS-1$ //$NON-NLS-2$
                + NOTHING_CHANGED);
        }
        TypeDescriptionExtension current = (TypeDescriptionExtension)extension.eGet(typeExtensionFeature);
        Classification classified = classify(requested, current, baseRead, infos, fqn, base);
        if (classified.refusal != null)
        {
            return Routed.refuse(classified.refusal);
        }
        Set<String> baseNames = new LinkedHashSet<>(baseRead.typeNames);
        Composition predicted = composeTypeExtension(current, baseNames, classified.additions, requested);
        if (!predicted.changed)
        {
            return Routed.unchanged("'type' of " + fqn + " unchanged: its type extension already holds what the " //$NON-NLS-1$ //$NON-NLS-2$
                + "call asks for" + typeDetails(classified.requestedBase, classified.requestedKept)); //$NON-NLS-1$
        }
        String unreadable = unreadableTypeRefusal(current, fqn, base);
        if (unreadable != null)
        {
            return Routed.refuse(unreadable);
        }
        String compositionRefusal = compositionRefusal(predicted, baseRead, infos, fqn, base);
        if (compositionRefusal != null)
        {
            return Routed.refuse(compositionRefusal);
        }
        TypePlan plan = new TypePlan(fqn, change.feature, baseNames, classified.additions, requested,
            classified.requestedBase, classified.requestedKept, predicted.added, predicted.removed,
            statesByName(current), Qualifiers.of(current));
        return Routed.plan(new Decision(Decision.Kind.TYPE_EXTENSION, plan), baseRead);
    }

    /** The request's types sorted into base types, types the extension checks, and additions. */
    private static final class Classification
    {
        private final List<TypeItem> additions = new ArrayList<>();
        private final List<String> requestedBase = new ArrayList<>();
        private final List<String> requestedKept = new ArrayList<>();
        private String refusal;
    }

    private static Classification classify(TypeDescription request, TypeDescriptionExtension current,
        BaseRead baseRead, TypeInfos infos, String fqn, String base)
    {
        Classification result = new Classification();
        Map<String, MdPropertyState> currentStates = statesByName(current);
        Qualifiers currentQualifiers = Qualifiers.of(current);
        Qualifiers requestQualifiers = Qualifiers.of(request);
        Set<String> seen = new LinkedHashSet<>();
        List<TypeItem> items = request.getTypes();
        for (int i = 0; i < items.size(); i++)
        {
            TypeItem item = items.get(i);
            String name = typeName(item);
            if (name == null)
            {
                result.refusal = "'type' of " + fqn + " was not written: the name of type.types[" + i //$NON-NLS-1$ //$NON-NLS-2$
                    + "] cannot be read." + NOTHING_CHANGED; //$NON-NLS-1$
                return result;
            }
            if (!seen.add(name))
            {
                continue;
            }
            MdPropertyState currentState = currentStates.get(name);
            if (baseRead.typeNames.contains(name))
            {
                Qualifiers stands = currentState != null && currentQualifiers.has(name) ? currentQualifiers
                    : baseRead.qualifiers;
                if (requestQualifiers.differ(name, stands))
                {
                    result.refusal = "'" + name + "' (type.types[" + i + "]) is already a base type of " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + adopted(fqn, base) + ", as " + stands.spell(name) //$NON-NLS-1$
                        + "; modify_metadata leaves base types as they stand " //$NON-NLS-1$
                        + "- EDT changes one by marking the base type Extended in its type-extension editor. Pass it " //$NON-NLS-1$
                        + "as it stands or leave it out: pass only the types to add." + NOTHING_CHANGED; //$NON-NLS-1$
                    return result;
                }
                result.requestedBase.add(name);
            }
            else if (currentState == MdPropertyState.CHECKED || currentState == MdPropertyState.NOTIFY)
            {
                if (requestQualifiers.differ(name, currentQualifiers))
                {
                    result.refusal = "'" + name + "' (type.types[" + i + "]) is a type the extension checks on " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + adopted(fqn, base) + ", as " + currentQualifiers.spell(name) //$NON-NLS-1$
                        + "; modify_metadata leaves the types the " //$NON-NLS-1$
                        + "extension checks as they stand. Pass it as it stands or leave it out." + NOTHING_CHANGED; //$NON-NLS-1$
                    return result;
                }
                result.requestedKept.add(name);
            }
            else
            {
                String refusal = additionRefusal(item, name, i, infos, fqn, base);
                if (refusal != null)
                {
                    result.refusal = refusal;
                    return result;
                }
                result.additions.add(item);
            }
        }
        return result;
    }

    /**
     * Whether EDT's type-extension tree offers {@code Extended} for {@code item}
     * ({@code TypeExtensionTree.createAvailableStates} at CAN_EXTEND): a type of an object the extension
     * owns always; otherwise not a type set, and not a single type while the type does not mix single types.
     * A type EDT's tree does not list at all is not offered either.
     */
    private static String additionRefusal(TypeItem item, String name, int index, TypeInfos infos, String fqn, // NOSONAR the refusal names the item, its place, the object and the base
        String base)
    {
        String head = "'" + name + "' (type.types[" + index + "]) cannot be added to " + adopted(fqn, base) + ": "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        boolean typeSet = item instanceof TypeSet;
        TypeInfoView info = infos.info(name, typeSet);
        if (info == null)
        {
            return head + "EDT's type-extension editor does not offer it for this type." + NOTHING_CHANGED; //$NON-NLS-1$
        }
        if (isExtensionOwnType(item))
        {
            return null;
        }
        if (typeSet)
        {
            return head + "it is a type set, which EDT lets an extension only check against the base, not add." //$NON-NLS-1$
                + NOTHING_CHANGED;
        }
        if (!infos.mixSingleEnable() && (info.single || info.strongSingle))
        {
            return head + "it is a type that cannot be combined with other types, which EDT lets an extension " //$NON-NLS-1$
                + "only check against the base, not add." + NOTHING_CHANGED; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The rules of EDT's {@code TypeExtensionTreeValidator} on the composition the write leaves: a type that
     * cannot be combined with others (a single type while mixing is off, a strong single type, an unlimited
     * String, an unlimited BinaryData) in a composition of more than one type; and an {@code Extended} type
     * next to a base type that cannot be part of a composite type.
     */
    private static String compositionRefusal(Composition composition, BaseRead baseRead, TypeInfos infos,
        String fqn, String base)
    {
        boolean mix = infos.mixSingleEnable();
        List<String> names = new ArrayList<>();
        List<String> singles = new ArrayList<>();
        for (TypeExtension type : composition.next.getTypes())
        {
            String name = typeName(type.getType());
            names.add(name);
            TypeInfoView info = infos.info(name, type.getType() instanceof TypeSet);
            boolean single = info != null && (info.single && !mix || info.strongSingle)
                || STRING.equals(name) && unlimited(composition.next.getStringQualifiers())
                || BINARY.equals(name) && unlimited(composition.next.getBinaryQualifiers());
            if (single)
            {
                singles.add(name);
            }
        }
        if (!singles.isEmpty() && names.size() > 1)
        {
            return "The type of " + adopted(fqn, base) + " would become " + String.join(", ", names) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", which EDT's type-extension editor refuses: " + String.join(", ", singles) //$NON-NLS-1$ //$NON-NLS-2$
                + " cannot be combined with other types (a single type, an unlimited String or an unlimited " //$NON-NLS-1$
                + "BinaryData stays alone)." + NOTHING_CHANGED; //$NON-NLS-1$
        }
        String blob = null;
        for (int i = 0; i < baseRead.typeNames.size() && blob == null; i++)
        {
            String name = baseRead.typeNames.get(i);
            TypeInfoView info = infos.info(name, baseRead.typeSets.get(i).booleanValue());
            if (info != null && (info.strongSingle || !mix && info.single
                || STRING.equals(name) && Objects.equals(baseRead.qualifiers.stringLength, Integer.valueOf(0))
                || BINARY.equals(name) && Objects.equals(baseRead.qualifiers.binaryLength, Integer.valueOf(0))))
            {
                blob = name;
            }
        }
        if (blob != null)
        {
            List<String> blocked = new ArrayList<>();
            for (TypeExtension type : composition.next.getTypes())
            {
                String name = typeName(type.getType());
                if (type.getState() == MdPropertyState.EXTENDED && !blob.equals(name))
                {
                    blocked.add(name);
                }
            }
            if (!blocked.isEmpty())
            {
                return "'" + String.join("', '", blocked) + "' cannot be added to " + adopted(fqn, base) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ": its base type " //$NON-NLS-1$
                    + "'" + blob + "' cannot be part of a composite type, so EDT's type-extension editor refuses any " //$NON-NLS-1$ //$NON-NLS-2$
                    + "type added next to it." + NOTHING_CHANGED; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static boolean unlimited(StringQualifiers qualifiers)
    {
        return qualifiers != null && qualifiers.getLength() == 0;
    }

    private static boolean unlimited(BinaryQualifiers qualifiers)
    {
        return qualifiers != null && qualifiers.getLength() == 0;
    }

    /**
     * Whether {@code item} is a type of an object the extension owns: its nearest containing metadata object
     * is {@link ObjectBelonging#NATIVE} ({@code TypeExtensionTree.isNativeType}).
     */
    static boolean isExtensionOwnType(TypeItem item)
    {
        for (EObject container = item == null ? null : item.eContainer(); container != null;
            container = container.eContainer())
        {
            if (container instanceof MdObject)
            {
                return ((MdObject)container).getObjectBelonging() == ObjectBelonging.NATIVE;
            }
        }
        return false;
    }

    // ================================ #710: an adopted event subscription's source ================================

    private static Routed routeSource(Request request, Change change, Environment env)
    {
        MdObject target = request.target;
        String fqn = request.fqn;
        Version version = request.version;
        String base = env.baseProjectName(request.project);
        if (version == null)
        {
            return Routed.refuse(unknownVersionRefusal("source", fqn, base)); //$NON-NLS-1$
        }
        if (!(target.getExtension() instanceof EventSubscriptionExtension))
        {
            return Routed.refuse(noBlockRefusal("source", fqn, base)); //$NON-NLS-1$
        }
        Collection<MdPropertyType> types = env.propertyTypes(target, change.feature, version);
        if (types == null)
        {
            return Routed.refuse(serviceRefusal("source", fqn, "IMdPropertyTypeProvider", base)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!types.contains(MdPropertyType.EXTENSIBLE))
        {
            return Routed.refuse("'source' of " + fqn + " was not written: " + fqn + " is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "configuration" + named(base) + ", and an extension for platform " + version + " cannot change " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "an event subscription's source (EDT offers it from platform 8.3.17, the extension project's " //$NON-NLS-1$
                + "platform version). Change it in the base configuration" + named(base) + "." + NOTHING_CHANGED); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IMdAdoptedPropertyAccess access = env.access();
        if (access == null)
        {
            return Routed.refuse(serviceRefusal("source", fqn, "IMdAdoptedPropertyAccess", base)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!(change.value instanceof TypeDescription))
        {
            return Routed.refuse("'source' of " + fqn + " was not written: the value is not a type." //$NON-NLS-1$ //$NON-NLS-2$
                + NOTHING_CHANGED);
        }
        BaseRead baseRead = env.readBase(request.project, target, change.feature);
        String baseRefusal = baseRefusal(baseRead, "source", fqn, base); //$NON-NLS-1$
        if (baseRefusal != null)
        {
            return Routed.refuse(baseRefusal);
        }
        List<String> baseNames = new ArrayList<>(new LinkedHashSet<>(baseRead.typeNames));
        List<TypeItem> additions = new ArrayList<>();
        List<String> additionNames = new ArrayList<>();
        List<String> requestedBase = new ArrayList<>();
        List<TypeItem> items = ((TypeDescription)change.value).getTypes();
        for (int i = 0; i < items.size(); i++)
        {
            String name = typeName(items.get(i));
            if (name == null)
            {
                return Routed.refuse("'source' of " + fqn + " was not written: the name of source.types[" + i //$NON-NLS-1$ //$NON-NLS-2$
                    + "] cannot be read." + NOTHING_CHANGED); //$NON-NLS-1$
            }
            if (baseNames.contains(name))
            {
                if (!requestedBase.contains(name))
                {
                    requestedBase.add(name);
                }
            }
            else if (!additionNames.contains(name))
            {
                additionNames.add(name);
                additions.add(items.get(i));
            }
        }
        // An adoption leaves the subscription's own source empty (EDT 2026.2); an earlier extended write fills it
        // with the base sources plus its additions, and a base source found there is carried from there. Every other
        // base source is built by name in the extension - from an object it adopted - or the call is refused.
        Map<String, TypeItem> copy = itemsByName(target.eGet(change.feature));
        Map<String, TypeItem> built = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : baseNames)
        {
            TypeItem item = env.buildSourceItem(request, name);
            if (item != null)
            {
                built.put(name, item);
            }
            else if (!copy.containsKey(name))
            {
                missing.add(name);
            }
        }
        if (!missing.isEmpty())
        {
            return Routed.refuse(notCarriedRefusal(fqn, missing, base));
        }
        MdPropertyState state = stateOf(target, change.feature);
        boolean extended = state != null && state != MdPropertyState.NONE;
        if (additions.isEmpty() && !extended)
        {
            return Routed.unchanged("'source' of " + fqn + " unchanged: the call adds no source beyond the base " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration's, and the source already follows the base (state None)"); //$NON-NLS-1$
        }
        // Compared as a set: the order of a type description's types carries no meaning - EDT's own type editor
        // sorts every type description it writes into the platform's runtime order
        // (AbstractTypeDescriptionModel.buildTypeDescriptionValue -> IModelObjectCollectionRuntimeOrderSorter),
        // whatever order the base or a caller lists them in.
        Set<String> wanted = new LinkedHashSet<>(baseNames);
        wanted.addAll(additionNames);
        if (!additions.isEmpty() && state == MdPropertyState.EXTENDED && copy.keySet().equals(wanted))
        {
            return Routed.unchanged("'source' of " + fqn + " unchanged: it is already Extended with the base " //$NON-NLS-1$ //$NON-NLS-2$
                + "sources plus " + String.join(", ", additionNames)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String unreadable = unreadableSourceRefusal(target.eGet(change.feature), fqn, base);
        if (unreadable != null)
        {
            return Routed.refuse(unreadable);
        }
        SourcePlan plan = new SourcePlan(fqn, change.feature, baseNames, built, additions, additionNames, access,
            copy.keySet(), state);
        return Routed.plan(new Decision(Decision.Kind.EXTENDED_SOURCE, plan), baseRead);
    }

    private static String notCarriedRefusal(String fqn, List<String> missing, String base)
    {
        List<String> objects = new ArrayList<>();
        for (String name : missing)
        {
            objects.add(sourceObject(name));
        }
        return "'source' of " + fqn + " was not written: the base source " + quoted(missing) + " of the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "configuration" + named(base) + " names " + String.join(", ", objects) + ", which the extension has " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "not adopted, and no earlier extended write left it in the subscription's own source in the extension; " //$NON-NLS-1$
            + "written without it, the extended source would drop that base source. Adopt " //$NON-NLS-1$
            + String.join(", ", objects) + " with adopt_metadata_object, then retry." + NOTHING_CHANGED; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The metadata object a source type names: {@code DocumentObject.Invoice} names {@code Document.Invoice}. */
    private static String sourceObject(String typeName)
    {
        int dot = typeName.indexOf('.');
        if (dot <= 0)
        {
            return typeName;
        }
        MetadataTypeBuilder.ProducedTypeKind kind = MetadataTypeBuilder.splitProducedTypeKind(typeName.substring(0, dot));
        return kind != null && kind.englishMetadataType != null
            ? kind.englishMetadataType + typeName.substring(dot) : typeName;
    }

    // ================================ the plans and their writes ================================

    /** What a routed write did, for the confirmation. */
    public static final class Outcome
    {
        private final boolean changed;
        private final String note;

        private Outcome(boolean changed, String note)
        {
            this.changed = changed;
            this.note = note;
        }

        /** @return whether the write changed the object */
        public boolean changed()
        {
            return changed;
        }

        /** @return what the confirmation says about it */
        public String note()
        {
            return note;
        }
    }

    /** An adopted defined type's type, as its type extension is to be written (#709). */
    public static final class TypePlan
    {
        private final String fqn;
        private final EStructuralFeature feature;
        private final Set<String> baseNames;
        private final List<TypeItem> additions;
        private final TypeDescription request;
        private final List<String> requestedBase;
        private final List<String> requestedKept;
        private final List<String> predictedAdded;
        private final List<String> predictedRemoved;
        /** The type extension the routing judged: its states by type name, and its qualifier values. */
        private final Map<String, MdPropertyState> readStates;
        private final Qualifiers readQualifiers;
        private Outcome outcome;

        TypePlan(String fqn, EStructuralFeature feature, Set<String> baseNames, List<TypeItem> additions, // NOSONAR the plan is the whole of what the write needs
            TypeDescription request, List<String> requestedBase, List<String> requestedKept,
            List<String> predictedAdded, List<String> predictedRemoved, Map<String, MdPropertyState> readStates,
            Qualifiers readQualifiers)
        {
            this.fqn = fqn;
            this.feature = feature;
            this.baseNames = Collections.unmodifiableSet(new LinkedHashSet<>(baseNames));
            this.additions = Collections.unmodifiableList(new ArrayList<>(additions));
            this.request = request;
            this.requestedBase = Collections.unmodifiableList(new ArrayList<>(requestedBase));
            this.requestedKept = Collections.unmodifiableList(new ArrayList<>(requestedKept));
            this.predictedAdded = Collections.unmodifiableList(new ArrayList<>(predictedAdded));
            this.predictedRemoved = Collections.unmodifiableList(new ArrayList<>(predictedRemoved));
            this.readStates = Collections.unmodifiableMap(new LinkedHashMap<>(readStates));
            this.readQualifiers = readQualifiers;
        }

        /** @return what the write did, or {@code null} before it ran */
        public Outcome outcome()
        {
            return outcome;
        }

        /** @return the warning of the consent preview: what the write adds and what it removes */
        public String consentWarning()
        {
            String text = "Adds " + (predictedAdded.isEmpty() ? "no type" : String.join(", ", predictedAdded)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " to the type extension of the adopted defined type; its base types are left as they are"; //$NON-NLS-1$
            if (!predictedRemoved.isEmpty())
            {
                text += ". Removes the earlier additions " + String.join(", ", predictedRemoved) //$NON-NLS-1$ //$NON-NLS-2$
                    + ", which can drop values of those types on the next database update"; //$NON-NLS-1$
            }
            return text + "."; //$NON-NLS-1$
        }
    }

    /** An adopted event subscription's source, as it is to be written (#710). */
    public static final class SourcePlan
    {
        private final String fqn;
        private final EStructuralFeature feature;
        private final List<String> baseNames;
        private final Map<String, TypeItem> built;
        private final List<TypeItem> additions;
        private final List<String> additionNames;
        private final IMdAdoptedPropertyAccess access;
        /** The source the routing judged: the names its own source held, and the state ({@code null}: unset). */
        private final Set<String> readNames;
        private final MdPropertyState readState;
        private Outcome outcome;

        SourcePlan(String fqn, EStructuralFeature feature, List<String> baseNames, Map<String, TypeItem> built, // NOSONAR the plan is the whole of what the write needs
            List<TypeItem> additions, List<String> additionNames, IMdAdoptedPropertyAccess access,
            Set<String> readNames, MdPropertyState readState)
        {
            this.fqn = fqn;
            this.feature = feature;
            this.baseNames = Collections.unmodifiableList(new ArrayList<>(baseNames));
            this.built = Collections.unmodifiableMap(new LinkedHashMap<>(built));
            this.additions = Collections.unmodifiableList(new ArrayList<>(additions));
            this.additionNames = Collections.unmodifiableList(new ArrayList<>(additionNames));
            this.access = access;
            this.readNames = Collections.unmodifiableSet(new LinkedHashSet<>(readNames));
            this.readState = readState;
        }

        /** @return what the write did, or {@code null} before it ran */
        public Outcome outcome()
        {
            return outcome;
        }

        /** @return the warning of the consent preview: what the subscription handles afterwards */
        public String consentWarning()
        {
            return additions.isEmpty()
                ? "Returns the source of the adopted event subscription to the base configuration's (no stored data " //$NON-NLS-1$
                    + "changes)." //$NON-NLS-1$
                : "Sets the source of the adopted event subscription to the base sources plus " //$NON-NLS-1$
                    + String.join(", ", additionNames) + " (no stored data changes)."; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Writes an adopted defined type's type extension inside the write transaction: the extension's
     * current type extension is read again and must be the one the routing judged - its states and qualifier
     * values (the consent gate can hold the call between the two, and EDT's editor can change it meanwhile) -
     * every base type and every type it checks or notifies about is kept as it is, the requested additions
     * replace the earlier ones as {@code Extended} ({@link #composeTypeExtension}), and the result is set - or
     * the feature unset when no type is left, as EDT's type-extension dialog leaves it with nothing selected.
     * The native type is left as it is, as that dialog leaves it; while the defined type's {@code type} state
     * is None, EDT's {@code .mdo} writer leaves it out of the extension's file. A type extension that holds a
     * type whose name cannot be read is never replaced: the write could not tell whether to keep that type.
     *
     * @param target the adopted defined type, inside the transaction
     * @param plan the plan
     * @throws IllegalStateException when the object has no type extension any more, its type extension is
     *             not the one the routing judged, or it holds a type whose name cannot be read (rolls back)
     */
    public static void applyTypeExtension(EObject target, TypePlan plan)
    {
        ObjectExtension extension = target instanceof MdObject ? ((MdObject)target).getExtension() : null;
        EStructuralFeature typeExtensionFeature = extension instanceof DefinedTypeExtension
            ? MdExtensionTypeUtil.getTypeExtensionFeature(extension) : null;
        if (typeExtensionFeature == null)
        {
            throw Refusals.state(noBlockRefusal("type", plan.fqn, null)); //$NON-NLS-1$
        }
        TypeDescriptionExtension current = (TypeDescriptionExtension)extension.eGet(typeExtensionFeature);
        if (!plan.readStates.equals(statesByName(current)) || !plan.readQualifiers.equals(Qualifiers.of(current)))
        {
            throw Refusals.state(changedRefusal("type", plan.fqn, "its type extension")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Composition composition = composeTypeExtension(current, plan.baseNames, plan.additions, plan.request);
        if (composition.changed)
        {
            String unreadable = unreadableTypeRefusal(current, plan.fqn, null);
            if (unreadable != null)
            {
                throw Refusals.state(unreadable);
            }
            if (composition.next.getTypes().isEmpty())
            {
                extension.eUnset(typeExtensionFeature);
            }
            else
            {
                extension.eSet(typeExtensionFeature, composition.next);
            }
        }
        plan.outcome = new Outcome(composition.changed, typeNote(plan, composition));
    }

    /**
     * Writes an adopted event subscription's source inside the write transaction: the subscription's own source
     * and its source state are read again and must be the ones the routing judged (the consent gate can hold the
     * call between the two, and EDT's editor can change them meanwhile); then the base sources - each the item
     * the own source already holds for it, as it does after an earlier extended write (an adoption leaves it
     * empty), else the item built for it in the extension - followed by the additions.
     * With additions the list is written through {@link IMdAdoptedPropertyAccess#setValue} with
     * {@link MdPropertyType#EXTENSIBLE} - the value and the state {@code Extended}; without, the state goes
     * back to None and the list to the base sources, so the source follows the base again.
     *
     * @param target the adopted event subscription, inside the transaction
     * @param plan the plan, with the adopted-property access it is written through
     * @throws IllegalStateException when the object has no extension block any more, its source or source
     *             state is not the one the routing judged, a base source can no longer be carried, or the own
     *             source to be replaced holds a source whose name cannot be read (rolls back)
     */
    public static void applySource(EObject target, SourcePlan plan)
    {
        IMdAdoptedPropertyAccess access = plan.access;
        if (!(target instanceof MdObject) || !(((MdObject)target).getExtension() instanceof EventSubscriptionExtension))
        {
            throw Refusals.state(noBlockRefusal("source", plan.fqn, null)); //$NON-NLS-1$
        }
        if (access == null)
        {
            throw Refusals.state(serviceRefusal("source", plan.fqn, "IMdAdoptedPropertyAccess", null)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Object current = target.eGet(plan.feature);
        MdPropertyState state = stateOf(target, plan.feature);
        if (!plan.readNames.equals(itemsByName(current).keySet()) || effective(state) != effective(plan.readState))
        {
            throw Refusals.state(changedRefusal("source", plan.fqn, "its source")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<TypeItem> next = composeSource(current instanceof TypeDescription ? (TypeDescription)current : null,
            plan.baseNames, plan.built, plan.additions, plan.fqn);
        TypeDescription value = McoreFactory.eINSTANCE.createTypeDescription();
        value.getTypes().addAll(next);
        if (!plan.additions.isEmpty())
        {
            if (state == MdPropertyState.EXTENDED && itemsByName(current).keySet().equals(itemsByName(value).keySet()))
            {
                plan.outcome = new Outcome(false, "'source' of " + plan.fqn + " unchanged: it is already Extended with " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the base sources plus " + String.join(", ", plan.additionNames)); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            requireReadableSource(current, plan.fqn);
            access.setValue(target, plan.feature, MdPropertyType.EXTENSIBLE, value);
            plan.outcome = new Outcome(true, plan.fqn + " is adopted: its source is Extended - the base sources " //$NON-NLS-1$ //$NON-NLS-2$
                + listOrNone(plan.baseNames) + " plus " + String.join(", ", plan.additionNames)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (state == null || state == MdPropertyState.NONE)
        {
            plan.outcome = new Outcome(false, "'source' of " + plan.fqn + " unchanged: it already follows the base " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration (state None)"); //$NON-NLS-1$
            return;
        }
        requireReadableSource(current, plan.fqn);
        target.eSet(plan.feature, value);
        access.setPropertyState(target, plan.feature, MdPropertyState.NONE);
        plan.outcome = new Outcome(true, plan.fqn + " is adopted and the call adds no source beyond the base " //$NON-NLS-1$ //$NON-NLS-2$
            + "configuration's: the source is no longer extended (state None) and follows the base configuration"); //$NON-NLS-1$
    }

    // ================================ the compositions ================================

    /** A type extension as a write leaves it, and how it differs from the current one. */
    static final class Composition
    {
        final TypeDescriptionExtension next;
        final List<String> kept;
        final List<String> added;
        final List<String> stillExtended;
        final List<String> removed;
        final boolean changed;

        Composition(TypeDescriptionExtension next, List<String> kept, List<String> added, // NOSONAR a value of five parts
            List<String> stillExtended, List<String> removed, boolean changed)
        {
            this.next = next;
            this.kept = kept;
            this.added = added;
            this.stillExtended = stillExtended;
            this.removed = removed;
            this.changed = changed;
        }
    }

    /**
     * The type extension a write leaves, from the current one: every current type of a base name, whatever
     * its state other than None, and every current type the extension checks or notifies about is kept -
     * a new type extension with the same type item and state, in the current order; then each addition,
     * deduplicated, as {@code Extended}. A current {@code Extended} type that is neither a base type nor
     * passed again is removed. Qualifiers per kind (String / Number / Date / BinaryData): an added type of
     * that kind takes the request's; otherwise a kept one keeps the current ones; otherwise there are none.
     * {@link Composition#changed} compares the types and states as a set, and the qualifiers by value.
     *
     * @param current the current type extension, may be {@code null}
     * @param baseNames the base composition's type names
     * @param additions the types to add, in request order
     * @param request the requested type (its qualifiers)
     * @return the composition
     */
    static Composition composeTypeExtension(TypeDescriptionExtension current, Set<String> baseNames,
        List<TypeItem> additions, TypeDescription request)
    {
        TypeDescriptionExtension next = MdExtensionTypeUtil.newTypeDescriptionExtension();
        Set<String> additionNames = new LinkedHashSet<>();
        for (TypeItem addition : additions)
        {
            String name = typeName(addition);
            if (name != null)
            {
                additionNames.add(name);
            }
        }
        List<String> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Map<String, MdPropertyState> currentStates = statesByName(current);
        if (current != null)
        {
            for (TypeExtension type : current.getTypes())
            {
                String name = typeName(type.getType());
                MdPropertyState state = type.getState();
                if (name == null || state == null || state == MdPropertyState.NONE)
                {
                    continue;
                }
                if (baseNames.contains(name) || state == MdPropertyState.CHECKED || state == MdPropertyState.NOTIFY)
                {
                    if (names.add(name))
                    {
                        next.getTypes().add(MdExtensionTypeUtil.newTypeExtension(type.getType(), state));
                        kept.add(name);
                    }
                }
                else if (!additionNames.contains(name) && !removed.contains(name))
                {
                    removed.add(name);
                }
            }
        }
        List<String> added = new ArrayList<>();
        List<String> stillExtended = new ArrayList<>();
        Set<String> addedKinds = new LinkedHashSet<>();
        for (TypeItem addition : additions)
        {
            String name = typeName(addition);
            if (name == null || !names.add(name))
            {
                continue;
            }
            next.getTypes().add(MdExtensionTypeUtil.newTypeExtension(addition, MdPropertyState.EXTENDED));
            addedKinds.add(name);
            if (currentStates.get(name) == MdPropertyState.EXTENDED)
            {
                stillExtended.add(name);
            }
            else
            {
                added.add(name);
            }
        }
        Set<String> keptKinds = new LinkedHashSet<>(kept);
        setQualifiers(next, request, current, addedKinds, keptKinds);
        boolean changed = !currentStates.equals(statesByName(next))
            || !Qualifiers.of(current).equals(Qualifiers.of(next));
        return new Composition(next, kept, added, stillExtended, removed, changed);
    }

    private static void setQualifiers(TypeDescriptionExtension next, TypeDescription request,
        TypeDescriptionExtension current, Set<String> addedKinds, Set<String> keptKinds)
    {
        if (addedKinds.contains(NUMBER))
        {
            next.setNumberQualifiers(copy(request.getNumberQualifiers()));
        }
        else if (keptKinds.contains(NUMBER) && current != null)
        {
            next.setNumberQualifiers(copy(current.getNumberQualifiers()));
        }
        if (addedKinds.contains(STRING))
        {
            next.setStringQualifiers(copy(request.getStringQualifiers()));
        }
        else if (keptKinds.contains(STRING) && current != null)
        {
            next.setStringQualifiers(copy(current.getStringQualifiers()));
        }
        if (addedKinds.contains(DATE))
        {
            next.setDateQualifiers(copy(request.getDateQualifiers()));
        }
        else if (keptKinds.contains(DATE) && current != null)
        {
            next.setDateQualifiers(copy(current.getDateQualifiers()));
        }
        if (addedKinds.contains(BINARY))
        {
            next.setBinaryQualifiers(copy(request.getBinaryQualifiers()));
        }
        else if (keptKinds.contains(BINARY) && current != null)
        {
            next.setBinaryQualifiers(copy(current.getBinaryQualifiers()));
        }
    }

    private static <T extends EObject> T copy(T qualifiers)
    {
        return qualifiers == null ? null : EcoreUtil.copy(qualifiers);
    }

    /**
     * The source a write leaves: each base source in base order (a writing order only - the order of a type
     * description's types carries no meaning, and EDT's own type editor sorts them into the platform's runtime
     * order) - the item the subscription's own source already
     * holds for it, as it does after an earlier extended write (this serves a {@code .mdo} written that way; an
     * adoption leaves the own source empty), else the item built for it in the extension - then each addition,
     * deduplicated by name.
     *
     * @param current the current native source, may be {@code null}
     * @param baseNames the base sources, in base order
     * @param built the items built in the extension, by base name
     * @param additions the sources to add
     * @param fqn the FQN of the subscription, for the message
     * @return the items, in order
     * @throws IllegalStateException when a base source can be carried neither way
     */
    static List<TypeItem> composeSource(TypeDescription current, List<String> baseNames, Map<String, TypeItem> built,
        List<TypeItem> additions, String fqn)
    {
        Map<String, TypeItem> copy = itemsByName(current);
        List<TypeItem> next = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (String name : baseNames)
        {
            TypeItem item = copy.containsKey(name) ? copy.get(name) : built.get(name);
            if (item == null)
            {
                throw Refusals.state("'source' of " + fqn + " was not written: the base source '" + name //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is no longer held by the extension; retry the call." + NOTHING_CHANGED); //$NON-NLS-1$
            }
            if (names.add(name))
            {
                next.add(item);
            }
        }
        for (TypeItem addition : additions)
        {
            String name = typeName(addition);
            if (name != null && names.add(name))
            {
                next.add(addition);
            }
        }
        return next;
    }

    // ================================ the messages ================================

    private static String typeNote(TypePlan plan, Composition composition)
    {
        if (!composition.changed)
        {
            return "'type' of " + plan.fqn + " unchanged: its type extension already holds what the call asks for" //$NON-NLS-1$ //$NON-NLS-2$
                + typeDetails(plan.requestedBase, plan.requestedKept);
        }
        StringBuilder note = new StringBuilder(plan.fqn)
            .append(" is adopted, so its type is written to the type extension in its extension block: added (Extended): ") //$NON-NLS-1$
            .append(listOrNothing(composition.added));
        if (!composition.stillExtended.isEmpty())
        {
            note.append("; still Extended: ").append(String.join(", ", composition.stillExtended)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        note.append(typeDetails(plan.requestedBase, plan.requestedKept));
        if (!composition.removed.isEmpty())
        {
            note.append("; earlier additions removed: ").append(String.join(", ", composition.removed)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return note.append(". get_metadata_details shows the defined type's native type, not its type extension") //$NON-NLS-1$
            .toString();
    }

    private static String typeDetails(List<String> requestedBase, List<String> requestedKept)
    {
        String details = ""; //$NON-NLS-1$
        if (!requestedBase.isEmpty())
        {
            details += "; already base types, left as they stand: " + String.join(", ", requestedBase); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!requestedKept.isEmpty())
        {
            details += "; still checked by the extension: " + String.join(", ", requestedKept); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return details;
    }

    private static String listOrNothing(List<String> names)
    {
        return names.isEmpty() ? "nothing" : String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String listOrNone(List<String> names)
    {
        return names.isEmpty() ? "(none)" : String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String quoted(List<String> names)
    {
        return "'" + String.join("', '", names) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String named(String base)
    {
        return base == null || base.isEmpty() ? "" : " '" + base + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** An adopted object and the base configuration it comes from, as a refusal names them. */
    private static String adopted(String fqn, String base)
    {
        return "adopted " + fqn + (base == null || base.isEmpty() ? "" : " (base configuration '" + base + "')"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static String unknownVersionRefusal(String property, String fqn, String base)
    {
        return "'" + property + "' of " + fqn + " was not written: " + fqn + " is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "configuration" + named(base) + ", and the extension project's platform version, which decides what an " //$NON-NLS-1$ //$NON-NLS-2$
            + "extension may change, is not known yet; " + LIST_PROJECTS_READY + "." + NOTHING_CHANGED; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String noBlockRefusal(String property, String fqn, String base)
    {
        return "'" + property + "' of " + fqn + " was not written: " + fqn + " is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "configuration" + named(base) + " but has no extension block of its kind, which EDT creates when it " //$NON-NLS-1$ //$NON-NLS-2$
            + "adopts an object and in which an extension keeps the '" + property + "' it changes; adopt the " //$NON-NLS-1$ //$NON-NLS-2$
            + "object again in EDT." + NOTHING_CHANGED; //$NON-NLS-1$
    }

    /**
     * The refusal of a write whose object is no longer what the routing judged: an edit in EDT while the call
     * waited (the consent gate), thrown inside the write transaction so it rolls back.
     */
    private static String changedRefusal(String property, String fqn, String what)
    {
        return "'" + property + "' of " + fqn + " was not written: " + what + " changed while the call waited; " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "retry the call." + NOTHING_CHANGED; //$NON-NLS-1$
    }

    /**
     * The refusal of a planned write whose base value is not the one its plan was built from - read again
     * right before the write ({@link Routing#baseChangedRefusal}); {@code null} when it is the same. The same
     * means the same types (by name, and whether each is a type set) with the same qualifiers; the order of the
     * types is not compared, as it carries no meaning (see {@link #composeSource}).
     */
    static String baseChanged(BaseRead planned, BaseRead now, String property, String fqn, String base)
    {
        String unreadable = baseRefusal(now, property, fqn, base);
        if (unreadable != null)
        {
            return unreadable;
        }
        if (sameTypes(planned, now) && planned.qualifiers.equals(now.qualifiers))
        {
            return null;
        }
        String what = sameTypes(planned, now) ? "the same types with other qualifiers" //$NON-NLS-1$
            : new LinkedHashSet<>(planned.typeNames).equals(new LinkedHashSet<>(now.typeNames))
                ? "the same type names, one of them now a type set or no longer one" //$NON-NLS-1$
                : listOrNone(planned.typeNames) + " when the call was checked, " + listOrNone(now.typeNames) //$NON-NLS-1$
                    + " now"; //$NON-NLS-1$
        return "'" + property + "' of " + fqn + " was not written: the base configuration" + named(base) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " changed the base value of '" + property + "' while the call waited (" + what + "), and the " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "base value decides what the extension writes; retry the call." + NOTHING_CHANGED; //$NON-NLS-1$
    }

    private static boolean sameTypes(BaseRead a, BaseRead b)
    {
        return typeKeys(a).equals(typeKeys(b));
    }

    private static Set<String> typeKeys(BaseRead read)
    {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < read.typeNames.size(); i++)
        {
            keys.add(read.typeNames.get(i) + (read.typeSets.get(i).booleanValue() ? "|set" : "|type")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return keys;
    }

    /**
     * The refusal of a write that would replace an adopted defined type's type extension while it holds a
     * type - in any state but None - whose name cannot be read (an unresolved reference): the write could not
     * tell whether that type is a base type to keep or an earlier addition to replace, and replacing the whole
     * type extension without it would lose it. {@code null} when every such name can be read.
     */
    private static String unreadableTypeRefusal(TypeDescriptionExtension current, String fqn, String base)
    {
        if (current == null)
        {
            return null;
        }
        List<TypeExtension> types = current.getTypes();
        for (int i = 0; i < types.size(); i++)
        {
            TypeExtension type = types.get(i);
            MdPropertyState state = type.getState();
            if (state != null && state != MdPropertyState.NONE && typeName(type.getType()) == null)
            {
                return "'type' of " + fqn + " was not written: the type extension of " + adopted(fqn, base) //$NON-NLS-1$ //$NON-NLS-2$
                    + " holds a type (typeExtension[" + i + "], " + state.getName() + ") whose name cannot be read - " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "an unresolved reference - and the write replaces the whole type extension, so it would lose " //$NON-NLS-1$
                    + "that type; EDT's type-extension editor does not list such a type either. Restore the object " //$NON-NLS-1$
                    + "it refers to, or remove the item from the extension's .mdo, then retry; get_project_errors on " //$NON-NLS-1$
                    + "the extension may show it." + NOTHING_CHANGED; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * The refusal of a write that would replace an adopted event subscription's own source while it holds a
     * source whose name cannot be read (an unresolved reference): replacing the whole list without it would
     * lose it. {@code null} when every name can be read.
     */
    private static String unreadableSourceRefusal(Object current, String fqn, String base)
    {
        if (!(current instanceof TypeDescription))
        {
            return null;
        }
        List<TypeItem> items = ((TypeDescription)current).getTypes();
        for (int i = 0; i < items.size(); i++)
        {
            if (typeName(items.get(i)) == null)
            {
                return "'source' of " + fqn + " was not written: the own source of " + adopted(fqn, base) //$NON-NLS-1$ //$NON-NLS-2$
                    + " in the extension holds a source (source[" + i + "]) whose name cannot be read - an " //$NON-NLS-1$ //$NON-NLS-2$
                    + "unresolved reference - and the write replaces the whole source, so it would lose that " //$NON-NLS-1$
                    + "source. Restore the object it refers to, or remove the source from the extension's .mdo, then " //$NON-NLS-1$
                    + "retry; get_project_errors on the extension may show it." + NOTHING_CHANGED; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static void requireReadableSource(Object current, String fqn)
    {
        String unreadable = unreadableSourceRefusal(current, fqn, null);
        if (unreadable != null)
        {
            throw Refusals.state(unreadable);
        }
    }

    /**
     * Inside the write transaction, for a change the routing wrote as prepared ({@link Decision.Kind#PLAIN}):
     * refuses - and so rolls back - when EDT's {@code .mdo} writer would now leave it out ({@link #wouldDrop}),
     * because the extension state of the property (or of the second feature the change writes) was set to None
     * after the routing judged it, while the call waited; written anyway, the value would not reach the file
     * while the call reports it written. A property of any other object never refuses.
     *
     * @param target the object written, inside the transaction
     * @param feature the native feature the change writes
     * @param binding a second feature the same change writes, or {@code null}
     * @param fqn the FQN the call addresses, for the message, or {@code null}
     * @throws IllegalStateException when the value would be dropped (rolls back)
     */
    public static void requireStillWritten(EObject target, EStructuralFeature feature, EStructuralFeature binding,
        String fqn)
    {
        EStructuralFeature dropped = wouldDrop(target, feature) ? feature
            : wouldDrop(target, binding) ? binding : null;
        if (dropped == null)
        {
            return;
        }
        String name = fqn != null ? fqn : ((MdObject)target).getName();
        String property = dropped.getName();
        throw Refusals.state("'" + property + "' of " + name + " was not written: " + name + " is adopted, and " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "the extension state of '" + property + "' became None while the call waited - EDT writes a property " //$NON-NLS-1$ //$NON-NLS-2$
            + "of an adopted object to the extension's .mdo only while the extension marks it, so the value would " //$NON-NLS-1$
            + "not reach the file; retry the call, which then says how it can be changed." + NOTHING_CHANGED); //$NON-NLS-1$
    }

    /** A source state as the routing reads it: an unset state is None. */
    private static MdPropertyState effective(MdPropertyState state)
    {
        return state == null ? MdPropertyState.NONE : state;
    }

    private static String serviceRefusal(String property, String fqn, String service, String base)
    {
        return "'" + property + "' of " + fqn + " was not written: " + fqn + " is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "configuration" + named(base) + ", and EDT's " + service + " service, which decides how an extension " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "changes it, is not available; " + LIST_PROJECTS_READY + "." + NOTHING_CHANGED; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String baseRefusal(BaseRead read, String property, String fqn, String base)
    {
        String head = "'" + property + "' of " + fqn + " was not written: "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        switch (read.status)
        {
            case FOUND:
                return null;
            case BASE_NOT_READY:
                return head + "the base configuration" + named(base) + " is not ready yet, and the base value decides " //$NON-NLS-1$ //$NON-NLS-2$
                    + "what the extension adds; retry once list_projects reports both projects ready." + NOTHING_CHANGED; //$NON-NLS-1$
            case NOT_FOUND:
                return head + "EDT does not find the object of adopted " + fqn + " in the base configuration" //$NON-NLS-1$ //$NON-NLS-2$
                    + named(base) + "; check the adoption with get_project_errors on the extension." + NOTHING_CHANGED; //$NON-NLS-1$
            case UNAVAILABLE:
            default:
                return head + "the base value could not be read from the base configuration" + named(base) + " (" //$NON-NLS-1$ //$NON-NLS-2$
                    + read.detail + "); " + LIST_PROJECTS_READY + "." + NOTHING_CHANGED; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The refusal of a type of an adopted object other than a defined type's {@code type} (and an event
     * subscription's {@code source}), worded by what EDT offers for it at the extension's platform version
     * ({@link IMdPropertyTypeProvider#getPropertyTypes}): a multi-state type (8.3.18 and later; a command's
     * parameter type from 8.3.25) lives in the object's type extension, which EDT's type-extension editor
     * changes - unless that editor only checks the types of this owner ({@link #levelOf}); a checkable one
     * (below that) can only be checked against the base; with none an extension cannot change it. When that
     * cannot be told - the version is not known yet, or the service is missing - the refusal claims nothing
     * about the platform.
     */
    private static String otherTypeRefusal(Request request, EStructuralFeature feature, Environment env)
    {
        String base = env.baseProjectName(request.project);
        String property = feature.getName();
        Version version = request.version;
        Collection<MdPropertyType> types = version == null ? null
            : env.propertyTypes(request.target, feature, version);
        String head = "'" + property + "' of " + request.fqn + " was not written: " + request.fqn + " is adopted from the " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "base configuration" + named(base) + ", and "; //$NON-NLS-1$ //$NON-NLS-2$
        String way;
        if (types == null)
        {
            way = "modify_metadata writes the type of an adopted object only for a defined type's 'type' (and " //$NON-NLS-1$
                + "changes an adopted event subscription's 'source'). Change it in the base configuration" //$NON-NLS-1$
                + named(base) + ", or in EDT's editors of the adopted object where EDT lets an extension change it."; //$NON-NLS-1$
        }
        else if (types.contains(MdPropertyType.MULTI_STATE)
            && levelOf(request.target.eClass(), version) == Level.CAN_EXTEND)
        {
            way = "EDT changes an adopted object's type only in its type extension, which modify_metadata writes " //$NON-NLS-1$
                + "for an adopted defined type's 'type' alone (and changes an adopted event subscription's " //$NON-NLS-1$
                + "'source'). Change it in EDT's type-extension editor of the adopted object, or in the base " //$NON-NLS-1$
                + "configuration" + named(base) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (types.contains(MdPropertyType.MULTI_STATE) || types.contains(MdPropertyType.CHECKABLE)
            || types.contains(MdPropertyType.CHECKABLE_OR_NOTIFY))
        {
            way = "an extension for platform " + version + " can only check '" + property + "' against the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "configuration, not change it; change it in the base configuration" + named(base) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            way = "an extension for platform " + version + " cannot change '" + property //$NON-NLS-1$ //$NON-NLS-2$
                + "'; change it in the base configuration" + named(base) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return head + way + NOTHING_CHANGED;
    }

    /**
     * The refusal of a write EDT's {@code .mdo} writer would drop, worded by the extension states EDT offers
     * for the property at the extension's platform version: none (the platform does not let an extension
     * change it), only checkable ones (an extension can only check it), or an extensible one (EDT's editor can
     * mark it Extended).
     */
    private static String droppedRefusal(MdObject target, EStructuralFeature feature, String fqn, IProject project,
        Version version, Environment env)
    {
        String base = env.baseProjectName(project);
        String property = feature.getName();
        String head = "'" + property + "' of " + fqn + " was not written: " + fqn + " is adopted from the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "configuration" + named(base) + ", and EDT writes a property of an adopted object to the extension's " //$NON-NLS-1$ //$NON-NLS-2$
            + ".mdo only when the extension marks it - the extension state of '" + property + "' is None, so EDT " //$NON-NLS-1$ //$NON-NLS-2$
            + "would leave the value out and the file would not change. "; //$NON-NLS-1$
        Collection<MdPropertyType> types = version == null ? null : env.propertyTypes(target, feature, version);
        String way;
        if (types == null)
        {
            way = "Change it in the base configuration" + named(base) //$NON-NLS-1$
                + ", or mark it in EDT's property editor of the adopted object where EDT offers a state for it."; //$NON-NLS-1$
        }
        else if (types.contains(MdPropertyType.EXTENSIBLE) || types.contains(MdPropertyType.CHECKABLE_OR_EXTENSIBLE))
        {
            way = "To change it in the extension, mark '" + property + "' Extended in EDT's property editor of the " //$NON-NLS-1$ //$NON-NLS-2$
                + "adopted object; or change it in the base configuration" + named(base) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (types.contains(MdPropertyType.CHECKABLE) || types.contains(MdPropertyType.CHECKABLE_OR_NOTIFY))
        {
            way = "An extension for platform " + version + " can only check '" + property + "' against the base " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "configuration, not change it; change it in the base configuration" + named(base) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            way = "An extension for platform " + version + " cannot change '" + property //$NON-NLS-1$ //$NON-NLS-2$
                + "'; change it in the base configuration" + named(base) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return head + way + NOTHING_CHANGED;
    }

    // ================================ helpers ================================

    /** The English type name of an item, as EDT names it in the file, or {@code null} when it cannot be read. */
    static String typeName(TypeItem item)
    {
        if (item == null)
        {
            return null;
        }
        try
        {
            String name = McoreUtil.getTypeName(item);
            return name == null || name.isEmpty() ? null : name;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Map<String, MdPropertyState> statesByName(TypeDescriptionExtension extension)
    {
        Map<String, MdPropertyState> states = new LinkedHashMap<>();
        if (extension != null)
        {
            for (TypeExtension type : extension.getTypes())
            {
                String name = typeName(type.getType());
                MdPropertyState state = type.getState();
                if (name != null && state != null && state != MdPropertyState.NONE)
                {
                    states.putIfAbsent(name, state);
                }
            }
        }
        return states;
    }

    private static Map<String, TypeItem> itemsByName(Object value)
    {
        Map<String, TypeItem> items = new LinkedHashMap<>();
        if (value instanceof TypeDescription)
        {
            for (TypeItem item : ((TypeDescription)value).getTypes())
            {
                String name = typeName(item);
                if (name != null)
                {
                    items.putIfAbsent(name, item);
                }
            }
        }
        return items;
    }

    /**
     * The qualifier values of a type description or a type extension, copied out of the model: a value of
     * this class crosses from the base's read transaction into the extension's write, a live base object
     * never does. {@code null} parts are qualifiers the type does not carry.
     */
    static final class Qualifiers
    {
        static final Qualifiers NONE = new Qualifiers(null, null, null, null, null, null, null, null);

        final Integer stringLength;
        final Boolean stringFixed;
        final Integer numberPrecision;
        final Integer numberScale;
        final Boolean numberNonNegative;
        final DateFractions dateFractions;
        final Integer binaryLength;
        final Boolean binaryFixed;

        Qualifiers(Integer stringLength, Boolean stringFixed, Integer numberPrecision, Integer numberScale, // NOSONAR a value of the four qualifier sets
            Boolean numberNonNegative, DateFractions dateFractions, Integer binaryLength, Boolean binaryFixed)
        {
            this.stringLength = stringLength;
            this.stringFixed = stringFixed;
            this.numberPrecision = numberPrecision;
            this.numberScale = numberScale;
            this.numberNonNegative = numberNonNegative;
            this.dateFractions = dateFractions;
            this.binaryLength = binaryLength;
            this.binaryFixed = binaryFixed;
        }

        static Qualifiers of(TypeDescription type)
        {
            return type == null ? NONE
                : of(type.getStringQualifiers(), type.getNumberQualifiers(), type.getDateQualifiers(),
                    type.getBinaryQualifiers());
        }

        static Qualifiers of(TypeDescriptionExtension type)
        {
            return type == null ? NONE
                : of(type.getStringQualifiers(), type.getNumberQualifiers(), type.getDateQualifiers(),
                    type.getBinaryQualifiers());
        }

        private static Qualifiers of(StringQualifiers string, NumberQualifiers number, DateQualifiers date,
            BinaryQualifiers binary)
        {
            return new Qualifiers(string == null ? null : Integer.valueOf(string.getLength()),
                string == null ? null : Boolean.valueOf(string.isFixed()),
                number == null ? null : Integer.valueOf(number.getPrecision()),
                number == null ? null : Integer.valueOf(number.getScale()),
                number == null ? null : Boolean.valueOf(number.isNonNegative()),
                date == null ? null : date.getDateFractions(),
                binary == null ? null : Integer.valueOf(binary.getLength()),
                binary == null ? null : Boolean.valueOf(binary.isFixed()));
        }

        /** Whether these values carry the qualifiers of the kind {@code typeName} names. */
        boolean has(String typeName)
        {
            switch (typeName)
            {
                case STRING:
                    return stringLength != null;
                case NUMBER:
                    return numberPrecision != null;
                case DATE:
                    return dateFractions != null;
                case BINARY:
                    return binaryLength != null;
                default:
                    return false;
            }
        }

        /**
         * Whether these (requested) qualifiers of the kind {@code typeName} names differ from {@code stands}.
         * Qualifiers a request does not carry do not differ: they are the ones the type stands with. A Date
         * without qualifiers is a date and time, as the platform reads it.
         */
        boolean differ(String typeName, Qualifiers stands)
        {
            switch (typeName)
            {
                case STRING:
                    return stringLength != null && !(Objects.equals(stringLength, stands.stringLength)
                        && Objects.equals(stringFixed, stands.stringFixed));
                case NUMBER:
                    return numberPrecision != null && !(Objects.equals(numberPrecision, stands.numberPrecision)
                        && Objects.equals(numberScale, stands.numberScale)
                        && Objects.equals(numberNonNegative, stands.numberNonNegative));
                case DATE:
                    return dateFractions != null && dateFractions != (stands.dateFractions == null
                        ? DateFractions.DATE_TIME : stands.dateFractions);
                case BINARY:
                    return binaryLength != null && !(Objects.equals(binaryLength, stands.binaryLength)
                        && Objects.equals(binaryFixed, stands.binaryFixed));
                default:
                    return false;
            }
        }

        /** How the type of the kind {@code typeName} names stands with these qualifiers, for a message. */
        String spell(String typeName)
        {
            switch (typeName)
            {
                case STRING:
                    return stringLength == null ? STRING
                        : STRING + "(" + stringLength + (Boolean.TRUE.equals(stringFixed) ? ", fixed" : "") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                case NUMBER:
                    return numberPrecision == null ? NUMBER
                        : NUMBER + "(" + numberPrecision + ", " + numberScale //$NON-NLS-1$ //$NON-NLS-2$
                            + (Boolean.TRUE.equals(numberNonNegative) ? ", nonNegative" : "") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                case DATE:
                    return dateFractions == null ? DATE : DATE + "(" + dateFractions.getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                case BINARY:
                    return binaryLength == null ? BINARY
                        : BINARY + "(" + binaryLength + (Boolean.TRUE.equals(binaryFixed) ? ", fixed" : "") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                default:
                    return typeName;
            }
        }

        @Override
        public boolean equals(Object other)
        {
            if (!(other instanceof Qualifiers))
            {
                return false;
            }
            Qualifiers that = (Qualifiers)other;
            return Objects.equals(stringLength, that.stringLength) && Objects.equals(stringFixed, that.stringFixed)
                && Objects.equals(numberPrecision, that.numberPrecision) && Objects.equals(numberScale, that.numberScale)
                && Objects.equals(numberNonNegative, that.numberNonNegative) && dateFractions == that.dateFractions
                && Objects.equals(binaryLength, that.binaryLength) && Objects.equals(binaryFixed, that.binaryFixed);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(stringLength, stringFixed, numberPrecision, numberScale, numberNonNegative,
                dateFractions, binaryLength, binaryFixed);
        }
    }

    // ================================ the platform ================================

    /** What the base configuration holds for an adopted object's property: names and values only. */
    static final class BaseRead
    {
        /** How the read ended. */
        enum Status
        {
            /** The base object was found and read. */
            FOUND,
            /** The base project, or its model, is not ready. */
            BASE_NOT_READY,
            /** EDT does not find the base object of the adopted copy. */
            NOT_FOUND,
            /** A service is missing, or the read failed. */
            UNAVAILABLE
        }

        final Status status;
        final String detail;
        final List<String> typeNames;
        final List<Boolean> typeSets;
        final Qualifiers qualifiers;

        private BaseRead(Status status, String detail, List<String> typeNames, List<Boolean> typeSets,
            Qualifiers qualifiers)
        {
            this.status = status;
            this.detail = detail;
            this.typeNames = Collections.unmodifiableList(new ArrayList<>(typeNames));
            this.typeSets = Collections.unmodifiableList(new ArrayList<>(typeSets));
            this.qualifiers = qualifiers;
        }

        static BaseRead found(List<String> typeNames, List<Boolean> typeSets, Qualifiers qualifiers)
        {
            return new BaseRead(Status.FOUND, null, typeNames, typeSets, qualifiers);
        }

        static BaseRead notReady()
        {
            return new BaseRead(Status.BASE_NOT_READY, null, List.of(), List.of(), Qualifiers.NONE);
        }

        static BaseRead notFound()
        {
            return new BaseRead(Status.NOT_FOUND, null, List.of(), List.of(), Qualifiers.NONE);
        }

        static BaseRead unavailable(String detail)
        {
            return new BaseRead(Status.UNAVAILABLE, detail, List.of(), List.of(), Qualifiers.NONE);
        }
    }

    /** How EDT's type information describes one type of a type feature. */
    static final class TypeInfoView
    {
        final boolean single;
        final boolean strongSingle;

        TypeInfoView(boolean single, boolean strongSingle)
        {
            this.single = single;
            this.strongSingle = strongSingle;
        }
    }

    /** EDT's type information for one type feature: the types its editor lists, and whether it mixes single ones. */
    interface TypeInfos
    {
        /** @return whether single types may be combined with others in this feature */
        boolean mixSingleEnable();

        /**
         * @param typeName the English type name
         * @param typeSet whether the type is a type set
         * @return how EDT describes it, or {@code null} when EDT does not list it
         */
        TypeInfoView info(String typeName, boolean typeSet);
    }

    /**
     * The platform calls the router makes, behind one seam so a test can decide every case against an
     * in-memory model. Package-visible: only the tests, in this package, supply another one.
     */
    interface Environment
    {
        /**
         * @param owner the adopted object
         * @param feature the native feature
         * @param version the runtime version
         * @return the extension states EDT offers for it ({@link IMdPropertyTypeProvider#getPropertyTypes}),
         *     or {@code null} when the service is not available
         */
        Collection<MdPropertyType> propertyTypes(EObject owner, EStructuralFeature feature, Version version);

        /**
         * Reads what the base configuration holds for {@code feature} of the base object of {@code adopted}, in
         * a read transaction on the BASE project's model.
         *
         * @param extensionProject the extension project
         * @param adopted the adopted object
         * @param feature the native feature
         * @return the read; never {@code null}
         */
        BaseRead readBase(IProject extensionProject, MdObject adopted, EStructuralFeature feature);

        /**
         * @param owner the adopted object
         * @param feature its type feature
         * @return EDT's type information for it, or {@code null} when it is not available
         */
        TypeInfos typeInfos(MdObject owner, EStructuralFeature feature);

        /**
         * Builds one event-subscription source by its type name in the extension (an object the extension
         * adopted or owns).
         *
         * @param request the call
         * @param typeName the English type name, e.g. {@code DocumentObject.Invoice}
         * @return the item, or {@code null} when the extension cannot build it
         */
        TypeItem buildSourceItem(Request request, String typeName);

        /** @return EDT's adopted-property access, or {@code null} when it is not available */
        IMdAdoptedPropertyAccess access();

        /**
         * @param extensionProject the extension project
         * @return the name of its base configuration project, or {@code null} when it cannot be told
         */
        String baseProjectName(IProject extensionProject);
    }

    /** The production platform. */
    static final Environment PLATFORM = new Environment()
    {
        @Override
        public Collection<MdPropertyType> propertyTypes(EObject owner, EStructuralFeature feature, Version version)
        {
            IMdPropertyTypeProvider provider = ExtensionServices.propertyTypeProvider();
            if (provider == null)
            {
                return null;
            }
            try
            {
                return provider.getPropertyTypes(owner, feature, version);
            }
            catch (RuntimeException e) // NOSONAR an unanswered question is refused, never guessed
            {
                Activator.logError("Error reading the extension property types of " + feature.getName(), e); //$NON-NLS-1$
                return null;
            }
        }

        @Override
        public BaseRead readBase(IProject extensionProject, MdObject adopted, EStructuralFeature feature)
        {
            IProject baseProject = ExtensionOriginUtils.resolveBaseProject(extensionProject);
            if (baseProject == null)
            {
                return BaseRead.notReady();
            }
            BmModelResolver.Resolution model = BmModelResolver.resolve(baseProject);
            if (!model.isAvailable())
            {
                return BaseRead.notReady();
            }
            IModelObjectAdopter adopter = ExtensionServices.modelObjectAdopter();
            if (adopter == null)
            {
                return BaseRead.unavailable("EDT's IModelObjectAdopter service is not available"); //$NON-NLS-1$
            }
            return AdoptedPropertyRouter.readBase(model.getModel(), adopter, adopted, feature);
        }

        @Override
        public TypeInfos typeInfos(MdObject owner, EStructuralFeature feature)
        {
            if (!(feature instanceof EReference))
            {
                return null;
            }
            try
            {
                TypeDescriptionInfoWithTypeInfo info = TypeProviderService.INSTANCE
                    .getTypeDescriptionInfoWithTypeInfo(owner, owner.eContainer(), (EReference)feature, null);
                if (info == null || info.getTypeInfos().isEmpty())
                {
                    return null;
                }
                return new TypeInfos()
                {
                    @Override
                    public boolean mixSingleEnable()
                    {
                        return info.isMixSingleEnable();
                    }

                    @Override
                    public TypeInfoView info(String typeName, boolean typeSet)
                    {
                        TypeInfo typeInfo = typeName == null ? null : info.getTypeInfo(typeName, typeSet);
                        return typeInfo == null ? null : new TypeInfoView(typeInfo.isSingle(), typeInfo.isStrongSingle());
                    }
                };
            }
            catch (RuntimeException e) // NOSONAR an unanswered question is refused, never guessed
            {
                Activator.logError("Error reading the type information of " + feature.getName(), e); //$NON-NLS-1$
                return null;
            }
        }

        @Override
        public TypeItem buildSourceItem(Request request, String typeName)
        {
            int dot = typeName.indexOf('.');
            JsonObject item = new JsonObject();
            item.addProperty("kind", dot < 0 ? typeName : typeName.substring(0, dot)); //$NON-NLS-1$
            if (dot > 0)
            {
                item.addProperty("ref", typeName.substring(dot + 1)); //$NON-NLS-1$
            }
            JsonArray types = new JsonArray();
            types.add(item);
            JsonObject spec = new JsonObject();
            spec.add("types", types); //$NON-NLS-1$
            try
            {
                MetadataTypeBuilder.Result built = MetadataTypeBuilder.build(spec, request.config, request.scope,
                    request.version, true, MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);
                return built.error == null && built.typeDescription != null
                    && built.typeDescription.getTypes().size() == 1 ? built.typeDescription.getTypes().get(0) : null;
            }
            catch (RuntimeException e) // NOSONAR a source the extension cannot build is refused by name
            {
                // A resolution miss comes back in Result.error; a throw is a platform failure - logged, and
                // the source is still refused by name rather than guessed.
                Activator.logError("Error building the event-subscription source " + typeName //$NON-NLS-1$
                    + " in the extension", e); //$NON-NLS-1$
                return null;
            }
        }

        @Override
        public IMdAdoptedPropertyAccess access()
        {
            return ExtensionServices.adoptedPropertyAccess();
        }

        @Override
        public String baseProjectName(IProject extensionProject)
        {
            IProject base = ExtensionOriginUtils.resolveBaseProject(extensionProject);
            return base != null ? base.getName() : ExtensionOriginUtils.declaredBaseProjectName(extensionProject);
        }
    };

    /**
     * Reads the base value of an adopted object's property in a read transaction on the BASE project's model
     * ({@code baseModel}): EDT's adopter finds the base object, and only its type names, type-set flags and
     * qualifier values leave the transaction. Package-visible: a test pins that the read runs in a read task
     * of that model.
     *
     * @param baseModel the base project's model
     * @param adopter EDT's adopter
     * @param adopted the adopted object
     * @param feature the native feature
     * @return the read; never {@code null}
     */
    static BaseRead readBase(IBmModel baseModel, IModelObjectAdopter adopter, MdObject adopted,
        EStructuralFeature feature)
    {
        try
        {
            return BmTransactions.read(baseModel, "Read the base value of an adopted object", (tx, monitor) -> //$NON-NLS-1$
            {
                EObject source = adopter.getSource(adopted);
                if (source == null)
                {
                    return BaseRead.notFound();
                }
                EObject base = source;
                if (source.eIsProxy())
                {
                    base = (EObject)tx.getObjectByUri(EcoreUtil.getURI(source));
                }
                else if (source instanceof IBmObject)
                {
                    base = (EObject)tx.getObjectById(((IBmObject)source).bmGetId());
                }
                if (base == null)
                {
                    return BaseRead.notFound();
                }
                Object value = base.eGet(feature);
                List<String> names = new ArrayList<>();
                List<Boolean> typeSets = new ArrayList<>();
                if (value instanceof TypeDescription)
                {
                    for (TypeItem item : ((TypeDescription)value).getTypes())
                    {
                        String name = typeName(item);
                        if (name == null)
                        {
                            return BaseRead.unavailable("a base type's name cannot be read"); //$NON-NLS-1$
                        }
                        names.add(name);
                        typeSets.add(Boolean.valueOf(item instanceof TypeSet));
                    }
                }
                return BaseRead.found(names, typeSets,
                    Qualifiers.of(value instanceof TypeDescription ? (TypeDescription)value : null));
            });
        }
        catch (RuntimeException e)
        {
            Activator.logError("Error reading the base value of an adopted object", e); //$NON-NLS-1$
            return BaseRead.unavailable(describe(e));
        }
    }

    /**
     * What failed, for a refusal: the message of {@code e}, else the first message down its cause chain, else
     * the class of {@code e}. The walk is bounded - a cause chain can loop - so a refusal never hangs on one.
     *
     * @param e the failure
     * @return a non-empty description
     */
    static String describe(Throwable e)
    {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause())
        {
            String message = t.getMessage();
            if (message != null && !message.isBlank())
            {
                return message;
            }
        }
        return e.getClass().getSimpleName();
    }
}
