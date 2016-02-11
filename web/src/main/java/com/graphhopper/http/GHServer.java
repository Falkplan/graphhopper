/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.http;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.servlet.GuiceFilter;
import com.graphhopper.util.CmdArgs;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHolder;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple server similar to integration tests setup.
 */
public class GHServer
{
    public static void main( String[] args ) throws Exception
    {
        new GHServer(CmdArgs.read(args)).start();
    }

    private final CmdArgs args;
    private Server server;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public GHServer( CmdArgs args )
    {
        this.args = args;
    }

    public void start() throws Exception
    {
        Injector injector = Guice.createInjector(createModule());
        start(injector);
    }

    public void start( Injector injector ) throws Exception
    {
        ResourceHandler resHandler = new ResourceHandler();
        resHandler.setDirectoriesListed(false);
        resHandler.setWelcomeFiles(new String[]
        {
            "index.html"
        });
        resHandler.setResourceBase(args.get("jetty.resourcebase", "./src/main/webapp"));

        server = new Server();
        // getSessionHandler and getSecurityHandler should always return null
        ServletContextHandler servHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        servHandler.setContextPath("/");

        servHandler.addServlet(new ServletHolder(new InvalidRequestServlet()), "/*");

        FilterHolder guiceFilter = new FilterHolder(injector.getInstance(GuiceFilter.class));
        servHandler.addFilter(guiceFilter, "/*", EnumSet.allOf(DispatcherType.class));

        SelectChannelConnector connector0 = new SelectChannelConnector();
        int httpPort = args.getInt("jetty.port", 8989);
        String host = args.get("jetty.host", "");
        connector0.setPort(httpPort);        
        connector0.setRequestHeaderSize(16192);
        if (!host.isEmpty())
            connector0.setHost(host);

        server.addConnector(connector0);
        
        // SSL
        if (args.getBool("jetty.useSsl", false) 
                && args.get("jetty.keystorePath", null) != null 
                && args.get("jetty.keystorePass", null) != null 
                && args.get("jetty.keymanagerPass", null) != null 
                && args.get("jetty.truststorePath", null) != null
                && args.get("jetty.truststorePass", null) != null) {
            SslContextFactory sslFactory = new SslContextFactory();
            sslFactory.setKeyStorePath(args.get("jetty.keystorePath", ""));
            sslFactory.setKeyStorePassword(args.get("jetty.keystorePass", ""));
            sslFactory.setKeyManagerPassword(args.get("jetty.keymanagerPass", ""));
            sslFactory.setTrustStore(args.get("jetty.truststorePath", ""));
            sslFactory.setTrustStorePassword(args.get("jetty.truststorePass", ""));

            SslSelectChannelConnector connector1 = new SslSelectChannelConnector(sslFactory);
            connector1.setPort(443);      
            connector1.setRequestHeaderSize(16192);
            if (!host.isEmpty())
                connector1.setHost(host);

            server.addConnector(connector1);
        }

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]
        {
            resHandler, servHandler
        });
        server.setHandler(handlers);
        server.start();
        logger.info("Started server at HTTP " + host + ":" + httpPort);
    }

    protected Module createModule()
    {
        return new AbstractModule()
        {
            @Override
            protected void configure()
            {
                binder().requireExplicitBindings();

                install(new DefaultModule(args));
                install(new GHServletModule(args));

                bind(GuiceFilter.class);
            }
        };
    }

    public void stop()
    {
        if (server == null)
            return;

        try
        {
            server.stop();
        } catch (Exception ex)
        {
            logger.error("Cannot stop jetty", ex);
        }
    }
}
