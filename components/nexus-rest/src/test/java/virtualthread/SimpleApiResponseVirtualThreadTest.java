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

import org.junit.Test;
import org.sonatype.nexus.rest.SimpleApiResponse;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static javax.ws.rs.core.Response.Status.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link SimpleApiResponse} when executed in virtual threads.
 * Verifies that REST response generation, status code handling, entity wrapping,
 * and media type processing work correctly in a virtual thread environment.
 * 
 * This test class ensures that SimpleApiResponse functionality works correctly
 * when running in Java 21 virtual threads, which are lightweight threads managed
 * by the JVM rather than the operating system. Virtual threads are designed for
 * I/O-bound operations and can significantly improve application throughput.
 *
 * @since 3.60
 */
public class SimpleApiResponseVirtualThreadTest
{
  /**
   * Executes the given task in a virtual thread and waits for it to complete.
   * Propagates any exceptions from the virtual thread to the calling thread.
   *
   * @param task the task to execute in a virtual thread
   * @throws Exception if the task execution fails
   */
  private void runInVirtualThread(Runnable task) throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      try {
        task.run();
      } catch (Throwable t) {
        exception.set(t);
      }
    });
    
    virtualThread.join(); // Wait for the virtual thread to complete
    
    // Propagate any exception that occurred in the virtual thread
    if (exception.get() != null) {
      if (exception.get() instanceof Exception) {
        throw (Exception) exception.get();
      } else {
        throw new RuntimeException("Error in virtual thread", exception.get());
      }
    }
  }

  /**
   * Verifies that the current thread is a virtual thread.
   * This test confirms that our test infrastructure is correctly creating and using virtual threads.
   */
  @Test
  public void testIsVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Thread currentThread = Thread.currentThread();
      assertThat("Current thread should be a virtual thread", currentThread.isVirtual(), is(true));
      assertThat("Thread name should be set correctly", currentThread.getName(), startsWith("test-virtual-thread"));
    });
  }

  @Test
  public void testOkResponseWithoutDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.ok("message");
      assertResponse(simpleApiResponse, OK, null);
    });
  }

  @Test
  public void testOkResponseWithDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.ok("message", new Data("bar"));
      assertResponse(simpleApiResponse, OK, "bar");
    });
  }

  @Test
  public void testNotFoundResponseWithoutDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.notFound("message");
      assertResponse(simpleApiResponse, NOT_FOUND, null);
    });
  }

  @Test
  public void testNotFoundResponseWithDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.notFound("message", new Data("bar"));
      assertResponse(simpleApiResponse, NOT_FOUND, "bar");
    });
  }

  @Test
  public void testBadRequestResponseWithoutDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.badRequest("message");
      assertResponse(simpleApiResponse, BAD_REQUEST, null);
    });
  }

  @Test
  public void testBadRequestResponseWithDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.badRequest("message", new Data("bar"));
      assertResponse(simpleApiResponse, BAD_REQUEST, "bar");
    });
  }

  @Test
  public void testUnauthorizedResponseWithoutDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.unauthorized("message");
      assertResponse(simpleApiResponse, UNAUTHORIZED, null);
    });
  }

  @Test
  public void testUnauthorizedResponseWithDataInVirtualThread() throws Exception {
    runInVirtualThread(() -> {
      Response simpleApiResponse = SimpleApiResponse.unauthorized("message", new Data("bar"));
      assertResponse(simpleApiResponse, UNAUTHORIZED, "bar");
    });
  }

  /**
   * Tests that multiple concurrent virtual threads can create and process SimpleApiResponse objects
   * without interference.
   */
  @Test
  public void testConcurrentResponsesInVirtualThreads() throws Exception {
    final int threadCount = 10;
    Thread[] threads = new Thread[threadCount];
    AtomicReference<Throwable> exception = new AtomicReference<>();
    CountDownLatch startLatch = new CountDownLatch(1); // Used to start all threads simultaneously
    
    // Create and start 10 virtual threads, each creating a different type of response
    for (int i = 0; i < threads.length; i++) {
      final int index = i;
      threads[i] = Thread.ofVirtual().name("concurrent-test-" + i).start(() -> {
        try {
          // Wait for the signal to start (ensures all threads start at approximately the same time)
          startLatch.await();
          
          Response response;
          String message = "message-" + index;
          
          switch (index % 4) {
            case 0:
              response = SimpleApiResponse.ok(message);
              assertThat(response.getStatus(), is(OK.getStatusCode()));
              SimpleApiResponse entity = (SimpleApiResponse) response.getEntity();
              assertThat(entity.getMessage(), is(message));
              break;
            case 1:
              response = SimpleApiResponse.notFound(message);
              assertThat(response.getStatus(), is(NOT_FOUND.getStatusCode()));
              entity = (SimpleApiResponse) response.getEntity();
              assertThat(entity.getMessage(), is(message));
              break;
            case 2:
              response = SimpleApiResponse.badRequest(message);
              assertThat(response.getStatus(), is(BAD_REQUEST.getStatusCode()));
              entity = (SimpleApiResponse) response.getEntity();
              assertThat(entity.getMessage(), is(message));
              break;
            case 3:
              response = SimpleApiResponse.unauthorized(message);
              assertThat(response.getStatus(), is(UNAUTHORIZED.getStatusCode()));
              entity = (SimpleApiResponse) response.getEntity();
              assertThat(entity.getMessage(), is(message));
              break;
          }
        } catch (Throwable t) {
          exception.set(t);
        }
      });
    }
    
    // Signal all threads to start simultaneously
    startLatch.countDown();
    
    // Wait for all virtual threads to complete
    for (Thread thread : threads) {
      thread.join();
    }
    
    // Propagate any exception that occurred in the virtual threads
    if (exception.get() != null) {
      if (exception.get() instanceof Exception) {
        throw (Exception) exception.get();
      } else {
        throw new RuntimeException("Error in concurrent virtual threads", exception.get());
      }
    }
  }

  private void assertResponse(Response simpleApiResponse, Status status, String value) {
    assertThat(simpleApiResponse.getStatus(), is(status.getStatusCode()));
    SimpleApiResponse entity = (SimpleApiResponse) simpleApiResponse.getEntity();
    assertThat(entity.getStatus(), is(status.getStatusCode()));
    assertThat(entity.getMessage(), is("message"));
    if (value == null) {
      assertThat(entity.getData(), is(nullValue()));
    }
    else {
      assertThat(((Data) entity.getData()).getFoo(), is("bar"));
    }
  }

  private static class Data
  {
    private final String foo;

    public Data(String foo) {
      this.foo = foo;
    }

    public String getFoo() {
      return foo;
    }
  }
}