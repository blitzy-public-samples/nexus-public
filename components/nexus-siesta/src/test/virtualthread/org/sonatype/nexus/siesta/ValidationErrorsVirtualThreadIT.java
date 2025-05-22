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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * This test class extends the standard validation error tests to verify that
 * validation error handling works correctly with Virtual Threads and can benefit
 * from the improved concurrency and scalability they provide.
 */
public class ValidationErrorsVirtualThreadIT
    extends VirtualThreadSiestaTestSupport
{
  private static final int CONCURRENT_USERS = 50;
  private static final int TEST_DURATION_SECONDS = 5;
  private static final int LARGE_PAYLOAD_SIZE = 10000;
  
  /**
   * Basic test for multiple manual validations with XML media type.
   */
  @Test
  public void put_multiple_manual_validations_XML() throws Exception {
    put_multiple_manual_validations(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
  }

  /**
   * Basic test for multiple manual validations with JSON media type.
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
   * Basic test for single manual validation with XML media type.
   */
  @Test
  public void put_single_manual_validation_XML() throws Exception {
    put_single_manual_validation(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
  }

  /**
   * Basic test for single manual validation with JSON media type.
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
   * Tests high-concurrency validation error handling with Virtual Threads.
   * 
   * This test simulates multiple concurrent users sending invalid requests
   * and verifies that all validation errors are handled correctly.
   */
  @Test
  public void concurrent_validation_errors_handling() throws Exception {
    final int concurrentRequests = 100;
    final AtomicInteger successCount = new AtomicInteger(0);
    final CountDownLatch latch = new CountDownLatch(concurrentRequests);
    
    // Use Virtual Threads for concurrent requests
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent requests
      List<Future<?>> futures = IntStream.range(0, concurrentRequests)
          .mapToObj(i -> executor.submit(() -> {
            try {
              UserXO sent = new UserXO();
              
              // Alternate between single and multiple validation endpoints
              String endpoint = i % 2 == 0 ? "validationErrors/manual/single" : "validationErrors/manual/multiple";
              
              // Alternate between XML and JSON media types
              MediaType mediaType = i % 2 == 0 ? APPLICATION_XML_TYPE : APPLICATION_JSON_TYPE;
              MediaType expectedResponseType = i % 2 == 0 ? 
                  VND_VALIDATION_ERRORS_V1_XML_TYPE : VND_VALIDATION_ERRORS_V1_JSON_TYPE;
              
              Response response = client().target(url(endpoint)).request()
                  .accept(mediaType)
                  .put(Entity.entity(sent, mediaType), Response.class);
              
              // Verify response status
              if (response.getStatusInfo().equals(Status.BAD_REQUEST) && 
                  response.getMediaType().equals(expectedResponseType)) {
                
                // Verify validation errors
                List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
                int expectedErrorCount = endpoint.endsWith("single") ? 1 : 2;
                
                if (errors.size() == expectedErrorCount) {
                  successCount.incrementAndGet();
                }
              }
            } 
            catch (Exception e) {
              log.error("Error in concurrent validation test", e);
            }
            finally {
              latch.countDown();
            }
          }))
          .collect(Collectors.toList());
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all requests were successful
      assertEquals("All concurrent validation requests should be handled correctly", 
          concurrentRequests, successCount.get());
    }
  }
  
  /**
   * Tests validation error handling with varying payload sizes.
   * 
   * This test verifies that validation error handling works correctly with
   * different payload sizes, including very large payloads.
   */
  @Test
  public void validation_errors_with_varying_payload_sizes() throws Exception {
    // Test with small payload
    UserXO smallPayload = new UserXO();
    testValidationWithPayload("Small payload", smallPayload);
    
    // Test with medium payload
    UserXO mediumPayload = new UserXO();
    mediumPayload.setDescription(generateString(1000));
    testValidationWithPayload("Medium payload", mediumPayload);
    
    // Test with large payload
    UserXO largePayload = new UserXO();
    largePayload.setDescription(generateString(LARGE_PAYLOAD_SIZE));
    testValidationWithPayload("Large payload", largePayload);
  }
  
  private void testValidationWithPayload(String testName, UserXO payload) {
    log.info("Testing validation with {}", testName);
    
    Response response = client().target(url("validationErrors/manual/multiple")).request()
        .accept(APPLICATION_JSON_TYPE)
        .put(Entity.entity(payload, APPLICATION_JSON_TYPE), Response.class);
    
    assertThat(response.getStatusInfo(), is(equalTo((StatusType)Status.BAD_REQUEST)));
    assertThat(response.getMediaType(), is(equalTo(VND_VALIDATION_ERRORS_V1_JSON_TYPE)));
    
    List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
    assertThat(errors, hasSize(2));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for validation error handling.
   * 
   * This test measures the throughput and latency of validation error handling
   * with both platform threads and virtual threads under high concurrency.
   */
  @Test
  public void compare_validation_error_performance() throws Exception {
    // Create a payload for testing
    UserXO payload = new UserXO();
    payload.setDescription(generateString(500)); // Medium-sized payload
    
    // Compare performance for single validation endpoint
    Map<String, PerformanceMetrics> singleValidationMetrics = compareThreadPerformance(
        "validationErrors/manual/single",
        () -> client().target(url("validationErrors/manual/single")).request()
            .accept(APPLICATION_JSON_TYPE)
            .put(Entity.entity(payload, APPLICATION_JSON_TYPE)),
        CONCURRENT_USERS,
        TEST_DURATION_SECONDS);
    
    // Compare performance for multiple validation endpoint
    Map<String, PerformanceMetrics> multipleValidationMetrics = compareThreadPerformance(
        "validationErrors/manual/multiple",
        () -> client().target(url("validationErrors/manual/multiple")).request()
            .accept(APPLICATION_JSON_TYPE)
            .put(Entity.entity(payload, APPLICATION_JSON_TYPE)),
        CONCURRENT_USERS,
        TEST_DURATION_SECONDS);
    
    // Verify that virtual threads provide better throughput
    assertThat("Virtual threads should handle more requests than platform threads for single validation",
        singleValidationMetrics.get("virtual").getTotalRequests(),
        greaterThan(singleValidationMetrics.get("platform").getTotalRequests()));
    
    assertThat("Virtual threads should handle more requests than platform threads for multiple validation",
        multipleValidationMetrics.get("virtual").getTotalRequests(),
        greaterThan(multipleValidationMetrics.get("platform").getTotalRequests()));
    
    // Verify that virtual threads provide better or comparable latency
    assertThat("Virtual threads should have lower or comparable average response time for single validation",
        singleValidationMetrics.get("virtual").getAverageResponseTime(),
        lessThan(singleValidationMetrics.get("platform").getAverageResponseTime() * 1.1)); // Allow 10% margin
    
    assertThat("Virtual threads should have lower or comparable average response time for multiple validation",
        multipleValidationMetrics.get("virtual").getAverageResponseTime(),
        lessThan(multipleValidationMetrics.get("platform").getAverageResponseTime() * 1.1)); // Allow 10% margin
  }
  
  /**
   * Tests concurrent validation error handling with mixed media types.
   * 
   * This test simulates multiple concurrent users sending invalid requests
   * with different media types (XML and JSON) and verifies that all validation
   * errors are handled correctly.
   */
  @Test
  public void concurrent_validation_with_mixed_media_types() throws Exception {
    // Run a load test with mixed media types
    PerformanceMetrics metrics = runLoadTest(
        "validationErrors/manual/multiple",
        () -> {
          // Randomly choose between XML and JSON
          boolean useXml = Math.random() < 0.5;
          MediaType mediaType = useXml ? APPLICATION_XML_TYPE : APPLICATION_JSON_TYPE;
          
          UserXO payload = new UserXO();
          return client().target(url("validationErrors/manual/multiple")).request()
              .accept(mediaType)
              .put(Entity.entity(payload, mediaType));
        },
        CONCURRENT_USERS,
        TEST_DURATION_SECONDS);
    
    // Verify that all requests were handled successfully
    assertEquals("All requests should have been processed", 
        metrics.getTotalRequests(), metrics.getSuccessfulRequests());
    
    // Verify that we handled a significant number of requests
    assertThat("Should handle a significant number of concurrent requests",
        metrics.getTotalRequests(), greaterThan(100L));
  }
  
  /**
   * Generates a string of the specified length for testing.
   */
  private String generateString(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append((char) ('a' + (i % 26)));
    }
    return sb.toString();
  }
}