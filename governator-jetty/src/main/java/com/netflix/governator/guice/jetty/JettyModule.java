
package com.netflix.governator.guice.jetty;

import java.util.EnumSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.ProvisionException;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.servlet.GuiceFilter;
import com.netflix.governator.AbstractLifecycleListener;
import com.netflix.governator.LifecycleManager;
import com.netflix.governator.LifecycleShutdownSignal;
import com.netflix.governator.spi.LifecycleListener;

/**
 * Installing JettyModule will create a Jetty web server within the context
 * of the Injector and will use servlet and filter bindings from any additionally
 * installed ServletModule.
 * 
 * 
 * <pre>
 * {@code
    public static void main(String args[]) throws Exception {
        Governator.createInjector(
                new SampleServletModule(), 
                new ShutdownHookModule(), 
                new JettyModule())
                .awaitTermination();
    }
 * }
 * 
 * To change Jetty's configuration provide an override binding for JettyConfig.class.
 * 
 * <pre>
 * {@code 
    public static void main(String args[]) throws Exception {
        Governator.createInjector(
                new SampleServletModule(), 
                new ShutdownHookModule(), 
                Modules.override(new JettyModule())
                       .with(new AbstractModule() {
                           @Overrides
                           private void configure() {}
                           
                           @Provider
                           @Singleton 
                           private JettyConfig getConfig() {
                               DefaultJettyConfig config = new DefaultJettyConfig();
                               config.setPort(80);
                               return config;
                           }
                       })
                .awaitTermination());
    }
 * }
 * </pre>
 * 
 * Note that only one Jetty server may be created in an Injector 
 * 
 * @author elandau
 *
 */
public final class JettyModule extends AbstractModule {
    private final static Logger LOG = LoggerFactory.getLogger(JettyModule.class);
    
    /**
     * Eager singleton to start the Jetty Server
     * 
     * @author elandau
     */
    @Singleton
    public static class JettyRunner {
        private final Server server;
        private final int port;

        @Inject
        public JettyRunner(Server server, final LifecycleManager manager) {
            this.server = server;
            LOG.info("Jetty server starting");
            try {
                server.start();
                port = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
                LOG.info("Jetty server on port {} started", port);
            } catch (Exception e) {
                try {
                    server.stop();
                }
                catch (Exception e2) {
                }
                throw new ProvisionException("Jetty server failed to start", e);
            }
        }
        
        public boolean isRunning() {
            return server.isRunning();
        }
        
        public int getLocalPort() {
            return this.port;
        }
    }
    
    @Singleton
    static class OptionalJettyConfig {
        
        @com.google.inject.Inject(optional=true)
        private JettyConfig jettyConfig;
        
        public JettyConfig getJettyConfig() {
           return jettyConfig != null ? jettyConfig : new DefaultJettyConfig();
        }
    }
    
    /**
     * LifecycleListener to stop Jetty Server.  This will catch shutting down 
     * Jetty when notified only through LifecycleManager#shutdown() and not via the 
     * LifecycleEvent#shutdown().
     * 
     * @author elandau
     *
     */
    @Singleton
    public static class JettyShutdown extends AbstractLifecycleListener {
        private Server server;
        
        @Inject
        public JettyShutdown(Server server) {
            this.server = server;
        }
        
        @Override
        public void onStopped(Throwable optionalError) {
            LOG.info("Jetty Server shutting down");
            try {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            server.stop();
                            LOG.info("Jetty Server shut down");
                        } catch (Exception e) {
                            LOG.warn("Failed to shut down Jetty server", e);
                        }
                    }
                });
                t.start();
            } catch (Exception e) {
                LOG.warn("Error shutting down Jetty server");
            }
        }
    }
    
    @Override
    protected void configure() {
        bind(JettyRunner.class).asEagerSingleton();
        Multibinder.newSetBinder(binder(), LifecycleListener.class).addBinding().to(JettyShutdown.class);
        bind(LifecycleShutdownSignal.class).to(JettyLifecycleShutdownSignal.class);
        Multibinder.newSetBinder(binder(), JettyConnectorProvider.class);
    }
    
    @Provides
    @Singleton
    private Server getServer(OptionalJettyConfig optionalConfig, Set<JettyConnectorProvider> jettyConnectors) {
        JettyConfig config = optionalConfig.getJettyConfig();
        Server server = new Server(config.getPort());
        Resource staticResourceBase = Resource.newClassPathResource(config.getResourceBase());
        if (staticResourceBase != null) {
            // Set up a full web app since we have static content. We require the app to have its static content
            // under src/main/webapp and any other static resources that are packaged into jars are expected under
            // META-INF/resources.
            WebAppContext webAppContext = new WebAppContext();
            // We want to fail fast if we don't have any root resources defined or we have other issues starting up.
            webAppContext.setThrowUnavailableOnStartupException(true);
            webAppContext.addFilter(GuiceFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
            webAppContext.addServlet(DefaultServlet.class, "/");
            webAppContext.setResourceBase("src/main/webapp");
            webAppContext.setContextPath("/");
            webAppContext.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*\\.jar$");
            webAppContext.setConfigurations(new Configuration[]{
                    new WebXmlConfiguration(),
                    new WebInfConfiguration(),
                    new MetaInfConfiguration(),
                    new FragmentConfiguration(),
            });
            server.setHandler(webAppContext);
        } else {
            // We don't have static content so just set up servlets.
            ServletContextHandler servletContextHandler = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);
            servletContextHandler.addFilter(GuiceFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
            servletContextHandler.addServlet(DefaultServlet.class, "/");
            servletContextHandler.setResourceBase("src/main/webapp");
        }

        if (jettyConnectors != null) {
            for (JettyConnectorProvider connectorProvider : jettyConnectors) {
                server.addConnector(connectorProvider.getConnector(server));
            }
        }

        return server;
    }
    
    @Provides
    @Singleton
    @Named("embeddedJettyPort")
    public Integer jettyPort(JettyRunner runner) {
        return runner.getLocalPort();
    }
    
    @Override
    public boolean equals(Object obj) {
        return JettyModule.class.equals(obj.getClass());
    }

    @Override
    public int hashCode() {
        return JettyModule.class.hashCode();
    }
}
