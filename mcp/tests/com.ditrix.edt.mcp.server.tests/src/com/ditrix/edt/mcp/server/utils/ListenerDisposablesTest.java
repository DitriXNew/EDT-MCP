package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ListenerDisposablesTest
{
    @Test
    public void disposalIsReversedAndIdempotentAndClosesLateRegistrations()
    {
        ListenerDisposables owner = new ListenerDisposables();
        List<Integer> order = new ArrayList<>();
        Runnable first = owner.add(() -> order.add(1));
        owner.add(() -> order.add(2));
        owner.dispose();
        owner.dispose();
        first.run();
        owner.add(() -> order.add(3));
        assertEquals(List.of(2, 1, 3), order);
    }
}
