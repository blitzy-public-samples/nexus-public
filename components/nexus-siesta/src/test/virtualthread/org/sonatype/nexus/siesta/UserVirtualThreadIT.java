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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status.Family;

import org.junit.Test;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_XML_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration test for the User REST endpoint using Java 21 Virtual Threads.
 * 
 * This test validates that the User resource functions correctly under high concurrency
 * with Virtual Threads and compares performance between platform and virtual threads.
 */
public class UserVirtualThreadIT
    extends VirtualThreadSiestaTestSupport
{
  private static final int CONCURRENT_USERS = 100;
  private static final int TEST_DURATION_SECONDS = 5;
  private static final int LARGE_PAYLOAD_SIZE = 10000;
  
  /**
   * Tests a single PUT operation with XML media type to verify basic functionality.
   */
  @Test
  public void put_happyPath_XML() throws Exception {
    UserXO sent = new UserXO().withName(UUID.randomUUID().toString());

    WebTarget target = client().target(url("user"));
    Response response = target.request()
        .accept(APPLICATION_XML_TYPE)
        .put(Entity.entity(sent, APPLICATION_XML_TYPE), Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getFamily(), equalTo(Family.SUCCESSFUL));

    UserXO received = response.readEntity(UserXO.class);
    assertThat(received, is(notNullValue()));
    assertThat(received.getName(), is(equalTo(sent.getName())));
  }

  /**
   * Tests a single PUT operation with JSON media type to verify basic functionality.
   */
  @Test
  public void put_happyPath_JSON() throws Exception {
    UserXO sent = new UserXO().withName(UUID.randomUUID().toString());

    WebTarget target = client().target(url("user"));
    Response response = target.request()
        .accept(APPLICATION_JSON_TYPE)
        .put(Entity.entity(sent, APPLICATION_JSON_TYPE), Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getFamily(), equalTo(Family.SUCCESSFUL));

    UserXO received = response.readEntity(UserXO.class);
    assertThat(received, is(notNullValue()));
    assertThat(received.getName(), is(equalTo(sent.getName())));
  }

  /**
   * Tests concurrent PUT operations with XML media type using Virtual Threads.
   * 
   * This test validates that the User endpoint can handle high concurrency
   * with Virtual Threads when processing XML payloads.
   */
  @Test
  public void concurrent_put_XML() throws Exception {
    int concurrentRequests = 50;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            UserXO user = new UserXO().withName(UUID.randomUUID().toString());
            
            WebTarget target = client().target(url("user"));
            Response response = target.request()
                .accept(APPLICATION_XML_TYPE)
                .put(Entity.entity(user, APPLICATION_XML_TYPE), Response.class);
            
            if (response.getStatusInfo().getFamily() == Family.SUCCESSFUL) {
              UserXO received = response.readEntity(UserXO.class);
              if (received != null && received.getName().equals(user.getName())) {
                successCount.incrementAndGet();
              } else {
                errorCount.incrementAndGet();
              }
            } else {
              errorCount.incrementAndGet();
            }
            
            response.close();
          } 
          catch (Exception e) {
            log.error("Error in concurrent PUT request", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    log("Concurrent XML PUT results - Success: {}, Errors: {}", successCount.get(), errorCount.get());
    assertThat("All requests should succeed", successCount.get(), equalTo(concurrentRequests));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
  }

  /**
   * Tests concurrent PUT operations with JSON media type using Virtual Threads.
   * 
   * This test validates that the User endpoint can handle high concurrency
   * with Virtual Threads when processing JSON payloads.
   */
  @Test
  public void concurrent_put_JSON() throws Exception {
    int concurrentRequests = 50;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            UserXO user = new UserXO().withName(UUID.randomUUID().toString());
            
            WebTarget target = client().target(url("user"));
            Response response = target.request()
                .accept(APPLICATION_JSON_TYPE)
                .put(Entity.entity(user, APPLICATION_JSON_TYPE), Response.class);
            
            if (response.getStatusInfo().getFamily() == Family.SUCCESSFUL) {
              UserXO received = response.readEntity(UserXO.class);
              if (received != null && received.getName().equals(user.getName())) {
                successCount.incrementAndGet();
              } else {
                errorCount.incrementAndGet();
              }
            } else {
              errorCount.incrementAndGet();
            }
            
            response.close();
          } 
          catch (Exception e) {
            log.error("Error in concurrent PUT request", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    log("Concurrent JSON PUT results - Success: {}, Errors: {}", successCount.get(), errorCount.get());
    assertThat("All requests should succeed", successCount.get(), equalTo(concurrentRequests));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
  }

  /**
   * Tests concurrent PUT operations with mixed media types (XML and JSON) using Virtual Threads.
   * 
   * This test validates that the User endpoint can handle high concurrency with
   * Virtual Threads when processing a mix of XML and JSON payloads.
   */
  @Test
  public void concurrent_put_mixed_media_types() throws Exception {
    int concurrentRequests = 100;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            UserXO user = new UserXO().withName(UUID.randomUUID().toString());
            
            // Alternate between XML and JSON
            MediaType mediaType = (index % 2 == 0) ? APPLICATION_XML_TYPE : APPLICATION_JSON_TYPE;
            
            WebTarget target = client().target(url("user"));
            Response response = target.request()
                .accept(mediaType)
                .put(Entity.entity(user, mediaType), Response.class);
            
            if (response.getStatusInfo().getFamily() == Family.SUCCESSFUL) {
              UserXO received = response.readEntity(UserXO.class);
              if (received != null && received.getName().equals(user.getName())) {
                successCount.incrementAndGet();
              } else {
                errorCount.incrementAndGet();
              }
            } else {
              errorCount.incrementAndGet();
            }
            
            response.close();
          } 
          catch (Exception e) {
            log.error("Error in concurrent PUT request", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    log("Concurrent mixed media type PUT results - Success: {}, Errors: {}", 
        successCount.get(), errorCount.get());
    assertThat("All requests should succeed", successCount.get(), equalTo(concurrentRequests));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
  }

  /**
   * Tests PUT operations with large payloads using Virtual Threads.
   * 
   * This test validates that the User endpoint can handle large payloads
   * efficiently with Virtual Threads, which is important for I/O-bound operations.
   */
  @Test
  public void put_large_payload() throws Exception {
    // Create a user with a large description
    StringBuilder largeDescription = new StringBuilder();
    IntStream.range(0, LARGE_PAYLOAD_SIZE).forEach(i -> largeDescription.append("X"));
    
    UserXO sent = new UserXO()
        .withName(UUID.randomUUID().toString())
        .withDescription(largeDescription.toString());

    WebTarget target = client().target(url("user"));
    Response response = target.request()
        .accept(APPLICATION_JSON_TYPE)
        .put(Entity.entity(sent, APPLICATION_JSON_TYPE), Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getFamily(), equalTo(Family.SUCCESSFUL));

    UserXO received = response.readEntity(UserXO.class);
    assertThat(received, is(notNullValue()));
    assertThat(received.getName(), is(equalTo(sent.getName())));
    assertThat(received.getDescription(), is(equalTo(sent.getDescription())));
    assertThat(received.getDescription().length(), is(equalTo(LARGE_PAYLOAD_SIZE)));
  }

  /**
   * Tests concurrent PUT operations with large payloads using Virtual Threads.
   * 
   * This test validates that the User endpoint can handle concurrent requests
   * with large payloads efficiently using Virtual Threads.
   */
  @Test
  public void concurrent_put_large_payload() throws Exception {
    int concurrentRequests = 20;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a large description template
    StringBuilder largeDescription = new StringBuilder();
    IntStream.range(0, LARGE_PAYLOAD_SIZE).forEach(i -> largeDescription.append("X"));
    String descriptionTemplate = largeDescription.toString();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            UserXO user = new UserXO()
                .withName(UUID.randomUUID().toString())
                .withDescription(descriptionTemplate);
            
            WebTarget target = client().target(url("user"));
            Response response = target.request()
                .accept(APPLICATION_JSON_TYPE)
                .put(Entity.entity(user, APPLICATION_JSON_TYPE), Response.class);
            
            if (response.getStatusInfo().getFamily() == Family.SUCCESSFUL) {
              UserXO received = response.readEntity(UserXO.class);
              if (received != null && 
                  received.getName().equals(user.getName()) &&
                  received.getDescription().length() == LARGE_PAYLOAD_SIZE) {
                successCount.incrementAndGet();
              } else {
                errorCount.incrementAndGet();
              }
            } else {
              errorCount.incrementAndGet();
            }
            
            response.close();
          } 
          catch (Exception e) {
            log.error("Error in concurrent large payload PUT request", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
    }
    
    log("Concurrent large payload PUT results - Success: {}, Errors: {}", 
        successCount.get(), errorCount.get());
    assertThat("All requests should succeed", successCount.get(), equalTo(concurrentRequests));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
  }

  /**
   * Compares performance between platform threads and virtual threads for the User endpoint.
   * 
   * This test runs load tests with both thread types and compares the results to validate
   * that Virtual Threads provide better performance for I/O-bound operations.
   */
  @Test
  public void compare_thread_performance() throws Exception {
    // Define the request supplier
    UserXO user = new UserXO().withName("performance-test");
    
    Map<String, PerformanceMetrics> results = compareThreadPerformance(
        "user",
        () -> {
          WebTarget target = client().target(url("user"));
          return target.request()
              .accept(APPLICATION_JSON_TYPE)
              .put(Entity.entity(user, APPLICATION_JSON_TYPE), Response.class);
        },
        CONCURRENT_USERS,
        TEST_DURATION_SECONDS
    );
    
    // Verify that virtual threads perform better than platform threads
    PerformanceMetrics platformMetrics = results.get("platform");
    PerformanceMetrics virtualMetrics = results.get("virtual");
    
    // Virtual threads should handle more requests
    assertThat("Virtual threads should handle more requests",
        virtualMetrics.getSuccessfulRequests(), greaterThan(platformMetrics.getSuccessfulRequests()));
    
    // Virtual threads should have lower average response time
    assertThat("Virtual threads should have lower average response time",
        virtualMetrics.getAverageResponseTime(), lessThan(platformMetrics.getAverageResponseTime()));
  }

  /**
   * Tests load with varying payload sizes to validate I/O performance with Virtual Threads.
   * 
   * This test runs load tests with different payload sizes to validate that Virtual Threads
   * provide consistent performance regardless of payload size.
   */
  @Test
  public void load_test_varying_payload_sizes() throws Exception {
    // Small payload test
    UserXO smallUser = new UserXO()
        .withName("small-payload")
        .withDescription("Small payload for testing");
    
    PerformanceMetrics smallPayloadMetrics = runLoadTest(
        "user-small",
        () -> {
          WebTarget target = client().target(url("user"));
          return target.request()
              .accept(APPLICATION_JSON_TYPE)
              .put(Entity.entity(smallUser, APPLICATION_JSON_TYPE), Response.class);
        },
        CONCURRENT_USERS,
        TEST_DURATION_SECONDS
    );
    
    // Large payload test
    StringBuilder largeDescription = new StringBuilder();
    IntStream.range(0, LARGE_PAYLOAD_SIZE).forEach(i -> largeDescription.append("X"));
    
    UserXO largeUser = new UserXO()
        .withName("large-payload")
        .withDescription(largeDescription.toString());
    
    PerformanceMetrics largePayloadMetrics = runLoadTest(
        "user-large",
        () -> {
          WebTarget target = client().target(url("user"));
          return target.request()
              .accept(APPLICATION_JSON_TYPE)
              .put(Entity.entity(largeUser, APPLICATION_JSON_TYPE), Response.class);
        },
        CONCURRENT_USERS,
        TEST_DURATION_SECONDS
    );
    
    log("Small payload metrics: {}", smallPayloadMetrics);
    log("Large payload metrics: {}", largePayloadMetrics);
    
    // Verify that both tests completed successfully
    assertThat("Small payload test should have successful requests",
        smallPayloadMetrics.getSuccessfulRequests(), greaterThan(0L));
    assertThat("Large payload test should have successful requests",
        largePayloadMetrics.getSuccessfulRequests(), greaterThan(0L));
    
    // Large payloads should have higher response times but still be reasonable
    assertThat("Large payload should have higher response time",
        largePayloadMetrics.getAverageResponseTime(), 
        greaterThan(smallPayloadMetrics.getAverageResponseTime()));
  }
}