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
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Support for Siesta tests.
 */
public class SiestaTestSupport
    extends TestSupport
{
  private ServletTester servletTester;

  private String url;

  private Client client;

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
    servletTester.start();

    // Use ResteasyClientBuilder for RESTEasy 6.2.7.Final compatibility
    client = ResteasyClientBuilder.newClient();
  }

  @AfterEach
  public void stopJetty() throws Exception {
    try {
      // Proper resource cleanup
      if (client != null) {
        client.close();
      }
    } finally {
      if (servletTester != null) {
        servletTester.stop();
      }
    }
  }

  /**
   * Creates a ThreadFactory that can be configured to use virtual threads when running on Java 21.
   * 
   * @param useVirtualThreads whether to use virtual threads (true) or platform threads (false)
   * @param namePrefix prefix for thread names
   * @return a ThreadFactory that creates either virtual or platform threads
   */
  protected ThreadFactory createThreadFactory(boolean useVirtualThreads, String namePrefix) {
    if (useVirtualThreads) {
      return Thread.ofVirtual().name(namePrefix, 0).factory();
    } else {
      return Thread.ofPlatform().name(namePrefix, 0).factory();
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