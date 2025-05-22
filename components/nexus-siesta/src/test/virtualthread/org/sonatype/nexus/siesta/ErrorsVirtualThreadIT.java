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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.rest.ExceptionMapperSupport;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests error handling with Java 21 Virtual Threads.
 * 
 * This test extends the standard {@link ErrorsIT} to validate that error handling
 * endpoints function correctly under high concurrency with Virtual Threads.
 */
public class ErrorsVirtualThreadIT
    extends VirtualThreadSiestaTestSupport
{
  private static final int CONCURRENT_REQUESTS = 100;
  private static final int TEST_DURATION_SECONDS = 5;
  
  /**
   * Basic test to verify error responses have fault IDs.
   */
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
   * Tests error handling under high concurrency with Virtual Threads.
   */
  @Test
  public void highConcurrencyErrorHandling() throws Exception {
    // Run a load test against the error endpoint
    PerformanceMetrics metrics = runLoadTest(
        "errors/406",
        () -> target("errors/406").request().get(Response.class),
        CONCURRENT_REQUESTS,
        TEST_DURATION_SECONDS
    );
    
    // Verify we handled a significant number of requests
    assertThat(metrics.getTotalRequests(), greaterThan(100L));
    
    // Verify all requests returned the expected 406 status
    assertThat(metrics.getErrorCount(), equalTo(0L));
    
    // Verify response times are reasonable
    assertThat(metrics.getAverageResponseTime(), lessThan(500.0));
  }
  
  /**
   * Compares error handling performance between platform threads and virtual threads.
   */
  @Test
  public void compareErrorHandlingPerformance() throws Exception {
    // Compare performance between platform and virtual threads
    Map<String, PerformanceMetrics> results = compareThreadPerformance(
        "errors/406",
        () -> target("errors/406").request().get(Response.class),
        CONCURRENT_REQUESTS,
        TEST_DURATION_SECONDS
    );
    
    // Get the metrics for each thread type
    PerformanceMetrics platformMetrics = results.get("platform");
    PerformanceMetrics virtualMetrics = results.get("virtual");
    
    // Verify that virtual threads handled at least as many requests as platform threads
    assertThat(virtualMetrics.getTotalRequests(), greaterThan(platformMetrics.getTotalRequests() * 0.9));
    
    // Verify that both thread types had zero error rates
    assertThat(platformMetrics.getErrorRate(), equalTo(0.0));
    assertThat(virtualMetrics.getErrorRate(), equalTo(0.0));
  }
  
  /**
   * Tests that fault IDs are generated consistently under high concurrency.
   */
  @Test
  public void faultIdGenerationUnderConcurrency() throws Exception {
    final int requestCount = 100;
    final Set<String> faultIds = ConcurrentHashMap.newKeySet();
    final CountDownLatch latch = new CountDownLatch(requestCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent requests
      for (int i = 0; i < requestCount; i++) {
        executor.submit(() -> {
          try {
            // Send request to error endpoint
            Response response = target("errors/406").request().get(Response.class);
            
            // Verify status code
            if (response.getStatusInfo().getStatusCode() == 406) {
              // Get the fault ID
              String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
              if (faultId != null) {
                // Add to the set of fault IDs
                faultIds.add(faultId);
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            log.warn("Error during fault ID test: {}", e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    // Verify that all requests were successful
    assertThat(successCount.get(), equalTo(requestCount));
    
    // Verify that each request got a unique fault ID
    assertThat(faultIds.size(), equalTo(requestCount));
    
    log("Generated {} unique fault IDs across {} concurrent requests", faultIds.size(), requestCount);
  }
  
  /**
   * Tests different types of error responses under concurrent load.
   */
  @Test
  public void multipleErrorTypesUnderConcurrency() throws Exception {
    final int requestsPerType = 50;
    final String[] errorPaths = {
        "errors/NotFoundException",
        "errors/BadRequestException",
        "errors/406"
    };
    
    final Map<String, Set<Integer>> statusCodes = new ConcurrentHashMap<>();
    final CountDownLatch latch = new CountDownLatch(requestsPerType * errorPaths.length);
    
    // Initialize status code sets
    for (String path : errorPaths) {
      statusCodes.put(path, ConcurrentHashMap.newKeySet());
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // For each error path
      for (String path : errorPaths) {
        // Submit concurrent requests
        for (int i = 0; i < requestsPerType; i++) {
          final String errorPath = path;
          executor.submit(() -> {
            try {
              // Send request to error endpoint
              Response response = target(errorPath).request().get(Response.class);
              
              // Record the status code
              statusCodes.get(errorPath).add(response.getStatus());
              
              // Verify fault ID is present
              String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
              assertThat(faultId, notNullValue());
            }
            catch (Exception e) {
              log.warn("Error during multi-error test: {}", e.getMessage());
            }
            finally {
              latch.countDown();
            }
          });
        }
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    // Verify that each error path returned consistent status codes
    assertThat(statusCodes.get("errors/NotFoundException").size(), equalTo(1));
    assertThat(statusCodes.get("errors/BadRequestException").size(), equalTo(1));
    assertThat(statusCodes.get("errors/406").size(), equalTo(1));
    
    // Verify the expected status codes
    assertThat(statusCodes.get("errors/NotFoundException").contains(404), equalTo(true));
    assertThat(statusCodes.get("errors/BadRequestException").contains(400), equalTo(true));
    assertThat(statusCodes.get("errors/406").contains(406), equalTo(true));
    
    log("Verified consistent status codes across multiple error types under concurrent load");
  }
}