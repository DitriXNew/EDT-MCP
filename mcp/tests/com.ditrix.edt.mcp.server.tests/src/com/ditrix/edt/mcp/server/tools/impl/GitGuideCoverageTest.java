/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.GuideLoader;

/** Keeps the git guide's exhaustive blocked-option list aligned with the parser guardrail. */
public class GitGuideCoverageTest
{
    @Before
    public void setUp()
    {
        GuideLoader.clearCache();
    }

    @After
    public void tearDown()
    {
        GuideLoader.clearCache();
    }

    @Test
    public void testGuideNamesEveryBlockedOption()
    {
        String guide = GuideLoader.load(GitTool.NAME);
        List<String> missing = new ArrayList<>();
        for (String flag : GitTool.BLOCKED_FLAGS)
        {
            // Whole-token match, not contains(): --config is a substring of --config-env and --exec
            // of --exec-path, so a plain containment check would report a flag as documented because
            // a DIFFERENT, longer flag happens to be - which is exactly the case this ratchet exists
            // to catch. The lookahead rejects any character that could continue a flag name.
            if (!Pattern.compile(Pattern.quote(flag) + "(?![A-Za-z0-9-])").matcher(guide).find()) //$NON-NLS-1$
            {
                missing.add(flag);
            }
        }
        Collections.sort(missing);

        assertTrue("The git guide is missing blocked options " + missing //$NON-NLS-1$
            + ". Document every GitTool.BLOCKED_FLAGS entry in guides/git.md.", //$NON-NLS-1$
            missing.isEmpty());
    }
}
