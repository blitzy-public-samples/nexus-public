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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.eclipse.sisu.inject.BeanLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.nexus.common.app.GlobalComponentLookupHelper;
import org.sonatype.nexus.common.script.ScriptCleanupHandler;
import org.sonatype.nexus.common.script.ScriptService;
import org.sonatype.nexus.internal.script.ScriptServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link ScriptService} implementation using Java 21 Virtual Threads.
 * 
 * This test ensures that script execution remains reliable when executed with virtual threads,
 * which is important for maintaining performance during high-concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
public class ScriptServiceVirtualThreadTest
{
  @Mock
  private ScriptEngineManager engineManager;

  @Mock
  private BeanLocator beanLocator;

  @Mock
  private GlobalComponentLookupHelper lookupHelper;

  @Mock
  private ScriptCleanupHandler scriptCleanupHandler;

  @Mock
  private ScriptEngine scriptEngine;

  @Mock
  private Bindings bindings;

  private ScriptService scriptService;

  @BeforeEach
  void setUp() {
    // Create a script service with groovyOnly=true for testing
    scriptService = new ScriptServiceImpl(
        engineManager,
        beanLocator,
        lookupHelper,
        new ArrayList<>(),
        scriptCleanupHandler,
        true);

    // Set up common mocks
    when(engineManager.getEngineByName("groovy")).thenReturn(scriptEngine);
    when(scriptEngine.createBindings()).thenReturn(bindings);
  }

  /**
   * Tests that the script engine can be retrieved correctly when running in a virtual thread.
   */
  @Test
  void engineForLanguageWorksInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ScriptEngine> engineRef = new AtomicReference<>();
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        engineRef.set(scriptService.engineForLanguage("groovy"));
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    virtualThread.join();

    // Verify results
    if (exceptionRef.get() != null) {
      throw exceptionRef.get();
    }

    assertNotNull(engineRef.get());
    assertEquals(scriptEngine, engineRef.get());
  }

  /**
   * Tests that script context creation works correctly in a virtual thread.
   */
  @Test
  void createContextWorksInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ScriptContext> contextRef = new AtomicReference<>();
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        contextRef.set(scriptService.createContext("groovy"));
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    virtualThread.join();

    // Verify results
    if (exceptionRef.get() != null) {
      throw exceptionRef.get();
    }

    assertNotNull(contextRef.get());
  }

  /**
   * Tests that binding customization works correctly in a virtual thread.
   */
  @Test
  void customizeBindingsWorksInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    // Create a context and prepare customizations
    ScriptContext context = scriptService.createContext("groovy");
    Map<String, Object> customizations = new HashMap<>();
    customizations.put("testKey", "testValue");

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        scriptService.customizeBindings(context, customizations);
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    virtualThread.join();

    // Verify results
    if (exceptionRef.get() != null) {
      throw exceptionRef.get();
    }

    // Verify that bindings were properly set
    verify(bindings).put("beanLocator", beanLocator);
    verify(bindings).put("container", lookupHelper);
    verify(bindings).put(ScriptServiceImpl.SCRIPT_CLEANUP_HANDLER, scriptCleanupHandler);
    verify(bindings).put("testKey", "testValue");
  }

  /**
   * Tests that script evaluation works correctly in a virtual thread.
   */
  @Test
  void evalWorksInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Object> resultRef = new AtomicReference<>();
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    // Set up the script engine to return a result
    String script = "return 'Hello from Virtual Thread'";
    String expectedResult = "Hello from Virtual Thread";
    ScriptContext context = mock(ScriptContext.class);
    when(scriptEngine.eval(script, context)).thenReturn(expectedResult);

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        resultRef.set(scriptService.eval("groovy", script, context));
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    virtualThread.join();

    // Verify results
    if (exceptionRef.get() != null) {
      throw exceptionRef.get();
    }

    assertEquals(expectedResult, resultRef.get());
  }

  /**
   * Tests that script evaluation with custom bindings works correctly in a virtual thread.
   */
  @Test
  void evalWithCustomBindingsWorksInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Object> resultRef = new AtomicReference<>();
    AtomicReference<Exception> exceptionRef = new AtomicReference<>();

    // Set up the script engine to return a result
    String script = "return 'Hello from Virtual Thread'";
    String expectedResult = "Hello from Virtual Thread";
    Map<String, Object> customBindings = new HashMap<>();
    customBindings.put("testKey", "testValue");

    // Mock the script engine to return the expected result when eval is called
    when(scriptEngine.eval(script, bindings)).thenReturn(expectedResult);

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        resultRef.set(scriptService.eval("groovy", script, customBindings));
      }
      catch (Exception e) {
        exceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    virtualThread.join();

    // Verify results
    if (exceptionRef.get() != null) {
      throw exceptionRef.get();
    }

    assertEquals(expectedResult, resultRef.get());
    verify(bindings).put("testKey", "testValue");
  }

  /**
   * Tests that script evaluation properly handles exceptions in a virtual thread.
   */
  @Test
  void evalHandlesExceptionsInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> caughtExceptionRef = new AtomicReference<>();

    // Set up the script engine to throw an exception
    String script = "throw new RuntimeException('Script error')";
    ScriptContext context = mock(ScriptContext.class);
    ScriptException scriptException = new ScriptException("Script execution failed");
    when(scriptEngine.eval(script, context)).thenThrow(scriptException);

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        scriptService.eval("groovy", script, context);
      }
      catch (Exception e) {
        caughtExceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    virtualThread.join();

    // Verify that the exception was properly propagated
    assertNotNull(caughtExceptionRef.get());
    assertEquals(scriptException, caughtExceptionRef.get());
  }

  /**
   * Tests that language validation works correctly in a virtual thread.
   */
  @Test
  void languageValidationWorksInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> caughtExceptionRef = new AtomicReference<>();

    // Execute the test in a virtual thread
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // This should throw an exception since we configured groovyOnly=true
        scriptService.engineForLanguage("javascript");
      }
      catch (Exception e) {
        caughtExceptionRef.set(e);
      }
      finally {
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    virtualThread.join();

    // Verify that the appropriate exception was thrown
    assertNotNull(caughtExceptionRef.get());
    assertEquals("Language: javascript is not allowed", caughtExceptionRef.get().getMessage());
  }
}