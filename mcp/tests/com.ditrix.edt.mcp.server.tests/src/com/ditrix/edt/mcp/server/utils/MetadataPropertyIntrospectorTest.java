/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.BinaryQualifiers;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.ValueKind;

/**
 * Tests {@link MetadataPropertyIntrospector} against real mdclass objects created via the EMF
 * factory (no live project needed - this is pure metamodel reflection).
 */
public class MetadataPropertyIntrospectorTest
{
    private static CatalogAttribute newAttribute()
    {
        return MdClassFactory.eINSTANCE.createCatalogAttribute();
    }

    private static Catalog newCatalog()
    {
        return MdClassFactory.eINSTANCE.createCatalog();
    }

    // ============ A reference renders short, but it does not IDENTIFY itself short ============

    /**
     * A subsystem's {@code content} is declared {@code refers MdObject[]} - it holds objects of any
     * type - so its two sides can hold a Catalog and a Document that happen to share a name. Both
     * render the bare {@code Foo}, which is right for a reader and wrong for anything that compares
     * the rendered text: it makes two different objects one value.
     */
    @Test
    public void testABroadReferenceIsIdentifiedByTypeAsWellAsName()
    {
        PropertyInfo content =
            MetadataPropertyIntrospector.find(subsystemHolding(catalogNamed("Foo")), "content"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(content);
        assertEquals("the reader still sees the short name", "Foo", content.currentValue); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("but the value identifies itself by type", "Catalog.Foo", //$NON-NLS-1$ //$NON-NLS-2$
            content.valueIdentity);
    }

    /** The other half of the same statement: a same-named target of another type is another value. */
    @Test
    public void testTwoTargetTypesSharingANameAreTwoIdentities()
    {
        PropertyInfo fromCatalog =
            MetadataPropertyIntrospector.find(subsystemHolding(catalogNamed("Foo")), "content"); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyInfo fromDocument =
            MetadataPropertyIntrospector.find(subsystemHolding(documentNamed("Foo")), "content"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the two render alike - that is the whole point", fromCatalog.currentValue, //$NON-NLS-1$
            fromDocument.currentValue);
        assertEquals("Document.Foo", fromDocument.valueIdentity); //$NON-NLS-1$
        assertFalse("...and must not identify alike: " + fromCatalog.valueIdentity, //$NON-NLS-1$
            fromCatalog.valueIdentity.equals(fromDocument.valueIdentity));
    }

    /** Every element of a MANY reference is qualified, not just the first. */
    @Test
    public void testEveryElementOfAReferenceListIsQualified()
    {
        Subsystem subsystem = subsystemHolding(catalogNamed("Foo")); //$NON-NLS-1$
        subsystem.getContent().add(documentNamed("Bar")); //$NON-NLS-1$

        PropertyInfo content = MetadataPropertyIntrospector.find(subsystem, "content"); //$NON-NLS-1$

        assertEquals("Foo, Bar", content.currentValue); //$NON-NLS-1$
        assertEquals("Catalog.Foo, Document.Bar", content.valueIdentity); //$NON-NLS-1$
    }

    /**
     * The control that keeps the new field from becoming a second, divergent rendering: a kind
     * whose text already says which value it is identifies itself by that same text.
     */
    @Test
    public void testAKindWithNothingToQualifyIdentifiesItselfByItsRenderedText()
    {
        Catalog catalog = newCatalog();
        catalog.setComment("a plain comment"); //$NON-NLS-1$

        PropertyInfo comment = MetadataPropertyIntrospector.find(catalog, "comment"); //$NON-NLS-1$

        assertEquals("a plain comment", comment.currentValue); //$NON-NLS-1$
        assertEquals("a plain comment", comment.valueIdentity); //$NON-NLS-1$
    }

    private static Subsystem subsystemHolding(MdObject target)
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        subsystem.setName("Sales"); //$NON-NLS-1$
        subsystem.getContent().add(target);
        return subsystem;
    }

    private static Catalog catalogNamed(String name)
    {
        Catalog catalog = newCatalog();
        catalog.setName(name);
        return catalog;
    }

    private static Document documentNamed(String name)
    {
        Document document = MdClassFactory.eINSTANCE.createDocument();
        document.setName(name);
        return document;
    }

    // ====== A target this class ADMITS is read, not silently dropped as an empty property ======

    /**
     * {@code classifyReference} admits a reference declared against the mcore {@code CommandGroup}
     * interface, and the platform's {@code StandardCommandGroup} is one of the things that interface
     * covers. The render path answered every non-{@code MdObject} target with ABSENT, so a command
     * that IS in a standard group reported the same {@code (null, null, not-failed)} as a command in
     * no group at all - a property this server admits it can address, and then claims nobody set.
     */
    @Test
    public void testAStandardCommandGroupIsReadRatherThanReportedAbsent()
    {
        PropertyInfo group = MetadataPropertyIntrospector.find(
            commandInStandardGroup("FormCommandBarImportant", null), "group"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the reader sees the platform's own name for the group", //$NON-NLS-1$
            "FormCommandBarImportant", group.currentValue); //$NON-NLS-1$
        assertEquals("StandardCommandGroup.FormCommandBarImportant", group.valueIdentity); //$NON-NLS-1$
        assertFalse("a group that was read is not a group that failed to read", group.readFailed); //$NON-NLS-1$
    }

    /** Two different standard groups are two values, which is what the old ABSENT hid. */
    @Test
    public void testTwoStandardCommandGroupsAreTwoIdentities()
    {
        PropertyInfo inCommandBar = MetadataPropertyIntrospector.find(
            commandInStandardGroup("FormCommandBarImportant", null), "group"); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyInfo inNavigation = MetadataPropertyIntrospector.find(
            commandInStandardGroup("NavigationPanelSeeAlso", null), "group"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("a standard group must identify itself at all", inCommandBar.valueIdentity); //$NON-NLS-1$
        assertFalse("two standard groups must not identify alike: " + inCommandBar.valueIdentity, //$NON-NLS-1$
            inCommandBar.valueIdentity.equals(inNavigation.valueIdentity));
    }

    /**
     * A group carrying no name still carries the {@code category} its own class declares, and the
     * category is what the rest of this server already prints for such a group. Two groups told
     * apart by nothing else are still two values.
     */
    @Test
    public void testAnUnnamedStandardCommandGroupIsIdentifiedByItsCategory()
    {
        PropertyInfo onCommandBar = MetadataPropertyIntrospector.find(
            commandInStandardGroup(null, CommandGroupCategory.FORM_COMMAND_BAR), "group"); //$NON-NLS-1$
        PropertyInfo onNavigation = MetadataPropertyIntrospector.find(
            commandInStandardGroup(null, CommandGroupCategory.NAVIGATION_PANEL), "group"); //$NON-NLS-1$

        assertEquals("a nameless group still shows what the platform knows about it", //$NON-NLS-1$
            "FormCommandBar", onCommandBar.currentValue); //$NON-NLS-1$
        assertEquals("StandardCommandGroup.FormCommandBar", onCommandBar.valueIdentity); //$NON-NLS-1$
        assertEquals("...and two categories are two values", //$NON-NLS-1$
            "StandardCommandGroup.NavigationPanel", onNavigation.valueIdentity); //$NON-NLS-1$
    }

    /**
     * The control that stops the fix from becoming a blanket: a command with NO group must still
     * report an empty property, or every reference anybody left unset becomes a value.
     */
    @Test
    public void testACommandWithNoGroupIsStillAnEmptyProperty()
    {
        PropertyInfo group = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createDataProcessorCommand(), "group"); //$NON-NLS-1$

        assertNull("an unset group renders no value", group.currentValue); //$NON-NLS-1$
        assertNull("...and identifies nothing", group.valueIdentity); //$NON-NLS-1$
        assertFalse("...and nothing failed", group.readFailed); //$NON-NLS-1$
    }

    /**
     * The other control: the metadata {@code CommandGroup} - the admitted target that IS an
     * {@code MdObject} - must keep the shape it already had, so the new branch is an addition
     * rather than a reroute.
     */
    @Test
    public void testAMetadataCommandGroupKeepsItsNameAndTypeIdentity()
    {
        CommandGroup group = MdClassFactory.eINSTANCE.createCommandGroup();
        group.setName("Sales"); //$NON-NLS-1$
        DataProcessorCommand command = MdClassFactory.eINSTANCE.createDataProcessorCommand();
        command.setGroup(group);

        PropertyInfo info = MetadataPropertyIntrospector.find(command, "group"); //$NON-NLS-1$

        assertEquals("Sales", info.currentValue); //$NON-NLS-1$
        assertEquals("CommandGroup.Sales", info.valueIdentity); //$NON-NLS-1$
    }

    // ====== A target with no NAME is not a reference with no TARGET ======

