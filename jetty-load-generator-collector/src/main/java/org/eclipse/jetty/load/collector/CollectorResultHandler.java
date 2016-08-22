//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.load.collector;

import org.eclipse.jetty.load.generator.CollectorInformations;

import java.util.Map;

/**
 *
 */
public interface CollectorResultHandler
{
    void handleLatencyInformations(CollectorInformations infos);

    /**
     *
     * @param responseTimePerPath  key is the http path, value is the reponse time information
     */
    void handleResponseTime( Map<String, CollectorInformations> responseTimePerPath );

}
