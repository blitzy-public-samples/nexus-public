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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link WebApplicationMessageException} behavior in virtual threads.
 * Ensures that exception mapping to HTTP status codes, validation error wrapping,
 * and error response generation function correctly when executed in virtual threads.
 */
public class WebApplicationMessageExceptionVirtualThreadTest
{
  /**
   * Tests the constructor that takes a Response.Status, a message, and a MediaType
   * when executed in a virtual thread.
   */
  @Test
  public void testConstructorInVirtualThread() throws ExecutionException, InterruptedException {
    AtomicReference<Response> responseRef = new AtomicReference<>();
    AtomicReference<Object> entityRef = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("constructor-test").start(() -> {
      WebApplicationMessageException exception = new WebApplicationMessageException(
          Response.Status.BAD_REQUEST, "Message", MediaType.APPLICATION_JSON);
      Response response = exception.getResponse();
      responseRef.set(response);
      entityRef.set(response.getEntity());
    });
    
    virtualThread.join();
    
    Response response = responseRef.get();
    Object entity = entityRef.get();
    
    assertEquals(400, response.getStatus());
    assertInstanceOf(ValidationErrorXO.class, entity);
    assertEquals("Message", ((ValidationErrorXO) entity).getMessage());
    assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
    assertEquals(ImmutableList.of(MediaType.APPLICATION_JSON), response.getHeaders().get("Content-Type"));
  }

  /**
   * Tests the constructor that takes only a Response.Status and a message (no MediaType)
   * when executed in a virtual thread.
   */
  @Test
  public void testConstructorNoMediaTypeInVirtualThread() throws InterruptedException {
    AtomicReference<Response> responseRef = new AtomicReference<>();
    AtomicReference<Object> entityRef = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("no-media-type-test").start(() -> {
      WebApplicationMessageException exception = new WebApplicationMessageException(
          Response.Status.NOT_FOUND, "Message");
      Response response = exception.getResponse();
      responseRef.set(response);
      entityRef.set(response.getEntity());
    });
    
    virtualThread.join();
    
    Response response = responseRef.get();
    Object entity = entityRef.get();
    
    assertEquals(404, response.getStatus());
    assertInstanceOf(ValidationErrorXO.class, entity);
    assertEquals("Message", ((ValidationErrorXO) entity).getMessage());
    assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
    assertEquals(ImmutableList.of(MediaType.TEXT_PLAIN), response.getHeaders().get("Content-Type"));
  }

  /**
   * Tests error message formatting with String Templates in virtual threads.
   * This test verifies that String Templates work correctly with WebApplicationMessageException
   * when executed in a virtual thread context.
   */
  @Test
  public void testStringTemplateErrorMessageInVirtualThread() throws InterruptedException {
    AtomicReference<Response> responseRef = new AtomicReference<>();
    AtomicReference<Object> entityRef = new AtomicReference<>();
    
    String resourceId = "test-resource-123";
    int errorCode = 404;
    
    Thread virtualThread = Thread.ofVirtual().name("string-template-test").start(() -> {
      // Using Java 21 String Template feature
      String errorMessage = STR."Resource with ID \{resourceId} not found (error code: \{errorCode})";
      
      WebApplicationMessageException exception = new WebApplicationMessageException(
          Response.Status.NOT_FOUND, errorMessage);
      Response response = exception.getResponse();
      responseRef.set(response);
      entityRef.set(response.getEntity());
    });
    
    virtualThread.join();
    
    Response response = responseRef.get();
    Object entity = entityRef.get();
    
    assertEquals(404, response.getStatus());
    assertInstanceOf(ValidationErrorXO.class, entity);
    assertEquals("Resource with ID test-resource-123 not found (error code: 404)", 
        ((ValidationErrorXO) entity).getMessage());
    assertEquals(ValidationErrorXO.GENERIC, ((ValidationErrorXO) entity).getId());
  }

  /**
   * Tests concurrent exception creation in multiple virtual threads.
   * Verifies that WebApplicationMessageException can be safely used in a highly concurrent
   * environment with many virtual threads.
   */
  @Test
  public void testConcurrentExceptionCreationInVirtualThreads() throws InterruptedException {
    final int threadCount = 100;
    Thread[] threads = new Thread[threadCount];
    AtomicReference<Boolean> success = new AtomicReference<>(true);
    
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      threads[i] = Thread.ofVirtual().name("concurrent-test-" + i).start(() -> {
        try {
          WebApplicationMessageException exception = new WebApplicationMessageException(
              Response.Status.BAD_REQUEST, "Message from thread " + threadId, MediaType.APPLICATION_JSON);
          Response response = exception.getResponse();
          
          // Verify basic properties
          if (response.getStatus() != 400) {
            success.set(false);
          }
          
          Object entity = response.getEntity();
          if (!(entity instanceof ValidationErrorXO)) {
            success.set(false);
          }
          
          if (!((ValidationErrorXO) entity).getMessage().equals("Message from thread " + threadId)) {
            success.set(false);
          }
        } catch (Exception e) {
          success.set(false);
        }
      });
    }
    
    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join();
    }
    
    assertTrue(success.get(), "All virtual threads should successfully create and validate exceptions");
  }
}