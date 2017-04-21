/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper GmbH licenses this file to you under the Apache License, 
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

import com.google.inject.*;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.ServletModule;
import com.graphhopper.GraphHopper;
import com.graphhopper.json.GHJson;
import com.graphhopper.json.GHJsonBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.util.CmdArgs;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHolder;
import java.util.EnumSet;
import javax.net.ssl.SSLParameters;
import javax.servlet.DispatcherType;
import org.eclipse.jetty.http.HttpVersion;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import java.io.IOException;
import java.util.EnumSet;
/**
 * Simple server similar to integration tests setup.
 */
public class GHServer {
    private final CmdArgs args;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Server server;

    public GHServer(CmdArgs args) {
        this.args = args;
    }

    public static void main(String[] args) throws Exception {
        new GHServer(CmdArgs.read(args)).start();
    }

    public void start() throws Exception {
        Injector injector = Guice.createInjector(createModule());
        start(injector);
    }

    public void start(Injector injector) throws Exception {
        ResourceHandler resHandler = new ResourceHandler();
        resHandler.setDirectoriesListed(false);
        resHandler.setWelcomeFiles(new String[]{
                "index.html"
        });
        resHandler.setResourceBase(args.get("jetty.resourcebase", "./web/src/main/webapp"));

        server = new Server();
        // getSessionHandler and getSecurityHandler should always return null
        ServletContextHandler servHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        servHandler.setErrorHandler(new GHErrorHandler());
        servHandler.setContextPath("/");

        servHandler.addServlet(new ServletHolder(new InvalidRequestServlet()), "/*");

        FilterHolder guiceFilter = new FilterHolder(injector.getInstance(GuiceFilter.class));
        servHandler.addFilter(guiceFilter, "/*", EnumSet.allOf(DispatcherType.class));

        ServerConnector connector0 = new ServerConnector(server);
        int httpPort = args.getInt("jetty.port", 8989);
        int httpsPort = args.getInt("jetty.sslport", 443);
        String host = args.get("jetty.host", "");
        connector0.setPort(httpPort);

        int requestHeaderSize = 16192;//args.getInt("jetty.request_header_size", -1);
        if (requestHeaderSize > 0)
            connector0.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setRequestHeaderSize(requestHeaderSize);

        if (!host.isEmpty())
            connector0.setHost(host);

        server.addConnector(connector0);
        
        // SSL
        if (args.getBool("jetty.usessl", false) 
                && args.get("jetty.keystorepath", null) != null 
                && args.get("jetty.keystorepass", null) != null 
                && args.get("jetty.keymanagerpass", null) != null 
                && args.get("jetty.truststorepath", null) != null
                && args.get("jetty.truststorepass", null) != null) {                        
            SslContextFactory sslFactory = new SslContextFactory();
            sslFactory.setKeyStorePath(args.get("jetty.keystorepath", ""));
            sslFactory.setKeyStorePassword(args.get("jetty.keystorepass", ""));
            sslFactory.setKeyManagerPassword(args.get("jetty.keymanagerpass", ""));
            sslFactory.setTrustStorePath(args.get("jetty.truststorepath", ""));
            sslFactory.setTrustStorePassword(args.get("jetty.truststorepass", ""));
            
            HttpConfiguration https_config = new HttpConfiguration();
            https_config.setSecureScheme("https");
            https_config.setSecurePort(httpsPort);
            https_config.setRequestHeaderSize(requestHeaderSize);
            
            SecureRequestCustomizer src = new SecureRequestCustomizer();
            src.setStsMaxAge(2000);
            src.setStsIncludeSubDomains(true);
            https_config.addCustomizer(src);

            ServerConnector connector1 = new ServerConnector(server,
            new SslConnectionFactory(sslFactory,HttpVersion.HTTP_1_1.asString()), new HttpConnectionFactory(https_config));
            connector1.setPort(httpsPort);   
            if (!host.isEmpty())
                connector1.setHost(host);

            server.addConnector(connector1);
        }

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{
                resHandler, servHandler
        });

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setIncludedMethods("GET", "POST");
        // Note: gzip only affects the response body like our previous 'GHGZIPHook' behaviour: http://stackoverflow.com/a/31565805/194609
        // If no mimeTypes are defined the content-type is "not 'application/gzip'", See also https://github.com/graphhopper/directions-api/issues/28 for pitfalls
        // gzipHandler.setIncludedMimeTypes();
        gzipHandler.setHandler(handlers);

        server.setHandler(gzipHandler);
        server.setStopAtShutdown(true);
        server.start();
        logger.info("Started server at HTTP " + host + ":" + httpPort);
        if (args.getBool("jetty.usessl", false) == true)
            logger.info("Started server at HTTPS " + host + ":" + httpsPort);
    }

    protected Module createModule() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                binder().requireExplicitBindings();
                if (args.has("gtfs.file")) {  // switch to different API implementation when using Pt
                    install(new PtModule(args));
                    // Close resources on exit. Ugly, but neither guice nor guice-servlet have a lifecycle API,
                    // so I have to abuse a Filter for that.
                    install(new ServletModule() {
                        @Override
                        protected void configureServlets() {
                            final Provider<GraphHopperStorage> graphHopperStorage = getProvider(GraphHopperStorage.class);
                            final Provider<LocationIndex> locationIndex = getProvider(LocationIndex.class);
                            filter("*").through(new Filter() {
                                @Override
                                public void init(FilterConfig filterConfig) throws ServletException {}
                                @Override
                                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                                    chain.doFilter(request, response);
                                }
                                @Override
                                public void destroy() {
                                    graphHopperStorage.get().close();
                                    locationIndex.get().close();
                                }
                            });
                        }
                    });
                } else {
                    install(new DefaultModule(args));
                    install(new ServletModule() {
                        @Override
                        protected void configureServlets() {
                            final Provider<GraphHopper> graphHopper = getProvider(GraphHopper.class);
                            filter("*").through(new Filter() {
                                @Override
                                public void init(FilterConfig filterConfig) throws ServletException {}
                                @Override
                                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                                    chain.doFilter(request, response);
                                }
                                @Override
                                public void destroy() {
                                    graphHopper.get().close();
                                }
                            });
                        }
                    });
                }
                install(new GHServletModule(args));

                bind(GuiceFilter.class);
            }
        };
    }

    public void stop() {
        if (server == null)
            return;

        try {
            server.stop();
        } catch (Exception ex) {
            logger.error("Cannot stop jetty", ex);
        }
    }
}
