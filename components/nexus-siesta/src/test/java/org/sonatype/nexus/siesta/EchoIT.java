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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.ws.rs.client.WebTarget;

import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test use of {@link Echo} proxy to access {@link EchoResource}.
 */
public class EchoIT
    extends SiestaTestSupport
{
  @Test
  public void basic() throws Exception {
    WebTarget target = client().target(url());
    Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
    List<String> result = echo.get("hi");
    assertThat(result, notNullValue());
    assertThat(result, hasItem("foo=hi"));
  }
  
  /**
   * Test using RESTEasy 6.2.7.Final API with updated proxy creation.
   */
  @Test
  public void testWithUpdatedResteasyApi() throws Exception {
    WebTarget target = client().target(url());
    // Using the RESTEasy 6.2.7.Final API for proxy creation
    Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
    List<String> result = echo.get("hello");
    assertThat(result, notNullValue());
    assertThat(result, hasItem("foo=hello"));
  }
  
  /**
   * Test Echo resource behavior with Virtual Threads.
   * This test verifies that the Echo resource works correctly when accessed from a virtual thread.
   */
  @Test
  public void testWithVirtualThread() throws Exception {
    // Only run this test if we're on Java 21 or newer with virtual thread support
    try {
      // Check if we can access the virtual thread API
      Class.forName("java.lang.Thread$Builder$OfVirtual");
      
      // Create a virtual thread executor
      try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit the test to run in a virtual thread
        Future<List<String>> future = virtualExecutor.submit(() -> {
          WebTarget target = client().target(url());
          Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
          return echo.get("virtualThread");
        });
        
        // Get the result from the virtual thread
        List<String> result = future.get();
        assertThat(result, notNullValue());
        assertThat(result, hasItem("foo=virtualThread"));
      }
    } catch (ClassNotFoundException e) {
      // Running on Java version prior to 21, virtual threads not available
      System.out.println("Skipping virtual thread test as it requires Java 21 or newer");
    }
  }
}