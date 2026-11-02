/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/** Tests saved marker coordinates, without deriving a range from the source or line. */
public class MarkerRowsRangeTest
{
    @Test
    public void testStoredUtf16RangesSurviveCyrillicNonBmpLineEndingsAndBom()
    {
        String first = "ПроверкаПути";
        String second = "ДругаяПроверка";
        for (String bom : new String[] {"", "\uFEFF"})
        {
            for (String newline : new String[] {"\n", "\r\n"})
            {
                String source = bom + "// \uD83D\uDE00" + newline + first + "(); " + second + "();";
                int firstOffset = source.indexOf(first);
                MarkerRows.Row firstRow = row(info(firstOffset, first.length()));
                MarkerRows.Row secondRow = row(info(source.indexOf(second), second.length()));
                assertEquals(Integer.valueOf(firstOffset), firstRow.offset);
                assertEquals(Integer.valueOf(first.length()), firstRow.length);
                assertEquals(first, source.substring(firstRow.offset, firstRow.offset + firstRow.length));
                assertEquals(second, source.substring(secondRow.offset, secondRow.offset + secondRow.length));
                assertEquals(firstRow.line, secondRow.line);
                assertTrue(secondRow.offset > firstRow.offset);
                assertEquals(firstOffset - 1, source.codePointCount(0, firstOffset));
            }
        }
    }

    @Test
    public void testMissingPartialAndZeroCoordinatesRemainIndependent()
    {
        MarkerRows.Row missing = row(info(null, null));
        assertNull(missing.offset);
        assertNull(missing.length);
        MarkerRows.Row offsetOnly = row(info(0, null));
        assertEquals(Integer.valueOf(0), offsetOnly.offset);
        assertNull(offsetOnly.length);
        MarkerRows.Row lengthOnly = row(info(null, 8));
        assertNull(lengthOnly.offset);
        assertEquals(Integer.valueOf(8), lengthOnly.length);
        MarkerRows.Row zero = row(info(0, 0));
        assertEquals(Integer.valueOf(0), zero.offset);
        assertEquals(Integer.valueOf(0), zero.length);
    }

    @Test
    public void testMetadataAndNullExtraInfoDoNotInventCoordinates()
    {
        MarkerRows.Row metadata = row(new ExtraInfo());
        assertNull(metadata.modulePath);
        assertNull(metadata.offset);
        assertNull(metadata.length);
        MarkerRows.Row noInfo = row(null);
        assertNull(noInfo.offset);
        assertNull(noInfo.length);
        ExtraInfo withoutBslUri = info(12, 3);
        withoutBslUri.remove(StandardExtraInfo.TEXT_URI_TO_PROBLEM.getKey());
        MarkerRows.Row textOnly = row(withoutBslUri);
        assertNull(textOnly.modulePath);
        assertEquals(Integer.valueOf(12), textOnly.offset);
        assertEquals(Integer.valueOf(3), textOnly.length);
    }

    @Test
    public void testMalformedAndNegativeCoordinatesDoNotDiscardOtherFields()
    {
        ExtraInfo info = info(12, 7);
        info.put("offset", "not-an-integer");
        MarkerRows.Row badOffset = row(info);
        assertNull(badOffset.offset);
        assertEquals(Integer.valueOf(7), badOffset.length);
        assertEquals("CommonModules/Probe/Module.bsl", badOffset.modulePath);
        info.put("offset", "12");
        info.put("length", "2147483648");
        MarkerRows.Row badLength = row(info);
        assertEquals(Integer.valueOf(12), badLength.offset);
        assertNull(badLength.length);
        MarkerRows.Row negative = row(info(-1, -1));
        assertNull(negative.offset);
        assertNull(negative.length);
    }

    @Test
    public void testStoredOutOfBoundsCoordinatesAreNotClampedOrReplaced()
    {
        MarkerRows.Row saved = row(info(Integer.MAX_VALUE, 8));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), saved.offset);
        assertEquals(Integer.valueOf(8), saved.length);
    }

    @Test
    public void testIdentityIsCopiedFromMarkerAndResolvedUid()
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("ProbeProject");
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(project);
        when(marker.getCheckId()).thenReturn("SU23");
        ICheckRepository repository = mock(ICheckRepository.class);
        when(repository.getUidForShortUid("SU23", project)).thenReturn(new CheckUid("probe-check", "probe.plugin"));
        MarkerRows.Row row = MarkerRows.buildIfMatches(marker, null, null, Collections.emptySet(),
            repository, null, new int[] {0}, new int[] {0}, false, MarkerRows.Row::new);
        assertEquals("ProbeProject", row.project);
        assertEquals("probe-check", row.checkId);
        assertEquals("probe.plugin", row.contributorId);
        assertEquals("SU23", row.checkCode);
    }

    private static MarkerRows.Row row(IExtraInfoMap info)
    {
        Marker marker = mock(Marker.class);
        when(marker.getExtraInfo()).thenReturn(info);
        return MarkerRows.buildIfMatches(marker, null, null, Collections.emptySet(), null, null,
            new int[] {0}, new int[] {0}, false, MarkerRows.Row::new);
    }

    private static ExtraInfo info(Integer offset, Integer length)
    {
        ExtraInfo info = new ExtraInfo();
        info.put(StandardExtraInfo.TEXT_URI_TO_PROBLEM,
            "platform:/resource/ProbeProject/src/CommonModules/Probe/Module.bsl");
        info.put(StandardExtraInfo.TEXT_LINE, 2);
        info.put(StandardExtraInfo.TEXT_OFFSET, offset);
        info.put(StandardExtraInfo.TEXT_LENGTH, length);
        return info;
    }

    private static final class ExtraInfo extends HashMap<String, String> implements IExtraInfoMap
    {
        private static final long serialVersionUID = 1L;
    }
}
