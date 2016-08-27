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

package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.load.generator.latency.LatencyListener;
import org.eclipse.jetty.load.generator.response.ResponseTimeListener;
import org.eclipse.jetty.toolchain.perf.PlatformTimer;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 *
 */
public class LoadGenerator
{

    private static final Logger LOGGER = Log.getLogger( LoadGenerator.class );

    private int users;

    private volatile int requestRate;

    private AtomicBoolean stop;

    private int selectors = 1;

    /**
     * target host scheme
     */
    private String scheme;

    /**
     * target host
     */
    private String host;

    /**
     * target host port
     */
    private int port;

    private LoadGeneratorProfile profile;

    private Transport transport;

    private SslContextFactory sslContextFactory;

    private List<Request.Listener> requestListeners;

    private ExecutorService executorService;

    private ExecutorService runnersExecutorService;

    private CopyOnWriteArrayList<HttpClient> clients = new CopyOnWriteArrayList<>();

    private Scheduler scheduler;

    private SocketAddressResolver socketAddressResolver;

    private List<LatencyListener> latencyListeners;

    private List<ResponseTimeListener> responseTimeListeners;

    private LoadGeneratorResultHandler _loadGeneratorResultHandler;

    private List<HttpProxy> httpProxies = new ArrayList<>(  );

    private HttpClientTransportSupplier<HttpClientTransport> httpClientTransportSupplier;

    public enum Transport
    {
        HTTP,
        HTTPS,
        H2C,
        H2,
        FCGI
    }

    LoadGenerator( int users, int requestRate, String host, int port, LoadGeneratorProfile profile )
    {
        this.users = users;
        this.requestRate = requestRate;
        this.host = host;
        this.port = port;
        this.stop = new AtomicBoolean( false );
        this.profile = profile;
    }

    //--------------------------------------------------------------
    //  getters
    //--------------------------------------------------------------

    public int getUsers()
    {
        return users;
    }

    public int getRequestRate()
    {
        return requestRate;
    }

