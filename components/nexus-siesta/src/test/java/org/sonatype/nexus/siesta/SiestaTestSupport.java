/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.siesta;

import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.servlet.DispatcherType;
import javax.ws.rs.client.Client;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import org.eclipse.jetty.servlet.ServletTester;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Support for Siesta tests.
 */
public class SiestaTestSupport
    extends TestSupport
{
  private ServletTester servletTester;

  private String url;

  private Client client;
  
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void startJetty() throws Exception {
    servletTester = new ServletTester();
    servletTester.getContext().addEventListener(new GuiceServletContextListener()
    {
      final Injector injector = Guice.createInjector(new TestModule());

      @Override
      protected Injector getInjector() {
        return injector;
      }
    });

    // Configure ServletTester for Java 21 compatibility
    url = servletTester.createConnector(true) + TestModule.MOUNT_POINT;
    servletTester.addFilter(GuiceFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    servletTester.addServlet(DummyServlet.class, "/*");
    
    // Configure virtual threads if running on Java 21 or newer
    configureVirtualThreads();
    
    servletTester.start();

    // Use RESTEasy client builder for compatibility with RESTEasy 6.2.7.Final
    client = ResteasyClientBuilder.newClient();
  }

  @AfterEach
  public void stopJetty() throws Exception {
    try {
      if (client != null) {
        client.close();
      }
    } finally {
      if (servletTester != null) {
        servletTester.stop();
      }
      
      if (virtualThreadExecutor != null) {
        virtualThreadExecutor.shutdown();
      }
    }
  }
  
  /**
   * Configure virtual threads if running on Java 21 or newer.
   */
  private void configureVirtualThreads() {
    try {
      // Check if we're running on Java 21 or newer with virtual threads support
      Class<?> virtualThreadBuilderClass = Class.forName("java.lang.Thread$Builder$OfVirtual");
      if (virtualThreadBuilderClass != null) {
        // Create a virtual thread executor
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        // Configure ServletTester to use virtual threads if possible
        // This is a best-effort approach as the exact API might vary by Jetty version
        try {
          // Try to set virtual thread executor on the ServletTester if the method exists
          java.lang.reflect.Method setVirtualThreadsExecutorMethod = 
              servletTester.getClass().getMethod("setVirtualThreadsExecutor", ExecutorService.class);
          if (setVirtualThreadsExecutorMethod != null) {
            setVirtualThreadsExecutorMethod.invoke(servletTester, virtualThreadExecutor);
          }
        } catch (Exception e) {
          // Virtual thread configuration not supported in this Jetty version, continue with platform threads
          log.debug("Virtual thread configuration not supported in this Jetty version", e);
        }
      }
    } catch (ClassNotFoundException e) {
      // Running on Java version prior to 21, virtual threads not available
      log.debug("Virtual threads not available in this Java version");
    }
  }

  protected Client client() {
    return client;
  }

  protected String url() {
    return url;
  }

  protected String url(final String path) {
    return url + "/" + path;
  }
}