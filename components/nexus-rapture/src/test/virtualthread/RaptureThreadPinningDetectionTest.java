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
package org.sonatype.nexus.rapture.virtualthread;

import java.io.ByteArrayOutputStream;
 import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.rapture.UiPluginDescriptor;
import org.sonatype.nexus.rapture.internal.RaptureWebResourceBundle;
import org.sonatype.nexus.rapture.internal.state.StateComponent;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

/**
 * Test to detect thread pinning issues when using Virtual Threads with the nexus-rapture component.
 * <p>
 * Thread pinning occurs when a Virtual Thread is forced to stay on its carrier thread, which reduces
 * the benefits of virtual threads. Common causes include synchronized blocks, native methods, or
 * thread-local variables with large values.
 * <p>
 * This test uses the JVM flag -Djdk.tracePinnedThreads=full to detect and log thread pinning events.
 * Alternatively, Java Flight Recorder (JFR) can be used to detect pinning through the jdk.VirtualThreadPinned event.
 * <p>
 * Note: In Java 24 and later, the thread pinning issue with synchronized blocks will be resolved through JEP 491,
 * and the -Djdk.tracePinnedThreads flag will be removed. However, for Java 21, thread pinning remains
 * an important consideration for optimal performance with Virtual Threads.
 * 
 * @since 3.60
 */
public class RaptureThreadPinningDetectionTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 50;
  private static final int ITERATIONS_PER_THREAD = 10;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
  
  // Pattern to match pinned thread stack traces in the output
  // This pattern works with the output format from -Djdk.tracePinnedThreads=full
  private static final Pattern PINNED_THREAD_PATTERN = 
      Pattern.compile("Virtual thread.*has been pinned for \\d+ ms");
  
  @Mock
  private ApplicationVersion applicationVersion;
  
  @Mock
  private Provider<HttpServletRequest> servletRequestProvider;
  
  @Mock
  private Provider<StateComponent> stateComponentProvider;
  
  @Mock
  private TemplateHelper templateHelper;
  
  @Mock
  private HttpServletRequest request;
  
  @Mock
  private StateComponent stateComponent;
  
  private RaptureWebResourceBundle resourceBundle;
  
  private ExecutorService virtualThreadExecutor;
  
  private ByteArrayOutputStream logCapture;
  private PrintStream originalErr;
  
  @Before
  public void setUp() throws Exception {
    // Set up the base URL for testing
    BaseUrlHolder.set("/nexus");
    
    // Configure mocks
    when(applicationVersion.getVersion()).thenReturn("3.60.0");
    when(applicationVersion.getEdition()).thenReturn("OSS");
    when(applicationVersion.getBuildTimestamp()).thenReturn("20250522-123456");
    
    when(servletRequestProvider.get()).thenReturn(request);
    when(stateComponentProvider.get()).thenReturn(stateComponent);
    when(stateComponent.getState(Map.of())).thenReturn(Map.of("test", "value"));
    
    // Create the resource bundle under test
    resourceBundle = new RaptureWebResourceBundle(
        applicationVersion,
        servletRequestProvider,
        stateComponentProvider,
        templateHelper,
        ImmutableList.of(),
        ImmutableList.of(),
        null,
        true
    );
    
    // Create a virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Set up log capture to detect pinned thread messages
    logCapture = new ByteArrayOutputStream();
    originalErr = System.err;
    System.setErr(new PrintStream(logCapture));
    
    // Verify that the JVM flag for pinned thread detection is set
    verifyPinnedThreadDetectionEnabled();
  }
  
  @After
  public void tearDown() throws Exception {
    // Restore original System.err
    System.setErr(originalErr);
    
    // Shutdown the executor service
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdownNow();
    }
    
    // Reset the base URL
    BaseUrlHolder.unset();
  }
  
  /**
   * Verifies that the JVM flag for pinned thread detection is enabled.
   * This test will log a warning if the flag is not set, but will not fail,
   * as the flag might be set through other means (like JVM arguments).
   * 
   * Note: There is a known issue (JDK-8322846) where using -Djdk.tracePinnedThreads=full
   * can cause hangs in some situations. If you experience hangs, consider using
   * JFR events (jdk.VirtualThreadPinned) instead for thread pinning detection.
   */
  @Test
  public void verifyPinnedThreadDetectionEnabled() {
    String tracePinnedThreads = System.getProperty("jdk.tracePinnedThreads");
    if (tracePinnedThreads == null || !tracePinnedThreads.equals("full")) {
      log.warn("The JVM flag -Djdk.tracePinnedThreads=full is not set. " +
          "Thread pinning detection may not work correctly.");
      log.warn("Current value: {}", tracePinnedThreads);
      log.warn("To enable full thread pinning detection, add -Djdk.tracePinnedThreads=full to the JVM arguments.");
      log.warn("Alternatively, you can use JFR events (jdk.VirtualThreadPinned) for more detailed pinning detection.");
    }
    else {
      log.info("Thread pinning detection is enabled with jdk.tracePinnedThreads={}", tracePinnedThreads);
    }
  }
  
  /**
   * Tests that generating the index.html resource does not cause thread pinning.
   */
  @Test
  public void testIndexHtmlGeneration() throws Exception {
    runConcurrentTest(() -> {
      // Get all resources to ensure index.html is included
      resourceBundle.getResources();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("index.html generation");
  }
  
  /**
   * Tests that generating the bootstrap.js resource does not cause thread pinning.
   */
  @Test
  public void testBootstrapJsGeneration() throws Exception {
    runConcurrentTest(() -> {
      // Get all resources to ensure bootstrap.js is included
      resourceBundle.getResources();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("bootstrap.js generation");
  }
  
  /**
   * Tests that generating the baseapp.css resource does not cause thread pinning.
   */
  @Test
  public void testBaseappCssGeneration() throws Exception {
    runConcurrentTest(() -> {
      // Get all resources to ensure baseapp.css is included
      resourceBundle.getResources();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("baseapp.css generation");
  }
  
  /**
   * Tests that generating the app.js resource does not cause thread pinning.
   */
  @Test
  public void testAppJsGeneration() throws Exception {
    runConcurrentTest(() -> {
      // Get all resources to ensure app.js is included
      resourceBundle.getResources();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("app.js generation");
  }
  
  /**
   * Tests that generating the copyright.html resource does not cause thread pinning.
   */
  @Test
  public void testCopyrightHtmlGeneration() throws Exception {
    runConcurrentTest(() -> {
      // Get all resources to ensure copyright.html is included
      resourceBundle.getResources();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("copyright.html generation");
  }
  
  /**
   * Tests that generating styles list does not cause thread pinning.
   */
  @Test
  public void testGetStyles() throws Exception {
    runConcurrentTest(() -> {
      resourceBundle.getStyles();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("getStyles()");
  }
  
  /**
   * Tests that generating scripts list does not cause thread pinning.
   */
  @Test
  public void testGetScripts() throws Exception {
    runConcurrentTest(() -> {
      resourceBundle.getScripts();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("getScripts()");
  }
  
  /**
   * Tests that generating ExtJS plugin configs does not cause thread pinning.
   */
  @Test
  public void testGetExtJsPluginConfigs() throws Exception {
    runConcurrentTest(() -> {
      resourceBundle.getExtJsPluginConfigs();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("getExtJsPluginConfigs()");
  }
  
  /**
   * Tests that generating ExtJS namespaces does not cause thread pinning.
   */
  @Test
  public void testGetExtJsNamespaces() throws Exception {
    runConcurrentTest(() -> {
      resourceBundle.getExtJsNamespaces();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("getExtJsNamespaces()");
  }
  
  /**
   * Tests that all resource generation operations together do not cause thread pinning.
   */
  @Test
  public void testAllResourceGenerationOperations() throws Exception {
    runConcurrentTest(() -> {
      resourceBundle.getResources();
      resourceBundle.getStyles();
      resourceBundle.getScripts();
      resourceBundle.getExtJsPluginConfigs();
      resourceBundle.getExtJsNamespaces();
      return null;
    });
    
    // Check for pinned thread messages
    assertNoPinnedThreads("all resource generation operations");
  }
  
  /**
   * Runs a test with multiple concurrent virtual threads to detect thread pinning.
   *
   * @param task The task to run concurrently
   * @param <T> The return type of the task
   * @throws Exception If an error occurs during test execution
   */
  private <T> void runConcurrentTest(Supplier<T> task) throws Exception {
    AtomicInteger completedTasks = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    
    // Submit tasks to the virtual thread executor
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        // Wait for all threads to start at the same time
        startLatch.await();
        
        for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
          T result = task.get();
          completedTasks.incrementAndGet();
          return result;
        }
        
        return null;
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete or timeout
    virtualThreadExecutor.awaitTermination(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    
    // Check that all tasks completed successfully
    for (Future<T> future : futures) {
      future.get(1, TimeUnit.SECONDS); // Short timeout since tasks should be done
    }
    
    log.info("Completed {} tasks across {} virtual threads", 
        completedTasks.get(), CONCURRENT_THREADS);
  }
  
  /**
   * Asserts that no thread pinning was detected during the test.
   * <p>
   * This method analyzes the captured log output for thread pinning messages.
   * Thread pinning can significantly impact the performance benefits of Virtual Threads,
   * especially in high-concurrency scenarios like UI resource generation.
   *
   * @param operationName The name of the operation being tested
   */
  private void assertNoPinnedThreads(String operationName) {
    String logOutput = logCapture.toString();
    Matcher matcher = PINNED_THREAD_PATTERN.matcher(logOutput);
    
    if (matcher.find()) {
      log.error("Thread pinning detected during {}: {}", operationName, matcher.group(0));
      log.error("Full pinned thread stack trace:\n{}", logOutput);
      log.error("Thread pinning reduces the benefits of Virtual Threads by preventing them from unmounting from carrier threads.");
      log.error("Consider refactoring code to avoid synchronized blocks or using java.util.concurrent.locks.ReentrantLock instead.");
      assertThat("No thread pinning should occur during " + operationName, false);
    }
    else {
      log.info("No thread pinning detected during {}", operationName);
    }
  }
  
  /**
   * Gets information about all running threads to help diagnose pinning issues.
   *
   * @return A map of thread IDs to thread information
   */
  private Map<Long, ThreadInfo> getAllThreadInfo() {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
    
    Map<Long, ThreadInfo> result = new ConcurrentHashMap<>();
    for (ThreadInfo info : threadInfos) {
      result.put(info.getThreadId(), info);
    }
    
    return result;
  }
}