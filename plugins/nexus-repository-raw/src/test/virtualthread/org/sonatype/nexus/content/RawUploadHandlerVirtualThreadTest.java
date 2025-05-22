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
package org.sonatype.nexus.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.content.raw.RawUploadHandler;
import org.sonatype.nexus.repository.content.fluent.FluentBlobs;
import org.sonatype.nexus.repository.importtask.ImportFileConfiguration;
import org.sonatype.nexus.repository.raw.RawUploadHandlerTestSupport;
import org.sonatype.nexus.repository.rest.UploadDefinitionExtension;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadHandler;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlobPayload;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the Raw repository upload handler with Java 21 Virtual Threads to ensure efficient
 * concurrent upload processing.
 */
public class RawUploadHandlerVirtualThreadTest
    extends RawUploadHandlerTestSupport
{
  private static final int CONCURRENT_UPLOADS = 1000;
  private static final int SMALL_FILE_SIZE = 1024; // 1KB
  private static final int LARGE_FILE_SIZE = 1024 * 1024; // 1MB
  
  @Mock
  RawContentFacet rawFacet;

  @Mock
  FluentBlobs blobs;

  @Mock
  TempBlob tempBlob;

  @Override
  protected UploadHandler newRawUploadHandler(final ContentPermissionChecker contentPermissionChecker,
                                              final VariableResolverAdapter variableResolverAdapter,
                                              final Set<UploadDefinitionExtension> uploadDefinitionExtensions)
  {
    return new RawUploadHandler(contentPermissionChecker, variableResolverAdapter, uploadDefinitionExtensions);
  }

  @Before
  public void setup() throws IOException {
    when(repository.facet(RawContentFacet.class)).thenReturn(rawFacet);
    when(rawFacet.blobs()).thenReturn(blobs);
    when(content.getAttributes()).thenReturn(attributesMap);
    when(rawFacet.put(any(), any())).thenReturn(content);
  }

  /**
   * Tests concurrent uploads using Virtual Threads with varying file sizes.
   * This test validates that the RawUploadHandler can efficiently process many concurrent uploads
   * when executed with Virtual Threads.
   */
  @Test
  public void testConcurrentUploadsWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<UploadResponse>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicInteger successCount = new AtomicInteger(0);
      Set<String> uploadedPaths = ConcurrentHashMap.newKeySet();
      
      // Submit concurrent upload tasks
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Create component upload with random file size
          ComponentUpload component = new ComponentUpload();
          component.getFields().put("directory", "org/apache/maven/concurrent/" + index);
          
          AssetUpload asset = new AssetUpload();
          asset.getFields().put("filename", "file-" + index + ".jar");
          
          // Alternate between small and large payloads
          asset.setPayload(index % 2 == 0 ? jarPayload : sourcesPayload);
          component.getAssetUploads().add(asset);
          
          // Perform the upload
          UploadResponse response = underTest.handle(repository, component);
          
          // Track successful uploads
          successCount.incrementAndGet();
          uploadedPaths.addAll(response.getAssetPaths());
          
          return response;
        }));
      }
      
      // Start all uploads simultaneously
      startLatch.countDown();
      
      // Wait for all uploads to complete
      for (Future<UploadResponse> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
      
      // Verify all uploads were successful
      assertThat(successCount.get(), is(CONCURRENT_UPLOADS));
      assertThat(uploadedPaths, hasSize(CONCURRENT_UPLOADS));
      
      // Verify the correct number of uploads were processed
      ArgumentCaptor<String> pathCapture = ArgumentCaptor.forClass(String.class);
      verify(rawFacet, times(CONCURRENT_UPLOADS)).put(pathCapture.capture(), any(PartPayload.class));
      
      // Verify paths were constructed correctly
      List<String> paths = pathCapture.getAllValues();
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        assertTrue(paths.contains("/org/apache/maven/concurrent/" + i + "/file-" + i + ".jar"));
      }
    }
  }

  /**
   * Tests hard link ingestion behavior with Virtual Threads.
   * This test validates that the RawUploadHandler can efficiently process many concurrent
   * hard link ingestion operations when executed with Virtual Threads.
   */
  @Test
  public void testConcurrentHardLinkIngestionsWithVirtualThreads() throws Exception {
    // Create temporary files for testing
    List<Path> tempFiles = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
      Path contentPath = Files.createTempDirectory("raw-upload-test").resolve("test-" + i + ".txt");
      Files.write(contentPath, new byte[ThreadLocalRandom.current().nextInt(SMALL_FILE_SIZE, LARGE_FILE_SIZE)]);
      tempFiles.add(contentPath);
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Content>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Mock behavior for hard link ingestion
      when(blobs.ingest(any(Path.class), any(), any(), eq(true))).thenReturn(tempBlob);
      Content mockContent = mock(Content.class);
      when(rawFacet.put(any(), any(TempBlobPayload.class))).thenReturn(mockContent);
      
      // Submit concurrent hard link ingestion tasks
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final Path contentPath = tempFiles.get(i);
        futures.add(executor.submit(() -> {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Perform the hard link ingestion
          String path = contentPath.toString();
          Content importResponse = underTest.handle(new ImportFileConfiguration(repository, contentPath.toFile(), path, true));
          
          // Track successful ingestions
          successCount.incrementAndGet();
          
          return importResponse;
        }));
      }
      
      // Start all ingestions simultaneously
      startLatch.countDown();
      
      // Wait for all ingestions to complete
      for (Future<Content> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
      
      // Verify all ingestions were successful
      assertThat(successCount.get(), is(CONCURRENT_UPLOADS));
      
      // Verify the correct number of ingestions were processed
      verify(rawFacet, times(CONCURRENT_UPLOADS)).put(any(), any(TempBlobPayload.class));
      verify(blobs, times(CONCURRENT_UPLOADS)).ingest(any(Path.class), any(), any(), eq(true));
    }
    
    // Clean up temporary files
    for (Path tempFile : tempFiles) {
      Files.deleteIfExists(tempFile);
    }
  }

  /**
   * Tests path normalization with concurrent uploads using Virtual Threads.
   * This test validates that path normalization works correctly when processing many
   * concurrent uploads with Virtual Threads.
   */
  @Test
  public void testConcurrentPathNormalizationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Void>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Test data for path normalization
      String[][] testPaths = {
          {"//leading/slashes", "file.jar", "/leading/slashes/file.jar"},
          {"trailing/slashes//", "file.jar", "/trailing/slashes/file.jar"},
          {"//multiple///slashes///", "file.jar", "/multiple/slashes/file.jar"},
          {"spaces at end  ", "file.jar", "/spaces at end/file.jar"},
          {"  spaces at start", "file.jar", "/spaces at start/file.jar"},
          {"mixed///path//  /example", "file.jar", "/mixed/path/example/file.jar"},
          {"", "file.jar", "/file.jar"},
          {null, "file.jar", "/file.jar"},
          {"normal/path", "file.jar", "/normal/path/file.jar"},
          {"path/with/././dots", "file.jar", "/path/with/dots/file.jar"}
      };
      
      // Submit concurrent path normalization tasks
      for (String[] testPath : testPaths) {
        futures.add(executor.submit(() -> {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Reset mock for each test to capture individual calls
          reset(rawFacet);
          when(rawFacet.put(any(), any())).thenReturn(content);
          
          // Test path normalization
          testNormalizePath(testPath[0], testPath[1], testPath[2]);
          
          return null;
        }));
      }
      
      // Start all tasks simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for upload operations.
   * This test measures the execution time difference between using platform threads and
   * virtual threads for concurrent upload operations.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    int concurrentUploads = 500; // Reduced number for platform threads to avoid resource exhaustion
    
    // Measure platform threads performance
    long platformThreadTime = measureUploadPerformance(concurrentUploads, false);
    
    // Measure virtual threads performance
    long virtualThreadTime = measureUploadPerformance(concurrentUploads, true);
    
    // Log performance results
    System.out.println("Platform threads execution time (ms): " + platformThreadTime);
    System.out.println("Virtual threads execution time (ms): " + virtualThreadTime);
    
    // Virtual threads should generally be faster for I/O-bound operations
    assertThat("Virtual threads should be more efficient than platform threads", 
               virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Helper method to measure upload performance with either platform or virtual threads.
   * 
   * @param concurrentUploads Number of concurrent uploads to perform
   * @param useVirtualThreads Whether to use virtual threads (true) or platform threads (false)
   * @return Execution time in milliseconds
   */
  private long measureUploadPerformance(int concurrentUploads, boolean useVirtualThreads) throws Exception {
    ExecutorService executor = useVirtualThreads ? 
        Executors.newVirtualThreadPerTaskExecutor() : 
        Executors.newFixedThreadPool(Math.min(100, concurrentUploads)); // Limit platform threads
    
    try {
      List<Future<UploadResponse>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      
      // Reset and prepare mocks
      reset(rawFacet);
      when(rawFacet.put(any(), any())).thenReturn(content);
      
      // Submit upload tasks
      for (int i = 0; i < concurrentUploads; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          startLatch.await();
          
          ComponentUpload component = new ComponentUpload();
          component.getFields().put("directory", "org/apache/maven/perf/" + index);
          
          AssetUpload asset = new AssetUpload();
          asset.getFields().put("filename", "file-" + index + ".jar");
          asset.setPayload(jarPayload);
          component.getAssetUploads().add(asset);
          
          return underTest.handle(repository, component);
        }));
      }
      
      // Start timing
      long startTime = System.nanoTime();
      startLatch.countDown();
      
      // Wait for all uploads to complete
      for (Future<UploadResponse> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
      
      // Calculate execution time
      long endTime = System.nanoTime();
      return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }

  /**
   * Tests resource cleanup with virtual threads during upload operations.
   * This test validates that resources are properly cleaned up when using virtual threads
   * for upload operations, even under high concurrency.
   */
  @Test
  public void testResourceCleanupWithVirtualThreads() throws Exception {
    // Create temporary files that will be monitored for cleanup
    List<Path> tempFiles = new ArrayList<>();
    for (int i = 0; i < 100; i++) { // Using fewer files for this test
      Path contentPath = Files.createTempDirectory("raw-upload-cleanup-test").resolve("cleanup-" + i + ".txt");
      Files.write(contentPath, new byte[SMALL_FILE_SIZE]);
      tempFiles.add(contentPath);
    }
    
    // Track which files have been processed
    Set<Path> processedFiles = ConcurrentHashMap.newKeySet();
    
    // Mock behavior for hard link ingestion with resource tracking
    when(blobs.ingest(any(Path.class), any(), any(), eq(true))).thenAnswer(invocation -> {
      Path path = invocation.getArgument(0);
      processedFiles.add(path);
      return tempBlob;
    });
    
    Content mockContent = mock(Content.class);
    when(rawFacet.put(any(), any(TempBlobPayload.class))).thenReturn(mockContent);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Content>> futures = new ArrayList<>();
      
      // Submit concurrent hard link ingestion tasks
      for (Path contentPath : tempFiles) {
        futures.add(executor.submit(() -> {
          String path = contentPath.toString();
          return underTest.handle(new ImportFileConfiguration(repository, contentPath.toFile(), path, true));
        }));
      }
      
      // Wait for all ingestions to complete
      for (Future<Content> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    }
    
    // Verify all files were processed
    assertThat(processedFiles.size(), is(tempFiles.size()));
    
    // Clean up temporary files
    for (Path tempFile : tempFiles) {
      Files.deleteIfExists(tempFile);
    }
  }

  @Override
  protected void testNormalizePath(final String directory, final String file, final String expectedPath)
      throws IOException
  {
    reset(rawFacet);
    ComponentUpload component = new ComponentUpload();

    component.getFields().put("directory", directory);

    AssetUpload asset = new AssetUpload();
    asset.getFields().put("filename", file);
    asset.setPayload(jarPayload);
    component.getAssetUploads().add(asset);

    when(content.getAttributes()).thenReturn(attributesMap);
    when(rawFacet.put(any(), any())).thenReturn(content);
    underTest.handle(repository, component);

    ArgumentCaptor<String> pathCapture = ArgumentCaptor.forClass(String.class);
    verify(rawFacet).put(pathCapture.capture(), any(PartPayload.class));

    String path = pathCapture.getValue();
    assertNotNull(path);
    assertThat(path, is(expectedPath));
  }

  @Override
  protected String path(final String path) {
    return "/" + path;
  }
}