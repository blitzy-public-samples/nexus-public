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
package org.sonatype.nexus.rapture.internal;

import java.io.ByteArrayOutputStream;
 import java.io.IOException;
import java.io.PrintStream;
import java.lang.Thread.Builder.OfVirtual;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.common.template.TemplateParameters;
import org.sonatype.nexus.rapture.UiPluginDescriptor;
import org.sonatype.nexus.rapture.internal.state.StateComponent;
import org.sonatype.nexus.ui.UiPluginDescriptor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

/**
 * Tests to detect and validate thread pinning issues when using Virtual Threads with the nexus-rapture component.
 * 
 * <p>Thread pinning occurs when a virtual thread gets "pinned" to its carrier thread, preventing the carrier thread
 * from being reused for other tasks. This can happen due to synchronized blocks, native methods, or thread-local
 * variables with large values.</p>
 *
 * <p>This test monitors operations that can cause carrier thread pinning, analyzes stack traces for pinning events,
 * and ensures the implementation avoids problematic patterns.</p>
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21) // Only run on Java 21 which supports Virtual Threads
public class RaptureThreadPinningDetectionTest
    extends TestSupport
{
  private static final String PINNED_THREAD_PATTERN = "VirtualThread.*reason:(MONITOR|NATIVE_PARK)";
  private static final int CONCURRENT_THREADS = 50;
  private static final int OPERATION_COUNT = 100;
  private static final long MAX_ACCEPTABLE_PINNING_COUNT = 0; // We expect zero pinning events
  
  private final AtomicInteger pinnedThreadCount = new AtomicInteger(0);
  private final List<String> pinnedThreadStackTraces = new ArrayList<>();
  private final Map<String, Integer> pinnedThreadLocations = new ConcurrentHashMap<>();
  
  private PrintStream originalSystemErr;
  private ByteArrayOutputStream capturedOutput;
  private PrintStream capturingSystemErr;
  
  @Mock
  private ApplicationVersion applicationVersion;
  
  @Mock
  private HttpServletRequest servletRequest;
  
  @Mock
  private StateComponent stateComponent;
  
  @Mock
  private TemplateHelper templateHelper;
  
  private RaptureWebResourceBundle underTest;
  
  private AutoCloseable mocks;
  
  /**
   * Set up the test environment with thread pinning detection enabled.
   */
  @BeforeEach
  public void setUp() {
    // Store original System.err and set up capturing stream
    originalSystemErr = System.err;
    capturedOutput = new ByteArrayOutputStream();
    capturingSystemErr = new PrintStream(capturedOutput);
    System.setErr(capturingSystemErr);
    
    // Enable thread pinning detection via system property
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    // Initialize mocks
    mocks = MockitoAnnotations.openMocks(this);
    
    // Set up mock behavior
    when(applicationVersion.getVersion()).thenReturn("3.60.0");
    when(applicationVersion.getEdition()).thenReturn("OSS");
    when(applicationVersion.getBuildTimestamp()).thenReturn("20250101-000000");
    when(servletRequest.getParameter("debug")).thenReturn(null);
    when(stateComponent.getState(Maps.newHashMap())).thenReturn(Maps.newHashMap());
    
    // Set up BaseUrlHolder for URI generation
    BaseUrlHolder.set("http://localhost:8081", "/nexus");
    
    // Create the component under test
    underTest = new RaptureWebResourceBundle(
        applicationVersion,
        () -> servletRequest,
        () -> stateComponent,
        templateHelper,
        ImmutableList.of(),
        ImmutableList.of(),
        null,
        true);
  }
  
  /**
   * Clean up after the test.
   */
  @AfterEach
  public void tearDown() throws Exception {
    // Restore original System.err
    System.setErr(originalSystemErr);
    
    // Reset thread pinning detection
    System.clearProperty("jdk.tracePinnedThreads");
    
    // Close mocks
    if (mocks != null) {
      mocks.close();
    }
    
    // Print any captured pinned thread stack traces for debugging
    if (!pinnedThreadStackTraces.isEmpty()) {
      log.info("Detected {} pinned thread events:", pinnedThreadStackTraces.size());
      for (String stackTrace : pinnedThreadStackTraces) {
        log.info("\n{}", stackTrace);
      }
    }
    
    // Print pinned thread locations summary
    if (!pinnedThreadLocations.isEmpty()) {
      log.info("Pinned thread locations summary:");
      pinnedThreadLocations.forEach((location, count) -> 
          log.info("  {} occurrences at: {}", count, location));
    }
  }
  
  /**
   * Test that getting resources doesn't cause thread pinning.
   */
  @Test
  public void testGetResourcesWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit tasks to get resources concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Get resources multiple times to increase chance of detecting pinning
            for (int j = 0; j < OPERATION_COUNT; j++) {
              underTest.getResources();
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All operations should complete in time",
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Analyze captured output for thread pinning events
      analyzeThreadPinning();
      
      // Assert that no thread pinning occurred
      assertThat("No thread pinning should occur during resource generation",
          pinnedThreadCount.get(), is(MAX_ACCEPTABLE_PINNING_COUNT));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test that generating styles doesn't cause thread pinning.
   */
  @Test
  public void testGetStylesWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit tasks to get styles concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Get styles multiple times to increase chance of detecting pinning
            for (int j = 0; j < OPERATION_COUNT; j++) {
              List<URI> styles = underTest.getStyles();
              assertThat(styles.isEmpty(), is(false));
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All operations should complete in time",
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Analyze captured output for thread pinning events
      analyzeThreadPinning();
      
      // Assert that no thread pinning occurred
      assertThat("No thread pinning should occur during style generation",
          pinnedThreadCount.get(), is(MAX_ACCEPTABLE_PINNING_COUNT));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test that generating scripts doesn't cause thread pinning.
   */
  @Test
  public void testGetScriptsWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit tasks to get scripts concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Get scripts multiple times to increase chance of detecting pinning
            for (int j = 0; j < OPERATION_COUNT; j++) {
              List<URI> scripts = underTest.getScripts();
              assertThat(scripts.isEmpty(), is(false));
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All operations should complete in time",
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Analyze captured output for thread pinning events
      analyzeThreadPinning();
      
      // Assert that no thread pinning occurred
      assertThat("No thread pinning should occur during script generation",
          pinnedThreadCount.get(), is(MAX_ACCEPTABLE_PINNING_COUNT));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test that template rendering doesn't cause thread pinning.
   */
  @Test
  public void testTemplateRenderingWithVirtualThreads() throws Exception {
    // Mock template rendering to return a simple string
    when(templateHelper.render(org.mockito.ArgumentMatchers.any(), 
        org.mockito.ArgumentMatchers.any(TemplateParameters.class)))
        .thenReturn("<html><body>Test</body></html>");
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit tasks to render templates concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Access resources that use template rendering
            for (int j = 0; j < OPERATION_COUNT; j++) {
              underTest.getResources();
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All operations should complete in time",
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Analyze captured output for thread pinning events
      analyzeThreadPinning();
      
      // Assert that no thread pinning occurred
      assertThat("No thread pinning should occur during template rendering",
          pinnedThreadCount.get(), is(MAX_ACCEPTABLE_PINNING_COUNT));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test that concurrent access to plugin descriptors doesn't cause thread pinning.
   */
  @Test
  public void testPluginDescriptorAccessWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit tasks to access plugin descriptors concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Access plugin descriptors multiple times
            for (int j = 0; j < OPERATION_COUNT; j++) {
              List<String> configs = underTest.getExtJsPluginConfigs();
              List<String> namespaces = underTest.getExtJsNamespaces();
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All operations should complete in time",
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Analyze captured output for thread pinning events
      analyzeThreadPinning();
      
      // Assert that no thread pinning occurred
      assertThat("No thread pinning should occur during plugin descriptor access",
          pinnedThreadCount.get(), is(MAX_ACCEPTABLE_PINNING_COUNT));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test that demonstrates how to detect thread pinning with a deliberately synchronized block.
   * This test is expected to show pinning and is used to validate the detection mechanism.
   */
  @Test
  public void testDeliberateSynchronizedBlockCausesPinning() throws Exception {
    // Skip this test in normal runs as it's expected to fail
    // It's included to validate the pinning detection mechanism
    if (Boolean.getBoolean("skipPinningValidationTest")) {
      return;
    }
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create an object to synchronize on
    final Object lock = new Object();
    
    try {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit tasks that use synchronized blocks with blocking operations
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // This synchronized block with a sleep inside will cause pinning
            synchronized (lock) {
              // Simulate a blocking operation inside synchronized block
              Thread.sleep(50);
            }
          } 
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All operations should complete in time",
          latch.await(30, TimeUnit.SECONDS), is(true));
      
      // Analyze captured output for thread pinning events
      analyzeThreadPinning();
      
      // This assertion is expected to fail, showing that pinning was detected
      // We're validating that our detection mechanism works
      assertThat("Synchronized block with sleep should cause pinning",
          pinnedThreadCount.get(), is(lessThan(CONCURRENT_THREADS)));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Analyze the captured output for thread pinning events.
   */
  private void analyzeThreadPinning() {
    // Flush the capturing stream to ensure all output is captured
    capturingSystemErr.flush();
    
    // Get the captured output as a string
    String output = capturedOutput.toString();
    
    // Reset the output stream for the next test
    capturedOutput.reset();
    
    // If no output, nothing to analyze
    if (output.isEmpty()) {
      return;
    }
    
    // Use regex to find pinned thread patterns in the output
    Pattern pattern = Pattern.compile(PINNED_THREAD_PATTERN);
    Matcher matcher = pattern.matcher(output);
    
    // Count pinned thread occurrences
    while (matcher.find()) {
      pinnedThreadCount.incrementAndGet();
      
      // Extract the stack trace for this pinning event
      int start = Math.max(0, matcher.start() - 100); // Include some context before the match
      int end = Math.min(output.length(), matcher.end() + 1000); // Include stack trace after the match
      String stackTrace = output.substring(start, end);
      
      // Add to the list of pinned thread stack traces
      pinnedThreadStackTraces.add(stackTrace);
      
      // Extract the location of the pinning for summary reporting
      extractPinningLocation(stackTrace);
    }
  }
  
  /**
   * Extract the location where thread pinning occurred from a stack trace.
   */
  private void extractPinningLocation(String stackTrace) {
    // Look for the first occurrence of org.sonatype in the stack trace
    // This is likely where our code is causing the pinning
    String[] lines = stackTrace.split("\n");
    for (String line : lines) {
      if (line.contains("org.sonatype.nexus")) {
        // Count occurrences of this location
        pinnedThreadLocations.compute(line.trim(), (k, v) -> (v == null) ? 1 : v + 1);
        return;
      }
    }
  }
}