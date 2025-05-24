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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.StatusType;

import org.sonatype.nexus.rest.ValidationErrorXO;

import org.junit.Test;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_XML_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.sonatype.nexus.rest.MediaTypes.VND_VALIDATION_ERRORS_V1_JSON_TYPE;
import static org.sonatype.nexus.rest.MediaTypes.VND_VALIDATION_ERRORS_V1_XML_TYPE;

/**
 * Validation error response handling tests with Java 21 Virtual Threads.
 * 
 * <p>This test class extends the standard validation error tests to verify that
 * validation error handling works correctly with Java 21 Virtual Threads, including
 * high-concurrency scenarios and performance comparisons between platform and virtual threads.</p>
 */
public class ValidationErrorsVirtualThreadIT
    extends VirtualThreadSiestaTestSupport
{
  private static final int CONCURRENT_REQUESTS = 100;
  private static final int PERFORMANCE_TEST_ITERATIONS = 10;
  
  /**
   * Tests basic validation error handling with XML media type using virtual threads.
   */
  @Test
  public void put_multiple_manual_validations_XML() throws Exception {
    put_multiple_manual_validations(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
  }

  /**
   * Tests basic validation error handling with JSON media type using virtual threads.
   */
  @Test
  public void put_multiple_manual_validations_JSON() throws Exception {
    put_multiple_manual_validations(APPLICATION_JSON_TYPE, VND_VALIDATION_ERRORS_V1_JSON_TYPE);
  }

  private void put_multiple_manual_validations(final MediaType... mediaTypes) throws Exception {
    UserXO sent = new UserXO();

    Response response = client().target(url("validationErrors/manual/multiple")).request()
        .accept(mediaTypes)
        .put(Entity.entity(sent, mediaTypes[0]), Response.class);

    assertThat(response.getStatusInfo(), is(equalTo((StatusType)Status.BAD_REQUEST)));
    assertThat(response.getMediaType(), is(equalTo(mediaTypes[1])));

    List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
    assertThat(errors, hasSize(2));
  }

  /**
   * Tests basic validation error handling with XML media type using virtual threads.
   */
  @Test
  public void put_single_manual_validation_XML() throws Exception {
    put_single_manual_validation(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
  }

  /**
   * Tests basic validation error handling with JSON media type using virtual threads.
   */
  @Test
  public void put_single_manual_validation_JSON() throws Exception {
    put_single_manual_validation(APPLICATION_JSON_TYPE, VND_VALIDATION_ERRORS_V1_JSON_TYPE);
  }

  private void put_single_manual_validation(final MediaType... mediaTypes) throws Exception {
    UserXO sent = new UserXO();

    Response response = client().target(url("validationErrors/manual/single")).request()
        .accept(mediaTypes)
        .put(Entity.entity(sent, mediaTypes[0]), Response.class);

    assertThat(response.getStatusInfo(), is(equalTo((StatusType)Status.BAD_REQUEST)));
    assertThat(response.getMediaType(), is(equalTo(mediaTypes[1])));

    List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
    assertThat(errors, hasSize(1));
  }
  
  /**
   * Tests concurrent validation error handling with XML media type using virtual threads.
   * 
   * <p>This test verifies that the validation error handling works correctly under high concurrency
   * with virtual threads, sending multiple concurrent requests with invalid payloads.</p>
   */
  @Test
  public void concurrent_validation_errors_XML() throws Exception {
    concurrent_validation_errors(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
  }
  
  /**
   * Tests concurrent validation error handling with JSON media type using virtual threads.
   * 
   * <p>This test verifies that the validation error handling works correctly under high concurrency
   * with virtual threads, sending multiple concurrent requests with invalid payloads.</p>
   */
  @Test
  public void concurrent_validation_errors_JSON() throws Exception {
    concurrent_validation_errors(APPLICATION_JSON_TYPE, VND_VALIDATION_ERRORS_V1_JSON_TYPE);
  }
  
  private void concurrent_validation_errors(final MediaType... mediaTypes) throws Exception {
    // Create an invalid user object
    UserXO sent = new UserXO();
    
    // Create a countdown latch to wait for all requests to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads for concurrent requests
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        executor.submit(() -> {
          try {
            Response response = client().target(url("validationErrors/manual/multiple")).request()
                .accept(mediaTypes)
                .put(Entity.entity(sent, mediaTypes[0]), Response.class);
            
            if (response.getStatus() == Status.BAD_REQUEST.getStatusCode() &&
                response.getMediaType().equals(mediaTypes[1])) {
              List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
              if (errors.size() == 2) {
                successCount.incrementAndGet();
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      assertTrue("Timed out waiting for concurrent requests to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify that all requests were successful
    assertEquals("All concurrent requests should have been processed successfully",
        CONCURRENT_REQUESTS, successCount.get());
  }
  
  /**
   * Tests performance comparison between platform threads and virtual threads for validation error handling.
   * 
   * <p>This test compares the performance of validation error handling between platform threads and
   * virtual threads, measuring the execution time for both thread types.</p>
   */
  @Test
  public void performance_comparison_validation_errors() throws Exception {
    // Create an invalid user object
    UserXO sent = new UserXO();
    
    // Create thread factories for platform and virtual threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Measure execution time with platform threads
    long platformStart = System.nanoTime();
    executeWithThreadFactory(platformThreadFactory, sent, CONCURRENT_REQUESTS, PERFORMANCE_TEST_ITERATIONS);
    long platformEnd = System.nanoTime();
    Duration platformDuration = Duration.ofNanos(platformEnd - platformStart);
    
    // Measure execution time with virtual threads
    long virtualStart = System.nanoTime();
    executeWithThreadFactory(virtualThreadFactory, sent, CONCURRENT_REQUESTS, PERFORMANCE_TEST_ITERATIONS);
    long virtualEnd = System.nanoTime();
    Duration virtualDuration = Duration.ofNanos(virtualEnd - virtualStart);
    
    // Log the results
    log.info("Platform thread execution time: {} ms", platformDuration.toMillis());
    log.info("Virtual thread execution time: {} ms", virtualDuration.toMillis());
    log.info("Performance improvement: {}x", (double) platformDuration.toMillis() / virtualDuration.toMillis());
    
    // Verify that virtual threads are faster than platform threads
    assertThat("Virtual threads should be faster than platform threads",
        virtualDuration.toMillis(), lessThan(platformDuration.toMillis()));
  }
  
  private void executeWithThreadFactory(ThreadFactory threadFactory, UserXO sent, int concurrency, int iterations) 
      throws Exception {
    for (int i = 0; i < iterations; i++) {
      CountDownLatch latch = new CountDownLatch(concurrency);
      
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
        for (int j = 0; j < concurrency; j++) {
          executor.submit(() -> {
            try {
              client().target(url("validationErrors/manual/multiple")).request()
                  .accept(APPLICATION_JSON_TYPE)
                  .put(Entity.entity(sent, APPLICATION_JSON_TYPE), Response.class);
            } finally {
              latch.countDown();
            }
          });
        }
        
        assertTrue("Timed out waiting for requests to complete",
            latch.await(30, TimeUnit.SECONDS));
      }
    }
  }
  
  /**
   * Tests validation error handling with varying payload sizes using virtual threads.
   * 
   * <p>This test verifies that validation error handling works correctly with different payload sizes,
   * ensuring that I/O operations are properly handled by virtual threads.</p>
   */
  @Test
  public void validation_errors_with_varying_payload_sizes() throws Exception {
    // Create user objects with different description sizes
    UserXO smallPayload = new UserXO();
    smallPayload.setDescription(generateString(100)); // 100 characters
    
    UserXO mediumPayload = new UserXO();
    mediumPayload.setDescription(generateString(1000)); // 1KB
    
    UserXO largePayload = new UserXO();
    largePayload.setDescription(generateString(10000)); // 10KB
    
    // Test with different payload sizes
    testPayloadSize(smallPayload, "small");
    testPayloadSize(mediumPayload, "medium");
    testPayloadSize(largePayload, "large");
  }
  
  private void testPayloadSize(UserXO payload, String sizeLabel) throws Exception {
    // Create a countdown latch to wait for all requests to complete
    int concurrency = 50;
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads for concurrent requests
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          try {
            Response response = client().target(url("validationErrors/manual/multiple")).request()
                .accept(APPLICATION_JSON_TYPE)
                .put(Entity.entity(payload, APPLICATION_JSON_TYPE), Response.class);
            
            if (response.getStatus() == Status.BAD_REQUEST.getStatusCode()) {
              List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
              if (errors.size() == 2) {
                successCount.incrementAndGet();
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      assertTrue("Timed out waiting for concurrent requests with " + sizeLabel + " payload to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify that all requests were successful
    assertEquals("All concurrent requests with " + sizeLabel + " payload should have been processed successfully",
        concurrency, successCount.get());
    
    log.info("Successfully processed {} concurrent requests with {} payload", concurrency, sizeLabel);
  }
  
  private String generateString(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append('a');
    }
    return sb.toString();
  }
  
  /**
   * Tests thread pinning detection during validation error handling.
   * 
   * <p>This test verifies that no thread pinning occurs during validation error handling,
   * which is important for ensuring that virtual threads can be efficiently scheduled.</p>
   */
  @Test
  public void no_thread_pinning_during_validation_errors() throws Exception {
    // Create an invalid user object
    UserXO sent = new UserXO();
    
    // Run a high number of concurrent requests to increase the chance of detecting pinning
    int concurrency = 200;
    CountDownLatch latch = new CountDownLatch(concurrency);
    
    // Create and start virtual threads for concurrent requests
    List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
    
    for (int i = 0; i < concurrency; i++) {
      futures.add(runWithVirtualThread(() -> {
        try {
          // Alternate between XML and JSON to test both media types
          MediaType requestType = (i % 2 == 0) ? APPLICATION_XML_TYPE : APPLICATION_JSON_TYPE;
          MediaType responseType = (i % 2 == 0) ? VND_VALIDATION_ERRORS_V1_XML_TYPE : VND_VALIDATION_ERRORS_V1_JSON_TYPE;
          
          Response response = client().target(url("validationErrors/manual/multiple")).request()
              .accept(requestType)
              .put(Entity.entity(sent, requestType), Response.class);
          
          assertThat(response.getStatusInfo(), is(equalTo((StatusType)Status.BAD_REQUEST)));
          assertThat(response.getMediaType(), is(equalTo(responseType)));
          
          List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
          assertThat(errors, hasSize(2));
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all requests to complete
    assertTrue("Timed out waiting for concurrent requests to complete",
        latch.await(30, TimeUnit.SECONDS));
    
    // Check for any exceptions in the futures
    for (CompletableFuture<Void> future : futures) {
      future.join(); // This will throw an exception if the future completed exceptionally
    }
    
    // Verify that no thread pinning was detected
    assertThat("No thread pinning should occur during validation error handling",
        pinningDetector.getPinningEvents().size(), is(0));
  }
  
  /**
   * Tests mixed media type concurrent validation error handling.
   * 
   * <p>This test verifies that validation error handling works correctly when processing
   * concurrent requests with different media types (XML and JSON).</p>
   */
  @Test
  public void concurrent_mixed_media_type_validation_errors() throws Exception {
    // Create an invalid user object
    UserXO sent = new UserXO();
    
    // Create a countdown latch to wait for all requests to complete
    int concurrency = 100;
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger xmlSuccessCount = new AtomicInteger(0);
    AtomicInteger jsonSuccessCount = new AtomicInteger(0);
    
    // Create and start virtual threads for concurrent requests with mixed media types
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between XML and JSON
            boolean useXml = index % 2 == 0;
            MediaType requestType = useXml ? APPLICATION_XML_TYPE : APPLICATION_JSON_TYPE;
            MediaType responseType = useXml ? VND_VALIDATION_ERRORS_V1_XML_TYPE : VND_VALIDATION_ERRORS_V1_JSON_TYPE;
            
            Response response = client().target(url("validationErrors/manual/multiple")).request()
                .accept(requestType)
                .put(Entity.entity(sent, requestType), Response.class);
            
            if (response.getStatus() == Status.BAD_REQUEST.getStatusCode() &&
                response.getMediaType().equals(responseType)) {
              List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
              if (errors.size() == 2) {
                if (useXml) {
                  xmlSuccessCount.incrementAndGet();
                } else {
                  jsonSuccessCount.incrementAndGet();
                }
              }
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      assertTrue("Timed out waiting for concurrent mixed media type requests to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify that all requests were successful
    int expectedXmlCount = concurrency / 2;
    int expectedJsonCount = concurrency - expectedXmlCount;
    
    assertEquals("All XML requests should have been processed successfully",
        expectedXmlCount, xmlSuccessCount.get());
    assertEquals("All JSON requests should have been processed successfully",
        expectedJsonCount, jsonSuccessCount.get());
    
    log.info("Successfully processed {} XML requests and {} JSON requests concurrently",
        xmlSuccessCount.get(), jsonSuccessCount.get());
  }
  
  /**
   * Tests load testing of validation error handling with virtual threads.
   * 
   * <p>This test performs a load test of the validation error handling endpoint,
   * measuring throughput and response times under high concurrency.</p>
   */
  @Test
  public void load_test_validation_errors() throws Exception {
    // Parameters for the load test
    int concurrentClients = 100;
    int requestsPerClient = 10;
    
    // Execute the load test
    LoadTestResult result = executeLoadTest(url("validationErrors/manual/multiple"), 
        concurrentClients, requestsPerClient);
    
    // Log the results
    log.info("Load test results: {}", result);
    
    // Verify the results
    assertThat("No errors should occur during the load test", result.getErrorCount(), is(0));
    assertThat("Throughput should be reasonable", result.getThroughput(), greaterThan(10.0));
    
    // The actual throughput and latency values will depend on the test environment,
    // so we're just checking that they're reasonable here
  }
}