/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import com._1c.g5.v8.dt.metadata.mdclass.*;

/** Detached metamodel objects shared by the EDS contract tests; no EDT workbench required. */
public final class ExternalDataSourceFixtures
{
    public static final String EDS = "ExternalDataSource.Eds";
    public static final String TABLE = EDS + ".Table.T";
    public static final String FIELD = TABLE + ".Field.F";
    public static final String FUNCTION = EDS + ".Function.Fn";
    public static final String CUBE = EDS + ".Cube.C";
    public static final String RU_EDS = "\u0412\u043d\u0435\u0448\u043d\u0438\u0439\u0418\u0441\u0442\u043e\u0447\u043d\u0438\u043a\u0414\u0430\u043d\u043d\u044b\u0445";
    public static final String RU_TABLE = "\u0422\u0430\u0431\u043b\u0438\u0446\u0430";
    public static final String RU_FIELD = "\u041f\u043e\u043b\u0435";
    public static final String RU_FUNCTION = "\u0424\u0443\u043d\u043a\u0446\u0438\u044f";
    public static final String RU_CUBE = "\u041a\u0443\u0431";
    public final Configuration config = named(MdClassFactory.eINSTANCE.createConfiguration(), "Cfg");
    public final ExternalDataSource eds = named(MdClassFactory.eINSTANCE.createExternalDataSource(), "Eds");
    public final Table table = named(MdClassFactory.eINSTANCE.createTable(), "T");
    public final Field field = named(MdClassFactory.eINSTANCE.createField(), "F");
    public final com._1c.g5.v8.dt.metadata.mdclass.Function function =
        named(MdClassFactory.eINSTANCE.createFunction(), "Fn");
    public final Cube cube = named(MdClassFactory.eINSTANCE.createCube(), "C");

    public ExternalDataSourceFixtures()
    {
        config.getExternalDataSources().add(eds);
        eds.getTables().add(table);
        table.setParentDataSource(eds);
        table.getTableFields().add(field);
        eds.getFunctions().add(function);
        eds.getCubes().add(cube);
        cube.setParentDataSource(eds);
    }

    public MetadataScope scope()
    {
        return MetadataScope.ofConfiguration(config);
    }

    public static <T extends MdObject> T named(T object, String name)
    {
        object.setName(name);
        return object;
    }
}
