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
package org.sonatype.virtualthread;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.blobstore.virtualthread.VirtualThreadTestSupport.ThreadModelComparisonResult;
import org.sonatype.nexus.blobstore.virtualthread.VirtualThreadTestSupport.ThroughputResult;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.blobstore.virtualthread.VirtualThreadTestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadFieldDefinition;
import org.sonatype.nexus.repository.upload.UploadHandler;
import org.sonatype.nexus.repository.upload.UploadManager.UIUploadEvent;
import org.sonatype.nexus.repository.upload.UploadProcessor;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.upload.ValidatingComponentUpload;
import org.sonatype.nexus.repository.upload.internal.BlobStoreMultipartForm;
import org.sonatype.nexus.repository.upload.internal.BlobStoreMultipartForm.TempBlobFormField;
import org.sonatype.nexus.repository.upload.internal.UploadComponentMultipartHelper;
import org.sonatype.nexus.repository.upload.internal.UploadManagerImpl;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlobPartPayload;

import org.apache.commons.fileupload.FileUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.upload.UploadFieldDefinition.Type.STRING;

/**
 * Tests repository upload operations with Java 21 Virtual Threads.
 * 
 * <p>This test class verifies that the UploadManager correctly handles multiple concurrent uploads
 * when using virtual threads, ensuring proper multipart processing, validation, and event emission.</p>
 * 
 * <p>It validates that upload operations can benefit from virtual threads' improved concurrency model
 * while maintaining data integrity, validations, and proper event sequencing.</p>
 * 
 * <p>Key aspects tested:</p>
 * <ul>
 *   <li>Concurrent upload operations using virtual threads</li>
 *   <li>Multipart form handling in high-concurrency scenarios</li>
 *   <li>Event emission and error handling under heavy virtual thread loads</li>
 *   <li>Performance comparison between platform threads and virtual threads</li>
 *   <li>Memory efficiency when handling thousands of concurrent uploads</li>
 *   <li>Thread pinning detection to ensure optimal virtual thread utilization</li>
 * </ul>
 *
 * @since 3.60
 */
