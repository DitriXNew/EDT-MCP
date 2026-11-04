// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.Hashtable;
import java.util.Optional;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler.IInfobaseSynchonizationQuestionContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchonizationQuestionHandler.InfobaseSynchonizationQuestionAnswer;

/** Unknown runtime questions have no unattended answer; never call the platform's UI handler. */
public final class HeadlessPlatformQuestions
{
    private HeadlessPlatformQuestions() { }

    public static ServiceRegistration<IInfobaseSynchonizationQuestionHandler> register(BundleContext context)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, Integer.MAX_VALUE);
        return context.registerService(IInfobaseSynchonizationQuestionHandler.class,
            HeadlessPlatformQuestions::reject, properties);
    }

    static Optional<InfobaseSynchonizationQuestionAnswer> reject(IInfobaseSynchonizationQuestionContext context)
    {
        // The native Designer adapter turns an unanswered question into DesignerQuestionException.
        return Optional.empty();
    }
}
