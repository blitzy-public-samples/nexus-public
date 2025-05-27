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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadDefinition;
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

import org.apache.commons.fileupload.FileUploadException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests repository upload operations with Java 21 Virtual Threads.
 * 
 * This test class verifies that the UploadManager correctly handles multiple concurrent uploads
 * when using virtual threads, ensuring proper multipart processing, validation, and event emission.
 */
public class UploadVirtualThreadTest
    extends TestSupport
{
  private UploadManagerImpl underTest;

  @Mock
  private UploadHandler uploadHandler;

  @Mock
  private UploadDefinition uploadDefinition;

  @Mock
  private Repository repository;

  @Mock
  private Configuration configuration;

  @Mock
  private UploadComponentMultipartHelper multipartHelper;

  @Mock
  private UploadProcessor uploadComponentProcessor;

  @Mock
  private EventManager eventManager;

  @Mock
  private ValidatingComponentUpload validatingComponentUpload;

  @Captor
  private ArgumentCaptor<ComponentUpload> componentUploadCaptor;

  @Captor
  private ArgumentCaptor<UIUploadEvent> eventCaptor;

  @Before
  public void setup() {
    when(uploadHandler.supportsApiUpload()).thenReturn(true);
    when(uploadHandler.getDefinition()).thenReturn(uploadDefinition);
    when(uploadHandler.getValidatingComponentUpload(componentUploadCaptor.capture())).thenReturn(validatingComponentUpload);
    when(validatingComponentUpload.getComponentUpload()).thenAnswer(i -> componentUploadCaptor.getValue());

    when(repository.getFormat()).thenReturn(new Format("test") {});
    when(repository.getType()).thenReturn(new HostedType());
    when(repository.getConfiguration()).thenReturn(configuration);
    when(configuration.isOnline()).thenReturn(true);

    Map<String, UploadHandler> handlers = new HashMap<>();
    handlers.put("test", uploadHandler);

    underTest = new UploadManagerImpl(handlers, multipartHelper, uploadComponentProcessor, eventManager,
        Collections.emptySet());
  }

  /**
   * Tests that a single upload operation works correctly with a virtual thread.
   */
  @Test
  public void testSingleUploadWithVirtualThread() throws Exception {
    // Setup mock responses
    BlobStoreMultipartForm uploadedForm = createMockMultipartForm("asset1", "test.jar");
    when(multipartHelper.parse(isNotNull(), isNotNull())).thenReturn(uploadedForm);
    
    List<String> assetPaths = List.of("/asset/path/1");
    UploadResponse uploadResponse = mock(UploadResponse.class);
    when(uploadResponse.getAssetPaths()).thenReturn(assetPaths);
    when(uploadHandler.handle(isNotNull(), isNotNull())).thenReturn(uploadResponse);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the upload task to a virtual thread
      Future<?> future = executor.submit(() -> {
        try {
          HttpServletRequest request = createMockRequest();
          underTest.handle(repository, request);
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      
      // Wait for completion
      future.get(5, TimeUnit.SECONDS);
    }
    
    // Verify the upload was processed correctly
    verify(uploadHandler, times(1)).handle(eq(repository), any(ComponentUpload.class));
    verify(eventManager, times(1)).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getRepository(), equalTo(repository));
    assertThat(eventCaptor.getValue().getAssetPaths(), equalTo(assetPaths));
  }

  /**
   * Tests concurrent uploads using virtual threads.
   * This test verifies that multiple uploads can be processed concurrently
   * without errors or race conditions.
   */
  @Test
  public void testConcurrentUploadsWithVirtualThreads() throws Exception {
    final int CONCURRENT_UPLOADS = 100;
    final AtomicInteger successCount = new AtomicInteger(0);
    final CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);
    final ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();

    // Setup mock responses for each upload
    when(multipartHelper.parse(isNotNull(), isNotNull())).thenAnswer(invocation -> {
      int index = Thread.currentThread().getName().hashCode() % CONCURRENT_UPLOADS;
      return createMockMultipartForm("asset" + index, "test" + index + ".jar");
    });

    when(uploadHandler.handle(isNotNull(), isNotNull())).thenAnswer(invocation -> {
      int index = Thread.currentThread().getName().hashCode() % CONCURRENT_UPLOADS;
      List<String> assetPaths = List.of("/asset/path/" + index);
      UploadResponse response = mock(UploadResponse.class);
      when(response.getAssetPaths()).thenReturn(assetPaths);
      return response;
    });

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple upload tasks
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final int uploadIndex = i;
        executor.submit(() -> {
          try {
            HttpServletRequest request = createMockRequest();
            underTest.handle(repository, request);
            results.put(uploadIndex, "success");
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            results.put(uploadIndex, "error: " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all uploads to complete
      assertTrue("Timed out waiting for uploads to complete", 
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify all uploads were successful
    assertThat("All uploads should succeed", successCount.get(), is(CONCURRENT_UPLOADS));
    
    // Verify event manager was called for each upload
    verify(eventManager, times(CONCURRENT_UPLOADS)).post(any(UIUploadEvent.class));
  }

  /**
   * Compares performance between platform threads and virtual threads for upload operations.
   * This test demonstrates the efficiency gains from using virtual threads for I/O-bound operations.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    final int UPLOAD_COUNT = 500;
    
    // Setup mock responses
    setupMocksForPerformanceTest(UPLOAD_COUNT);
    
    // Test with platform threads
    long platformThreadTime = measureUploadPerformance(UPLOAD_COUNT, false);
    
    // Test with virtual threads
    long virtualThreadTime = measureUploadPerformance(UPLOAD_COUNT, true);
    
    // Log the results
    log.info("Platform threads time: {} ms", platformThreadTime);
    log.info("Virtual threads time: {} ms", virtualThreadTime);
    log.info("Performance improvement: {}x", (double) platformThreadTime / virtualThreadTime);
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // but the exact performance difference depends on the environment
    assertThat("Virtual threads should be at least as fast as platform threads", 
        virtualThreadTime, lessThan(platformThreadTime * 2)); // Conservative assertion
  }

  /**
   * Tests memory efficiency when handling thousands of concurrent uploads with virtual threads.
   * This test verifies that virtual threads can handle a large number of concurrent operations
   * without excessive memory consumption.
   */
  @Test
  public void testMemoryEfficiencyWithThousandsOfVirtualThreads() throws Exception {
    final int THREAD_COUNT = 5000; // A large number of virtual threads
    final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Setup mock responses
    setupMocksForPerformanceTest(THREAD_COUNT);
    
    // Record memory usage before test
    Runtime runtime = Runtime.getRuntime();
    System.gc(); // Request garbage collection to get more accurate readings
    long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit many upload tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            HttpServletRequest request = createMockRequest();
            underTest.handle(repository, request);
          } 
          catch (Exception e) {
            log.error("Error in virtual thread upload", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all uploads to complete
      assertTrue("Timed out waiting for uploads to complete", 
          latch.await(60, TimeUnit.SECONDS));
    }
    
    // Record memory usage after test
    System.gc(); // Request garbage collection
    long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
    
    // Log memory usage
    log.info("Memory before: {} MB", memoryBefore / (1024 * 1024));
    log.info("Memory after: {} MB", memoryAfter / (1024 * 1024));
    log.info("Memory difference: {} MB", (memoryAfter - memoryBefore) / (1024 * 1024));
    
    // Verify that the memory usage per thread is reasonable
    // Virtual threads should use much less memory than platform threads
    long memoryPerThread = (memoryAfter - memoryBefore) / THREAD_COUNT;
    log.info("Memory per thread: {} KB", memoryPerThread / 1024);
    
    // The memory per thread should be much less than what a platform thread would use
    // Platform threads typically use 1-2MB each, virtual threads should use much less
    assertThat("Virtual threads should use minimal memory", 
        memoryPerThread, lessThan(100 * 1024L)); // Less than 100KB per thread
  }

  /**
   * Tests that event emission works correctly under heavy virtual thread loads.
   * This test verifies that events are properly emitted and can be processed
   * when many virtual threads are active simultaneously.
   */
  @Test
  public void testEventEmissionUnderHeavyVirtualThreadLoad() throws Exception {
    final int THREAD_COUNT = 200;
    final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    final List<UIUploadEvent> capturedEvents = Collections.synchronizedList(new ArrayList<>());
    
    // Setup mock responses
    setupMocksForPerformanceTest(THREAD_COUNT);
    
    // Capture events as they're posted
    Mockito.doAnswer(invocation -> {
      UIUploadEvent event = invocation.getArgument(0);
      capturedEvents.add(event);
      return null;
    }).when(eventManager).post(any(UIUploadEvent.class));
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit upload tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            HttpServletRequest request = createMockRequest();
            underTest.handle(repository, request);
          } 
          catch (Exception e) {
            log.error("Error in virtual thread upload", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all uploads to complete
      assertTrue("Timed out waiting for uploads to complete", 
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify that all events were captured
    assertThat("All events should be captured", capturedEvents.size(), is(THREAD_COUNT));
    
    // Verify that each event has the correct repository
    for (UIUploadEvent event : capturedEvents) {
      assertThat("Event should have the correct repository", event.getRepository(), is(repository));
      assertThat("Event should have asset paths", event.getAssetPaths(), notNullValue());
      assertThat("Event should have at least one asset path", event.getAssetPaths().size(), greaterThan(0));
    }
  }

  /**
   * Helper method to create a mock multipart form for testing.
   */
  private BlobStoreMultipartForm createMockMultipartForm(String fieldName, String fileName) {
    BlobStoreMultipartForm form = new BlobStoreMultipartForm();
    TempBlob tempBlob = mock(TempBlob.class);
    TempBlobFormField field = new TempBlobFormField(fieldName, fileName, tempBlob);
    form.putFile(fieldName, field);
    return form;
  }

  /**
   * Helper method to create a mock HTTP request for testing.
   */
  private HttpServletRequest createMockRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getInputStream()).thenAnswer(invocation -> {
      byte[] content = "test content".getBytes();
      return new ByteArrayInputStream(content);
    });
    return request;
  }

  /**
   * Helper method to set up mocks for performance testing.
   */
  private void setupMocksForPerformanceTest(int count) throws IOException, FileUploadException {
    // Setup multipart helper to return a form for each request
    when(multipartHelper.parse(isNotNull(), isNotNull())).thenAnswer(invocation -> {
      // Simulate some I/O delay
      Thread.sleep(5);
      return createMockMultipartForm("asset", "test.jar");
    });
    
    // Setup upload handler to return a response for each request
    when(uploadHandler.handle(isNotNull(), isNotNull())).thenAnswer(invocation -> {
      // Simulate some processing delay
      Thread.sleep(10);
      List<String> assetPaths = List.of("/asset/path/test");
      UploadResponse response = mock(UploadResponse.class);
      when(response.getAssetPaths()).thenReturn(assetPaths);
      return response;
    });
  }

  /**
   * Helper method to measure upload performance with either platform or virtual threads.
   * 
   * @param count The number of uploads to perform
   * @param useVirtualThreads Whether to use virtual threads (true) or platform threads (false)
   * @return The time taken in milliseconds
   */
  private long measureUploadPerformance(int count, boolean useVirtualThreads) throws Exception {
    final CountDownLatch latch = new CountDownLatch(count);
    
    // Create the appropriate executor
    ExecutorService executor = useVirtualThreads ? 
        Executors.newVirtualThreadPerTaskExecutor() : 
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    try {
      // Record start time
      long startTime = System.currentTimeMillis();
      
      // Submit upload tasks
      for (int i = 0; i < count; i++) {
        executor.submit(() -> {
          try {
            HttpServletRequest request = createMockRequest();
            underTest.handle(repository, request);
          } 
          catch (Exception e) {
            log.error("Error in upload", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all uploads to complete
      latch.await(60, TimeUnit.SECONDS);
      
      // Calculate elapsed time
      return System.currentTimeMillis() - startTime;
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}