/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.FilterCriterion;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.SessionParameter;
import com._1c.g5.v8.dt.metadata.mdclass.extension.DefinedTypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.EventSubscriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

/**
 * Issue #955: the type-valued properties of a metadata object - an event subscription's {@code source}, the
 * {@code type} of a constant / defined type / session parameter / common attribute / filter criterion / chart
 * of characteristic types, a common command's {@code commandParameterType} - are single CONTAINED
 * {@code TypeDescription}s. The reflective dump skipped every containment reference and the collection
 * formatters render only many-valued ones, so neither view of get_metadata_details showed any of them.
 * <p>
 * Every case runs through the public {@link UniversalMetadataFormatter#format} with in-memory, non-proxy
 * objects; {@code McoreUtil.getTypeName} returns an in-memory {@link Type}'s name verbatim.
 */
public class UniversalMetadataFormatterTypePropertiesTest
{
    private final UniversalMetadataFormatter f = UniversalMetadataFormatter.getInstance();

    private static final String EN = "en"; //$NON-NLS-1$

    private static final String NOT_STORED = "- (not stored in the extension: state None, the base " //$NON-NLS-1$
        + "configuration's value applies)"; //$NON-NLS-1$

    private static final String TYPE_EXTENSION_NOTE = " (the extension's type extension: the base " //$NON-NLS-1$
        + "configuration's types apply, an Extended type is added by the extension)"; //$NON-NLS-1$

