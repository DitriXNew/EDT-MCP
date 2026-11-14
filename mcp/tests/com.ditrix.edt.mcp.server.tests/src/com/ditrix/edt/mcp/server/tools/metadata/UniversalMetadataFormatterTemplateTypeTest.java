/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

/**
 * Issue #919: the full details table of a report's templates shows each template's content type, so a
 * DataCompositionSchema template created by create_metadata reads as one instead of '-'.
 */
public class UniversalMetadataFormatterTemplateTypeTest
{
    @Test public void testFullTemplatesTableShowsTheContentType()
    {
        Report report = MdClassFactory.eINSTANCE.createReport();
        report.setName("Sales919"); //$NON-NLS-1$
        report.getTemplates().add(template("Main", TemplateType.DATA_COMPOSITION_SCHEMA)); //$NON-NLS-1$
        report.getTemplates().add(template("Print", TemplateType.SPREADSHEET_DOCUMENT)); //$NON-NLS-1$
        String out = UniversalMetadataFormatter.getInstance().format(report, true, "en"); //$NON-NLS-1$
        assertTrue(out, out.contains("| Main |  | DataCompositionSchema |"));
        assertTrue(out, out.contains("| Print |  | SpreadsheetDocument |"));
    }

    private static Template template(String name, TemplateType type)
    {
        Template template = MdClassFactory.eINSTANCE.createTemplate();
        template.setName(name);
        template.setTemplateType(type);
        return template;
    }
}
