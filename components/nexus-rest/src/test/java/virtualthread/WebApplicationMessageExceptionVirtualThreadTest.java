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
package virtualthread;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.sonatype.nexus.rest.ValidationErrorXO;
import org.sonatype.nexus.rest.WebApplicationMessageException;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link WebApplicationMessageException} behavior when executed in virtual threads.
 * Validates that exception handling, HTTP status code mapping, and error response generation
 * work correctly in the Java 21 virtual thread model.
 */
public class WebApplicationMessageExceptionVirtualThreadTest
{
  /**
   * Tests the constructor with media type when executed in a virtual thread.
   */
  @Test
  public void testConstructorInVirtualThread() throws Exception {
    executeInVirtualThread(() -> {
      WebApplicationMessageException exception = new WebApplicationMessageException(
          Response.Status.BAD_REQUEST, "Message", MediaType.APPLICATION_JSON);
      Response response = exception.getResponse();

      assertEquals(400, response.getStatus());

      Object entity = response.getEntity();

      assertInstanceOf(ValidationErrorXO.class, entity);
      assertEquals("Message", ((ValidationErrorXO) entity).getMessage());
      assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
      assertEquals(ImmutableList.of(MediaType.APPLICATION_JSON), response.getHeaders().get("Content-Type"));
    });
  }

  /**
   * Tests the constructor without media type when executed in a virtual thread.
   */
  @Test
  public void testConstructorNoMediaTypeInVirtualThread() throws Exception {
    executeInVirtualThread(() -> {
      WebApplicationMessageException exception = new WebApplicationMessageException(
          Response.Status.NOT_FOUND, "Message");
      Response response = exception.getResponse();

      assertEquals(404, response.getStatus());

      Object entity = response.getEntity();

      assertInstanceOf(ValidationErrorXO.class, entity);
      assertEquals("Message", ((ValidationErrorXO) entity).getMessage());
      assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
      assertEquals(ImmutableList.of(MediaType.TEXT_PLAIN), response.getHeaders().get("Content-Type"));
    });
  }

  /**
   * Tests exception handling with String Templates (Java 21 feature) in virtual threads.
   */
  @Test
  public void testStringTemplateErrorMessageInVirtualThread() throws Exception {
    executeInVirtualThread(() -> {
      String resourceId = "test-resource-123";
      String action = "update";
      
      // Using Java 21 String Template feature
      String errorMessage = STR."Resource \{resourceId} cannot be \{action}d";
      
      WebApplicationMessageException exception = new WebApplicationMessageException(
          Response.Status.FORBIDDEN, errorMessage, MediaType.APPLICATION_JSON);
      Response response = exception.getResponse();

      assertEquals(403, response.getStatus());

      Object entity = response.getEntity();

      assertInstanceOf(ValidationErrorXO.class, entity);
      assertEquals("Resource test-resource-123 cannot be updated", ((ValidationErrorXO) entity).getMessage());
      assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
    });
  }

  /**
   * Tests concurrent exception creation and handling in multiple virtual threads.
   */
  @Test
  public void testConcurrentExceptionHandlingInVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int statusCode = 400 + (i % 5); // Generate different status codes
        final String message = "Error message " + i;
        
        executor.submit(() -> {
          try {
            Response.Status status = Response.Status.fromStatusCode(statusCode);
            WebApplicationMessageException exception = new WebApplicationMessageException(
                status, message, MediaType.APPLICATION_JSON);
            Response response = exception.getResponse();

            // Verify the response has the correct status code
            assertEquals(statusCode, response.getStatus());

            // Verify the entity is a ValidationErrorXO with the correct message
            Object entity = response.getEntity();
            assertInstanceOf(ValidationErrorXO.class, entity);
            assertEquals(message, ((ValidationErrorXO) entity).getMessage());
          } 
          catch (Throwable t) {
            failure.set(t);
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Check if any thread failed
      if (failure.get() != null) {
        throw new AssertionError("Test failed in virtual thread", failure.get());
      }
    }
  }

  /**
   * Helper method to execute a runnable in a virtual thread and wait for its completion.
   */
  private void executeInVirtualThread(Runnable task) throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.ofVirtual().name("virtual-test-thread").start(() -> {
      try {
        task.run();
      } 
      catch (Throwable t) {
        exception.set(t);
      } 
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for virtual thread to complete");
    
    // If an exception occurred in the virtual thread, rethrow it
    if (exception.get() != null) {
      throw new AssertionError("Test failed in virtual thread", exception.get());
    }
  }
}