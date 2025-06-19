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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

import org.eclipse.sisu.inject.BeanLocator;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.GlobalComponentLookupHelper;
import org.sonatype.nexus.common.script.ScriptApi;
import org.sonatype.nexus.common.script.ScriptCleanupHandler;
import org.sonatype.nexus.internal.script.ScriptEngineManagerProvider;
import org.sonatype.nexus.internal.script.ScriptServiceImpl;

/**
 * Tests the {@link ScriptServiceImpl} implementation using Java 21 Virtual Threads to verify that
 * script execution works correctly in a virtual thread environment.
 * 
 * This test ensures that script execution remains reliable when executed with virtual threads,
 * which is important for maintaining performance during high-concurrency scenarios.
 */
public class ScriptServiceVirtualThreadTest
    extends TestSupport
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
  
  private List<ScriptApi> scriptApis;
  
  private ScriptServiceImpl underTest;
  
  @Before
  public void setup() {
    MockitoAnnotations.openMocks(this);
    
    scriptApis = Collections.emptyList();
    
    when(engineManager.getEngineByName(ScriptEngineManagerProvider.DEFAULT_LANGUAGE)).thenReturn(scriptEngine);
    when(scriptEngine.createBindings()).thenReturn(bindings);
    
    underTest = new ScriptServiceImpl(
        engineManager,
        beanLocator,
        lookupHelper,
        scriptApis,
        scriptCleanupHandler,
        true);
  }
  
  /**
   * Tests that script engine initialization works correctly when executed in a virtual thread.
   */
  @Test
  public void testEngineForLanguageInVirtualThread() throws Exception {
    AtomicReference<ScriptEngine> engineRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        engineRef.set(underTest.engineForLanguage(ScriptEngineManagerProvider.DEFAULT_LANGUAGE));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    assertThat(engineRef.get(), is(scriptEngine));
    verify(engineManager).getEngineByName(ScriptEngineManagerProvider.DEFAULT_LANGUAGE);
  }
  
  /**
   * Tests that script context creation works correctly when executed in a virtual thread.
   */
  @Test
  public void testCreateContextInVirtualThread() throws Exception {
    AtomicReference<ScriptContext> contextRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        contextRef.set(underTest.createContext(ScriptEngineManagerProvider.DEFAULT_LANGUAGE));
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    ScriptContext context = contextRef.get();
    assertThat(context, notNullValue());
    assertThat(context.getBindings(ScriptContext.ENGINE_SCOPE), is(bindings));
  }
  
  /**
   * Tests that binding customization works correctly when executed in a virtual thread.
   */
  @Test
  public void testCustomizeBindingsInVirtualThread() throws Exception {
    ScriptContext context = new SimpleScriptContext();
    context.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);
    
    Map<String, Object> customizations = new HashMap<>();
    customizations.put("testKey", "testValue");
    
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        underTest.customizeBindings(context, customizations);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    Bindings resultBindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
    assertThat(resultBindings.get("testKey"), equalTo("testValue"));
    assertThat(resultBindings.get("beanLocator"), is(beanLocator));
    assertThat(resultBindings.get("container"), is(lookupHelper));
    assertThat(resultBindings.get(ScriptServiceImpl.SCRIPT_CLEANUP_HANDLER), is(scriptCleanupHandler));
  }
  
  /**
   * Tests that script evaluation works correctly when executed in a virtual thread.
   */
  @Test
  public void testEvalInVirtualThread() throws Exception {
    String script = "println('Hello from script')";
    String expectedResult = "executed";
    ScriptContext context = new SimpleScriptContext();
    
    when(scriptEngine.eval(script, context)).thenReturn(expectedResult);
    
    AtomicReference<Object> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        resultRef.set(underTest.eval(ScriptEngineManagerProvider.DEFAULT_LANGUAGE, script, context));
      } catch (ScriptException e) {
        logger.error("Script execution failed", e);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    assertThat(resultRef.get(), equalTo(expectedResult));
    verify(scriptEngine).eval(script, context);
  }
  
  /**
   * Tests that script evaluation with custom bindings works correctly when executed in a virtual thread.
   */
  @Test
  public void testEvalWithCustomBindingsInVirtualThread() throws Exception {
    String script = "println('Hello with custom bindings')";
    String expectedResult = "executed with bindings";
    Map<String, Object> customBindings = new HashMap<>();
    customBindings.put("customKey", "customValue");
    
    when(scriptEngine.eval(any(String.class), any(ScriptContext.class))).thenReturn(expectedResult);
    
    AtomicReference<Object> resultRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        resultRef.set(underTest.eval(ScriptEngineManagerProvider.DEFAULT_LANGUAGE, script, customBindings));
      } catch (ScriptException e) {
        logger.error("Script execution failed", e);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    virtualThread.join();
    
    assertThat(resultRef.get(), equalTo(expectedResult));
  }
  
  /**
   * Tests that multiple concurrent script evaluations work correctly when executed in virtual threads.
   */
  @Test
  public void testConcurrentScriptEvaluationInVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    String script = "println('Concurrent execution')";
    String expectedResult = "concurrent result";
    
    when(scriptEngine.eval(any(String.class), any(ScriptContext.class))).thenReturn(expectedResult);
    
    for (int i = 0; i < threadCount; i++) {
      Thread.startVirtualThread(() -> {
        try {
          ScriptContext context = underTest.createContext(ScriptEngineManagerProvider.DEFAULT_LANGUAGE);
          Object result = underTest.eval(ScriptEngineManagerProvider.DEFAULT_LANGUAGE, script, context);
          assertThat(result, equalTo(expectedResult));
        } catch (ScriptException e) {
          logger.error("Script execution failed", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all virtual threads to complete
    assertThat("All virtual threads should complete in time", 
        latch.await(10, TimeUnit.SECONDS), is(true));
  }
}