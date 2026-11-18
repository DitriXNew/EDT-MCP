/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.BASIC_FEATURE_TYPE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.BaseRead;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.BaseValue;
import com.ditrix.edt.mcp.server.utils.AdoptedPropertyRouter.Qualifiers;

/** Compares saved extension controls with current base values inside the respective BM read boundaries. */
public final class ControlledMetadataProperties
{
    public record Row(String property, String controlled, String base, Status status, boolean refreshable) { }

    public enum Status { MATCH, MISMATCH, UNCHECKED, UNAVAILABLE }

    private record TypeControl(String text, BaseRead checked, boolean multiState, boolean nativeChecked,
        boolean refreshable) { }

    private ControlledMetadataProperties()
    {
    }

    public static Row compareType(IProject project, MdObject owner)
    {
        TypeControl control = typeControl(owner, ProjectContext.platformVersion(project));
        BaseValue<BaseRead> base = AdoptedPropertyRouter.readBaseValue(project, owner,
            source -> AdoptedPropertyRouter.snapshotType((TypeDescription)source.eGet(BASIC_FEATURE_TYPE)));
        return compareType(control, base);
    }

    public static List<Row> differences(IProject project, MdObject owner, Version version)
    {
        Map<EAttribute, Object> scalarControls = new LinkedHashMap<>();
        List<Row> unavailable = new ArrayList<>();
        for (EAttribute feature : checkedAttributes(owner))
        {
            var types = version == null ? null : AdoptedPropertyRouter.PLATFORM.propertyTypes(owner, feature, version);
            if (types == null)
            {
                unavailable.add(new Row(feature.getName(), "-", "EDT property rules are unavailable; check list_projects",
                    Status.UNAVAILABLE, false));
            }
            else if (types.contains(MdPropertyType.CHECKABLE))
            {
                scalarControls.put(feature, owner.eGet(feature));
            }
        }
        TypeControl control = owner instanceof BasicFeature ? typeControl(owner, version) : null;
        if (scalarControls.isEmpty() && control == null)
        {
            return unavailable;
        }
        BaseValue<List<Row>> read = AdoptedPropertyRouter.readBaseValue(project, owner, source ->
        {
            List<Row> rows = new ArrayList<>();
            if (control != null)
            {
                BaseRead baseType = AdoptedPropertyRouter.snapshotType((TypeDescription)source.eGet(BASIC_FEATURE_TYPE));
                Row type = compareType(control, new BaseValue<>(BaseRead.Status.FOUND, null, baseType));
                if (type.status() == Status.MISMATCH || type.status() == Status.UNAVAILABLE)
                {
                    rows.add(type);
                }
            }
            rows.addAll(attributeDifferences(scalarControls, source));
            return rows;
        });
        if (read.status() == BaseRead.Status.FOUND)
        {
            unavailable.addAll(read.value());
        }
        else
        {
            unavailable.add(new Row(control != null ? "type" : "(checked properties)", "-", read.statusText(),
                Status.UNAVAILABLE, false));
        }
        return unavailable;
    }

    static List<Row> attributeDifferences(Map<EAttribute, Object> controls, EObject source)
    {
        List<Row> rows = new ArrayList<>();
        controls.forEach((feature, controlled) ->
        {
            Object base = source.eGet(feature);
            if (!Objects.equals(controlled, base))
            {
                rows.add(new Row(feature.getName(), attributeText(feature, controlled),
                    attributeText(feature, base), Status.MISMATCH, false));
            }
        });
        return rows;
    }

    private static List<EAttribute> checkedAttributes(MdObject owner)
    {
        return owner.eClass().getEAllAttributes().stream()
            .filter(feature -> !feature.isMany() && feature != MdClassPackage.Literals.MD_OBJECT__EXTENDED_CONFIGURATION_OBJECT)
            .filter(feature -> AdoptedPropertyRouter.stateOf(owner, feature) == MdPropertyState.CHECKED)
            .toList();
    }

    private static String attributeText(EAttribute feature, Object value)
    {
        return value == null ? "-" : EcoreUtil.convertToString(feature.getEAttributeType(), value);
    }

    static Row compareType(MdObject owner, BaseValue<BaseRead> base, Version version)
    {
        return compareType(typeControl(owner, version), base);
    }

