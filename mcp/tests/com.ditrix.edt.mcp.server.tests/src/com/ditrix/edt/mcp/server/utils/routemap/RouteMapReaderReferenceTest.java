/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.bp.scheme.model.ModelFactory;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonObject;

public class RouteMapReaderReferenceTest
{
    @Test
    public void testAnUnresolvedSubprocessProxyKeepsItsSymbolicNameWithoutReadingTheBmFqn()
    {
        var target = MdClassFactory.eINSTANCE.createBusinessProcess();
        ((InternalEObject)target).eSetProxyURI(BmUriUtil.createTopBmObjectUri("missing", "BusinessProcess.Deleted"));
        assertTrue(target.eIsProxy());
        assertTrue(((IBmObject)target).bmIsTop());
        assertThrows(RuntimeException.class, () -> ((IBmObject)target).bmGetFqn());
        assertEquals("BusinessProcess.Deleted", read(target).get("subprocess").getAsString());
    }

    @Test
    public void testAnUnresolvedSchemeProxyKeepsItsSymbolicNameWithoutTheLeadingSlash()
    {
        var target = MdClassFactory.eINSTANCE.createBusinessProcess();
        ((InternalEObject)target).eSetProxyURI(URI.createURI("unresolved:/BusinessProcess.Deleted"));
        assertTrue(target.eIsProxy());
        assertThrows(RuntimeException.class, () -> ((IBmObject)target).bmGetFqn());
        var point = read(target);
        assertTrue("The unresolved subprocess name must be present", point.has("subprocess"));
        assertEquals("BusinessProcess.Deleted", point.get("subprocess").getAsString());
    }

    @Test
    public void testANonTopSubprocessReferenceDoesNotCallBmGetFqn()
    {
        BusinessProcess target = mock(BusinessProcess.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)target).bmIsTop()).thenReturn(false);
        when(((IBmObject)target).bmGetFqn()).thenThrow(new IllegalStateException("Not a top object"));
        assertFalse(read(target).has("subprocess"));
        verify((IBmObject)target, never()).bmGetFqn();
    }

    @Test
    public void testAResolvedTopSubprocessKeepsItsCanonicalFqn()
    {
        BusinessProcess target = mock(BusinessProcess.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)target).bmIsTop()).thenReturn(true);
        when(((IBmObject)target).bmGetFqn()).thenReturn("BusinessProcess.Child");
        assertEquals("BusinessProcess.Child", read(target).get("subprocess").getAsString());
    }

    private static JsonObject read(BusinessProcess target)
    {
        var factory = ModelFactory.eINSTANCE;
        var scheme = factory.createGraphicalScheme();
        var subprocess = factory.createGraphicalSchemeSubProcess();
        subprocess.setName("RunChild");
        subprocess.setItemType(GraphicalSchemeItemType.SUB_BUSINESS_PROCESS);
        subprocess.setSubprocess(target);
        scheme.getItems().add(subprocess);
        return RouteMapReader.read(scheme, null).getAsJsonArray("points").get(0).getAsJsonObject();
    }
}
