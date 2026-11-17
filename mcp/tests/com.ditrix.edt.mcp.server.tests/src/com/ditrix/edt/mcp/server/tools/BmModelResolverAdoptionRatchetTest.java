// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.Test;

/** Pins remaining direct model lookups, including calls inside nested classes. */
public class BmModelResolverAdoptionRatchetTest
{
    @Test public void directModelLookupsCannotGrow() throws Exception
    {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("CLAUDE.md"))) root = root.getParent();
        assertNotNull("Cannot locate source tree", root);
        Path source = root.resolve("mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server");
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("groups/ui/GroupNavigatorAdapter.java", 1);
        expected.put("pictures/CommonPicturesGalleryRenderer.java", 1);
        expected.put("tags/ui/TagFilterView.java", 1);
        expected.put("tools/impl/ApplyQuickFixTool.java", 1);
        expected.put("tools/impl/BusinessProcessRouteMapTool.java", 1);
        expected.put("tools/impl/ExportCommonPictureTool.java", 1);
        expected.put("tools/impl/ImportProjectFromFileTool.java", 1);
        expected.put("tools/impl/TranslateConfigurationTool.java", 1);
        expected.put("tools/reference/MetadataReferenceService.java", 1);
        expected.put("utils/EditorNavigation.java", 1);
        expected.put("utils/FormElementWriter.java", 1);
        expected.put("utils/QueryValidationService.java", 1);
        expected.put("utils/ReferenceMembershipWriter.java", 1);
        expected.put("utils/VendorSupportGuard.java", 1);
        Map<String, Integer> actual = new TreeMap<>();
        Pattern calls = Pattern.compile("(?<!ContentUtil)\\.getModel\\(\\s*[^\\s)]");
        Pattern nonCode = Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*|\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"");
        try (var files = Files.walk(source))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList())
            {
                if (file.getFileName().toString().equals("BmModelResolver.java")) continue;
                String code = nonCode.matcher(Files.readString(file)).replaceAll(" ");
                int count = (int)calls.matcher(code).results().count();
                if (count > 0) actual.put(source.relativize(file).toString().replace('\\', '/'), count);
            }
        }
        assertEquals("Resolve BM models through BmModelResolver; tighten this inventory when a caller migrates",
            expected, actual);
    }
}
