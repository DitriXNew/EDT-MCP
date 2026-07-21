/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.xdto.model.Enumeration;
import com._1c.g5.v8.dt.xdto.model.Import;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.xdto.model.ValueType;

/**
 * Shared READER that renders an XDTO Package's CONTENT (issue #183 stream 1) - the model behind an
 * {@code XDTOPackage}'s lazy {@code Package} content - to a Markdown section: the package's namespace /
 * form-qualification attributes, its ObjectTypes (each with its own properties), its package-global
 * properties, its value types (with their nested enumerations), and its imported-package dependencies.
 * Mirrors {@link DcsStructureReader}: a pure typed-API render (the xdto.model package is NOT Tycho
 * access-restricted, unlike the DCS "default settings" subtree or the form content model, so no EMF
 * reflection is needed here), wired into {@code GetMetadataDetailsTool} the same way.
 *
 * <p>Pure aside from reading the supplied {@link Package}, which the caller must still hold inside its
 * BM transaction when {@link #render} runs (the package is a transient {@code @ExternalProperty} whose
 * containing resource is only valid inside that boundary).</p>
 */
public final class XdtoStructureReader
{
    /** Shared Markdown table column header: a member's programmatic name. */
    private static final String COLUMN_NAME = "Name"; //$NON-NLS-1$

    /** Shared Markdown table column header: an XDTO type reference. */
    private static final String COLUMN_TYPE = "Type"; //$NON-NLS-1$

    private XdtoStructureReader()
    {
        // utility class
    }