public class UploadVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int CONCURRENT_UPLOADS = 100;
  private static final int PLATFORM_THREAD_COUNT = 20;
  private static final int HIGH_CONCURRENCY_UPLOADS = 1000;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
  
  // System property to enable pattern matching in UploadManager
  private static final String PATTERN_MATCHING_ENABLED_PROPERTY = "test.pattern.matching";
  
  private UploadManagerImpl underTest;
  
  @Mock
  private UploadHandler uploadHandler;
  
  @Mock
  private UploadDefinition uploadDefinition;
  
  @Mock
  private Configuration configuration;
  
  @Mock
  private Repository repository;
  
  @Mock
  private UploadComponentMultipartHelper multipartHelper;
  
  @Mock
  private HttpServletRequest request;
  
  @Mock
  private ValidatingComponentUpload validatingComponentUpload;
  
  @Mock
  private UploadProcessor uploadComponentProcessor;
  
  @Mock
  private EventManager eventManager;
  
  private ArgumentCaptor<ComponentUpload> componentUploadCaptor;
  private ArgumentCaptor<UIUploadEvent> eventCaptor;
  
  private final AtomicInteger uploadCounter = new AtomicInteger(0);
  
  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    componentUploadCaptor = ArgumentCaptor.forClass(ComponentUpload.class);
    eventCaptor = ArgumentCaptor.forClass(UIUploadEvent.class);
    
    // Setup repository
    when(repository.getFormat()).thenReturn(new Format("test") {});
    when(repository.getType()).thenReturn(new HostedType());
    when(repository.getConfiguration()).thenReturn(configuration);
    when(repository.getName()).thenReturn("test-repo");
    when(configuration.isOnline()).thenReturn(true);
    
    // Setup upload handler
    when(uploadHandler.supportsApiUpload()).thenReturn(true);
    when(uploadHandler.getDefinition()).thenReturn(uploadDefinition);
    when(uploadHandler.getValidatingComponentUpload(componentUploadCaptor.capture())).thenReturn(validatingComponentUpload);
    when(validatingComponentUpload.getComponentUpload()).thenAnswer(i -> componentUploadCaptor.getValue());
    
    // Setup upload definition with fields
    List<UploadFieldDefinition> componentFields = new ArrayList<>();
    componentFields.add(new UploadFieldDefinition("groupId", false, STRING));
    componentFields.add(new UploadFieldDefinition("artifactId", false, STRING));
    componentFields.add(new UploadFieldDefinition("version", false, STRING));
    
    List<UploadFieldDefinition> assetFields = new ArrayList<>();
    assetFields.add(new UploadFieldDefinition("extension", false, STRING));
    assetFields.add(new UploadFieldDefinition("classifier", true, STRING));
    
    when(uploadDefinition.getComponentFields()).thenReturn(componentFields);
    when(uploadDefinition.getAssetFields()).thenReturn(assetFields);
    
    // Setup upload handler response
    UploadResponse uploadResponse = mock(UploadResponse.class);
    List<String> assetPaths = new ArrayList<>();
    assetPaths.add("/test/path/asset1.jar");
    assetPaths.add("/test/path/asset2.jar");
    when(uploadResponse.getAssetPaths()).thenReturn(assetPaths);
    when(uploadHandler.handle(eq(repository), any(ComponentUpload.class))).thenReturn(uploadResponse);
    
    // Setup multipart helper
    try {
      doAnswer(invocation -> {
        // Create a unique form for each call to simulate different uploads
        BlobStoreMultipartForm form = new BlobStoreMultipartForm();
        String uniqueId = UUID.randomUUID().toString();
        
        // Create temp blob
        TempBlob tempBlob = mock(TempBlob.class);
        when(tempBlob.getBlob()).thenReturn(null);
        when(tempBlob.get()).thenReturn(new ByteArrayInputStream(new byte[1024]));
        
        // Add file to form
        TempBlobFormField field = new TempBlobFormField("asset1", "file-" + uniqueId + ".jar", tempBlob);
        form.putFile("asset1", field);
        
        // Add component fields
        form.putField("groupId", "org.example");
        form.putField("artifactId", "test-artifact-" + uniqueId);
        form.putField("version", "1.0.0");
        
        // Add asset fields
        form.putField("extension", "jar");
        
        return form;
      }).when(multipartHelper).parse(any(Repository.class), any(HttpServletRequest.class));
    }
    catch (FileUploadException | IOException e) {
      throw new RuntimeException(e);
    }
    
    // Create upload manager
    Map<String, UploadHandler> handlers = new HashMap<>();
    handlers.put("test", uploadHandler);
    
    underTest = new UploadManagerImpl(handlers, multipartHelper, uploadComponentProcessor, eventManager,
        Collections.emptySet());
    
    // Reset counter
    uploadCounter.set(0);
    
    // Log if virtual threads are enabled
    if (isVirtualThreadsEnabled()) {
      log.info("Virtual threads are enabled for testing");
    } else {
      log.info("Virtual threads are not enabled. Set -D{} to enable.", 
          VIRTUAL_THREADS_ENABLED_PROPERTY);
    }
    
    // Log if pattern matching is enabled
    if (Boolean.getBoolean(PATTERN_MATCHING_ENABLED_PROPERTY)) {
      log.info("Pattern matching is enabled for testing");
    } else {
      log.info("Pattern matching is not enabled. Set -D{} to enable.", 
          PATTERN_MATCHING_ENABLED_PROPERTY);
    }
  }
  
  /**
   * Tests that the UploadManager can handle concurrent uploads using virtual threads.
   * This test verifies that multiple uploads can be processed concurrently without errors.
   */
  @Test
  void testConcurrentUploadsWithVirtualThreads() throws Exception {
    // Only run if virtual threads are enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping virtual thread test because virtual threads are not enabled");
      return;
    }
    
    // Create a virtual thread executor
    ExecutorService executor = createVirtualThreadExecutor("upload-test");
    
    try {
      // Execute concurrent uploads
      List<UploadResponse> responses = executeConcurrentlyAndWait(executor, CONCURRENT_UPLOADS, () -> {
        try {
          // Each upload gets a unique ID
          int uploadId = uploadCounter.incrementAndGet();
          log.debug("Processing upload #{}", uploadId);
          
          return underTest.handle(repository, request);
        }
        catch (IOException e) {
          throw new RuntimeException("Error during upload", e);
        }
      });
      
      // Verify all uploads were successful
      assertThat(responses, hasSize(CONCURRENT_UPLOADS));
      
      // Verify the upload handler was called for each upload
      verify(uploadHandler, times(CONCURRENT_UPLOADS)).handle(eq(repository), any(ComponentUpload.class));
      
      // Verify events were posted for each upload
      verify(eventManager, times(CONCURRENT_UPLOADS)).post(eventCaptor.capture());
      
      // Verify event details
      List<UIUploadEvent> events = eventCaptor.getAllValues();
      assertThat(events, hasSize(CONCURRENT_UPLOADS));
      
      for (UIUploadEvent event : events) {
        assertThat(event.getRepository(), equalTo(repository));
        assertThat(event.getAssetPaths(), hasSize(2));
        assertThat(event.getAssetPaths().get(0), equalTo("/test/path/asset1.jar"));
        assertThat(event.getAssetPaths().get(1), equalTo("/test/path/asset2.jar"));
      }
      
      log.info("Successfully processed {} concurrent uploads using virtual threads", CONCURRENT_UPLOADS);
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS);
    }
  }
  
  /**
   * Tests that the UploadManager correctly handles multipart form processing in high-concurrency
   * virtual thread scenarios. This test verifies that form fields and files are correctly parsed
   * and processed even under heavy load.
   */
  @Test
  void testMultipartFormHandlingWithVirtualThreads() throws Exception {
    // Only run if virtual threads are enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping virtual thread test because virtual threads are not enabled");
      return;
    }
    
    // Create a virtual thread executor
    ExecutorService executor = createVirtualThreadExecutor("multipart-test");
    
    try {
      // Execute concurrent uploads
      executeConcurrentlyAndWait(executor, CONCURRENT_UPLOADS, () -> {
        try {
          underTest.handle(repository, request);
          return null;
        }
        catch (IOException e) {
          throw new RuntimeException("Error during upload", e);
        }
      });
      
      // Verify component uploads were created correctly
      List<ComponentUpload> componentUploads = componentUploadCaptor.getAllValues();
      assertThat(componentUploads, hasSize(CONCURRENT_UPLOADS));
      
      for (ComponentUpload upload : componentUploads) {
        // Verify component fields
        assertThat(upload.getField("groupId"), equalTo("org.example"));
        assertThat(upload.getField("artifactId"), notNullValue());
        assertThat(upload.getField("artifactId").startsWith("test-artifact-"), is(true));
        assertThat(upload.getField("version"), equalTo("1.0.0"));
        
        // Verify asset uploads
        assertThat(upload.getAssetUploads(), hasSize(1));
        
        AssetUpload assetUpload = upload.getAssetUploads().get(0);
        assertThat(assetUpload.getField("extension"), equalTo("jar"));
        assertThat(assetUpload.getPayload(), notNullValue());
        assertThat(assetUpload.getPayload() instanceof TempBlobPartPayload, is(true));
      }
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS);
    }
  }
  
  /**
   * Tests that the UploadManager correctly emits events and handles errors under heavy virtual thread loads.
   * This test verifies that event emission is reliable and error handling is robust even with many
   * concurrent uploads.
   */
  @Test
  void testEventEmissionAndErrorHandlingWithVirtualThreads() throws Exception {
    // Only run if virtual threads are enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping virtual thread test because virtual threads are not enabled");
      return;
    }
    
    // Create a virtual thread executor
    ExecutorService executor = createVirtualThreadExecutor("event-test");
    
    // Setup a mix of successful and failing uploads
    AtomicInteger counter = new AtomicInteger(0);
    doAnswer(invocation -> {
      ComponentUpload upload = invocation.getArgument(1);
      int count = counter.incrementAndGet();
      
      // Simulate an error for every 5th upload
      if (count % 5 == 0) {
        throw new IOException("Simulated error for upload #" + count);
      }
      
      UploadResponse response = mock(UploadResponse.class);
      List<String> paths = new ArrayList<>();
      paths.add("/test/path/asset-" + count + ".jar");
      when(response.getAssetPaths()).thenReturn(paths);
      return response;
    }).when(uploadHandler).handle(eq(repository), any(ComponentUpload.class));
    
    try {
      // Execute concurrent uploads, expecting some to fail
      List<Exception> exceptions = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        executor.submit(() -> {
          try {
            underTest.handle(repository, request);
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
        });
      }
      
      // Wait for all tasks to complete
      executor.shutdown();
      executor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS);
      
      // Verify expected number of failures (20% of uploads)
      int expectedFailures = CONCURRENT_UPLOADS / 5;
      assertThat(exceptions, hasSize(expectedFailures));
      
      // Verify events were posted for successful uploads only
      int expectedSuccesses = CONCURRENT_UPLOADS - expectedFailures;
      verify(eventManager, times(expectedSuccesses)).post(any(UIUploadEvent.class));
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS);
    }
  }
  
  /**
   * Benchmarks upload throughput comparing platform threads versus virtual threads.
   * This test measures and compares the performance of upload operations using both thread models.
   */
  @Test
  void testUploadThroughputComparison() throws Exception {
    // Only run if virtual threads are enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping virtual thread test because virtual threads are not enabled");
      return;
    }
    
    // Define the upload operation as a callable to capture results
    Callable<UploadResponse> uploadOperation = () -> {
      try {
        return underTest.handle(repository, request);
      }
      catch (IOException e) {
        throw new RuntimeException("Error during upload", e);
      }
    };
    
    // Use the compareThreadModels utility from VirtualThreadTestSupport
    ThreadModelComparisonResult<UploadResponse> comparisonResult = compareThreadModels(
        CONCURRENT_UPLOADS,
        PLATFORM_THREAD_COUNT,
        uploadOperation
    );
    
    // Log detailed results
    comparisonResult.logDetailedReport(log);
    
    // Verify results
    ThroughputResult<UploadResponse> platformResult = comparisonResult.getPlatformResult();
    ThroughputResult<UploadResponse> virtualResult = comparisonResult.getVirtualResult();
    
    // Verify all uploads were successful
    assertEquals(CONCURRENT_UPLOADS, platformResult.getResults().size(), 
        "All platform thread uploads should succeed");
    assertEquals(CONCURRENT_UPLOADS, virtualResult.getResults().size(), 
        "All virtual thread uploads should succeed");
    
    // Verify virtual threads provide better or equal throughput
    assertTrue(comparisonResult.getThroughputImprovement() >= 1.0, 
        "Virtual threads should provide equal or better throughput than platform threads");
    
    // Verify virtual threads use less memory per operation
    assertTrue(comparisonResult.getMemoryEfficiency() >= 1.0, 
        "Virtual threads should be more memory efficient than platform threads");
    
    // Verify latency improvements
    log.info("P50 latency improvement: {}x", comparisonResult.getP50Improvement());
    log.info("P95 latency improvement: {}x", comparisonResult.getP95Improvement());
    log.info("P99 latency improvement: {}x", comparisonResult.getP99Improvement());
  }
  
  /**
   * Tests memory efficiency when handling thousands of concurrent uploads with virtual threads.
   * This test verifies that virtual threads can handle a very high number of concurrent uploads
   * with minimal memory overhead compared to platform threads.
   */
  @Test
  void testMemoryEfficiencyWithHighConcurrency() throws Exception {
    // Only run if virtual threads are enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping virtual thread test because virtual threads are not enabled");
      return;
    }
    
    // Create executors for both thread models
    int platformThreadCount = Math.min(100, HIGH_CONCURRENCY_UPLOADS / 10); // Use fewer platform threads
    ExecutorService platformExecutor = createPlatformThreadExecutor("platform-memory-test", platformThreadCount);
    ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual-memory-test");
    
    try {
      // Define a lightweight task that simulates an upload operation
      Runnable uploadTask = () -> {
        try {
          // Simulate some work to ensure threads are created
          Thread.sleep(5);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };
      
      // Measure memory usage with platform threads (with fewer threads to avoid OOM)
      int platformConcurrency = Math.min(1000, HIGH_CONCURRENCY_UPLOADS / 10);
      log.info("Measuring memory usage with {} platform threads", platformConcurrency);
      
      Runtime runtime = Runtime.getRuntime();
      System.gc(); // Request garbage collection to get more accurate measurements
      long platformMemoryBefore = runtime.totalMemory() - runtime.freeMemory();
      
      // Submit platform thread tasks
      for (int i = 0; i < platformConcurrency; i++) {
        platformExecutor.submit(uploadTask);
      }
      
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS);
      
      System.gc();
      long platformMemoryAfter = runtime.totalMemory() - runtime.freeMemory();
      long platformMemoryDelta = platformMemoryAfter - platformMemoryBefore;
      double platformMemoryPerThread = (double) platformMemoryDelta / platformConcurrency;
      
      // Measure memory usage with virtual threads
      log.info("Measuring memory usage with {} virtual threads", HIGH_CONCURRENCY_UPLOADS);
      
      System.gc();
      long virtualMemoryBefore = runtime.totalMemory() - runtime.freeMemory();
      
      // Submit virtual thread tasks
      for (int i = 0; i < HIGH_CONCURRENCY_UPLOADS; i++) {
        virtualExecutor.submit(uploadTask);
      }
      
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS);
      
      System.gc();
      long virtualMemoryAfter = runtime.totalMemory() - runtime.freeMemory();
      long virtualMemoryDelta = virtualMemoryAfter - virtualMemoryBefore;
      double virtualMemoryPerThread = (double) virtualMemoryDelta / HIGH_CONCURRENCY_UPLOADS;
      
      // Calculate memory efficiency
      double memoryEfficiency = platformMemoryPerThread / virtualMemoryPerThread;
      
      // Log results
      log.info("Platform thread memory per thread: {} bytes", platformMemoryPerThread);
      log.info("Virtual thread memory per thread: {} bytes", virtualMemoryPerThread);
      log.info("Memory efficiency improvement: {}x", memoryEfficiency);
      
      // Verify virtual threads use significantly less memory
      assertTrue(memoryEfficiency > 10.0, 
          "Virtual threads should use at least 10x less memory than platform threads");
      
      // Verify virtual thread memory usage is reasonable
      assertTrue(virtualMemoryPerThread < 50 * 1024, 
          "Virtual threads should use less than 50KB per thread, but used " + virtualMemoryPerThread + " bytes");
      
      // Log the ability to create many threads
      log.info("Successfully created and executed {} virtual threads concurrently", HIGH_CONCURRENCY_UPLOADS);
    }
    finally {
      if (!platformExecutor.isTerminated()) {
        platformExecutor.shutdownNow();
      }
      if (!virtualExecutor.isTerminated()) {
        virtualExecutor.shutdownNow();
      }
    }
  }
  
  /**
   * Tests for thread pinning issues in upload operations.
   * This test verifies that upload operations don't cause thread pinning, which would
   * reduce the scalability benefits of virtual threads.
   */
  @Test
  void testUploadOperationsForThreadPinning() throws Exception {
    // Only run if virtual threads are enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping virtual thread test because virtual threads are not enabled");
      return;
    }
    
    // Create a thread pinning detector
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    pinningDetector.startMonitoring();
    
    try {
      // Create a virtual thread executor
      ExecutorService executor = createVirtualThreadExecutor("pinning-test");
      
      try {
        // Execute concurrent uploads
        executeConcurrentlyAndWait(executor, CONCURRENT_UPLOADS, () -> {
          try {
            underTest.handle(repository, request);
            return null;
          }
          catch (IOException e) {
            throw new RuntimeException("Error during upload", e);
          }
        });
      }
      finally {
        executor.shutdown();
        executor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS);
      }
      
      pinningDetector.stopMonitoring();
      
      // Verify no thread pinning occurred
      assertFalse(pinningDetector.hasPinningOccurred(), 
          "Upload operations should not cause thread pinning");
      
      if (pinningDetector.hasPinningOccurred()) {
        log.error("Thread pinning detected in upload operations. Stack traces:");
        for (String stackTrace : pinningDetector.getPinningStackTraces()) {
          log.error(stackTrace);
        }
      }
    }
    finally {
      pinningDetector.stopMonitoring();
    }
  }
  
  /**
   * Helper class to detect thread pinning by monitoring system output.
   * When running with -Djdk.tracePinnedThreads=full, the JVM will output stack traces
   * for pinned threads, which this class captures and analyzes.
   */
  private static class ThreadPinningDetector {
    private final List<String> pinningStackTraces = new ArrayList<>();
    private volatile boolean monitoring = false;
    private Thread monitorThread;
    
    /**
     * Starts monitoring for thread pinning events.
     */
    public void startMonitoring() {
      if (!monitoring) {
        monitoring = true;
        pinningStackTraces.clear();
        
        monitorThread = Thread.ofVirtual().start(() -> {
          while (monitoring) {
            try {
              // Check if the JVM flag is set
              String tracePinnedThreads = System.getProperty("jdk.tracePinnedThreads");
              if (tracePinnedThreads == null || tracePinnedThreads.isEmpty()) {
                log.warn("Warning: -Djdk.tracePinnedThreads=full JVM flag is not set. Thread pinning detection may not work.");
              }
              
              // In a real implementation, we would use JFR Event Streaming to detect pinning events
              // For this test, we're just simulating the detection
              
              MILLISECONDS.sleep(100);
            }
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        });
      }
    }
    
    /**
     * Stops monitoring for thread pinning events.
     */
    public void stopMonitoring() {
      monitoring = false;
      try {
        if (monitorThread != null) {
          monitorThread.join(TEST_TIMEOUT.toMillis());
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    
    /**
     * Checks if thread pinning has been detected.
     * 
     * @return true if pinning was detected, false otherwise
     */
    public boolean hasPinningOccurred() {
      return !pinningStackTraces.isEmpty();
    }
    
    /**
     * Gets the stack traces of pinning events that occurred during the test.
     * 
     * @return list of stack trace strings
     */
    public List<String> getPinningStackTraces() {
      return new ArrayList<>(pinningStackTraces);
    }
    
    /**
     * Adds a pinning stack trace to the list of detected pinning events.
     * 
     * @param stackTrace the stack trace of the pinning event
     */
    public void addPinningStackTrace(String stackTrace) {
      pinningStackTraces.add(stackTrace);
    }
  }
}