    public void setRequestRate( int requestRate )
    {
        this.requestRate = requestRate;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public Transport getTransport()
    {
        return transport;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public int getSelectors()
    {
        return selectors;
    }

    public List<Request.Listener> getRequestListeners()
    {
        return requestListeners;
    }

    public AtomicBoolean getStop()
    {
        return stop;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public SocketAddressResolver getSocketAddressResolver()
    {
        return socketAddressResolver;
    }

    public LoadGeneratorProfile getProfile()
    {
        return profile;
    }

    public String getScheme()
    {
        return scheme;
    }

    //--------------------------------------------------------------
    //  component implementation
    //--------------------------------------------------------------

    /**
     * start the generator lifecycle (this doesn't send any requests but just start few internal components)
     */
    private LoadGenerator start()
    {
        this.scheme = scheme( this.transport );

        int parallelism = Math.min( Runtime.getRuntime().availableProcessors(), getUsers() );

        this.executorService = Executors.newWorkStealingPool( parallelism );

        this.runnersExecutorService = Executors.newWorkStealingPool( parallelism );

        _loadGeneratorResultHandler =
            new LoadGeneratorResultHandler( responseTimeListeners, latencyListeners );

        return this;

    }

    /**
     * interrupt (clear resources) the generator lifecycle
     */
    public LoadGenerator interrupt()
    {
        this.stop.set( true );
        try
        {
            this.runnersExecutorService.shutdown();
            // wait the end?
            while( !runnersExecutorService.isTerminated()) {
                Thread.sleep( 2 );
            }

            this.executorService.shutdown();

            // wait the end?
            while( !executorService.isTerminated()) {
                Thread.sleep( 2 );
            }

            if ( latencyListeners != null )
            {
                for ( LatencyListener latencyListener : latencyListeners )
                {
                    latencyListener.onLoadGeneratorStop();
                }
            }
            if (responseTimeListeners != null)
            {
                for (ResponseTimeListener responseTimeListener : responseTimeListeners)
                {
                    responseTimeListener.onLoadGeneratorStop();
                }
            }
            for ( HttpClient httpClient : this.clients )
            {
                httpClient.stop();
            }
        }
        catch ( Exception e )
        {
            LOGGER.warn( e.getMessage(), e );
            throw new RuntimeException( e.getMessage(), e.getCause() );
        }
        return this;
    }

    /**
     * run the defined load (users / request numbers)
     */
    public void run()
        throws Exception
    {

        final List<Request.Listener> listeners = new ArrayList<>( getRequestListeners() );

        listeners.add( _loadGeneratorResultHandler );

        executorService.submit( () ->
            {
                HttpClientTransport httpClientTransport = httpClientTransportSupplier.get();

                for ( int i = getUsers(); i > 0; i-- )
                {
                    try
                    {
                        HttpClient httpClient = newHttpClient( httpClientTransport, getSslContextFactory() );
                        // TODO dynamic depending on the rate??
                        //httpClient.setMaxRequestsQueuedPerDestination( 2048 );
                        httpClient.setSocketAddressResolver( getSocketAddressResolver() );
                        httpClient.getRequestListeners().addAll( listeners );
                        if (this.httpProxies != null) {
                            httpClient.getProxyConfiguration().getProxies().addAll( this.httpProxies );
                        }
                        clients.add( httpClient );

                        LoadGeneratorRunner loadGeneratorRunner = //
                            new LoadGeneratorRunner( httpClient, this, _loadGeneratorResultHandler );

                        LoadGenerator.this.runnersExecutorService.submit( loadGeneratorRunner );
                    }
                    catch ( Exception e )
                    {
                        LOGGER.warn( "ignore exception", e );
                    }
                }

                try
                {
                    while ( !LoadGenerator.this.stop.get() )
                    {
                        // wait until stopped
                        Thread.sleep( 1 );
                    }
                }
                catch ( Throwable e )
                {
                    LOGGER.warn( "ignore exception", e );
                }
                LOGGER.debug( "exit run lambda" );
            }
        );
    }

    public void run( long time, TimeUnit timeUnit )
        throws Exception
    {
        this.run();
        PlatformTimer.detect().sleep( timeUnit.toMicros( time ) );
        this.interrupt();
    }


    protected HttpClient newHttpClient( HttpClientTransport transport, SslContextFactory sslContextFactory )
        throws Exception
    {
        HttpClient httpClient = new HttpClient( transport, sslContextFactory );
        switch ( this.getTransport() )
        {
            case HTTP:
            case HTTPS:
            {
                httpClient.setMaxConnectionsPerDestination( 7 );
            }
            case H2C:
            case H2:
            {
                httpClient.setMaxConnectionsPerDestination( 1 );
            }
            /*
            TODO
            case FCGI:
            {

            }
            */
            default:
            {
                // nothing this weird case already handled by #provideClientTransport
            }

        }

        if ( this.getScheduler() != null )
        {
            httpClient.setScheduler( this.getScheduler() );
        }

        // FIXME weird circularity
        transport.setHttpClient( httpClient );
        httpClient.start();

        return httpClient;
    }

    static String scheme( LoadGenerator.Transport transport)
    {
        switch ( transport )
        {
            case HTTP:
            case H2C:
            case FCGI:
                return HttpScheme.HTTP.asString();
            case HTTPS:
            case H2:
                return HttpScheme.HTTPS.asString();
            default:
                throw new IllegalArgumentException( "unknow scheme" );
        }

    }


    //--------------------------------------------------------------
    //  Builder
    //--------------------------------------------------------------

    public static class Builder
    {

        private int users;

        private int requestRate;

        private String host;

        private int port;

        private Transport transport;

        private SslContextFactory sslContextFactory;

        private int selectors = 1;

        private List<Request.Listener> requestListeners;

        private Scheduler httpScheduler;

        private SocketAddressResolver socketAddressResolver;

        private LoadGeneratorProfile profile;

        private List<LatencyListener> latencyListeners;

        private List<ResponseTimeListener> responseTimeListeners;

        private List<HttpProxy> httpProxies;

        private HttpClientTransportSupplier<HttpClientTransport> httpClientTransportSupplier;

        public Builder()
        {
            // no op
        }

        public Builder users( int users )
        {
            this.users = users;
            return this;
        }

        /**
         * @param requestRate number of requests per second
         * @return {@link Builder}
         */
        public Builder requestRate( int requestRate )
        {
            this.requestRate = requestRate;
            return this;
        }

        public Builder host( String host )
        {
            this.host = host;
            return this;
        }

        public Builder port( int port )
        {
            this.port = port;
            return this;
        }

        public Builder transport( Transport transport )
        {
            this.transport = transport;
            return this;
        }

        public Builder sslContextFactory( SslContextFactory sslContextFactory )
        {
            this.sslContextFactory = sslContextFactory;
            return this;
        }

        public Builder selectors( int selectors )
        {
            this.selectors = selectors;
            return this;
        }

        public Builder requestListeners( Request.Listener... requestListeners )
        {
            this.requestListeners = new ArrayList<>( Arrays.asList( requestListeners ) );
            return this;
        }

        public Builder scheduler( Scheduler scheduler )
        {
            this.httpScheduler = scheduler;
            return this;
        }

        public Builder httpClientSocketAddressResolver( SocketAddressResolver socketAddressResolver )
        {
            this.socketAddressResolver = socketAddressResolver;
            return this;
        }

        public Builder loadProfile( LoadGeneratorProfile loadGeneratorProfile )
        {
            this.profile = loadGeneratorProfile;
            return this;
        }

        public Builder latencyListeners( LatencyListener... latencyListeners )
        {
            this.latencyListeners = new ArrayList<>( Arrays.asList( latencyListeners ) );
            return this;
        }

        public Builder responseTimeListeners( ResponseTimeListener... responseTimeListeners )
        {
            this.responseTimeListeners = new ArrayList<>( Arrays.asList( responseTimeListeners ));
            return this;
        }

        public Builder httpProxies( HttpProxy... httpProxies )
        {
            this.httpProxies = new ArrayList<>( Arrays.asList( httpProxies ) );
            return this;
        }

        public Builder httppClientTransportSupplier (HttpClientTransportSupplier<HttpClientTransport> httpClientTransportSupplier) {
            this.httpClientTransportSupplier = httpClientTransportSupplier;
            return this;
        }

        public LoadGenerator build()
        {
            this.validate();
            LoadGenerator loadGenerator =
                new LoadGenerator( users, requestRate, host, port, this.profile );
            loadGenerator.transport = this.transport;
            loadGenerator.requestListeners = this.requestListeners == null ? new ArrayList<>() // //
                : this.requestListeners;
            loadGenerator.sslContextFactory = sslContextFactory;
            loadGenerator.selectors = selectors;
            loadGenerator.scheduler = httpScheduler;
            loadGenerator.socketAddressResolver = socketAddressResolver == null ? //
                new SocketAddressResolver.Sync() : socketAddressResolver;
            loadGenerator.latencyListeners = latencyListeners;
            loadGenerator.responseTimeListeners = responseTimeListeners;
            loadGenerator.httpProxies = httpProxies;
            loadGenerator.httpClientTransportSupplier = httpClientTransportSupplier;
            return loadGenerator.start();
        }

        public void validate()
        {
            if ( users < 1 )
            {
                throw new IllegalArgumentException( "users number must be at least 1" );
            }

            if ( requestRate < 0 )
            {
                throw new IllegalArgumentException( "users number must be at least 0" );
            }

            if ( StringUtil.isBlank( host ) )
            {
                throw new IllegalArgumentException( "host cannot be null or blank" );
            }

            if ( port < 1 )
            {
                throw new IllegalArgumentException( "port must be a positive integer" );
            }

            if ( this.profile == null )
            {
                throw new IllegalArgumentException( "a profile is mandatory" );
            }

        }

    }

}
