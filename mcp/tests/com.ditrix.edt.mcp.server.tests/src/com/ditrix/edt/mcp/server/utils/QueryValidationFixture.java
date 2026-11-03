/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import java.util.List;

/** Real shared-service results for tool adapter tests. */
public final class QueryValidationFixture
{
    private QueryValidationFixture() { }

    public static QueryValidationService.Result result(boolean dcsMode, QueryValidationService.Problem... issues)
    {
        return new QueryValidationService.Result(dcsMode, List.of(issues), List.of(), null);
    }

    public static QueryValidationService.Result unavailable(boolean dcsMode)
    {
        return QueryValidationService.Result.unavailable(dcsMode, "Wait for project loading and retry validate_query.");
    }
}
