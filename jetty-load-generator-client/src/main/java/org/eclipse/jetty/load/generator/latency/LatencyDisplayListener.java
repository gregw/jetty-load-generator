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

package org.eclipse.jetty.load.generator.latency;

import org.HdrHistogram.Recorder;
import org.eclipse.jetty.load.generator.CollectorInformations;
import org.eclipse.jetty.load.generator.LoadGenerator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class LatencyDisplayListener
    implements LatencyListener
{

    private static final Logger LOGGER = Log.getLogger( LatencyDisplayListener.class );

    private final Recorder latencyRecorder = new Recorder( TimeUnit.MICROSECONDS.toNanos( 1 ), //
                                                           TimeUnit.MINUTES.toNanos( 1 ), //
                                                           3 );

    private ScheduledExecutorService scheduledExecutorService;

    private ValueListenerRunnable runnable;

    public LatencyDisplayListener( long initialDelay, long delay, TimeUnit timeUnit )
    {

        runnable = new ValueListenerRunnable( this.latencyRecorder );

        // FIXME configurable or using a shared one
        scheduledExecutorService = Executors.newScheduledThreadPool( 1 );
        scheduledExecutorService.scheduleWithFixedDelay( runnable, initialDelay, delay, timeUnit );
    }

    public LatencyDisplayListener( )
    {
       this( 0, 1, TimeUnit.SECONDS );
    }

    private static class ValueListenerRunnable
        implements Runnable
    {
        private final Recorder latencyRecorder;

        private ValueListenerRunnable( Recorder latencyRecorder )
        {
            this.latencyRecorder = latencyRecorder;
        }

        @Override
        public void run()
        {
            LOGGER.info( "latency value: {}", new CollectorInformations( this.latencyRecorder.getIntervalHistogram(), //
                                                                         CollectorInformations.InformationType.LATENCY ) );
        }
    }

    @Override
    public void onLatencyValue( long latencyValue )
    {
        this.latencyRecorder.recordValue( latencyValue );
    }


    @Override
    public void onLoadGeneratorStop()
    {
        scheduledExecutorService.shutdown();
        // last run
        runnable.run();
    }
}