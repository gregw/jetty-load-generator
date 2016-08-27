package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;

import java.util.function.Supplier;

/**
 *
 */
public interface HttpClientTransportSupplier<T>
    extends Supplier<HttpClientTransport>
{
    static HttpClientTransport http( int selectors )
    {
        return new HttpClientTransportOverHTTP( selectors );
    }

    static HttpClientTransport https( int selectors )
    {
        return new HttpClientTransportOverHTTP( selectors );
    }

    static HttpClientTransport http2( int selectors )
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.setSelectors( selectors );
        return new HttpClientTransportOverHTTP2( http2Client );
    }

    static HttpClientTransport fcgi( int selectors )
    {
        return new HttpClientTransportOverFCGI( selectors, false, "" );
    }



}
