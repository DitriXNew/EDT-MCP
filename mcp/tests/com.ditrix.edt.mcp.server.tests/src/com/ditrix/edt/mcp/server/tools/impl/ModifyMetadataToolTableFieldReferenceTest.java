/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Table;
import com._1c.g5.v8.dt.metadata.mdtype.MdRefType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;
import com._1c.g5.v8.dt.metadata.mdtype.TableTypes;
import com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;

/**
 * A Table's {@code presentationField} / {@code parentField} take one of the Table's OWN fields, by the
 * EDT designer's choice lists (issue #448): the whole single-reference path is driven headlessly.
 */
public class ModifyMetadataToolTableFieldReferenceTest
{
    private final ExternalDataSourceFixtures f = new ExternalDataSourceFixtures();

    @Test
    public void presentationFieldAcceptsTheTablesOwnFieldByItsAddress()
    {
        List<ModifyMetadataTool.PreparedChange> out = new ArrayList<>();
        String err = prepare(f.table, "presentationField", FIELD, out); //$NON-NLS-1$
        assertNull("the Table's own Field must be accepted, got: " + err, err); //$NON-NLS-1$
        assertEquals(1, out.size());
    }

    @Test
    public void presentationFieldAcceptsTheRussianAddressOfTheOwnField()
    {
        List<ModifyMetadataTool.PreparedChange> out = new ArrayList<>();
        String err = prepare(f.table, "presentationField", //$NON-NLS-1$
            RU_EDS + ".Eds." + RU_TABLE + ".T." + RU_FIELD + ".F", out); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("the Russian address of the own Field must be accepted, got: " + err, err); //$NON-NLS-1$
        assertEquals(1, out.size());
    }

    @Test
    public void aFieldOfAnotherTableIsRefusedNamingTheOwner()
    {
        Table other = named(MdClassFactory.eINSTANCE.createTable(), "U"); //$NON-NLS-1$
        f.eds.getTables().add(other);
        other.setParentDataSource(f.eds);
        other.getTableFields().add(named(MdClassFactory.eINSTANCE.createField(), "G")); //$NON-NLS-1$
        List<ModifyMetadataTool.PreparedChange> out = new ArrayList<>();

        String err = prepare(f.table, "presentationField", EDS + ".Table.U.Field.G", out); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("a Field of another Table must be refused", err); //$NON-NLS-1$
        assertTrue(err, err.contains("must be a Field of the object being modified 'ExternalDataSource.Eds.Table.T'")); //$NON-NLS-1$
        assertTrue(err, err.contains("EDT offers only that object's own fields here")); //$NON-NLS-1$
        assertTrue("nothing may be queued for a refused value", out.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void parentFieldRefusesAnOwnFieldThatIsNotASelfReference()
    {
        List<ModifyMetadataTool.PreparedChange> out = new ArrayList<>();

        String err = prepare(f.table, "parentField", FIELD, out); //$NON-NLS-1$

        assertNotNull("an untyped own Field is not a parent field in EDT", err); //$NON-NLS-1$
        assertTrue(err, err.contains("'" + FIELD + "' for 'parentField' must be a Field whose type is exactly one type, " //$NON-NLS-1$ //$NON-NLS-2$
            + "a reference to this same Table")); //$NON-NLS-1$
        assertTrue(err, err.contains("reference type in the EDT designer")); //$NON-NLS-1$
        assertTrue(out.isEmpty());
    }

    @Test
    public void parentFieldAcceptsAnOwnFieldTypedAsAReferenceToTheSameTable()
    {
        f.field.setType(description(refTypeOf(f.table)));
        List<ModifyMetadataTool.PreparedChange> out = new ArrayList<>();

        String err = prepare(f.table, "parentField", FIELD, out); //$NON-NLS-1$

        assertNull("a self-reference Field is the designer's parent field, got: " + err, err); //$NON-NLS-1$
        assertEquals(1, out.size());
    }

    @Test
    public void parentFieldRefusesAReferenceToAnotherTableOrASecondType()
    {
        Table other = named(MdClassFactory.eINSTANCE.createTable(), "U"); //$NON-NLS-1$
        f.field.setType(description(refTypeOf(other)));
        assertNotNull(prepare(f.table, "parentField", FIELD, new ArrayList<>())); //$NON-NLS-1$

        f.field.setType(description(refTypeOf(f.table), McoreFactory.eINSTANCE.createType()));
        assertNotNull("a composite type is not offered either", //$NON-NLS-1$
            prepare(f.table, "parentField", FIELD, new ArrayList<>())); //$NON-NLS-1$
    }

    @Test
    public void presentationFieldStillRefusesAnyOtherMember()
    {
        String err = prepare(f.table, "presentationField", FUNCTION, new ArrayList<>()); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue("a non-Field member keeps the unchanged refusal: " + err, //$NON-NLS-1$
            err.contains("must be a top-level object; references to members are not supported")); //$NON-NLS-1$
    }

    @Test
    public void aFieldWithoutAnOwnerOrForAnotherReferenceIsRefused()
    {
        EStructuralFeature presentation = MdClassPackage.Literals.TABLE__PRESENTATION_FIELD;
        String noOwner = ModifyMetadataTool.validateReferenceTarget("presentationField", presentation, //$NON-NLS-1$
            f.field, FIELD);
        assertNotNull("without an owner a Field never passes", noOwner); //$NON-NLS-1$
        assertTrue(noOwner, noOwner.contains("must be a Field of the object being modified")); //$NON-NLS-1$

        EStructuralFeature defaultForm = MdClassPackage.Literals.TABLE__DEFAULT_LIST_FORM;
        String other = ModifyMetadataTool.validateReferenceTarget("defaultListForm", defaultForm, //$NON-NLS-1$
            f.field, FIELD, null, f.table);
        assertNotNull(other);
        assertTrue("a Field is still not a target of a non-Field reference: " + other, //$NON-NLS-1$
            other.contains("must be a top-level object; references to members are not supported")); //$NON-NLS-1$
    }

    private String prepare(Table owner, String property, String value,
        List<ModifyMetadataTool.PreparedChange> out)
    {
        PropertyInfo info = MetadataPropertyIntrospector.find(owner, property);
        assertNotNull("precondition: '" + property + "' must be an addressable Table property", info); //$NON-NLS-1$ //$NON-NLS-2$
        return ModifyMetadataTool.prepareReferenceWith(null, f.scope(), owner, property, value, info, out);
    }

    /** The reference type of a Table - a type item contained in its produced types, as in EDT. */
    private static Type refTypeOf(Table table)
    {
        TableTypes produced = MdTypeFactory.eINSTANCE.createTableTypes();
        MdRefType refType = MdTypeFactory.eINSTANCE.createMdRefType();
        Type type = McoreFactory.eINSTANCE.createType();
        refType.setType(type);
        produced.setRefType(refType);
        table.setProducedTypes(produced);
        return type;
    }

    private static TypeDescription description(Type... types)
    {
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        for (Type type : types)
        {
            description.getTypes().add(type);
        }
        return description;
    }
}