    private static Type newType(String name)
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }

    private static TypeDescription typesOf(String... names)
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        for (String name : names)
        {
            td.getTypes().add(newType(name));
        }
        return td;
    }

    private static EventSubscription subscription(String... sources)
    {
        EventSubscription s = MdClassFactory.eINSTANCE.createEventSubscription();
        s.setName("OnWriteRegisters"); //$NON-NLS-1$
        s.setEvent("OnWrite"); //$NON-NLS-1$
        s.setHandler("CommonModule.Handlers.OnWrite"); //$NON-NLS-1$
        if (sources.length > 0)
        {
            s.setSource(typesOf(sources));
        }
        return s;
    }

    private static String row(String property, String value)
    {
        return "| " + property + " | " + value + " |\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private void assertRowInBothViews(MdObject object, String property, String value)
    {
        String basic = f.format(object, false, EN);
        String full = f.format(object, true, EN);
        assertTrue("the default view must carry " + row(property, value) + "got:\n" + basic, //$NON-NLS-1$ //$NON-NLS-2$
            basic.contains(row(property, value)));
        assertTrue("the full view must carry " + row(property, value) + "got:\n" + full, //$NON-NLS-1$ //$NON-NLS-2$
            full.contains(row(property, value)));
    }

    // ==================== the reporter's case ====================

    @Test
    public void testEventSubscriptionSourcesRenderInBothViews()
    {
        EventSubscription s = subscription("InformationRegisterRecordSet.A", "InformationRegisterRecordSet.B"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRowInBothViews(s, "Source", "InformationRegisterRecordSet.A, InformationRegisterRecordSet.B"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheFullViewKeepsTheSourceAmongTheOtherProperties()
    {
        // The row sits in the All Properties table with Event and Handler, not in a section of its own.
        String full = f.format(subscription("CatalogObject.Goods"), true, EN); //$NON-NLS-1$
        int table = full.indexOf("### All Properties"); //$NON-NLS-1$
        int source = full.indexOf(row("Source", "CatalogObject.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        int handler = full.indexOf(row("Handler", "CommonModule.Handlers.OnWrite")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(full, table >= 0 && source > table && handler > table);
        assertFalse("no section between the source and the handler:\n" + full, //$NON-NLS-1$
            full.substring(Math.min(source, handler), Math.max(source, handler)).contains("###")); //$NON-NLS-1$
    }

    @Test
    public void testAnEmptySourceIsShownAsADash()
    {
        // A subscription without a source says so: a missing row would read as "not supported".
        assertRowInBothViews(subscription(), "Source", "-"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the other type-valued properties: the same cause ====================

    @Test
    public void testEveryTopObjectTypeRendersInBothViews()
    {
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        constant.setName("Rate"); //$NON-NLS-1$
        constant.setType(typesOf("Number")); //$NON-NLS-1$
        assertRowInBothViews(constant, "Type", "Number"); //$NON-NLS-1$ //$NON-NLS-2$

        DefinedType definedType = MdClassFactory.eINSTANCE.createDefinedType();
        definedType.setName("Party"); //$NON-NLS-1$
        definedType.setType(typesOf("CatalogRef.Partners", "CatalogRef.Companies")); //$NON-NLS-1$ //$NON-NLS-2$
        assertRowInBothViews(definedType, "Type", "CatalogRef.Partners, CatalogRef.Companies"); //$NON-NLS-1$ //$NON-NLS-2$

        SessionParameter parameter = MdClassFactory.eINSTANCE.createSessionParameter();
        parameter.setName("CurrentUser"); //$NON-NLS-1$
        parameter.setType(typesOf("CatalogRef.Users")); //$NON-NLS-1$
        assertRowInBothViews(parameter, "Type", "CatalogRef.Users"); //$NON-NLS-1$ //$NON-NLS-2$

        CommonAttribute attribute = MdClassFactory.eINSTANCE.createCommonAttribute();
        attribute.setName("Area"); //$NON-NLS-1$
        attribute.setType(typesOf("Boolean")); //$NON-NLS-1$
        assertRowInBothViews(attribute, "Type", "Boolean"); //$NON-NLS-1$ //$NON-NLS-2$

        FilterCriterion criterion = MdClassFactory.eINSTANCE.createFilterCriterion();
        criterion.setName("ByOwner"); //$NON-NLS-1$
        criterion.setType(typesOf("CatalogRef.Owners")); //$NON-NLS-1$
        assertRowInBothViews(criterion, "Type", "CatalogRef.Owners"); //$NON-NLS-1$ //$NON-NLS-2$

        ChartOfCharacteristicTypes chart = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        chart.setName("Properties"); //$NON-NLS-1$
        chart.setType(typesOf("Boolean", "Date")); //$NON-NLS-1$ //$NON-NLS-2$
        assertRowInBothViews(chart, "Type", "Boolean, Date(DateTime)"); //$NON-NLS-1$ //$NON-NLS-2$

        CommonCommand command = MdClassFactory.eINSTANCE.createCommonCommand();
        command.setName("Print"); //$NON-NLS-1$
        command.setCommandParameterType(typesOf("DocumentRef.Sale")); //$NON-NLS-1$
        assertRowInBothViews(command, "Command Parameter Type", "DocumentRef.Sale"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheTypeRendersWithItsQualifiersLikeAnAttributeType()
    {
        // One shared rendering: the qualifiers read as in an attribute table (formatType).
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        constant.setName("Code"); //$NON-NLS-1$
        TypeDescription type = typesOf("String"); //$NON-NLS-1$
        var string = McoreFactory.eINSTANCE.createStringQualifiers();
        string.setLength(25);
        type.setStringQualifiers(string);
        constant.setType(type);
        assertRowInBothViews(constant, "Type", f.formatType(type)); //$NON-NLS-1$
        assertEquals("String(25, Variable)", f.formatType(type)); //$NON-NLS-1$
    }

    @Test
    public void testTheTypeFeaturesAreExactlyTheContainedSingleTypeDescriptions()
    {
        // Pins what the predicate accepts across the whole metamodel, so an object kind the platform adds
        // with a contained type is picked up, and a many-valued containment never turns into a row.
        Set<String> top = new TreeSet<>();
        for (EClassifier classifier : MdClassPackage.eINSTANCE.getEClassifiers())
        {
            if (classifier instanceof EClass && !((EClass)classifier).isAbstract()
                && MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf((EClass)classifier))
            {
                for (EStructuralFeature feature : ((EClass)classifier).getEAllStructuralFeatures())
                {
                    if (AbstractMetadataFormatter.isTypeDescriptionFeature(feature))
                    {
                        top.add(classifier.getName() + "." + feature.getName()); //$NON-NLS-1$
                    }
                }
            }
        }
        for (String expected : new String[] { "EventSubscription.source", "Constant.type", "DefinedType.type", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "SessionParameter.type", "CommonAttribute.type", "FilterCriterion.type", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ChartOfCharacteristicTypes.type", "CommonCommand.commandParameterType", "CatalogAttribute.type" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(expected + " must be a type-valued property, got " + top, top.contains(expected)); //$NON-NLS-1$
        }
        List<String> wrong = new ArrayList<>();
        for (String entry : top)
        {
            String feature = entry.substring(entry.indexOf('.') + 1);
            if (!feature.equals("type") && !feature.equals("source") && !feature.equals("commandParameterType")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                wrong.add(entry);
            }
        }
        assertTrue("only type-valued properties are accepted: " + wrong, wrong.isEmpty()); //$NON-NLS-1$
        assertFalse(AbstractMetadataFormatter.isTypeDescriptionFeature(MdClassPackage.Literals.CATALOG__ATTRIBUTES));
        assertFalse(AbstractMetadataFormatter.isTypeDescriptionFeature(MdClassPackage.Literals.EVENT_SUBSCRIPTION__EVENT));
    }

    @Test
    public void testACatalogGainsNoTypeRow()
    {
        // A catalog has no type of its own: its attributes' types stay in the attributes table.
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        var attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Weight"); //$NON-NLS-1$
        attribute.setType(typesOf("Number")); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);
        for (boolean full : new boolean[] { false, true })
        {
            String md = f.format(catalog, full, EN);
            assertFalse(md, md.contains("\n| Type | ")); //$NON-NLS-1$
            assertTrue(md, md.contains("| Weight |")); //$NON-NLS-1$
        }
    }

    // ==================== an object an extension adopted ====================

    private static EventSubscription adoptedSubscription(MdPropertyState state, String... sources)
    {
        EventSubscription s = subscription(sources);
        s.setObjectBelonging(ObjectBelonging.ADOPTED);
        EventSubscriptionExtension block = MdClassExtensionFactory.eINSTANCE.createEventSubscriptionExtension();
        if (state != null)
        {
            block.setSource(state);
        }
        s.setExtension(block);
        return s;
    }

    @Test
    public void testAnAdoptedSourceTheExtensionDoesNotStoreSaysTheBaseApplies()
    {
        // A fresh adoption: the state is unset and the model's own source empty.
        assertRowInBothViews(adoptedSubscription(null), "Source", NOT_STORED); //$NON-NLS-1$
        // State None with a source left in the model (an earlier write returned the state to None): EDT does
        // not write that value, so it is not shown as if the extension held it.
        assertRowInBothViews(adoptedSubscription(MdPropertyState.NONE, "CatalogObject.Goods"), "Source", //$NON-NLS-1$ //$NON-NLS-2$
            NOT_STORED);
    }

    @Test
    public void testAnExtendedAdoptedSourceNamesItsState()
    {
        assertRowInBothViews(adoptedSubscription(MdPropertyState.EXTENDED, "CatalogObject.Goods", //$NON-NLS-1$
            "CatalogObject.ExtOnly"), "Source", //$NON-NLS-1$ //$NON-NLS-2$
            "CatalogObject.Goods, CatalogObject.ExtOnly (extension state Extended)"); //$NON-NLS-1$
    }

    @Test
    public void testTheExtensionsOwnSubscriptionKeepsThePlainValue()
    {
        // The reporter's case: a subscription the extension owns (NATIVE) is stored like any other object.
        EventSubscription s = subscription("InformationRegisterRecordSet.A", "InformationRegisterRecordSet.B"); //$NON-NLS-1$ //$NON-NLS-2$
        s.setObjectBelonging(ObjectBelonging.NATIVE);
        // Even a block with a state on it does not make an owned object's value an adopted one.
        EventSubscriptionExtension block = MdClassExtensionFactory.eINSTANCE.createEventSubscriptionExtension();
        block.setSource(MdPropertyState.EXTENDED);
        s.setExtension(block);
        assertRowInBothViews(s, "Source", "InformationRegisterRecordSet.A, InformationRegisterRecordSet.B"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnAdoptedTypeShowsItsTypeExtension()
    {
        // From 8.3.18 the adopter leaves the native type empty and keeps the composition in the block's type
        // extension, each type with its state; a native type left in the model is not what the cell shows.
        DefinedType definedType = MdClassFactory.eINSTANCE.createDefinedType();
        definedType.setName("Party"); //$NON-NLS-1$
        definedType.setType(typesOf("String")); //$NON-NLS-1$
        definedType.setObjectBelonging(ObjectBelonging.ADOPTED);
        DefinedTypeExtension block = MdClassExtensionFactory.eINSTANCE.createDefinedTypeExtension();
        TypeDescriptionExtension typeExtension = MdClassExtensionFactory.eINSTANCE.createTypeDescriptionExtension();
        typeExtension.getTypes().add(typeExtension("CatalogRef.Partners", MdPropertyState.CHECKED)); //$NON-NLS-1$
        typeExtension.getTypes().add(typeExtension("CatalogRef.ExtOnly", MdPropertyState.EXTENDED)); //$NON-NLS-1$
        typeExtension.getTypes().add(typeExtension("Number", null)); //$NON-NLS-1$
        block.setTypeExtension(typeExtension);
        definedType.setExtension(block);
        assertRowInBothViews(definedType, "Type", //$NON-NLS-1$
            "CatalogRef.Partners (Checked), CatalogRef.ExtOnly (Extended), Number (None)" + TYPE_EXTENSION_NOTE); //$NON-NLS-1$
    }

    @Test
    public void testAnAdoptedTypeWithoutATypeExtensionFollowsTheBase()
    {
        DefinedType definedType = MdClassFactory.eINSTANCE.createDefinedType();
        definedType.setName("Party"); //$NON-NLS-1$
        definedType.setObjectBelonging(ObjectBelonging.ADOPTED);
        definedType.setExtension(MdClassExtensionFactory.eINSTANCE.createDefinedTypeExtension());
        assertRowInBothViews(definedType, "Type", NOT_STORED); //$NON-NLS-1$
    }

    private static TypeExtension typeExtension(String name, MdPropertyState state)
    {
        TypeExtension item = MdClassExtensionFactory.eINSTANCE.createTypeExtension();
        item.setType(newType(name));
        if (state != null)
        {
            item.setState(state);
        }
        return item;
    }
}
