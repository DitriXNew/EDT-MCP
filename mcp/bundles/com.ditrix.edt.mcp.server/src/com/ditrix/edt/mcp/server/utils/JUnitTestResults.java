/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory model of parsed JUnit XML test results.
 *
 * Holds aggregate counts plus per-test-case details for failures, errors and
 * skipped tests. Passed cases are not retained individually because the report
 * does not need to enumerate them.
 */
public final class JUnitTestResults
{
    /** A single test case outcome with its diagnostic message and stack trace. */
    public static final class TestCase
    {
        public final String name;
        public final String message;
        public final String trace;

        public TestCase(String name, String message, String trace)
        {
            this.name = name;
            this.message = message;
            this.trace = trace;
        }
    }

    private int total;
    private int failures;
    private int errors;
    private int skipped;
    private final List<TestCase> failureDetails = new ArrayList<>();
    private final List<TestCase> errorDetails = new ArrayList<>();
    private final List<TestCase> skippedDetails = new ArrayList<>();

    public int getTotal()
    {
        return total;
    }

    public int getFailures()
    {
        return failures;
    }

    public int getErrors()
    {
        return errors;
    }

    public int getSkipped()
    {
        return skipped;
    }

    public int getPassed()
    {
        int p = total - failures - errors - skipped;
        return p < 0 ? 0 : p;
    }

    public boolean isPassed()
    {
        return failures == 0 && errors == 0;
    }

    public List<TestCase> getFailureDetails()
    {
        return Collections.unmodifiableList(failureDetails);
    }

    public List<TestCase> getErrorDetails()
    {
        return Collections.unmodifiableList(errorDetails);
    }

    public List<TestCase> getSkippedDetails()
    {
        return Collections.unmodifiableList(skippedDetails);
    }

    void addToTotals(int tests, int failures, int errors, int skipped)
    {
        this.total += tests;
        this.failures += failures;
        this.errors += errors;
        this.skipped += skipped;
    }

    void setTotal(int total)
    {
        this.total = total;
    }

    void addFailure(TestCase tc)
    {
        failureDetails.add(tc);
    }

    void addError(TestCase tc)
    {
        errorDetails.add(tc);
    }

    void addSkipped(TestCase tc)
    {
        skippedDetails.add(tc);
    }
}
