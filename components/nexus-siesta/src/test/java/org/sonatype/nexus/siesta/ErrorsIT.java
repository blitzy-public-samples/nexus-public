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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.rest.ExceptionMapperSupport;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests error handling.
 */
public class ErrorsIT
    extends SiestaTestSupport
{
  @Test
  public void errorResponseHasFaultId() throws Exception {
    WebTarget target = client().target(url("errors/406"));
    Response response = target.request().get(Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getStatusCode(), equalTo(406));
    String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
    log("Fault ID: {}", faultId);
    assertThat(faultId, notNullValue());
  }
  
  /**
   * Tests error handling with concurrent requests using Virtual Threads.
   * This test verifies that error responses include fault IDs for all concurrent requests,
   * demonstrating that the error handling mechanism works correctly under high concurrency
   * with Java 21's Virtual Threads.
   */
  @Test
  public void concurrentErrorResponsesWithVirtualThreads() throws Exception {
    // Skip test if not running on Java 21 or newer
    try {
      Class.forName("java.lang.Thread$Builder$OfVirtual");
    } catch (ClassNotFoundException e) {
      log("Skipping virtual thread test as Java 21 features are not available");
      return;
    }
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int requestCount = 100; // Number of concurrent requests
      CountDownLatch latch = new CountDownLatch(requestCount);
      AtomicInteger successCount = new AtomicInteger(0);
      List<String> faultIds = new ArrayList<>();
      
      // Submit concurrent requests
      for (int i = 0; i < requestCount; i++) {
        executor.submit(() -> {
          try {
            WebTarget target = client().target(url("errors/406"));
            Response response = target.request().get(Response.class);
            
            if (response.getStatusInfo().getStatusCode() == 406) {
              String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
              if (faultId != null) {
                synchronized (faultIds) {
                  faultIds.add(faultId);
                }
                successCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            log("Error in concurrent request: {}", e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      log("Completed: {}, Success count: {}, Fault IDs collected: {}", 
          completed, successCount.get(), faultIds.size());
      
      assertThat("All requests should complete", completed, equalTo(true));
      assertThat("All requests should succeed", successCount.get(), equalTo(requestCount));
      assertThat("All responses should have fault IDs", faultIds.size(), equalTo(requestCount));
    }
  }
}