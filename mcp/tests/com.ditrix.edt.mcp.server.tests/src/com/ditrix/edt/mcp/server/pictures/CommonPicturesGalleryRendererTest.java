// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.pictures;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.HeadlessReadFixture;
import org.eclipse.emf.common.util.BasicEList;
import org.junit.Test;

/** Gallery title and language preparation must obey the same boundary as the picture reads. */
public class CommonPicturesGalleryRendererTest
{
    @Test public void titleAndLanguageAreReadInsideTheBmBoundary() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            var languages = fixture.config.getLanguages();
            var language = fixture.config.getDefaultLanguage();
            Configuration config = mock(Configuration.class);
            when(config.eClass()).thenReturn(MdClassPackage.Literals.CONFIGURATION);
            when(config.getCommonPictures()).thenReturn(new BasicEList<>());
            when(config.getLanguages()).thenReturn(languages);
            when(config.getName()).thenAnswer(call -> {
                assertTrue("Gallery name read outside BM", fixture.depth > 0);
                return "Gallery";
            });
            when(config.getDefaultLanguage()).thenAnswer(call -> {
                assertTrue("Gallery language read outside BM", fixture.depth > 0);
                return language;
            });
            var configurations = Activator.getDefault().getConfigurationProvider();
            doAnswer(call -> {
                assertTrue("Gallery configuration read outside BM", fixture.depth > 0);
                return config;
            }).when(configurations).getConfiguration(fixture.project());

            var page = CommonPicturesGalleryRenderer.render(fixture.name(), "Pictures: ", null, "", 0, null);
            assertNotNull(page);
            assertEquals("Pictures: Gallery", page.title());
            assertTrue(page.html().contains("Gallery"));
            assertEquals(0, fixture.depth);
        }
    }
}
