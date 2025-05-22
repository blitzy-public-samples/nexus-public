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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.script.ScriptService;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptManager;
import org.sonatype.nexus.script.ScriptResultXO;
import org.sonatype.nexus.script.ScriptRunEvent;
import org.sonatype.nexus.script.ScriptXO;
import org.sonatype.nexus.script.plugin.internal.ScriptingDisabledException;
import org.sonatype.nexus.script.plugin.internal.rest.ScriptResource;
import org.sonatype.nexus.script.plugin.internal.security.ScriptPermission;
import org.sonatype.nexus.security.SecurityHelper;

/**
 * Tests the {@link ScriptResource} REST API implementation using Java 21 Virtual Threads.
 * 
 * This test ensures that REST operations (browse, read, add, edit, delete, run) work correctly
 * in a virtual thread environment, which is critical for maintaining performance during
 * high-concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
class ScriptResourceVirtualThreadTest
{
  private static final String SCRIPT_NAME = "test-script";
  private static final String SCRIPT_CONTENT = "return 'Hello, World!'";
  private static final String SCRIPT_TYPE = "groovy";
  private static final String SCRIPT_RESULT = "Hello, World!";

  @Mock
  private ScriptManager scriptManager;

  @Mock
  private SecurityHelper securityHelper;

  @Mock
  private ScriptService scriptService;

  @Mock
  private EventManager eventManager;

  @Mock
  private Script script;

  @Mock
  private Logger log;

  @Captor
  private ArgumentCaptor<ScriptRunEvent> eventCaptor;

  @InjectMocks
  private ScriptResource underTest;

  @BeforeEach
  void setUp() {
    // Common script setup
    when(script.getName()).thenReturn(SCRIPT_NAME);
    when(script.getContent()).thenReturn(SCRIPT_CONTENT);
    when(script.getType()).thenReturn(SCRIPT_TYPE);
  }

  /**
   * Tests the browse operation in a virtual thread.
   */
  @Test
  void testBrowseInVirtualThread() throws Exception {
    // Setup
    List<Script> scripts = new ArrayList<>();
    scripts.add(script);
    when(scriptManager.browse()).thenReturn(scripts);

    // Execute in virtual thread
    AtomicReference<List<ScriptXO>> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        resultRef.set(underTest.browse());
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    List<ScriptXO> result = resultRef.get();
    assertThat(result, notNullValue());
    assertThat(result, hasSize(1));
    assertThat(result.get(0).getName(), is(SCRIPT_NAME));
    assertThat(result.get(0).getContent(), is(SCRIPT_CONTENT));
    assertThat(result.get(0).getType(), is(SCRIPT_TYPE));
  }

  /**
   * Tests the read operation in a virtual thread.
   */
  @Test
  void testReadInVirtualThread() throws Exception {
    // Setup
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(script);

    // Execute in virtual thread
    AtomicReference<ScriptXO> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        resultRef.set(underTest.read(SCRIPT_NAME));
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    ScriptXO result = resultRef.get();
    assertThat(result, notNullValue());
    assertThat(result.getName(), is(SCRIPT_NAME));
    assertThat(result.getContent(), is(SCRIPT_CONTENT));
    assertThat(result.getType(), is(SCRIPT_TYPE));
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));
  }

  /**
   * Tests the read operation with a non-existent script in a virtual thread.
   */
  @Test
  void testReadNonExistentScriptInVirtualThread() throws Exception {
    // Setup
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(null);

    // Execute in virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.read(SCRIPT_NAME);
      } catch (Exception e) {
        exceptionRef.set(e);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    Throwable exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception instanceof NotFoundException, is(true));
  }

  /**
   * Tests the add operation in a virtual thread.
   */
  @Test
  void testAddInVirtualThread() throws Exception {
    // Setup
    ScriptXO scriptXO = new ScriptXO(SCRIPT_NAME, SCRIPT_CONTENT, SCRIPT_TYPE);

    // Execute in virtual thread
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.add(scriptXO);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    verify(scriptManager).create(SCRIPT_NAME, SCRIPT_CONTENT, SCRIPT_TYPE);
  }

  /**
   * Tests the add operation when scripting is disabled in a virtual thread.
   */
  @Test
  void testAddWhenScriptingDisabledInVirtualThread() throws Exception {
    // Setup
    ScriptXO scriptXO = new ScriptXO(SCRIPT_NAME, SCRIPT_CONTENT, SCRIPT_TYPE);
    doThrow(new ScriptingDisabledException("Scripting is disabled"))
        .when(scriptManager).create(SCRIPT_NAME, SCRIPT_CONTENT, SCRIPT_TYPE);

    // Execute in virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.add(scriptXO);
      } catch (Exception e) {
        exceptionRef.set(e);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    Throwable exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception instanceof WebApplicationException, is(true));
    WebApplicationException webException = (WebApplicationException) exception;
    assertThat(webException.getResponse().getStatus(), is(Response.Status.GONE.getStatusCode()));
  }

  /**
   * Tests the edit operation in a virtual thread.
   */
  @Test
  void testEditInVirtualThread() throws Exception {
    // Setup
    ScriptXO scriptXO = new ScriptXO(SCRIPT_NAME, "updated content", SCRIPT_TYPE);
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(script);

    // Execute in virtual thread
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.edit(SCRIPT_NAME, scriptXO);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));
    verify(scriptManager).update(SCRIPT_NAME, "updated content");
  }

  /**
   * Tests the edit operation with a non-existent script in a virtual thread.
   */
  @Test
  void testEditNonExistentScriptInVirtualThread() throws Exception {
    // Setup
    ScriptXO scriptXO = new ScriptXO(SCRIPT_NAME, "updated content", SCRIPT_TYPE);
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(null);

    // Execute in virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.edit(SCRIPT_NAME, scriptXO);
      } catch (Exception e) {
        exceptionRef.set(e);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    Throwable exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception instanceof NotFoundException, is(true));
  }

  /**
   * Tests the edit operation when scripting is disabled in a virtual thread.
   */
  @Test
  void testEditWhenScriptingDisabledInVirtualThread() throws Exception {
    // Setup
    ScriptXO scriptXO = new ScriptXO(SCRIPT_NAME, "updated content", SCRIPT_TYPE);
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(script);
    doThrow(new ScriptingDisabledException("Scripting is disabled"))
        .when(scriptManager).update(SCRIPT_NAME, "updated content");

    // Execute in virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.edit(SCRIPT_NAME, scriptXO);
      } catch (Exception e) {
        exceptionRef.set(e);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    Throwable exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception instanceof WebApplicationException, is(true));
    WebApplicationException webException = (WebApplicationException) exception;
    assertThat(webException.getResponse().getStatus(), is(Response.Status.GONE.getStatusCode()));
  }

  /**
   * Tests the delete operation in a virtual thread.
   */
  @Test
  void testDeleteInVirtualThread() throws Exception {
    // Setup
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(script);

    // Execute in virtual thread
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.delete(SCRIPT_NAME);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));
    verify(scriptManager).delete(SCRIPT_NAME);
  }

  /**
   * Tests the delete operation with a non-existent script in a virtual thread.
   */
  @Test
  void testDeleteNonExistentScriptInVirtualThread() throws Exception {
    // Setup
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(null);

    // Execute in virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.delete(SCRIPT_NAME);
      } catch (Exception e) {
        exceptionRef.set(e);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    Throwable exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception instanceof NotFoundException, is(true));
  }

  /**
   * Tests the run operation in a virtual thread.
   */
  @Test
  void testRunInVirtualThread() throws Exception {
    // Setup
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(script);
    when(scriptService.eval(eq(SCRIPT_TYPE), eq(SCRIPT_CONTENT), any(Map.class))).thenReturn(SCRIPT_RESULT);

    // Execute in virtual thread
    AtomicReference<ScriptResultXO> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        resultRef.set(underTest.run(SCRIPT_NAME, null));
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    ScriptResultXO result = resultRef.get();
    assertThat(result, notNullValue());
    assertThat(result.getName(), is(SCRIPT_NAME));
    assertThat(result.getResult(), is(SCRIPT_RESULT));
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));
    verify(eventManager).post(any(ScriptRunEvent.class));
  }

  /**
   * Tests the run operation with a script execution error in a virtual thread.
   */
  @Test
  void testRunWithExecutionErrorInVirtualThread() throws Exception {
    // Setup
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(script);
    when(scriptService.eval(eq(SCRIPT_TYPE), eq(SCRIPT_CONTENT), any(Map.class)))
        .thenThrow(new RuntimeException("Script execution error"));

    // Execute in virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.run(SCRIPT_NAME, null);
      } catch (Exception e) {
        exceptionRef.set(e);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    Throwable exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception instanceof WebApplicationException, is(true));
    WebApplicationException webException = (WebApplicationException) exception;
    assertThat(webException.getResponse().getStatus(), is(Response.Status.BAD_REQUEST.getStatusCode()));
    verify(eventManager, never()).post(any(ScriptRunEvent.class));
  }

  /**
   * Tests the run operation with a non-existent script in a virtual thread.
   */
  @Test
  void testRunNonExistentScriptInVirtualThread() throws Exception {
    // Setup
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(null);

    // Execute in virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.run(SCRIPT_NAME, null);
      } catch (Exception e) {
        exceptionRef.set(e);
      } finally {
        latch.countDown();
      }
    });

    // Wait for completion
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();

    // Verify
    Throwable exception = exceptionRef.get();
    assertThat(exception, notNullValue());
    assertThat(exception instanceof NotFoundException, is(true));
  }

  /**
   * Tests concurrent script operations in multiple virtual threads.
   */
  @Test
  void testConcurrentOperationsInVirtualThreads() throws Exception {
    // Setup
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Thread> threads = new ArrayList<>();
    List<Script> scripts = Collections.singletonList(script);
    
    when(scriptManager.browse()).thenReturn(scripts);
    when(scriptManager.get(SCRIPT_NAME)).thenReturn(script);
    when(scriptService.eval(eq(SCRIPT_TYPE), eq(SCRIPT_CONTENT), any(Map.class))).thenReturn(SCRIPT_RESULT);

    // Create and start virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread virtualThread = Thread.startVirtualThread(() -> {
        try {
          // Perform different operations based on thread index
          switch (index % 5) {
            case 0:
              underTest.browse();
              break;
            case 1:
              underTest.read(SCRIPT_NAME);
              break;
            case 2:
              underTest.add(new ScriptXO(SCRIPT_NAME + index, SCRIPT_CONTENT, SCRIPT_TYPE));
              break;
            case 3:
              underTest.edit(SCRIPT_NAME, new ScriptXO(SCRIPT_NAME, SCRIPT_CONTENT, SCRIPT_TYPE));
              break;
            case 4:
              underTest.run(SCRIPT_NAME, null);
              break;
          }
        } catch (Exception e) {
          // Ignore exceptions for this test
        } finally {
          latch.countDown();
        }
      });
      threads.add(virtualThread);
    }

    // Wait for all threads to complete
    boolean completed = latch.await(10, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete within timeout", completed, is(true));

    // Join all threads to ensure they're done
    for (Thread thread : threads) {
      thread.join(1000);
    }
  }
}