    /**
     * ABSENT was chosen on the rendered text alone, so a reference pointing at an object whose name
     * is not set threw away the identity it had already built and came back indistinguishable from
     * a reference pointing at nothing - which a comparison then reports as agreement.
     */
    @Test
    public void testAPresentButUnnamedTargetKeepsATypeIdentity()
    {
        PropertyInfo parent = MetadataPropertyIntrospector.find(
            subsystemUnder(MdClassFactory.eINSTANCE.createSubsystem()), "parentSubsystem"); //$NON-NLS-1$

        assertEquals("the type is what is left to identify it by", "Subsystem", //$NON-NLS-1$ //$NON-NLS-2$
            parent.valueIdentity);
        assertFalse("it was read, so nothing failed", parent.readFailed); //$NON-NLS-1$
    }

    /**
     * The cell is a NAME, and there is no name - so it stays empty rather than inventing one. The
     * identity above, not the cell, is what keeps this apart from an unset reference.
     */
    @Test
    public void testAPresentButUnnamedTargetStillPrintsAnEmptyCell()
    {
        PropertyInfo parent = MetadataPropertyIntrospector.find(
            subsystemUnder(MdClassFactory.eINSTANCE.createSubsystem()), "parentSubsystem"); //$NON-NLS-1$

        assertNull("a nameless target must not be given a printed name", parent.currentValue); //$NON-NLS-1$
    }

    /** The control: pointing at nothing still identifies nothing, so the two remain distinguishable. */
    @Test
    public void testAnUnsetReferenceIdentifiesNothingAtAll()
    {
        PropertyInfo parent = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createSubsystem(), "parentSubsystem"); //$NON-NLS-1$

