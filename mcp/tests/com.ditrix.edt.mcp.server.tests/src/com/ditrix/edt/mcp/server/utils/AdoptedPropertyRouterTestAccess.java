/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.md.extension.IMdAdoptedPropertyAccess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.extension.DefinedTypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.EventSubscriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.SubsystemExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyType;
import com._1c.g5.v8.dt.metadata.mdtype.CatalogTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdRefType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Headless fixtures of objects an extension adopted, and a platform the {@link AdoptedPropertyRouter}
 * consults instead of EDT's services - public, so the tests of other packages (the tool's own) drive the
 * router through the same seam (issues #709 / #710).
 */
public final class AdoptedPropertyRouterTestAccess
{
    private AdoptedPropertyRouterTestAccess()
    {
        // Test support
    }

    // ================================ types ================================

    /** A resolved type item named {@code name}, as a platform or produced type reads by its name. */
    public static Type type(String name)
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }

    /** A type set named {@code name} (AnyRef, a defined type's set, ...). */
    public static TypeSet typeSet(String name)
    {
        TypeSet set = McoreFactory.eINSTANCE.createTypeSet();
        set.setName(name);
        return set;
    }

    /**
     * The reference type of a catalog of the given belonging - a type item contained in that metadata
     * object, as a produced type is.
     */
    public static Type catalogRefType(String catalogName, ObjectBelonging belonging)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(catalogName);
        catalog.setObjectBelonging(belonging);
        CatalogTypes produced = MdTypeFactory.eINSTANCE.createCatalogTypes();
        MdRefType refType = MdTypeFactory.eINSTANCE.createMdRefType();
        Type type = type("CatalogRef." + catalogName); //$NON-NLS-1$
        refType.setType(type);
        produced.setRefType(refType);
        catalog.setProducedTypes(produced);
        return type;
    }

    /** A detached type description of the given items. */
    public static TypeDescription description(TypeItem... items)
    {
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        for (TypeItem item : items)
        {
            description.getTypes().add(item);
        }
        return description;
    }

    /** A String of the given length (not fixed). */
    public static StringQualifiers stringQualifiers(int length)
    {
        StringQualifiers qualifiers = McoreFactory.eINSTANCE.createStringQualifiers();
        qualifiers.setLength(length);
        return qualifiers;
    }

    /** A type extension of (item, state) pairs. */
    public static TypeDescriptionExtension typeExtension(Object... itemStatePairs)
    {
        TypeDescriptionExtension extension = MdClassExtensionFactory.eINSTANCE.createTypeDescriptionExtension();
        for (int i = 0; i < itemStatePairs.length; i += 2)
        {
            TypeExtension type = MdClassExtensionFactory.eINSTANCE.createTypeExtension();
            type.setType((TypeItem)itemStatePairs[i]);
            type.setState((MdPropertyState)itemStatePairs[i + 1]);
            extension.getTypes().add(type);
        }
        return extension;
    }

    // ================================ objects ================================

    /**
     * A defined type an extension adopted at runtime 8.3.18 or later, as EDT's adopter leaves it: an empty
     * native type, and the composition in the extension block's type extension.
     */
    public static DefinedType adoptedDefinedType(String name, TypeDescriptionExtension typeExtension)
    {
        DefinedType definedType = MdClassFactory.eINSTANCE.createDefinedType();
        definedType.setName(name);
        definedType.setObjectBelonging(ObjectBelonging.ADOPTED);
        definedType.setType(McoreFactory.eINSTANCE.createTypeDescription());
        DefinedTypeExtension extension = MdClassExtensionFactory.eINSTANCE.createDefinedTypeExtension();
        extension.setTypeExtension(typeExtension);
        definedType.setExtension(extension);
        return definedType;
    }

    /**
     * An event subscription an extension adopted, with its own source ({@code null}: none, as an adoption leaves
     * it; an earlier extended write leaves the base sources plus its additions there) and the source state of its
     * extension block ({@code null}: not set).
     */
    public static EventSubscription adoptedSubscription(String name, TypeDescription source, MdPropertyState state)
    {
        EventSubscription subscription = MdClassFactory.eINSTANCE.createEventSubscription();
        subscription.setName(name);
        subscription.setObjectBelonging(ObjectBelonging.ADOPTED);
        if (source != null)
        {
            subscription.setSource(source);
        }
        EventSubscriptionExtension extension = MdClassExtensionFactory.eINSTANCE.createEventSubscriptionExtension();
        if (state != null)
        {
            extension.setSource(state);
        }
        subscription.setExtension(extension);
        return subscription;
    }

    /**
     * A subsystem an extension adopted, with the content state of its extension block ({@code null}: not set,
     * which a generated block reads as None) and its content members. EDT's adopter marks an adopted subsystem's
     * content Extended ({@code SubsystemAdopterParticipant}); a test passes None for a state set back after that.
     */
    public static Subsystem adoptedSubsystem(String name, MdPropertyState contentState, MdObject... members)
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        subsystem.setName(name);
        subsystem.setObjectBelonging(ObjectBelonging.ADOPTED);
        SubsystemExtension extension = MdClassExtensionFactory.eINSTANCE.createSubsystemExtension();
        if (contentState != null)
        {
            extension.setContent(contentState);
        }
        subsystem.setExtension(extension);
        for (MdObject member : members)
        {
            subsystem.getContent().add(member);
        }
        return subsystem;
    }

    // ================================ the platform ================================

    /** A router over {@code platform}. */
    public static Function<AdoptedPropertyRouter.Request, AdoptedPropertyRouter.Routing> router(FakePlatform platform)
    {
        return request -> AdoptedPropertyRouter.route(request, platform);
    }

    /**
     * The platform a test sets up: the property types EDT offers, what the base configuration holds, EDT's
     * type information, the sources the extension can build, the adopted-property access and the base
     * project's name. The defaults are EDT's answers at runtime 8.5.1 for the features the router asks
     * about, and a base that holds nothing.
     */
    public static final class FakePlatform implements AdoptedPropertyRouter.Environment
    {
        private final Map<EStructuralFeature, List<MdPropertyType>> propertyTypes = new HashMap<>();
        private boolean propertyTypesAvailable = true;
        private AdoptedPropertyRouter.BaseRead base = AdoptedPropertyRouter.BaseRead.found(List.of(), List.of(),
            AdoptedPropertyRouter.Qualifiers.NONE);
        private final Map<String, AdoptedPropertyRouter.TypeInfoView> infos = new HashMap<>();
        private boolean infosAvailable = true;
        private boolean mixSingle;
        private final Map<String, TypeItem> buildable = new HashMap<>();
        private IMdAdoptedPropertyAccess access = new FakeAccess();
        private String baseProject = "TestConfiguration"; //$NON-NLS-1$
        private int baseReads;

        /** EDT's answers at runtime 8.5.1 for the features the router asks about. */
        public FakePlatform()
        {
            propertyTypes.put(MdClassPackage.Literals.DEFINED_TYPE__TYPE, List.of(MdPropertyType.MULTI_STATE));
            propertyTypes.put(MdClassPackage.Literals.EVENT_SUBSCRIPTION__SOURCE, List.of(MdPropertyType.EXTENSIBLE));
            propertyTypes.put(MdClassPackage.Literals.MD_OBJECT__SYNONYM, List.of(MdPropertyType.EXTENSIBLE));
            propertyTypes.put(MdClassPackage.Literals.CATALOG__OWNERS,
                List.of(MdPropertyType.CHECKABLE, MdPropertyType.CHECKABLE_OR_NOTIFY, MdPropertyType.NORMAL));
            propertyTypes.put(MdClassPackage.Literals.CATALOG__CODE_LENGTH,
                List.of(MdPropertyType.CHECKABLE, MdPropertyType.CHECKABLE_OR_NOTIFY, MdPropertyType.NORMAL));
            // Every other type of an object is multi-state at 8.5.1 (EDT's delegate: MULTI_STATE from 8.3.18, a
            // command's parameter type from 8.3.25).
            for (EStructuralFeature type : List.of(MdClassPackage.Literals.BASIC_FEATURE__TYPE,
                MdClassPackage.Literals.BASIC_COMMAND__COMMAND_PARAMETER_TYPE, MdClassPackage.Literals.CONSTANT__TYPE,
                MdClassPackage.Literals.SESSION_PARAMETER__TYPE,
                MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES__TYPE,
                MdClassPackage.Literals.SEQUENCE_DIMENSION__TYPE, MdClassPackage.Literals.FILTER_CRITERION__TYPE,
                MdClassPackage.Literals.FIELD__TYPE, MdClassPackage.Literals.DIMENSION__TYPE,
                MdClassPackage.Literals.RESOURCE__TYPE, MdClassPackage.Literals.FUNCTION__TYPE))
            {
                propertyTypes.put(type, List.of(MdPropertyType.MULTI_STATE));
            }
        }

        /** EDT's property types of {@code feature}, as the delegate of some runtime version answers them. */
        public FakePlatform propertyTypes(EStructuralFeature feature, MdPropertyType... types)
        {
            propertyTypes.put(feature, List.of(types));
            return this;
        }

        /** The property-type service is missing. */
        public FakePlatform withoutPropertyTypes()
        {
            propertyTypesAvailable = false;
            return this;
        }

        /** The base composition: type names in base order, without qualifiers. */
        public FakePlatform base(String... names)
        {
            return base(AdoptedPropertyRouter.Qualifiers.NONE, names);
        }

        /** The base composition with a String of the given length. */
        public FakePlatform baseWithString(int length, String... names)
        {
            return base(new AdoptedPropertyRouter.Qualifiers(Integer.valueOf(length), Boolean.FALSE, null, null, null,
                null, null, null), names);
        }

        /** The base composition with a BinaryData of the given length. */
        public FakePlatform baseWithBinary(int length, String... names)
        {
            return base(new AdoptedPropertyRouter.Qualifiers(null, null, null, null, null, null, Integer.valueOf(length),
                Boolean.FALSE), names);
        }

        private FakePlatform base(AdoptedPropertyRouter.Qualifiers qualifiers, String... names)
        {
            List<Boolean> typeSets = new ArrayList<>();
            for (int i = 0; i < names.length; i++)
            {
                typeSets.add(Boolean.FALSE);
            }
            base = AdoptedPropertyRouter.BaseRead.found(List.of(names), typeSets, qualifiers);
            return this;
        }

        /** EDT does not find the base object. */
        public FakePlatform baseNotFound()
        {
            base = AdoptedPropertyRouter.BaseRead.notFound();
            return this;
        }

        /** The base read failed for {@code detail} (a missing service, an exception). */
        public FakePlatform baseUnavailable(String detail)
        {
            base = AdoptedPropertyRouter.BaseRead.unavailable(detail);
            return this;
        }

        /** The base project is still loading. */
        public FakePlatform baseNotReady()
        {
            base = AdoptedPropertyRouter.BaseRead.notReady();
            return this;
        }

        /** EDT's type editor lists {@code name}, neither single nor strong single. */
        public FakePlatform offer(String name)
        {
            infos.put(name + "|false", new AdoptedPropertyRouter.TypeInfoView(false, false)); //$NON-NLS-1$
            return this;
        }

        /** EDT's type editor lists the type set {@code name}. */
        public FakePlatform offerTypeSet(String name)
        {
            infos.put(name + "|true", new AdoptedPropertyRouter.TypeInfoView(false, false)); //$NON-NLS-1$
            return this;
        }

        /** EDT's type editor lists {@code name} as a single type. */
        public FakePlatform offerSingle(String name)
        {
            infos.put(name + "|false", new AdoptedPropertyRouter.TypeInfoView(true, false)); //$NON-NLS-1$
            return this;
        }

        /** EDT's type editor lists {@code name} as a strong single type. */
        public FakePlatform offerStrongSingle(String name)
        {
            infos.put(name + "|false", new AdoptedPropertyRouter.TypeInfoView(false, true)); //$NON-NLS-1$
            return this;
        }

        /** The type feature mixes single types. */
        public FakePlatform mixSingle()
        {
            mixSingle = true;
            return this;
        }

        /** EDT's type information is missing. */
        public FakePlatform withoutTypeInfos()
        {
            infosAvailable = false;
            return this;
        }

        /** The extension can build the source {@code typeName} (an object it adopted or owns). */
        public FakePlatform buildable(String typeName, TypeItem item)
        {
            buildable.put(typeName, item);
            return this;
        }

        /** The adopted-property access the router hands to its writes. */
        public FakePlatform access(IMdAdoptedPropertyAccess value)
        {
            access = value;
            return this;
        }

        /** @return how many times the base was read */
        public int baseReads()
        {
            return baseReads;
        }

        @Override
        public Collection<MdPropertyType> propertyTypes(EObject owner, EStructuralFeature feature, Version version)
        {
            if (!propertyTypesAvailable)
            {
                return null;
            }
            return propertyTypes.getOrDefault(feature, List.of());
        }

        @Override
        public AdoptedPropertyRouter.BaseRead readBase(IProject extensionProject, MdObject adopted,
            EStructuralFeature feature)
        {
            baseReads++;
            return base;
        }

        @Override
        public AdoptedPropertyRouter.TypeInfos typeInfos(MdObject owner, EStructuralFeature feature)
        {
            if (!infosAvailable)
            {
                return null;
            }
            return new AdoptedPropertyRouter.TypeInfos()
            {
                @Override
                public boolean mixSingleEnable()
                {
                    return mixSingle;
                }

                @Override
                public AdoptedPropertyRouter.TypeInfoView info(String typeName, boolean typeSet)
                {
                    return infos.get(typeName + "|" + typeSet); //$NON-NLS-1$
                }
            };
        }

        @Override
        public TypeItem buildSourceItem(AdoptedPropertyRouter.Request request, String typeName)
        {
            return buildable.get(typeName);
        }

        @Override
        public IMdAdoptedPropertyAccess access()
        {
            return access;
        }

        @Override
        public String baseProjectName(IProject extensionProject)
        {
            return baseProject;
        }
    }

    /**
     * EDT's adopted-property access as {@code MdAdoptedPropertyAccess} behaves for the calls the router
     * makes: {@code setValue} sets the value and - unless the type is multi-state - the state its type
     * stands for; {@code setPropertyState} sets the state. Every call is recorded.
     */
    public static final class FakeAccess implements IMdAdoptedPropertyAccess
    {
        /** The calls, as {@code setValue <feature> <type>} / {@code setPropertyState <feature> <state>}. */
        public final List<String> calls = new ArrayList<>();

        @Override
        public <T> T getValue(EObject source, EStructuralFeature feature, MdPropertyType propertyType)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPropertyState(EObject source, EStructuralFeature feature, MdPropertyState state)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setValue(EObject target, EStructuralFeature feature, MdPropertyType propertyType, Object newValue)
        {
            calls.add("setValue " + feature.getName() + " " + propertyType.name()); //$NON-NLS-1$ //$NON-NLS-2$
            target.eSet(feature, newValue);
            if (propertyType == MdPropertyType.EXTENSIBLE)
            {
                setState(target, feature, MdPropertyState.EXTENDED);
            }
            else if (propertyType == MdPropertyType.CHECKABLE)
            {
                setState(target, feature, MdPropertyState.CHECKED);
            }
            else if (propertyType == MdPropertyType.NORMAL)
            {
                setState(target, feature, MdPropertyState.NONE);
            }
        }

        @Override
        public void setPropertyState(EObject target, EStructuralFeature feature, MdPropertyState state)
        {
            calls.add("setPropertyState " + feature.getName() + " " + state.name()); //$NON-NLS-1$ //$NON-NLS-2$
            setState(target, feature, state);
        }

        @Override
        public void acceptWithExtension(EObject target, Consumer<ObjectExtension> action)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> applyToExtension(EObject target, Function<ObjectExtension, T> action)
        {
            throw new UnsupportedOperationException();
        }

        private static void setState(EObject target, EStructuralFeature feature, MdPropertyState state)
        {
            ObjectExtension extension = ((MdObject)target).getExtension();
            extension.eSet(extension.eClass().getEStructuralFeature(feature.getName()), state);
        }
    }
}