    /**
     * Renders the FULL package structure to a Markdown section: attributes, object types (with their
     * properties), package-global properties, value types (with enumerations) and dependencies. Every
     * subsection is skipped when its underlying collection is empty.
     *
     * @param fqn the (normalized) XDTOPackage FQN, for the heading
     * @param pkg the resolved package content (must still be inside the caller's read/rollback
     *            transaction); {@code null} renders a minimal note
     * @return the Markdown section
     */
    public static String render(String fqn, Package pkg)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## XDTO content: ").append(fqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (pkg == null)
        {
            sb.append("_(no package content)_\n"); //$NON-NLS-1$
            return sb.toString();
        }
        renderPackageAttributes(sb, pkg);
        renderObjectTypes(sb, pkg);
        renderPackageProperties(sb, pkg);
        renderValueTypes(sb, pkg);
        renderDependencies(sb, pkg);
        return sb.toString();
    }

    // ==================== Package attributes ====================

    private static void renderPackageAttributes(StringBuilder sb, Package pkg)
    {
        sb.append("**Namespace (nsUri):** ").append(emptyIfNull(pkg.getNsUri())).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (pkg.isSetElementFormQualified())
        {
            sb.append("**Element form qualified:** ").append(pkg.isElementFormQualified()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (pkg.isSetAttributeFormQualified())
        {
            sb.append("**Attribute form qualified:** ").append(pkg.isAttributeFormQualified()) //$NON-NLS-1$
                .append("\n\n"); //$NON-NLS-1$
        }
    }

    // ==================== Object types ====================

    private static void renderObjectTypes(StringBuilder sb, Package pkg)
    {
        EList<ObjectType> types = pkg.getObjects();
        if (types.isEmpty())
        {
            return;
        }
        sb.append("### Object types\n\n"); //$NON-NLS-1$
        for (ObjectType type : types)
        {
            renderObjectType(sb, type);
        }
    }

    private static void renderObjectType(StringBuilder sb, ObjectType type)
    {
        sb.append("#### ").append(nameOrUnnamed(type.getName())); //$NON-NLS-1$
        String flags = objectTypeFlagsSummary(type);
        if (!flags.isEmpty())
        {
            sb.append(" (").append(flags).append(')'); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
        QName baseType = type.getBaseType();
        if (baseType != null)
        {
            sb.append("**Base type:** ").append(describeQName(baseType)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        renderPropertyTable(sb, type.getProperties());
    }

    /** Comma-joined list of the object type's SET (non-default) boolean flags. */
    private static String objectTypeFlagsSummary(ObjectType type)
    {
        List<String> flags = new java.util.ArrayList<>();
        if (type.isSetOpen())
        {
            flags.add("open=" + type.isOpen()); //$NON-NLS-1$
        }
        if (type.isSetAbstract())
        {
            flags.add("abstract=" + type.isAbstract()); //$NON-NLS-1$
        }
        if (type.isSetMixed())
        {
            flags.add("mixed=" + type.isMixed()); //$NON-NLS-1$
        }
        if (type.isSetOrdered())
        {
            flags.add("ordered=" + type.isOrdered()); //$NON-NLS-1$
        }
        if (type.isSetSequenced())
        {
            flags.add("sequenced=" + type.isSequenced()); //$NON-NLS-1$
        }
        return String.join(", ", flags); //$NON-NLS-1$
    }

    // ==================== Properties (shared: object-owned and package-global) ====================

    private static void renderPackageProperties(StringBuilder sb, Package pkg)
    {
        EList<Property> properties = pkg.getProperties();
        if (properties.isEmpty())
        {
            return;
        }
        sb.append("### Package-global properties\n\n"); //$NON-NLS-1$
        renderPropertyTable(sb, properties);
    }

    private static void renderPropertyTable(StringBuilder sb, EList<Property> properties)
    {
        if (properties.isEmpty())
        {
            return;
        }
        sb.append(MarkdownUtils.tableHeader(COLUMN_NAME, COLUMN_TYPE, "Bounds", "Nillable", "Ref")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Property property : properties)
        {
            sb.append(MarkdownUtils.tableRow(nameOrUnnamed(property.getName()),
                describeQName(property.getType()), boundsSummary(property), nillableSummary(property),
                describeQName(property.getRef())));
        }
        sb.append('\n');
    }

    /** {@code "[lower..upper]"} when either bound is explicitly SET, else {@code ""} (platform defaults). */
    private static String boundsSummary(Property property)
    {
        if (!property.isSetLowerBound() && !property.isSetUpperBound())
        {
            return ""; //$NON-NLS-1$
        }
        int lower = property.getLowerBound();
        int upper = property.getUpperBound();
        return "[" + lower + ".." + (upper < 0 ? "*" : Integer.toString(upper)) + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static String nillableSummary(Property property)
    {
        return property.isSetNillable() ? Boolean.toString(property.isNillable()) : ""; //$NON-NLS-1$
    }

    // ==================== Value types / enumerations ====================

    private static void renderValueTypes(StringBuilder sb, Package pkg)
    {
        EList<ValueType> types = pkg.getTypes();
        if (types.isEmpty())
        {
            return;
        }
        sb.append("### Value types\n\n"); //$NON-NLS-1$
        for (ValueType type : types)
        {
            sb.append("- **").append(nameOrUnnamed(type.getName())).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
            QName baseType = type.getBaseType();
            if (baseType != null)
            {
                sb.append(" (base: ").append(describeQName(baseType)).append(')'); //$NON-NLS-1$
            }
            sb.append('\n');
            String enumerations = enumerationsSummary(type);
            if (!enumerations.isEmpty())
            {
                sb.append("  - Enumerations: ").append(enumerations).append('\n'); //$NON-NLS-1$
            }
        }
        sb.append('\n');
    }

    private static String enumerationsSummary(ValueType type)
    {
        EList<Enumeration> enumerations = type.getEnumerations();
        if (enumerations.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        List<String> values = new java.util.ArrayList<>();
        for (Enumeration enumeration : enumerations)
        {
            values.add(emptyIfNull(enumeration.getContent()));
        }
        return String.join(", ", values); //$NON-NLS-1$
    }

    // ==================== Dependencies (imports) ====================

    private static void renderDependencies(StringBuilder sb, Package pkg)
    {
        EList<Import> dependencies = pkg.getDependencies();
        if (dependencies.isEmpty())
        {
            return;
        }
        sb.append("### Dependencies\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Namespace", "Location")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Import dependency : dependencies)
        {
            sb.append(MarkdownUtils.tableRow(emptyIfNull(dependency.getNamespace()),
                emptyIfNull(dependency.getLocation())));
        }
        sb.append('\n');
    }

    // ==================== shared helpers ====================

    /**
     * Describes an mcore {@link QName} as {@code "name"}, or {@code "name [nsUri]"} when the namespace
     * is present (no prefix registry is maintained here, so the raw {@code nsUri} is shown rather than a
     * synthesized prefix).
     *
     * @return the described QName, or {@code ""} when {@code qname} is {@code null}
     */
    private static String describeQName(QName qname)
    {
        if (qname == null)
        {
            return ""; //$NON-NLS-1$
        }
        String name = emptyIfNull(qname.getName());
        String nsUri = qname.getNsUri();
        return nonEmpty(nsUri) ? name + " [" + nsUri + "]" : name; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String nameOrUnnamed(String name)
    {
        return nonEmpty(name) ? name : "(unnamed)"; //$NON-NLS-1$
    }

    private static String emptyIfNull(String value)
    {
        return nonEmpty(value) ? value : ""; //$NON-NLS-1$
    }

    private static boolean nonEmpty(String value)
    {
        return value != null && !value.isEmpty();
    }
}
