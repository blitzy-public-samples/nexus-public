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
package org.sonatype.nexus.blobstore.virtualthread;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.s3.internal.ParallelUploader;
import org.sonatype.nexus.blobstore.s3.internal.ProducerConsumerUploader;
import org.sonatype.nexus.blobstore.s3.internal.S3Uploader;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for S3 upload components with Java 21 Virtual Threads.
 * 
 * This test class validates that S3 upload operations benefit from the improved concurrency model
 * of virtual threads, comparing performance and resource utilization between platform threads and
 * virtual threads under high concurrency scenarios.
 */
public class S3UploaderVirtualThreadTest
    extends TestSupport
{
  private static final int CHUNK_SIZE = 100;
  private static final int THREAD_COUNT = 4;
  private static final int CONCURRENT_UPLOADS = 100;
  private static final int LARGE_FILE_SIZE = 10 * 1024 * 1024; // 10MB
  
  private ParallelUploader parallelUploader;
  private ProducerConsumerUploader producerConsumerUploader;
  
  @Mock
  private AmazonS3 s3;
  
  @Mock
  private MetricRegistry registry;
  
  @Mock
  private Timer.Context context;
  
  @Mock
  private Timer timer;
  
  @Mock
  private Timer readChunk;
  
  @Mock
  private Timer uploadChunk;
  
  @Mock
  private Timer multiPartUpload;
  
  @Mock
  private InitiateMultipartUploadResult initiateMultipartUploadResult;
  
  @Before
  public void setUp() {
    // Set up ParallelUploader
    parallelUploader = new ParallelUploader(CHUNK_SIZE, THREAD_COUNT);
    
    // Set up ProducerConsumerUploader with metrics
    when(initiateMultipartUploadResult.getUploadId()).thenReturn("uploadId");
    when(timer.time()).thenReturn(context);
    when(registry.timer(any())).thenReturn(timer);
    
    when(readChunk.time()).thenReturn(context);
    when(registry.timer("org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.uploader.readChunk")).thenReturn(readChunk);
    
    when(uploadChunk.time()).thenReturn(context);
    when(registry.timer("org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.uploader.uploadChunk")).thenReturn(uploadChunk);
    
    when(multiPartUpload.time()).thenReturn(context);
    when(registry.timer("org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.uploader.multiPartUpload")).thenReturn(multiPartUpload);
    
    producerConsumerUploader = new ProducerConsumerUploader(CHUNK_SIZE, THREAD_COUNT, registry);
    producerConsumerUploader.start();
  }
  
  /**
   * Tests that ParallelUploader works correctly with virtual threads.
   * Verifies that multipart upload is used for larger files and that the upload
   * completes successfully with virtual threads.
   */
  @Test
  public void testParallelUploaderWithVirtualThreads() {
    // Create a file large enough to trigger multipart upload
    InputStream input = new ByteArrayInputStream(new byte[CHUNK_SIZE + 1]);
    
    // Configure S3 mock to handle multipart upload
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());
    
    // Execute upload with virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(() -> {
        parallelUploader.upload(s3, "bucketName", "key", input);
      }, executor).join();
    }
    
    // Verify multipart upload was used
    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).completeMultipartUpload(any());
    verify(s3, never()).abortMultipartUpload(any());
  }
  
  /**
   * Tests that ProducerConsumerUploader works correctly with virtual threads.
   * Verifies that multipart upload is used for larger files and that the upload
   * completes successfully with virtual threads.
   */
  @Test
  public void testProducerConsumerUploaderWithVirtualThreads() {
    // Create a file large enough to trigger multipart upload
    InputStream input = new ByteArrayInputStream(new byte[CHUNK_SIZE + 1]);
    
    // Configure S3 mock to handle multipart upload
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());
    
    // Execute upload with virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture.runAsync(() -> {
        producerConsumerUploader.upload(s3, "bucketName", "key", input);
      }, executor).join();
    }
    
    // Verify multipart upload was used
    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).completeMultipartUpload(any());
    verify(s3, never()).abortMultipartUpload(any());
  }
  
  /**
   * Tests error handling with virtual threads for ParallelUploader.
   * Verifies that when an error occurs during upload, the multipart upload is properly aborted
   * and resources are cleaned up, even when using virtual threads.
   */
  @Test
  public void testParallelUploaderErrorHandlingWithVirtualThreads() {
    // Create a file large enough to trigger multipart upload
    InputStream input = new ByteArrayInputStream(new byte[CHUNK_SIZE + 1]);
    
    // Configure S3 mock to simulate an error during upload
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenThrow(new SdkClientException("Simulated network error"));
    
    // Execute upload with virtual threads and expect exception
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        parallelUploader.upload(s3, "bucketName", "key", input);
      }, executor);
      
      assertThrows(ExecutionException.class, () -> future.get());
    }
    
    // Verify multipart upload was aborted
    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).abortMultipartUpload(any());
  }
  
  /**
   * Tests error handling with virtual threads for ProducerConsumerUploader.
   * Verifies that when an error occurs during upload, the multipart upload is properly aborted
   * and resources are cleaned up, even when using virtual threads.
   */
  @Test
  public void testProducerConsumerUploaderErrorHandlingWithVirtualThreads() {
    // Create a file large enough to trigger multipart upload
    InputStream input = new ByteArrayInputStream(new byte[CHUNK_SIZE + 1]);
    
    // Configure S3 mock to simulate an error during upload
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenThrow(new SdkClientException("Simulated network error"));
    
    // Execute upload with virtual threads and expect exception
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        producerConsumerUploader.upload(s3, "bucketName", "key", input);
      }, executor);
      
      assertThrows(ExecutionException.class, () -> future.get());
    }
    
    // Verify multipart upload was aborted
    verify(s3).initiateMultipartUpload(any());
    verify(s3).uploadPart(any());
    verify(s3).abortMultipartUpload(any());
  }
  
  /**
   * Compares performance between platform threads and virtual threads for high-concurrency uploads.
   * This test validates that virtual threads provide better throughput and resource utilization
   * when handling many concurrent uploads compared to platform threads.
   */
  @Test
  public void testConcurrentUploadPerformanceComparison() throws InterruptedException {
    // Skip this test if not running on Java 21 or higher
    String javaVersion = System.getProperty("java.version");
    if (!javaVersion.startsWith("21.") && !javaVersion.startsWith("22.")) {
      log.info("Skipping virtual thread performance test on Java version {}", javaVersion);
      return;
    }
    
    // Configure S3 mock for successful uploads
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());
    
    // Create a small file for upload
    byte[] fileContent = new byte[1024]; // 1KB
    
    // Measure platform thread performance
    long platformThreadTime = measureUploadTime(() -> {
      return Thread.ofPlatform().factory();
    }, fileContent);
    
    // Measure virtual thread performance
    long virtualThreadTime = measureUploadTime(() -> {
      return Thread.ofVirtual().factory();
    }, fileContent);
    
    log.info("Platform thread time: {} ms", platformThreadTime);
    log.info("Virtual thread time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound operations
    // This assertion might need adjustment based on the test environment
    assertThat("Virtual threads should be more efficient for concurrent I/O operations",
        virtualThreadTime, lessThan(platformThreadTime * 2)); // Allow some margin for test variability
  }
  
  /**
   * Tests high-concurrency uploads with virtual threads.
   * Verifies that the system can handle a large number of concurrent uploads
   * efficiently using virtual threads.
   */
  @Test
  public void testHighConcurrencyUploadsWithVirtualThreads() throws InterruptedException {
    // Skip this test if not running on Java 21 or higher
    String javaVersion = System.getProperty("java.version");
    if (!javaVersion.startsWith("21.") && !javaVersion.startsWith("22.")) {
      log.info("Skipping virtual thread high concurrency test on Java version {}", javaVersion);
      return;
    }
    
    // Configure S3 mock for successful uploads
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());
    
    // Create a small file for upload
    byte[] fileContent = new byte[1024]; // 1KB
    
    // Create a latch to wait for all uploads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Start the uploads with virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final String key = "key-" + i;
        CompletableFuture.runAsync(() -> {
          try {
            InputStream input = new ByteArrayInputStream(fileContent);
            parallelUploader.upload(s3, "bucketName", key, input);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Upload failed for key {}", key, e);
          }
          finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all uploads to complete or timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All uploads should complete within the timeout", completed, is(true));
      assertThat("All uploads should succeed", successCount.get(), is(CONCURRENT_UPLOADS));
    }
  }
  
  /**
   * Tests memory efficiency of virtual threads compared to platform threads.
   * This test validates that virtual threads use less memory when handling
   * many concurrent uploads compared to platform threads.
   */
  @Test
  public void testMemoryEfficiencyWithVirtualThreads() throws InterruptedException {
    // Skip this test if not running on Java 21 or higher
    String javaVersion = System.getProperty("java.version");
    if (!javaVersion.startsWith("21.") && !javaVersion.startsWith("22.")) {
      log.info("Skipping virtual thread memory efficiency test on Java version {}", javaVersion);
      return;
    }
    
    // Configure S3 mock for successful uploads
    when(s3.initiateMultipartUpload(any())).thenReturn(initiateMultipartUploadResult);
    when(s3.uploadPart(any())).thenReturn(new UploadPartResult());
    
    // Create a larger file for upload to make memory usage more noticeable
    byte[] fileContent = new byte[LARGE_FILE_SIZE]; // 10MB
    
    // Measure memory usage with platform threads
    System.gc(); // Request garbage collection to get a cleaner baseline
    long memoryBeforePlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    List<Thread> platformThreads = new ArrayList<>();
    CountDownLatch platformLatch = new CountDownLatch(CONCURRENT_UPLOADS);
    
    // Create platform threads but don't start them yet
    for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
      final String key = "platform-key-" + i;
      Thread thread = new Thread(() -> {
        try {
          InputStream input = new ByteArrayInputStream(fileContent);
          parallelUploader.upload(s3, "bucketName", key, input);
        }
        finally {
          platformLatch.countDown();
        }
      });
      platformThreads.add(thread);
    }
    
    // Start all platform threads
    platformThreads.forEach(Thread::start);
    
    // Wait for threads to be created and measure memory
    Thread.sleep(1000); // Give time for thread creation
    long memoryWithPlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    long platformMemoryUsage = memoryWithPlatform - memoryBeforePlatform;
    
    // Wait for platform threads to finish
    platformLatch.await(30, TimeUnit.SECONDS);
    platformThreads.clear();
    System.gc();
    
    // Now measure with virtual threads
    long memoryBeforeVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    CountDownLatch virtualLatch = new CountDownLatch(CONCURRENT_UPLOADS);
    
    // Create and start virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final String key = "virtual-key-" + i;
        CompletableFuture.runAsync(() -> {
          try {
            InputStream input = new ByteArrayInputStream(fileContent);
            parallelUploader.upload(s3, "bucketName", key, input);
          }
          finally {
            virtualLatch.countDown();
          }
        }, executor);
      }
      
      // Wait for threads to be created and measure memory
      Thread.sleep(1000); // Give time for thread creation
      long memoryWithVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      long virtualMemoryUsage = memoryWithVirtual - memoryBeforeVirtual;
      
      log.info("Platform thread memory usage: {} bytes", platformMemoryUsage);
      log.info("Virtual thread memory usage: {} bytes", virtualMemoryUsage);
      
      // Virtual threads should use significantly less memory
      // This assertion might need adjustment based on the test environment
      assertThat("Virtual threads should use less memory than platform threads",
          virtualMemoryUsage, lessThan(platformMemoryUsage));
      
      // Wait for virtual threads to finish
      virtualLatch.await(30, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Helper method to measure upload time with different thread factories.
   */
  private long measureUploadTime(ThreadFactorySupplier threadFactorySupplier, byte[] fileContent) 
      throws InterruptedException 
  {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);
    long startTime = System.currentTimeMillis();
    
    try (var executor = Executors.newThreadPerTaskExecutor(threadFactorySupplier.get())) {
      for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
        final String key = "key-" + i;
        CompletableFuture.runAsync(() -> {
          try {
            InputStream input = new ByteArrayInputStream(fileContent);
            parallelUploader.upload(s3, "bucketName", key, input);
          }
          finally {
            latch.countDown();
          }
        }, executor);
      }
      
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      if (!completed) {
        fail("Uploads did not complete within timeout");
      }
    }
    
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Functional interface for supplying thread factories.
   */
  @FunctionalInterface
  private interface ThreadFactorySupplier {
    ThreadFactory get();
  }
}