    private static TypeControl typeControl(MdObject owner, Version version)
    {
        if (owner.getExtension() == null)
        {
            return new TypeControl("-", BaseRead.unavailable("the adoption's extension block is missing"), false, false, false);
        }
        TypeDescriptionExtension extension = AdoptedTypeCell.typeExtension(owner.getExtension(), BASIC_FEATURE_TYPE);
        boolean multiState = extension != null;
        TypeDescription nativeType = (TypeDescription)owner.eGet(BASIC_FEATURE_TYPE);
        String extensionText = AdoptedTypeCell.typeExtensionText(owner.getExtension(), BASIC_FEATURE_TYPE);
        boolean nativeChecked = !multiState && AdoptedPropertyRouter.stateOf(owner, BASIC_FEATURE_TYPE) == MdPropertyState.CHECKED;
        String controlled = extensionText != null ? extensionText : nativeChecked
            ? MetadataPropertyIntrospector.formatTypeDescription(nativeType) + " (Checked)" : AdoptedTypeCell.NOT_STORED;
        boolean refreshable = version != null && (multiState || nativeChecked
            || version.isGreaterThan(Version.V8_3_17)
                && AdoptedPropertyRouter.levelOf(owner.eClass(), version) != AdoptedPropertyRouter.Level.ONE_STATUS_ONLY);
        return new TypeControl(controlled,
            nativeChecked ? AdoptedPropertyRouter.snapshotType(nativeType) : checkedTypes(extension),
            multiState, nativeChecked, refreshable);
    }

    private static Row compareType(TypeControl control, BaseValue<BaseRead> base)
    {
        if (base.status() != BaseRead.Status.FOUND || base.value().status != BaseRead.Status.FOUND)
        {
            String detail = base.status() == BaseRead.Status.FOUND ? base.value().detail : base.statusText();
            return new Row("type", control.text(), detail, Status.UNAVAILABLE, false);
        }

        BaseRead source = base.value();
        String baseText = render(source);
        BaseRead stored = control.checked();
        if (stored.status != BaseRead.Status.FOUND)
        {
            return new Row("type", control.text() + " (" + stored.detail + ")", baseText, Status.UNAVAILABLE, false);
        }
        Status status = !control.nativeChecked() && stored.typeNames.isEmpty() ? Status.UNCHECKED
            : matches(stored, source, control.multiState()) ? Status.MATCH : Status.MISMATCH;
        return new Row("type", control.text(), baseText, status, control.refreshable());
    }

    private static BaseRead checkedTypes(TypeDescriptionExtension extension)
    {
        List<String> names = new ArrayList<>();
        List<Boolean> sets = new ArrayList<>();
        if (extension != null)
        {
            for (TypeExtension type : extension.getTypes())
            {
                if (type.getState() != MdPropertyState.CHECKED)
                {
                    continue;
                }
                String name = AdoptedPropertyRouter.typeName(type.getType());
                if (name == null)
                {
                    return BaseRead.unavailable("a checked type cannot be resolved");
                }
                names.add(name);
                sets.add(type.getType() instanceof TypeSet);
            }
        }
        return BaseRead.found(names, sets, Qualifiers.of(extension));
    }

    private static boolean matches(BaseRead stored, BaseRead source, boolean multiState)
    {
        Set<String> expected = AdoptedPropertyRouter.typeKeys(stored);
        Set<String> actual = AdoptedPropertyRouter.typeKeys(source);
        if (AdoptedPropertyRouter.isCompositeReference(source)
            && (expected.equals(Set.of(AdoptedPropertyRouter.typeKey("AnyRef", true)))
                || expected.equals(Set.of(AdoptedPropertyRouter.typeKey("Arbitrary", true)))))
        {
            return true;
        }
        return (multiState ? actual.containsAll(expected) : actual.equals(expected))
            && stored.typeNames.stream().allMatch(name ->
                MetadataPropertyIntrospector.formatQualifiedType(name, stored.qualifiers)
                    .equals(MetadataPropertyIntrospector.formatQualifiedType(name, source.qualifiers)));
    }

    static String render(BaseRead type)
    {
        return type.typeNames.isEmpty() ? "(empty)" : String.join(", ", type.typeNames.stream()
            .map(name -> MetadataPropertyIntrospector.formatQualifiedType(name, type.qualifiers)).toList());
    }
}