        assertNull(parent.currentValue);
        assertNull("an unset reference must not acquire an identity", parent.valueIdentity); //$NON-NLS-1$
    }

    /**
     * A command placed in a platform standard group.
     *
     * @param name the group's name, or {@code null} to leave it unset
     * @param category the group's category, or {@code null} to leave the model's default
     * @return the command
     */
    private static DataProcessorCommand commandInStandardGroup(String name,
        CommandGroupCategory category)
    {
        StandardCommandGroup group = McoreFactory.eINSTANCE.createStandardCommandGroup();
        if (name != null)
        {
            group.setName(name);
        }
        if (category != null)
        {
            group.setCategory(category);
        }
        DataProcessorCommand command = MdClassFactory.eINSTANCE.createDataProcessorCommand();
        command.setGroup(group);
        return command;
    }

    /**
     * A subsystem whose {@code parentSubsystem} points at {@code parent} - a single-valued
     * reference to a named object, so it says nothing about the many-valued {@code content} above.
     *
     * @param parent the object the reference points at
     * @return the subsystem
     */
    private static Subsystem subsystemUnder(Subsystem parent)
    {
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        subsystem.setName("Sales"); //$NON-NLS-1$
        subsystem.setParentSubsystem(parent);
        return subsystem;
    }

    // ====== A 1C type is its qualifiers as well as its names ======

    /**
     * The type cell prints the type NAMES and nothing else, so a {@code String} bounded at 10
     * characters and one bounded at 100 are the same six letters on the page. That is right for a
     * reader and wrong for anything that compares the cells: EDT stores the two as different
     * database columns, and a report built on the rendered text called them one value.
     * <p>
     * The pair is not hypothetical. One ordinary catalog {@code .mdo} carries an attribute typed
     * {@code String} with an empty {@code <stringQualifiers/>} beside another with
     * {@code <length>10</length>}.
     */
    @Test
    public void testTwoStringLengthsThatPrintAlikeAreTwoIdentities()
    {
        String shortString = typeIdentity(stringTypeBounded(10, false));
        String longString = typeIdentity(stringTypeBounded(100, false));

        assertFalse("two string lengths must not be one value: " + shortString, //$NON-NLS-1$
            shortString.equals(longString));
    }

    /** The other half of the same statement, on the qualifier the length does not cover. */
    @Test
    public void testAFixedStringIsNotTheSameValueAsAVariableOne()
    {
        String variable = typeIdentity(stringTypeBounded(10, false));
        String fixed = typeIdentity(stringTypeBounded(10, true));

        assertFalse("fixed and variable are two column types: " + variable, //$NON-NLS-1$
            variable.equals(fixed));
    }

    /**
     * A number differs the same way, and by the qualifier a reader is likeliest to care about: the
     * scale is what says whether the column holds whole roubles or kopecks.
     */
    @Test
    public void testTwoNumberScalesThatPrintAlikeAreTwoIdentities()
    {
        String whole = typeIdentity(numberType(10, 0, false));
        String fractional = typeIdentity(numberType(10, 2, false));

        assertFalse("two scales must not be one value: " + whole, whole.equals(fractional)); //$NON-NLS-1$
    }

    /** ...and by its sign, which is a constraint on the stored values rather than on their width. */
    @Test
    public void testANonNegativeNumberIsNotTheSameValueAsASignedOne()
    {
        String signed = typeIdentity(numberType(10, 2, false));
        String nonNegative = typeIdentity(numberType(10, 2, true));

        assertFalse("the sign is part of the type: " + signed, signed.equals(nonNegative)); //$NON-NLS-1$
    }

    /** A date that stores only the date is not a date that stores the time as well. */
    @Test
    public void testTwoDateFractionsThatPrintAlikeAreTwoIdentities()
    {
        String dateOnly = typeIdentity(dateType(DateFractions.DATE));
        String timeOnly = typeIdentity(dateType(DateFractions.TIME));

        assertFalse("two date fractions must not be one value: " + dateOnly, //$NON-NLS-1$
            dateOnly.equals(timeOnly));
    }

    /**
     * The fourth qualifier group {@code TypeDescription} declares. It is not in the note this work
     * started from - it was read out of {@code model/Mcore.xcore} - and it is carried for the same
     * reason as the other three.
     */
    @Test
    public void testTwoBinaryLengthsThatPrintAlikeAreTwoIdentities()
    {
        TypeDescription small = typeDescriptionOf("ValueStorage"); //$NON-NLS-1$
        small.setBinaryQualifiers(binaryQualifiers(64, false));
        TypeDescription large = typeDescriptionOf("ValueStorage"); //$NON-NLS-1$
        large.setBinaryQualifiers(binaryQualifiers(128, false));

        assertFalse("two binary lengths must not be one value: " + typeIdentity(small), //$NON-NLS-1$
            typeIdentity(small).equals(typeIdentity(large)));
    }

    /**
     * The many-valued case: a composite type holds several alternatives AND a qualifier group per
     * primitive among them. Walking only the names loses every one of those groups at once.
     */
    @Test
    public void testACompositeTypeCarriesEveryQualifierGroupItHolds()
    {
        TypeDescription whole = typeDescriptionOf("String", "Number", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        whole.setStringQualifiers(stringQualifiers(10, false));
        whole.setNumberQualifiers(numberQualifiers(10, 0, false));
        whole.setDateQualifiers(dateQualifiers(DateFractions.DATE));
        TypeDescription fractional = typeDescriptionOf("String", "Number", "Date"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        fractional.setStringQualifiers(stringQualifiers(10, false));
        fractional.setNumberQualifiers(numberQualifiers(10, 2, false));
        fractional.setDateQualifiers(dateQualifiers(DateFractions.DATE));

        assertEquals("the cell is the alternatives, unchanged", "String, Number, Date", //$NON-NLS-1$ //$NON-NLS-2$
            typeCell(whole));
        assertFalse("a group behind the first one still separates the two: " + typeIdentity(whole), //$NON-NLS-1$
            typeIdentity(whole).equals(typeIdentity(fractional)));
    }

    /**
     * The composite case again, with the difference moved OFF the first group the identity emits.
     * <p>
     * The test above cannot see this: its two sides differ in the number scale, and the number
     * group is the first one written - so an identity that stopped after ONE group still separated
     * them, and the measured mutation "only the first group reaches the identity" survived. Here
     * the two sides share their string bound and differ only in the date fractions, which is
     * written last.
     */
    @Test
    public void testACompositeTypeSeparatesOnAGroupThatIsNotTheFirstWritten()
    {
        TypeDescription dateOnly = typeDescriptionOf("String", "Date"); //$NON-NLS-1$ //$NON-NLS-2$
        dateOnly.setStringQualifiers(stringQualifiers(10, false));
        dateOnly.setDateQualifiers(dateQualifiers(DateFractions.DATE));
        TypeDescription timeOnly = typeDescriptionOf("String", "Date"); //$NON-NLS-1$ //$NON-NLS-2$
        timeOnly.setStringQualifiers(stringQualifiers(10, false));
        timeOnly.setDateQualifiers(dateQualifiers(DateFractions.TIME));

        assertFalse("the LAST group written still separates the two: " + typeIdentity(dateOnly), //$NON-NLS-1$
            typeIdentity(dateOnly).equals(typeIdentity(timeOnly)));
    }

    /**
     * The display is NOT what changed. Spelling the qualifiers into the cell would widen every type
     * row in every report to fix something nobody reads out of the table.
     */
    @Test
    public void testAQualifiedTypeStillPrintsJustTheTypeName()
    {
        assertEquals("the reader still sees the short type", "String", //$NON-NLS-1$ //$NON-NLS-2$
            typeCell(stringTypeBounded(10, false)));
    }

    /**
     * The control against the opposite error: two types qualified identically are ONE value, and
     * qualifying the comparison must not make every type row differ.
     */
    @Test
    public void testTwoIdenticallyQualifiedTypesStillAgree()
    {
        assertEquals("one type described twice is one value", //$NON-NLS-1$
            typeIdentity(stringTypeBounded(10, true)), typeIdentity(stringTypeBounded(10, true)));
    }

    /**
     * The second control, and the one that keeps the fix from being worse than the defect. EDT
     * writes an EMPTY {@code <stringQualifiers/>} element for an unbounded string, so one side can
     * hold a defaulted qualifier object where the other holds none at all - and both mean the same
     * unbounded type. An identity that spelled the group out unconditionally would report that as a
     * difference on ordinary configurations.
     */
    @Test
    public void testADefaultedQualifierGroupIsTheSameValueAsNoneAtAll()
    {
        TypeDescription defaulted = typeDescriptionOf("String"); //$NON-NLS-1$
        defaulted.setStringQualifiers(stringQualifiers(0, false));

        assertEquals("an empty <stringQualifiers/> is not a difference from none", //$NON-NLS-1$
            typeIdentity(typeDescriptionOf("String")), typeIdentity(defaulted)); //$NON-NLS-1$
    }

    /** The same control on the date group, whose default is the fractions literal rather than a zero. */
    @Test
    public void testADefaultedDateGroupIsTheSameValueAsNoneAtAll()
    {
        assertEquals("DateTime is the model default, so it says nothing", //$NON-NLS-1$
            typeIdentity(typeDescriptionOf("Date")), //$NON-NLS-1$
            typeIdentity(dateType(DateFractions.DATE_TIME)));
    }

    /**
     * @param typeNames the alternatives the description names, in order
     * @return a detached {@code TypeDescription} naming them
     */
    private static TypeDescription typeDescriptionOf(String... typeNames)
    {
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        for (String typeName : typeNames)
        {
            Type type = McoreFactory.eINSTANCE.createType();
            type.setName(typeName);
            description.getTypes().add(type);
        }
        return description;
    }

    private static StringQualifiers stringQualifiers(int length, boolean fixed)
    {
        StringQualifiers qualifiers = McoreFactory.eINSTANCE.createStringQualifiers();
        qualifiers.setLength(length);
        qualifiers.setFixed(fixed);
        return qualifiers;
    }

    private static NumberQualifiers numberQualifiers(int precision, int scale, boolean nonNegative)
    {
        NumberQualifiers qualifiers = McoreFactory.eINSTANCE.createNumberQualifiers();
        qualifiers.setPrecision(precision);
        qualifiers.setScale(scale);
        qualifiers.setNonNegative(nonNegative);
        return qualifiers;
    }

    private static DateQualifiers dateQualifiers(DateFractions fractions)
    {
        DateQualifiers qualifiers = McoreFactory.eINSTANCE.createDateQualifiers();
        qualifiers.setDateFractions(fractions);
        return qualifiers;
    }

    private static BinaryQualifiers binaryQualifiers(int length, boolean fixed)
    {
        BinaryQualifiers qualifiers = McoreFactory.eINSTANCE.createBinaryQualifiers();
        qualifiers.setLength(length);
        qualifiers.setFixed(fixed);
        return qualifiers;
    }

    private static TypeDescription stringTypeBounded(int length, boolean fixed)
    {
        TypeDescription description = typeDescriptionOf("String"); //$NON-NLS-1$
        description.setStringQualifiers(stringQualifiers(length, fixed));
        return description;
    }

    private static TypeDescription numberType(int precision, int scale, boolean nonNegative)
    {
        TypeDescription description = typeDescriptionOf("Number"); //$NON-NLS-1$
        description.setNumberQualifiers(numberQualifiers(precision, scale, nonNegative));
        return description;
    }

    private static TypeDescription dateType(DateFractions fractions)
    {
        TypeDescription description = typeDescriptionOf("Date"); //$NON-NLS-1$
        description.setDateQualifiers(dateQualifiers(fractions));
        return description;
    }

    /** What a comparison sees of an attribute typed {@code type}. */
    private static String typeIdentity(TypeDescription type)
    {
        return typeProperty(type).valueIdentity;
    }

    /** What a reader sees of the same attribute. */
    private static String typeCell(TypeDescription type)
    {
        return typeProperty(type).currentValue;
    }

    private static PropertyInfo typeProperty(TypeDescription type)
    {
        CatalogAttribute attribute = newAttribute();
        attribute.setType(type);
        PropertyInfo info = MetadataPropertyIntrospector.find(attribute, "type"); //$NON-NLS-1$
        assertNotNull("the type property must be introspected", info); //$NON-NLS-1$
        return info;
    }

    // ==================== A failed read is not an empty value ====================

    /**
     * The read of one property is guarded so that a single dangling proxy cannot abort the whole
     * object. The guard used to answer {@code null} - the same answer as "this property is not
     * set" - so a failure to read arrived at every consumer as a fact about the model.
     */
    @Test
    public void testAFailedReadIsReportedAsFailedRatherThanEmpty()
    {
        PropertyInfo comment = MetadataPropertyIntrospector.find(explodingOn("comment"), "comment"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("the failure must not remove the property from the list", comment); //$NON-NLS-1$
        assertTrue("a property whose read threw must say so", comment.readFailed); //$NON-NLS-1$
        assertNull("and it carries no value, because none was read", comment.currentValue); //$NON-NLS-1$
    }

    /** The control: a property nobody set is empty, and that is NOT a failure. */
    @Test
    public void testAnUnsetPropertyIsEmptyAndNotAFailure()
    {
        PropertyInfo comment = MetadataPropertyIntrospector.find(explodingOn("name"), "comment"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(comment);
        assertNull("nobody set it, so there is no value", comment.currentValue); //$NON-NLS-1$
        assertFalse("but an unset property must not be reported as unreadable", comment.readFailed); //$NON-NLS-1$
    }

    /** One unreadable property must not make the others unreadable, nor stop the walk. */
    @Test
    public void testOnlyTheUnreadablePropertyIsMarked()
    {
        List<PropertyInfo> all = MetadataPropertyIntrospector.introspect(explodingOn("comment")); //$NON-NLS-1$

        int failed = 0;
        for (PropertyInfo info : all)
        {
            if (info.readFailed)
            {
                failed++;
            }
        }
        assertEquals("exactly the one feature whose read threw is marked", 1, failed); //$NON-NLS-1$
        assertTrue("and the rest of the object is still introspected", all.size() > 1); //$NON-NLS-1$
    }

    /**
     * An object with two plain string properties whose {@code eGet} throws for ONE of them - the
     * shape a dangling proxy takes when the resolver behind it is not available.
     *
     * @param failing the feature name whose read must throw
     * @return the object
     */
    private static EObject explodingOn(String failing)
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("explodinglike"); //$NON-NLS-1$
        pkg.setNsPrefix("explodinglike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/explodinglike"); //$NON-NLS-1$
        EClass holder = f.createEClass();
        holder.setName("ExplodingHolder"); //$NON-NLS-1$
        addString(f, holder, "name"); //$NON-NLS-1$
        addString(f, holder, "comment"); //$NON-NLS-1$
        pkg.getEClassifiers().add(holder);
        return new ExplodingObject(holder, failing);
    }

    /** A dynamic EObject that refuses to yield ONE named feature. */
    private static final class ExplodingObject
        extends org.eclipse.emf.ecore.impl.DynamicEObjectImpl
    {
        private final String failing;

        ExplodingObject(EClass eClass, String failing)
        {
            super(eClass);
            this.failing = failing;
        }

        @Override
        public Object eGet(EStructuralFeature feature)
        {
            if (failing.equals(feature.getName()))
            {
                throw new IllegalStateException("the value behind this feature cannot be resolved"); //$NON-NLS-1$
            }
            return super.eGet(feature);
        }
    }

    @Test
    public void testNameAndCommentAreAssignableStrings()
    {
        CatalogAttribute attr = newAttribute();
        PropertyInfo name = MetadataPropertyIntrospector.find(attr, "name"); //$NON-NLS-1$
        assertNotNull("name must be assignable", name); //$NON-NLS-1$
        assertTrue(name.valueKind == ValueKind.STRING);

        PropertyInfo comment = MetadataPropertyIntrospector.find(attr, "comment"); //$NON-NLS-1$
        assertNotNull("comment must be assignable", comment); //$NON-NLS-1$
        assertTrue(comment.valueKind == ValueKind.STRING);
    }

    @Test
    public void testSynonymIsLocalizedString()
    {
        PropertyInfo synonym = MetadataPropertyIntrospector.find(newAttribute(), "synonym"); //$NON-NLS-1$
        assertNotNull("synonym must be assignable", synonym); //$NON-NLS-1$
        assertTrue("synonym must be the localized-string kind", //$NON-NLS-1$
            synonym.valueKind == ValueKind.LOCALIZED_STRING);
    }

    @Test
    public void testSynonymCurrentValueRendersPerLanguageEntry()
    {
        CatalogAttribute attr = newAttribute();
        attr.getSynonym().put("en", "Weight"); //$NON-NLS-1$ //$NON-NLS-2$
        PropertyInfo synonym = MetadataPropertyIntrospector.find(attr, "synonym"); //$NON-NLS-1$
        assertNotNull(synonym);
        assertTrue("synonym current must render the per-language entry, got: " + synonym.currentValue, //$NON-NLS-1$
            "en=Weight".equals(synonym.currentValue)); //$NON-NLS-1$
    }

    @Test
    public void testEnumCurrentValueSharesAllowedVocabulary()
    {
        // After setting an enum to one of its literals, the rendered current value must be one of
        // the allowedValues (same vocabulary), so a client can compare Current vs Allowed.
        CatalogAttribute attr = newAttribute();
        PropertyInfo anyEnum = null;
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(attr))
        {
            if (info.valueKind == ValueKind.ENUM && !info.allowedValues.isEmpty())
            {
                anyEnum = info;
                break;
            }
        }
        assertNotNull(anyEnum);
        org.eclipse.emf.ecore.EEnumLiteral lit =
            MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature, anyEnum.allowedValues.get(0));
        assertNotNull(lit);
        attr.eSet(anyEnum.feature, lit.getInstance());
        PropertyInfo reread = MetadataPropertyIntrospector.find(attr, anyEnum.name);
        assertNotNull(reread.currentValue);
        assertTrue("enum current must be one of the allowed literal names", //$NON-NLS-1$
            reread.allowedValues.contains(reread.currentValue));
    }

    @Test
    public void testFindFeatureMatchesFindWithoutRenderingCurrentValue()
    {
        // findFeature is the per-property validation lookup: same feature / kind / allowedValues as
        // find(), but the current value is never rendered (validation never reads it, and find()'s
        // full introspect() renders the current value of EVERY assignable feature per lookup).
        CatalogAttribute attr = newAttribute();
        attr.getSynonym().put("en", "Weight"); //$NON-NLS-1$ //$NON-NLS-2$
        List<PropertyInfo> all = MetadataPropertyIntrospector.introspect(attr);
        assertFalse(all.isEmpty());
        for (PropertyInfo full : all)
        {
            PropertyInfo light = MetadataPropertyIntrospector.findFeature(attr, full.name);
            assertNotNull("findFeature must locate " + full.name, light); //$NON-NLS-1$
            assertTrue("feature must match for " + full.name, light.feature == full.feature); //$NON-NLS-1$
            assertTrue("kind must match for " + full.name, light.valueKind == full.valueKind); //$NON-NLS-1$
            assertTrue("allowedValues must match for " + full.name, //$NON-NLS-1$
                light.allowedValues.equals(full.allowedValues));
            assertNull("findFeature must not render a current value", light.currentValue); //$NON-NLS-1$
        }
        assertNull(MetadataPropertyIntrospector.findFeature(attr, "noSuchProperty")); //$NON-NLS-1$
        assertNull(MetadataPropertyIntrospector.findFeature(null, "name")); //$NON-NLS-1$
        assertNull(MetadataPropertyIntrospector.findFeature(attr, null));
    }

    @Test
    public void testAssignableNamesAgreeWithIntrospect()
    {
        // assignableNames uses a names-only iteration (no value rendering); it must list exactly
        // the names the full introspect() yields, in the same model feature order.
        CatalogAttribute attr = newAttribute();
        List<String> names = MetadataPropertyIntrospector.assignableNames(attr);
        List<PropertyInfo> all = MetadataPropertyIntrospector.introspect(attr);
        assertTrue("name count must agree", names.size() == all.size()); //$NON-NLS-1$
        for (int i = 0; i < all.size(); i++)
        {
            assertTrue("name #" + i + " must agree", names.get(i).equals(all.get(i).name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue(MetadataPropertyIntrospector.assignableNames(null).isEmpty());
    }

    @Test
    public void testAttributeTypeIsTypeDescription()
    {
        PropertyInfo type = MetadataPropertyIntrospector.find(newAttribute(), "type"); //$NON-NLS-1$
        assertNotNull("an attribute's type must be assignable", type); //$NON-NLS-1$
        assertTrue("type must be the TypeDescription kind", //$NON-NLS-1$
            type.valueKind == ValueKind.TYPE_DESCRIPTION);
    }

    @Test
    public void testAttributeHasAnEnumPropertyWithAllowedValues()
    {
        // A db-object attribute carries enum flags (indexing / fillChecking / ...). Don't hardcode
        // the exact name; assert that at least one ENUM property is present and exposes its literals.
        boolean foundEnumWithValues = false;
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(newAttribute()))
        {
            if (info.valueKind == ValueKind.ENUM && !info.allowedValues.isEmpty())
            {
                foundEnumWithValues = true;
                break;
            }
        }
        assertTrue("an attribute must expose at least one enum property with allowed values", //$NON-NLS-1$
            foundEnumWithValues);
    }

    @Test
    public void testResolveEnumLiteralIsCaseInsensitiveAndRejectsUnknown()
    {
        CatalogAttribute attr = newAttribute();
        PropertyInfo anyEnum = null;
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(attr))
        {
            if (info.valueKind == ValueKind.ENUM && !info.allowedValues.isEmpty())
            {
                anyEnum = info;
                break;
            }
        }
        assertNotNull("precondition: an enum property exists", anyEnum); //$NON-NLS-1$
        String literal = anyEnum.allowedValues.get(0);
        // exact + lower-case both resolve
        assertNotNull(MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature, literal));
        assertNotNull(MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature,
            literal.toLowerCase()));
        // a bogus value does not resolve
        assertNull(MetadataPropertyIntrospector.resolveEnumLiteral(anyEnum.feature, "NotARealLiteral_zzz")); //$NON-NLS-1$
    }

    @Test
    public void testManyEnumAttributeClassifiesSeparatelyFromScalarEnum()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("enumMultiplicity"); //$NON-NLS-1$
        pkg.setNsPrefix("enumMultiplicity"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/enum-multiplicity"); //$NON-NLS-1$
        EEnum purpose = newEnum(factory, "ApplicationUsePurpose", //$NON-NLS-1$
            "PersonalComputer", "MobileDevice"); //$NON-NLS-1$ //$NON-NLS-2$
        EClass holderClass = factory.createEClass();
        holderClass.setName("PurposeHolder"); //$NON-NLS-1$
        addEnum(factory, holderClass, "singlePurpose", purpose); //$NON-NLS-1$
        addEnum(factory, holderClass, "usePurposes", purpose, true); //$NON-NLS-1$
        pkg.getEClassifiers().add(purpose);
        pkg.getEClassifiers().add(holderClass);
        EObject holder = new org.eclipse.emf.ecore.impl.DynamicEObjectImpl(holderClass);

        PropertyInfo single = MetadataPropertyIntrospector.findFeature(holder, "singlePurpose"); //$NON-NLS-1$
        PropertyInfo many = MetadataPropertyIntrospector.findFeature(holder, "usePurposes"); //$NON-NLS-1$

        assertNotNull(single);
        assertEquals("a single-valued enum must keep the scalar classification", //$NON-NLS-1$
            ValueKind.ENUM, single.valueKind);
        assertNotNull(many);
        assertEquals(ValueKind.MANY_ENUM, many.valueKind);
        assertEquals(java.util.Arrays.asList("PersonalComputer", "MobileDevice"), //$NON-NLS-1$ //$NON-NLS-2$
            many.allowedValues);
    }

    @Test
    public void testContainmentChildrenAreNotAssignable()
    {
        // A Catalog's attributes / tabularSections / forms / commands are child collections created
        // via create_metadata, NOT assignable scalar properties.
        List<String> names = MetadataPropertyIntrospector.assignableNames(newCatalog());
        assertFalse("attributes (containment) must NOT be assignable", names.contains("attributes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("forms (containment) must NOT be assignable", names.contains("forms")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("tabularSections (containment) must NOT be assignable", //$NON-NLS-1$
            names.contains("tabularSections")); //$NON-NLS-1$
        // but the catalog's own scalar/flag properties ARE assignable
        assertTrue("comment must be assignable on a Catalog", names.contains("comment")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullObjectYieldsEmpty()
    {
        assertTrue(MetadataPropertyIntrospector.introspect(null).isEmpty());
        assertNull(MetadataPropertyIntrospector.find(null, "name")); //$NON-NLS-1$
    }

    @Test
    public void testSubsystemContentIsManyReferenceWithTargetType()
    {
        // A Subsystem's `content` is a non-containment list of MdObject references -> MANY_REFERENCE,
        // reporting its (base) target type as the allowed value.
        PropertyInfo content = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createSubsystem(), "content"); //$NON-NLS-1$
        assertNotNull("a subsystem's content must be assignable", content); //$NON-NLS-1$
        assertTrue("content must be a MANY_REFERENCE", //$NON-NLS-1$
            content.valueKind == ValueKind.MANY_REFERENCE);
        assertFalse("a reference must report its allowed target type", //$NON-NLS-1$
            content.allowedValues.isEmpty());
    }

    @Test
    public void testSubsystemParentIsSingleReference()
    {
        PropertyInfo parent = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createSubsystem(), "parentSubsystem"); //$NON-NLS-1$
        assertNotNull("a subsystem's parentSubsystem must be assignable", parent); //$NON-NLS-1$
        assertTrue("parentSubsystem must be a single REFERENCE", //$NON-NLS-1$
            parent.valueKind == ValueKind.REFERENCE);
        assertTrue("parentSubsystem must report its Subsystem target type", //$NON-NLS-1$
            parent.allowedValues.contains("Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testStyleItemValueIsStyleValueKind()
    {
        // A StyleItem's `value` is a single-valued containment ref to an mcore Value (Color / Font).
        // It is assignable as the dedicated STYLE_VALUE kind (the generic containment-ref filter would
        // otherwise drop it), so modify_metadata can set the color / font.
        PropertyInfo value = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createStyleItem(), "value"); //$NON-NLS-1$
        assertNotNull("a StyleItem's value must be assignable", value); //$NON-NLS-1$
        assertTrue("value must be the STYLE_VALUE kind", value.valueKind == ValueKind.STYLE_VALUE); //$NON-NLS-1$
    }

    @Test
    public void testStyleItemColorValueRendersCurrent()
    {
        // After setting a ColorValue, the assignable "Current" must render the color (RGB / Auto),
        // proving the STYLE_VALUE current-render path is wired.
        com._1c.g5.v8.dt.metadata.mdclass.StyleItem item = MdClassFactory.eINSTANCE.createStyleItem();
        com._1c.g5.v8.dt.mcore.ColorValue cv = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createColorValue();
        com._1c.g5.v8.dt.mcore.ColorDef def = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createColorDef();
        def.setRed(255);
        def.setGreen(0);
        def.setBlue(0);
        cv.setValue(def);
        item.setValue(cv);
        PropertyInfo value = MetadataPropertyIntrospector.find(item, "value"); //$NON-NLS-1$
        assertNotNull(value);
        assertNotNull("the current color must render", value.currentValue); //$NON-NLS-1$
        assertTrue("the current must show the RGB color, got: " + value.currentValue, //$NON-NLS-1$
            value.currentValue.contains("RGB(255, 0, 0)")); //$NON-NLS-1$
    }

    @Test
    public void testAccountingRegisterChartOfAccountsIsSingleReference()
    {
        PropertyInfo coa = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createAccountingRegister(), "chartOfAccounts"); //$NON-NLS-1$
        assertNotNull("an AccountingRegister.chartOfAccounts must be assignable", coa); //$NON-NLS-1$
        assertTrue("chartOfAccounts must be a single REFERENCE", coa.valueKind == ValueKind.REFERENCE); //$NON-NLS-1$
        assertTrue("chartOfAccounts must report its ChartOfAccounts target type", //$NON-NLS-1$
            coa.allowedValues.contains("ChartOfAccounts")); //$NON-NLS-1$
    }

    // ---- BasicCommand.group (issue #262) --------------------------------------------------------
    //
    // BasicCommand.getGroup() is declared against com._1c.g5.v8.dt.mcore.CommandGroup - the base
    // interface both the metadata com._1c.g5.v8.dt.metadata.mdclass.CommandGroup (a real top MdObject,
    // FQN-addressable) and the platform's StandardCommandGroup (a built-in group, addressed by an enum
    // category - out of scope here) implement. The generic MdObject-subtype check alone would miss it
    // (the DECLARED target type is the mcore interface, not the mdclass one), so 'group' was previously
    // unclassified (excluded from the assignable set) on every BasicCommand subtype.

    @Test
    public void testDataProcessorCommandGroupIsSingleReference()
    {
        PropertyInfo group = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createDataProcessorCommand(), "group"); //$NON-NLS-1$
        assertNotNull("a DataProcessorCommand's group must be assignable", group); //$NON-NLS-1$
        assertTrue("group must be a single REFERENCE", group.valueKind == ValueKind.REFERENCE); //$NON-NLS-1$
        assertTrue("group must report its CommandGroup target type", //$NON-NLS-1$
            group.allowedValues.contains("CommandGroup")); //$NON-NLS-1$
    }

    @Test
    public void testCommonCommandGroupIsSingleReferenceToo()
    {
        // The fix is on the generic mcore-interface target type, not a one-off for a single
        // BasicCommand subtype - CommonCommand (a different EClass entirely) must classify the same.
        PropertyInfo group = MetadataPropertyIntrospector.find(
            MdClassFactory.eINSTANCE.createCommonCommand(), "group"); //$NON-NLS-1$
        assertNotNull("a CommonCommand's group must be assignable", group); //$NON-NLS-1$
        assertTrue("group must be a single REFERENCE", group.valueKind == ValueKind.REFERENCE); //$NON-NLS-1$
    }

    @Test
    public void testCommandGroupOwnSuppressObjectStaysExcluded()
    {
        // A CommandGroup's own suppressObject is a plain EObject-typed reference (not an MdObject / mcore
        // CommandGroup target) - it must stay excluded, proving the new mcore-interface admission is
        // scoped to the CommandGroup target type and does not open the filter generally.
        List<String> names = MetadataPropertyIntrospector.assignableNames(
            MdClassFactory.eINSTANCE.createCommandGroup());
        assertFalse("suppressObject must stay excluded (not MdObject / CommandGroup typed)", //$NON-NLS-1$
            names.contains("suppressObject")); //$NON-NLS-1$
    }

    // ---- contained mcore value classes (issues #497 / #450) -----------------------------------

    @Test
    public void testAContainedPictureIsThePictureKind()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.PICTURE, true, false);
        PropertyInfo picture = MetadataPropertyIntrospector.findFeature(holder, "flag"); //$NON-NLS-1$

        assertNotNull("a single contained Picture must be assignable", picture); //$NON-NLS-1$
        assertEquals(ValueKind.PICTURE, picture.valueKind);
    }

    @Test
    public void testAContainedQNameIsTheQNameKind()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.QNAME, true, false);
        PropertyInfo qname = MetadataPropertyIntrospector.findFeature(holder, "flag"); //$NON-NLS-1$

        assertNotNull("a single contained QName must be assignable", qname); //$NON-NLS-1$
        assertEquals(ValueKind.QNAME, qname.valueKind);
    }

    @Test
    public void testQNameCurrentValueRendersCompactForm()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.QNAME, true, false);
        QName value = McoreFactory.eINSTANCE.createQName();
        value.setName("string"); //$NON-NLS-1$
        value.setNsUri("http://www.w3.org/2001/XMLSchema"); //$NON-NLS-1$
        holder.eSet(holder.eClass().getEStructuralFeature("flag"), value); //$NON-NLS-1$

        assertEquals("{http://www.w3.org/2001/XMLSchema}string", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(holder, "flag").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testAManyContainedPictureStaysExcludedAsAChildCollection()
    {
        assertNull("a child-collection containment reference must stay unassignable", //$NON-NLS-1$
            MetadataPropertyIntrospector.findFeature(
                newFlagHolder(McorePackage.Literals.PICTURE, true, true), "flag")); //$NON-NLS-1$
    }

    @Test
    public void testASubtypeOfPictureIsStillClassified()
    {
        EClass subtype = EcoreFactory.eINSTANCE.createEClass();
        subtype.setName("SpecializedPicture"); //$NON-NLS-1$
        subtype.getESuperTypes().add(McorePackage.Literals.PICTURE);

        PropertyInfo picture = MetadataPropertyIntrospector.findFeature(
            newFlagHolder(subtype, true, false), "flag"); //$NON-NLS-1$
        assertNotNull("a subtype of Picture must still be assignable", picture); //$NON-NLS-1$
        assertEquals(ValueKind.PICTURE, picture.valueKind);
    }

    @Test
    public void testAManyContainedMcoreValueIsTheMcoreValueListKind()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.VALUE, true, true);
        PropertyInfo values = MetadataPropertyIntrospector.findFeature(holder, "flag"); //$NON-NLS-1$

        assertNotNull("a many containment declared against mcore Value must be assignable", values); //$NON-NLS-1$
        assertEquals(ValueKind.MCORE_VALUE_LIST, values.valueKind);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMcoreValueListCurrentValueRendersAsRoundTrippableJsonArray()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.VALUE, true, true);
        EList<Value> values = (EList<Value>)holder.eGet(
            holder.eClass().getEStructuralFeature("flag")); //$NON-NLS-1$

        XDTOPackage xdtoPackage = MdClassFactory.eINSTANCE.createXDTOPackage();
        xdtoPackage.setName("Orders"); //$NON-NLS-1$
        ReferenceValue reference = McoreFactory.eINSTANCE.createReferenceValue();
        reference.setValue(xdtoPackage);
        values.add(reference);

        StringValue namespace = McoreFactory.eINSTANCE.createStringValue();
        namespace.setValue("http://v8.1c.ru/8.1/data/core"); //$NON-NLS-1$
        values.add(namespace);

        assertEquals("[\"XDTOPackage.Orders\",\"http://v8.1c.ru/8.1/data/core\"]", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(holder, "flag").currentValue); //$NON-NLS-1$
    }

    // ---- a value list that could not be read is not an empty one ---------------------------------
    //
    // eGet has already answered before this kind's renderer runs, so a property nobody set is
    // decided THERE, and an empty list renders as "[]". By the time the renderer gives up there is
    // a non-empty list it could not turn into text - and calling that an absence cost a claim one
    // consumer further out: the comparison renderer gives an absent value an EMPTY identity, so two
    // sides whose entries both failed to resolve carried the same identity and the property was
    // reported as SAME over two lists that may name entirely different packages.

    @Test
    @SuppressWarnings("unchecked")
    public void testAnUnreadableXdtoPackageEntryIsAFailedReadRatherThanAnEmptyProperty()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.VALUE, true, true);
        EList<Value> values = (EList<Value>)holder.eGet(
            holder.eClass().getEStructuralFeature("flag")); //$NON-NLS-1$

        // The shape a dangling XDTO package reference actually takes. EcoreUtil.resolve swallows
        // whatever went wrong and hands the PROXY back, so the entry arrives carrying no name -
        // which is the branch this pins, and the branch a throwing resolution lands in as well.
        XDTOPackage unresolved = MdClassFactory.eINSTANCE.createXDTOPackage();
        ((InternalEObject)unresolved).eSetProxyURI(URI.createURI("unresolved:/XDTOPackage.Orders")); //$NON-NLS-1$
        ReferenceValue reference = McoreFactory.eINSTANCE.createReferenceValue();
        reference.setValue(unresolved);
        values.add(reference);

        PropertyInfo info = MetadataPropertyIntrospector.find(holder, "flag"); //$NON-NLS-1$
        assertNotNull("the failure must not remove the property from the list", info); //$NON-NLS-1$
        assertTrue("a list holding an entry nothing could be read from must say so", //$NON-NLS-1$
            info.readFailed);
    }

    /** The control: a list that WAS read is not marked unreadable. */
    @Test
    @SuppressWarnings("unchecked")
    public void testAReadableMcoreValueListIsNotMarkedUnreadable()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.VALUE, true, true);
        EList<Value> values = (EList<Value>)holder.eGet(
            holder.eClass().getEStructuralFeature("flag")); //$NON-NLS-1$

        XDTOPackage xdtoPackage = MdClassFactory.eINSTANCE.createXDTOPackage();
        xdtoPackage.setName("Orders"); //$NON-NLS-1$
        ReferenceValue reference = McoreFactory.eINSTANCE.createReferenceValue();
        reference.setValue(xdtoPackage);
        values.add(reference);

        assertFalse("a list that rendered is not a list that failed", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(holder, "flag").readFailed); //$NON-NLS-1$
    }

    /**
     * The other control, and the one that keeps the change from overreaching: a list with no
     * entries is a value - the empty array - and never a failed read. Without it, "anything that
     * does not render is unreadable" could be satisfied by marking every empty list.
     */
    @Test
    public void testAnEmptyMcoreValueListIsStillTheEmptyArrayAndNotAFailure()
    {
        EObject holder = newFlagHolder(McorePackage.Literals.VALUE, true, true);

        PropertyInfo info = MetadataPropertyIntrospector.find(holder, "flag"); //$NON-NLS-1$
        assertEquals("[]", info.currentValue); //$NON-NLS-1$
    }

    // ---- contained AdjustableBoolean flags (issue #382) -----------------------------------------
    //
    // A form attribute's view / edit, a form item's userVisible and a form command's use are NOT
    // boolean attributes: each is a SINGLE-VALUED CONTAINMENT reference to the mdclass
    // AdjustableBoolean - an object holding the flag itself (`common`) next to optional per-role
    // overrides (`for`). The generic containment-ref filter would drop them, so they are classified
    // explicitly, and BY TARGET TYPE rather than by name so one rule covers every such flag.

    @Test
    public void testAContainedAdjustableBooleanIsTheAdjustableBooleanKind()
    {
        // Driven through the REAL metamodel: StandardCommand declares `contains AdjustableBoolean
        // visible`, the very shape the form model uses for view / edit / userVisible / use.
        StandardCommand command = MdClassFactory.eINSTANCE.createStandardCommand();
        PropertyInfo visible = MetadataPropertyIntrospector.find(command, "visible"); //$NON-NLS-1$
        assertNotNull("a contained AdjustableBoolean must be assignable", visible); //$NON-NLS-1$
        assertTrue("visible must be the ADJUSTABLE_BOOLEAN kind", //$NON-NLS-1$
            visible.valueKind == ValueKind.ADJUSTABLE_BOOLEAN);
        assertTrue("...so it must be listed as an assignable property", //$NON-NLS-1$
            MetadataPropertyIntrospector.assignableNames(command).contains("visible")); //$NON-NLS-1$
    }

    @Test
    public void testAdjustableBooleanCurrentValueRendersTheNestedCommonFlag()
    {
        // The wire boolean addresses the nested `common` flag, so that is what "Current" must show:
        // nothing at all while the flag object is absent, then each polarity as the boolean a caller
        // would write back.
        StandardCommand command = MdClassFactory.eINSTANCE.createStandardCommand();
        assertNull("an unset AdjustableBoolean renders no current value", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(command, "visible").currentValue); //$NON-NLS-1$

        AdjustableBoolean flag = MdClassFactory.eINSTANCE.createAdjustableBoolean();
        flag.setCommon(true);
        command.setVisible(flag);
        assertEquals("common = true must render as true", "true", //$NON-NLS-1$ //$NON-NLS-2$
            MetadataPropertyIntrospector.find(command, "visible").currentValue); //$NON-NLS-1$

        flag.setCommon(false);
        assertEquals("common = false must render as false, not as an absent value", "false", //$NON-NLS-1$ //$NON-NLS-2$
            MetadataPropertyIntrospector.find(command, "visible").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testAManyAdjustableBooleanReferenceIsNotClassified()
    {
        // The rule admits the SINGLE-valued flag only - a list of AdjustableBooleans is not one flag
        // a wire boolean can address.
        assertNull("a many AdjustableBoolean reference must not be assignable", //$NON-NLS-1$
            MetadataPropertyIntrospector.findFeature(
                newFlagHolder(MdClassPackage.Literals.ADJUSTABLE_BOOLEAN, true, true), "flag")); //$NON-NLS-1$
    }

    @Test
    public void testANonContainmentAdjustableBooleanReferenceIsNotClassified()
    {
        // A reference that merely POINTS at an AdjustableBoolean owns no flag of its own: writing
        // through it would rewrite an object whose owner is somewhere else entirely.
        assertNull("a non-containment AdjustableBoolean reference must not be assignable", //$NON-NLS-1$
            MetadataPropertyIntrospector.findFeature(
                newFlagHolder(MdClassPackage.Literals.ADJUSTABLE_BOOLEAN, false, false), "flag")); //$NON-NLS-1$
    }

    @Test
    public void testAContainedUnrelatedTypeIsNotClassified()
    {
        // The pin that keeps the rule from widening into "any single contained mdclass object":
        // ForRoleType is the AdjustableBoolean's OWN `for` element type and still is not one.
        assertNull("a contained non-AdjustableBoolean must stay unassignable", //$NON-NLS-1$
            MetadataPropertyIntrospector.findFeature(
                newFlagHolder(MdClassPackage.Literals.FOR_ROLE_TYPE, true, false), "flag")); //$NON-NLS-1$
    }

    @Test
    public void testASubtypeOfAdjustableBooleanIsStillClassified()
    {
        // Admitted by isSuperTypeOf, not by identity, so a specialization of the type stays
        // recognized. The subtype is SYNTHETIC and only declares the real EClass as its supertype (a
        // plain, non-containment reference); the real EClass is never added to a synthetic package,
        // which would reparent it out of MdClassPackage for the whole JVM.
        EClass subtype = EcoreFactory.eINSTANCE.createEClass();
        subtype.setName("SpecializedAdjustableBoolean"); //$NON-NLS-1$
        subtype.getESuperTypes().add(MdClassPackage.Literals.ADJUSTABLE_BOOLEAN);

        PropertyInfo flag =
            MetadataPropertyIntrospector.findFeature(newFlagHolder(subtype, true, false), "flag"); //$NON-NLS-1$
        assertNotNull("a subtype of AdjustableBoolean must still be assignable", flag); //$NON-NLS-1$
        assertTrue("...as the ADJUSTABLE_BOOLEAN kind", //$NON-NLS-1$
            flag.valueKind == ValueKind.ADJUSTABLE_BOOLEAN);
    }

    /**
     * A synthetic holder carrying ONE reference named {@code flag} of the given target type and shape,
     * so the classification rule can be probed on each axis it tests (target type, containment,
     * cardinality). The target EClass is only REFERRED to, never added to the holder's package:
     * EClassifier containment is single-parent, so adding a real mdclass EClass would reparent it out
     * of MdClassPackage for the whole JVM.
     */
    private static EObject newFlagHolder(EClass target, boolean containment, boolean many)
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("flaglike"); //$NON-NLS-1$
        pkg.setNsPrefix("flaglike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/flaglike"); //$NON-NLS-1$

        EReference flag = f.createEReference();
        flag.setName("flag"); //$NON-NLS-1$
        flag.setEType(target);
        flag.setContainment(containment);
        flag.setUpperBound(many ? -1 : 1);

        EClass holder = f.createEClass();
        holder.setName("FlagHolder"); //$NON-NLS-1$
        holder.getEStructuralFeatures().add(flag);
        pkg.getEClassifiers().add(holder);
        return pkg.getEFactoryInstance().create(holder);
    }

    // ---- extInfo-aware overloads (issue #235) ---------------------------------------------------
    //
    // A form element carries its kind-specific / layout properties on a nested <extInfo> EObject (e.g.
    // a group's UsualGroupExtInfo group/united/showLeftMargin/...). These are exercised headlessly on a
    // synthetic EPackage (newGroupFixture) shaped like the form metamodel - NO form-model dependency,
    // the introspector only sees EObjects/EClasses.

    @Test
    public void testFindFeatureResolvesExtInfoLayoutEnum()
    {
        GroupFixture fx = newGroupFixture();
        PropertyInfo group = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "group"); //$NON-NLS-1$
        assertNotNull("the group layout enum lives on the extInfo and must resolve", group); //$NON-NLS-1$
        assertTrue("group must be an ENUM", group.valueKind == ValueKind.ENUM); //$NON-NLS-1$
        assertTrue("group must be reported as living on the extInfo", group.onExtInfo); //$NON-NLS-1$
        assertSame("the resolved feature must be the extInfo's own feature", //$NON-NLS-1$
            fx.extInfoEClass.getEStructuralFeature("group"), group.feature); //$NON-NLS-1$
        assertTrue("group must list its grouping literals", //$NON-NLS-1$
            group.allowedValues.contains("Horizontal") && group.allowedValues.contains("Vertical")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFindFeatureResolvesExtInfoBoolean()
    {
        GroupFixture fx = newGroupFixture();
        PropertyInfo united = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "united"); //$NON-NLS-1$
        assertNotNull("the 'united' layout flag lives on the extInfo and must resolve", united); //$NON-NLS-1$
        assertTrue("united must be a BOOLEAN", united.valueKind == ValueKind.BOOLEAN); //$NON-NLS-1$
        assertTrue("united must be reported as living on the extInfo", united.onExtInfo); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureDirectWinsOnNameCollision()
    {
        // The synthetic extInfo declares a boolean 'name' that COLLIDES with the element's direct String
        // 'name'. The DIRECT feature must win: the resolved property is the element's String, onExtInfo
        // false, so a write targets the element - never the extInfo.
        GroupFixture fx = newGroupFixture();
        PropertyInfo name = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "name"); //$NON-NLS-1$
        assertNotNull(name);
        assertFalse("a direct feature must win the name collision (not the extInfo one)", name.onExtInfo); //$NON-NLS-1$
        assertTrue("the winning direct 'name' is the element's String", name.valueKind == ValueKind.STRING); //$NON-NLS-1$
        assertSame("the resolved feature must be the element's own feature", //$NON-NLS-1$
            fx.group.eClass().getEStructuralFeature("name"), name.feature); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureDirectFeatureHasOnExtInfoFalse()
    {
        GroupFixture fx = newGroupFixture();
        PropertyInfo id = MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "id"); //$NON-NLS-1$
        assertNotNull(id);
        assertTrue("id is a direct INTEGER", id.valueKind == ValueKind.INTEGER); //$NON-NLS-1$
        assertFalse("a direct feature is never onExtInfo", id.onExtInfo); //$NON-NLS-1$
    }

    @Test
    public void testLongAttributeIsClassifiedAsLong()
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("longlike"); //$NON-NLS-1$
        pkg.setNsPrefix("longlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/longlike"); //$NON-NLS-1$

        EClass service = f.createEClass();
        service.setName("WebService"); //$NON-NLS-1$
        EAttribute sessionMaxAge = f.createEAttribute();
        sessionMaxAge.setName("sessionMaxAge"); //$NON-NLS-1$
        sessionMaxAge.setEType(EcorePackage.Literals.ELONG);
        service.getEStructuralFeatures().add(sessionMaxAge);
        pkg.getEClassifiers().add(service);

        PropertyInfo info = MetadataPropertyIntrospector.findFeature(
            pkg.getEFactoryInstance().create(service), "sessionMaxAge"); //$NON-NLS-1$
        assertNotNull(info);
        assertEquals("an ELong attribute must advertise its 64-bit kind", //$NON-NLS-1$
            "LONG", info.valueKind.name()); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureWithNullExtInfoResolvesOnlyDirect()
    {
        // A null extInfo (the element has no extInfo instance yet) still resolves the DIRECT features but
        // cannot reach an extInfo-only property.
        GroupFixture fx = newGroupFixture();
        PropertyInfo name = MetadataPropertyIntrospector.findFeature(fx.group, null, "name"); //$NON-NLS-1$
        assertNotNull("a direct feature must resolve even without an extInfo", name); //$NON-NLS-1$
        assertFalse(name.onExtInfo);
        assertNull("an extInfo-only property is unreachable without the extInfo instance", //$NON-NLS-1$
            MetadataPropertyIntrospector.findFeature(fx.group, null, "group")); //$NON-NLS-1$
    }

    @Test
    public void testFindFeatureUnknownReturnsNull()
    {
        GroupFixture fx = newGroupFixture();
        assertNull(MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "noSuchProperty")); //$NON-NLS-1$
        // The raw containment 'extInfo' reference is not itself an assignable property.
        assertNull(MetadataPropertyIntrospector.findFeature(fx.group, fx.extInfo, "extInfo")); //$NON-NLS-1$
    }

    @Test
    public void testAssignableNamesUnionDirectThenExtInfo()
    {
        GroupFixture fx = newGroupFixture();
        List<String> union = MetadataPropertyIntrospector.assignableNames(fx.group, fx.extInfoEClass);
        for (String direct : new String[] {"name", "id", "type"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("union must keep the direct property " + direct, union.contains(direct)); //$NON-NLS-1$
        }
        for (String ext : new String[] {"group", "united", "showLeftMargin", "throughAlign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "currentRowUse", "representation"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("union must add the extInfo property " + ext, union.contains(ext)); //$NON-NLS-1$
        }
        // Direct-precedence: the colliding 'name' appears exactly once, and the direct names precede the
        // extInfo ones.
        assertEquals("the colliding 'name' must appear exactly once", //$NON-NLS-1$
            1, Collections.frequency(union, "name")); //$NON-NLS-1$
        assertTrue("direct names must precede extInfo names", //$NON-NLS-1$
            union.indexOf("type") < union.indexOf("group")); //$NON-NLS-1$ //$NON-NLS-2$
        // The raw containment extInfo reference is not listed.
        assertFalse("the raw extInfo containment ref must not be listed", union.contains("extInfo")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAssignableNamesNullExtInfoEqualsDirect()
    {
        GroupFixture fx = newGroupFixture();
        assertEquals("a null extInfo EClass must reduce to the direct-only listing", //$NON-NLS-1$
            MetadataPropertyIntrospector.assignableNames(fx.group),
            MetadataPropertyIntrospector.assignableNames(fx.group, (EClass)null));
    }

    @Test
    public void testIntrospectExtInfoEClassListsKindAndAllowedWithoutCurrent()
    {
        // The EClass overload lists the extInfo features (kind + allowed enum values) but, having no
        // instance, renders no current value.
        GroupFixture fx = newGroupFixture();
        PropertyInfo group = pick(
            MetadataPropertyIntrospector.introspect(fx.group, fx.extInfoEClass), "group"); //$NON-NLS-1$
        assertNotNull(group);
        assertTrue("group must be an ENUM", group.valueKind == ValueKind.ENUM); //$NON-NLS-1$
        assertTrue("group must be onExtInfo", group.onExtInfo); //$NON-NLS-1$
        assertTrue("group must list its allowed literals", //$NON-NLS-1$
            group.allowedValues.contains("Horizontal")); //$NON-NLS-1$
        assertNull("the EClass listing renders no current value", group.currentValue); //$NON-NLS-1$
    }

    @Test
    public void testIntrospectExtInfoInstanceRendersCurrent()
    {
        // The instance overload reads the current values off the live extInfo instance.
        GroupFixture fx = newGroupFixture();
        EStructuralFeature groupFeature = fx.extInfoEClass.getEStructuralFeature("group"); //$NON-NLS-1$
        fx.extInfo.eSet(groupFeature, fx.grouping.getEEnumLiteralByLiteral("Horizontal")); //$NON-NLS-1$
        fx.extInfo.eSet(fx.extInfoEClass.getEStructuralFeature("united"), Boolean.TRUE); //$NON-NLS-1$

        List<PropertyInfo> props = MetadataPropertyIntrospector.introspect(fx.group, fx.extInfo);
        PropertyInfo group = pick(props, "group"); //$NON-NLS-1$
        assertNotNull(group);
        assertTrue("group must be onExtInfo", group.onExtInfo); //$NON-NLS-1$
        assertEquals("the current group must render as the set literal name", "Horizontal", //$NON-NLS-1$ //$NON-NLS-2$
            group.currentValue);
        assertTrue("the current value must share the allowed vocabulary", //$NON-NLS-1$
            group.allowedValues.contains(group.currentValue));
        assertEquals("the current 'united' flag must render", "true", //$NON-NLS-1$ //$NON-NLS-2$
            pick(props, "united").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testIntrospectDirectPropsPrecedeAndKeepOnExtInfoFalse()
    {
        GroupFixture fx = newGroupFixture();
        List<PropertyInfo> direct = MetadataPropertyIntrospector.introspect(fx.group);
        List<PropertyInfo> union = MetadataPropertyIntrospector.introspect(fx.group, fx.extInfoEClass);
        // The union starts with exactly the direct properties (same order), each still onExtInfo false.
        assertTrue("the union must be longer than the direct-only listing", union.size() > direct.size()); //$NON-NLS-1$
        for (int i = 0; i < direct.size(); i++)
        {
            assertEquals(direct.get(i).name, union.get(i).name);
            assertSame(direct.get(i).feature, union.get(i).feature);
            assertFalse("a direct property must stay onExtInfo false", union.get(i).onExtInfo); //$NON-NLS-1$
        }
    }

    @Test
    public void testResolveEnumLiteralOnExtInfoFeatureIsCaseInsensitive()
    {
        // The shared resolveEnumLiteral works for an extInfo feature exactly as for a direct one.
        GroupFixture fx = newGroupFixture();
        EStructuralFeature groupFeature = fx.extInfoEClass.getEStructuralFeature("group"); //$NON-NLS-1$
        EEnumLiteral lit = MetadataPropertyIntrospector.resolveEnumLiteral(groupFeature, "horizontal"); //$NON-NLS-1$
        assertNotNull("an extInfo enum must resolve case-insensitively", lit); //$NON-NLS-1$
        assertEquals("Horizontal", lit.getName()); //$NON-NLS-1$
        assertNull(MetadataPropertyIntrospector.resolveEnumLiteral(groupFeature, "NotAGrouping_zzz")); //$NON-NLS-1$
    }

    @Test
    public void testSingleArgIntrospectUnaffectedByExtInfoOverloads()
    {
        // The mdclass path is unchanged: a real object (no extInfo) introspected with a null extInfo
        // EClass yields exactly the single-arg listing.
        CatalogAttribute attr = newAttribute();
        List<String> viaNull = MetadataPropertyIntrospector.assignableNames(attr, (EClass)null);
        assertEquals(MetadataPropertyIntrospector.assignableNames(attr), viaNull);
        // And the direct listing never carries an onExtInfo property.
        for (PropertyInfo p : MetadataPropertyIntrospector.introspect(attr))
        {
            assertFalse("mdclass properties are never onExtInfo", p.onExtInfo); //$NON-NLS-1$
        }
    }

    /** Picks the property named {@code name} from a listing, or {@code null}. */
    private static PropertyInfo pick(List<PropertyInfo> props, String name)
    {
        for (PropertyInfo p : props)
        {
            if (p.name.equals(name))
            {
                return p;
            }
        }
        return null;
    }

    /**
     * A synthetic form-group-like element with its nested extInfo, built on a dynamic EPackage so the
     * extInfo-aware overloads can be exercised headlessly (no form-model dependency). The extInfo
     * declares the #235 layout features plus a boolean {@code name} that deliberately collides with the
     * element's direct {@code name} (to probe DIRECT-precedence).
     */
    private static final class GroupFixture
    {
        final EObject group;
        final EObject extInfo;
        final EClass extInfoEClass;
        final EEnum grouping;

        GroupFixture(EObject group, EObject extInfo, EClass extInfoEClass, EEnum grouping)
        {
            this.group = group;
            this.extInfo = extInfo;
            this.extInfoEClass = extInfoEClass;
            this.grouping = grouping;
        }
    }

    private static GroupFixture newGroupFixture()
    {
        EcoreFactory f = EcoreFactory.eINSTANCE;
        EPackage pkg = f.createEPackage();
        pkg.setName("introspectlike"); //$NON-NLS-1$
        pkg.setNsPrefix("introspectlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/introspectlike"); //$NON-NLS-1$

        EEnum grouping = newEnum(f, "FormChildrenGroup", //$NON-NLS-1$
            "Auto", "Vertical", "Horizontal", "HorizontalIfPossible"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        EEnum throughAlign = newEnum(f, "UsualGroupThroughAlign", "Auto", "Use", "DontUse"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        EEnum currentRowUse = newEnum(f, "CurrentRowUse", "DontUse", "Use", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        EEnum representation = newEnum(f, "UsualGroupRepresentation", //$NON-NLS-1$
            "None", "StrongSeparation", "WeakSeparation"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        EEnum groupType = newEnum(f, "ManagedFormGroupType", "UsualGroup", "Pages", "Page"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        EClass extInfoBase = f.createEClass();
        extInfoBase.setName("FormItemExtInfo"); //$NON-NLS-1$
        extInfoBase.setAbstract(true);

        EClass usualGroupExtInfo = f.createEClass();
        usualGroupExtInfo.setName("UsualGroupExtInfo"); //$NON-NLS-1$
        usualGroupExtInfo.getESuperTypes().add(extInfoBase);
        addEnum(f, usualGroupExtInfo, "group", grouping); //$NON-NLS-1$
        addBoolean(f, usualGroupExtInfo, "united"); //$NON-NLS-1$
        addBoolean(f, usualGroupExtInfo, "showLeftMargin"); //$NON-NLS-1$
        addEnum(f, usualGroupExtInfo, "throughAlign", throughAlign); //$NON-NLS-1$
        addEnum(f, usualGroupExtInfo, "currentRowUse", currentRowUse); //$NON-NLS-1$
        addEnum(f, usualGroupExtInfo, "representation", representation); //$NON-NLS-1$
        // A boolean 'name' that COLLIDES with the element's direct String 'name' (precedence probe).
        addBoolean(f, usualGroupExtInfo, "name"); //$NON-NLS-1$

        EClass formGroup = f.createEClass();
        formGroup.setName("FormGroup"); //$NON-NLS-1$
        addString(f, formGroup, "name"); //$NON-NLS-1$
        addInt(f, formGroup, "id"); //$NON-NLS-1$
        addEnum(f, formGroup, "type", groupType); //$NON-NLS-1$
        EReference extInfoRef = f.createEReference();
        extInfoRef.setName("extInfo"); //$NON-NLS-1$
        extInfoRef.setEType(extInfoBase);
        extInfoRef.setContainment(true);
        extInfoRef.setUpperBound(1);
        formGroup.getEStructuralFeatures().add(extInfoRef);

        pkg.getEClassifiers().add(grouping);
        pkg.getEClassifiers().add(throughAlign);
        pkg.getEClassifiers().add(currentRowUse);
        pkg.getEClassifiers().add(representation);
        pkg.getEClassifiers().add(groupType);
        pkg.getEClassifiers().add(extInfoBase);
        pkg.getEClassifiers().add(usualGroupExtInfo);
        pkg.getEClassifiers().add(formGroup);

        EObject group = pkg.getEFactoryInstance().create(formGroup);
        EObject extInfo = pkg.getEFactoryInstance().create(usualGroupExtInfo);
        group.eSet(extInfoRef, extInfo);
        return new GroupFixture(group, extInfo, usualGroupExtInfo, grouping);
    }

    private static EEnum newEnum(EcoreFactory f, String name, String... literals)
    {
        EEnum eEnum = f.createEEnum();
        eEnum.setName(name);
        int value = 0;
        for (String literal : literals)
        {
            EEnumLiteral eLiteral = f.createEEnumLiteral();
            eLiteral.setName(literal);
            eLiteral.setLiteral(literal);
            eLiteral.setValue(value++);
            eEnum.getELiterals().add(eLiteral);
        }
        return eEnum;
    }

    private static void addString(EcoreFactory f, EClass owner, String name)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(EcorePackage.Literals.ESTRING);
        owner.getEStructuralFeatures().add(attribute);
    }

    private static void addInt(EcoreFactory f, EClass owner, String name)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(EcorePackage.Literals.EINT);
        owner.getEStructuralFeatures().add(attribute);
    }

    private static void addBoolean(EcoreFactory f, EClass owner, String name)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(EcorePackage.Literals.EBOOLEAN);
        owner.getEStructuralFeatures().add(attribute);
    }

    private static void addEnum(EcoreFactory f, EClass owner, String name, EEnum type)
    {
        addEnum(f, owner, name, type, false);
    }

    private static void addEnum(EcoreFactory f, EClass owner, String name, EEnum type, boolean many)
    {
        EAttribute attribute = f.createEAttribute();
        attribute.setName(name);
        attribute.setEType(type);
        if (many)
        {
            attribute.setUpperBound(-1);
        }
        owner.getEStructuralFeatures().add(attribute);
    }
}
