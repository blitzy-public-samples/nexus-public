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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.StatusType;

import org.sonatype.nexus.rest.ValidationErrorXO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_XML_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.rest.MediaTypes.VND_VALIDATION_ERRORS_V1_JSON_TYPE;
import static org.sonatype.nexus.rest.MediaTypes.VND_VALIDATION_ERRORS_V1_XML_TYPE;

/**
 * Validation error response handling tests.
 */
public class ValidationErrorsIT
    extends SiestaTestSupport
{
  @Test
  public void put_multiple_manual_validations_XML() throws Exception {
    put_multiple_manual_validations(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
  }

  @Test
  public void put_multiple_manual_validations_JSON() throws Exception {
    put_multiple_manual_validations(APPLICATION_JSON_TYPE, VND_VALIDATION_ERRORS_V1_JSON_TYPE);
  }

  private void put_multiple_manual_validations(final MediaType... mediaTypes) throws Exception {
    UserXO sent = new UserXO();

    Response response = client().target(url("validationErrors/manual/multiple")).request()
        .accept(mediaTypes)
        .put(Entity.entity(sent, mediaTypes[0]), Response.class);

    try {
      assertThat(response.getStatusInfo(), is(equalTo((StatusType)Status.BAD_REQUEST)));
      assertThat(response.getMediaType(), is(equalTo(mediaTypes[1])));

      List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
      assertThat(errors, hasSize(2));
    } finally {
      response.close(); // Ensure connection is properly released
    }
  }

  @Test
  public void put_single_manual_validation_XML() throws Exception {
    put_single_manual_validation(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
  }

  @Test
  public void put_single_manual_validation_JSON() throws Exception {
    put_single_manual_validation(APPLICATION_JSON_TYPE, VND_VALIDATION_ERRORS_V1_JSON_TYPE);
  }

  private void put_single_manual_validation(final MediaType... mediaTypes) throws Exception {
    UserXO sent = new UserXO();

    Response response = client().target(url("validationErrors/manual/single")).request()
        .accept(mediaTypes)
        .put(Entity.entity(sent, mediaTypes[0]), Response.class);

    try {
      assertThat(response.getStatusInfo(), is(equalTo((StatusType)Status.BAD_REQUEST)));
      assertThat(response.getMediaType(), is(equalTo(mediaTypes[1])));

      List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
      assertThat(errors, hasSize(1));
    } finally {
      response.close(); // Ensure connection is properly released
    }
  }
  
  /**
   * Tests validation error handling with concurrent requests using Virtual Threads.
   * This test verifies that validation errors are properly handled when multiple
   * concurrent requests are made using Java 21's Virtual Threads feature.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void concurrent_validation_errors_with_virtual_threads() throws Exception {
    final int threadCount = 10;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    final List<Exception> exceptions = new ArrayList<>();
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create and submit tasks
      for (int i = 0; i < threadCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Alternate between XML and JSON requests
            if (taskId % 2 == 0) {
              validateConcurrentRequest(APPLICATION_XML_TYPE, VND_VALIDATION_ERRORS_V1_XML_TYPE);
            } else {
              validateConcurrentRequest(APPLICATION_JSON_TYPE, VND_VALIDATION_ERRORS_V1_JSON_TYPE);
            }
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    // Check if there were any exceptions
    if (!exceptions.isEmpty()) {
      throw new AssertionError("Test failed with " + exceptions.size() + " exceptions. First exception: ", 
          exceptions.get(0));
    }
    
    // Verify all threads completed successfully
    assertThat("All virtual threads should complete successfully", 
        successCount.get(), is(equalTo(threadCount)));
  }
  
  /**
   * Helper method to validate a request in the concurrent test.
   */
  private void validateConcurrentRequest(final MediaType contentType, final MediaType expectedResponseType) throws Exception {
    UserXO sent = new UserXO();
    WebTarget target = client().target(url("validationErrors/manual/multiple"));
    
    Response response = target.request()
        .accept(expectedResponseType)
        .put(Entity.entity(sent, contentType), Response.class);
    
    try {
      // Verify response status
      assertThat(response.getStatusInfo(), is(equalTo((StatusType)Status.BAD_REQUEST)));
      assertThat(response.getMediaType(), is(equalTo(expectedResponseType)));
      
      // Verify validation errors
      List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
      assertThat(errors, hasSize(2));
    } finally {
      response.close();
    }
  }
}