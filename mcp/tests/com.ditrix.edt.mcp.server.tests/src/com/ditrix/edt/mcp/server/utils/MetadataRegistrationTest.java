// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.nio.file.Files;
import org.junit.Test;

public class MetadataRegistrationTest
{
    @Test public void inlineLanguagesAndTopReferencesUseTheActualDiskBytes() throws Exception
    {
        try (var files = SearchFixtureProject.create())
        {
            var file = files.file("src/Configuration/Configuration.mdo", "<Configuration/>");
            for (String name : new String[] { "EnglishName", "Код" })
            {
                Files.writeString(file.getLocation().toFile().toPath(), "<Configuration><languages><name>"
                    + name + "</name><languageCode>en</languageCode></languages><catalogs>Catalog."
                    + name + "</catalogs></Configuration>");
                for (String type : new String[] { "Language", "Catalog" })
                {
                    assertEquals(MetadataRegistration.State.PRESENT, MetadataRegistration.verify(files.name(),
                        "src/Configuration/Configuration.mdo", "Configuration", type + "." + name));
                    assertEquals(MetadataRegistration.State.ABSENT, MetadataRegistration.verify(files.name(),
                        "src/Configuration/Configuration.mdo", "Configuration", type + ".Missing"));
                }
                Files.writeString(file.getLocation().toFile().toPath(), "<Configuration><catalogs>Catalog."
                    + name + "New</catalogs></Configuration>");
                assertTrue(MetadataRegistration.verifyRename(files.name(), "Catalog." + name, "Catalog." + name + "New"));
                assertFalse(MetadataRegistration.verifyRename(files.name(), "Catalog." + name + "New", "Catalog." + name));
            }
        }
    }

    @Test public void caseOnlyRenameRequiresOnlyTheNewSpelling() throws Exception
    {
        try (var files = SearchFixtureProject.create())
        {
            var file = files.file("src/Configuration/Configuration.mdo",
                "<Configuration><catalogs>Catalog.Name</catalogs></Configuration>");
            assertFalse(MetadataRegistration.verifyRename(files.name(), "Catalog.Name", "Catalog.NAME"));
            Files.writeString(file.getLocation().toFile().toPath(),
                "<Configuration><catalogs>Catalog.NAME</catalogs></Configuration>");
            assertTrue(MetadataRegistration.verifyRename(files.name(), "Catalog.Name", "Catalog.NAME"));
            Files.writeString(file.getLocation().toFile().toPath(),
                "<Configuration><catalogs>Catalog.Name</catalogs><catalogs>Catalog.NAME</catalogs></Configuration>");
            assertFalse(MetadataRegistration.verifyRename(files.name(), "Catalog.Name", "Catalog.NAME"));
        }
    }

    @Test public void wrongRootAndMissingFilesCannotProveAbsence() throws Exception
    {
        try (var files = SearchFixtureProject.create())
        {
            files.file("src/Configuration/Configuration.mdo", "<Catalog/>");
            for (String path : new String[] { "src/Configuration/Configuration.mdo", "src/Missing.mdo" })
                assertEquals(MetadataRegistration.State.UNVERIFIABLE,
                    MetadataRegistration.verify(files.name(), path, "Configuration", "Catalog.Missing"));
        }
    }
}
