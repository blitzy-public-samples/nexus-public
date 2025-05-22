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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status.Family;

import org.junit.jupiter.api.Test;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_XML_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests related to happy paths for a resource.
 */
public class UserIT
    extends SiestaTestSupport
{
  private void put_happyPath(final MediaType mediaType) throws Exception {
    UserXO sent = new UserXO().withName(UUID.randomUUID().toString());

    WebTarget target = client().target(url("user"));
    Response response = target.request()
        .accept(mediaType)
        .put(Entity.entity(sent, mediaType), Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getFamily(), equalTo(Family.SUCCESSFUL));

    UserXO received = response.readEntity(UserXO.class);
    assertThat(received, is(notNullValue()));
    assertThat(received.getName(), is(equalTo(sent.getName())));
  }

  @Test
  public void put_happyPath_XML()
      throws Exception
  {
    put_happyPath(APPLICATION_XML_TYPE);
  }

  @Test
  public void put_happyPath_JSON()
      throws Exception
  {
    put_happyPath(APPLICATION_JSON_TYPE);
  }
  
  /**
   * Test concurrent requests using Java 21 Virtual Threads.
   * This test verifies that the user resource can handle multiple concurrent requests
   * by using Virtual Threads to make parallel calls to the API.
   */
  @Test
  public void put_concurrent_requests_with_virtual_threads() throws Exception {
    // Number of concurrent requests to make
    int concurrentRequests = 50;
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that creates a new virtual thread for each task
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Use a CountDownLatch to wait for all requests to complete
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    
    // Track any errors that occur during concurrent execution
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> errorMessages = new ArrayList<>();
    
    try {
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        executor.submit(() -> {
          try {
            // Create a unique user for each request
            UserXO user = new UserXO().withName(UUID.randomUUID().toString());
            
            // Create a target for the user resource
            WebTarget target = client().target(url("user"));
            
            // Make the request with proper content negotiation
            Response response = target.request()
                .accept(APPLICATION_JSON_TYPE)
                .put(Entity.entity(user, APPLICATION_JSON_TYPE), Response.class);
            
            // Verify the response status
            if (response.getStatusInfo().getFamily() != Family.SUCCESSFUL) {
              String errorMsg = "Request failed with status: " + response.getStatus();
              synchronized (errorMessages) {
                errorMessages.add(errorMsg);
              }
              errorCount.incrementAndGet();
            }
            
            // Verify the response body
            UserXO received = response.readEntity(UserXO.class);
            if (received == null || !user.getName().equals(received.getName())) {
              String errorMsg = "Response validation failed: expected=" + user.getName() + 
                  ", actual=" + (received != null ? received.getName() : "null");
              synchronized (errorMessages) {
                errorMessages.add(errorMsg);
              }
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            String errorMsg = "Exception during request: " + e.getMessage();
            synchronized (errorMessages) {
              errorMessages.add(errorMsg);
            }
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete (with a timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all requests completed within the timeout
      assertThat("All requests should complete within timeout", completed, is(true));
      
      // Verify no errors occurred
      if (errorCount.get() > 0) {
        StringBuilder errorMessage = new StringBuilder("Errors occurred during concurrent requests:\n");
        errorMessages.forEach(msg -> errorMessage.append(" - ").append(msg).append("\n"));
        throw new AssertionError(errorMessage.toString());
      }
      
      // Log success
      log("Successfully completed {} concurrent requests using virtual threads", concurrentRequests);
      
    } finally {
      // Shutdown the executor service
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }
}