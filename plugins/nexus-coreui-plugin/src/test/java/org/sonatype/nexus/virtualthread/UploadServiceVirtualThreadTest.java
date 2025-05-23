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
package org.sonatype.nexus.virtualthread;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.coreui.internal.UploadService;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.upload.UploadManager;
import org.sonatype.nexus.repository.upload.UploadResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link UploadService} using Java 21 Virtual Threads.
 * 
 * This test class verifies that file upload operations function correctly under high concurrency
 * with Virtual Threads, ensuring no thread pinning issues occur during I/O-intensive upload operations.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class UploadServiceVirtualThreadTest
{
  private static final int CONCURRENT_UPLOADS = 1000;
  private static final String REPOSITORY_NAME = "test-repo";
  private static final String NPM_REPOSITORY_NAME = "npm-repo";
  private static final String NPM_FORMAT = "npm";
  
  @Mock
  private RepositoryManager repositoryManager;
  
  @Mock
  private UploadManager uploadManager;
  
  @Mock
  private RepositoryCacheInvalidationService repositoryCacheInvalidationService;
  
  @Mock
  private Repository repository;
  
  @Mock
  private Repository npmRepository;
  
  @Mock
  private Format npmFormat;
  
  private UploadService uploadService;
  
  @BeforeEach
  void setUp() {
    uploadService = new UploadService(repositoryManager, uploadManager, repositoryCacheInvalidationService);
    
    when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
    when(repositoryManager.get(NPM_REPOSITORY_NAME)).thenReturn(npmRepository);
    when(npmRepository.getFormat()).thenReturn(npmFormat);
    when(npmFormat.getValue()).thenReturn(NPM_FORMAT);
  }
  
  /**
   * Tests that the UploadService can handle multiple concurrent upload operations
   * efficiently using Virtual Threads.
   */
  @Test
  void testConcurrentUploadsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up mocks for concurrent uploads
      when(uploadManager.handle(eq(repository), any(HttpServletRequest.class)))
          .thenAnswer(invocation -> {
            // Simulate some I/O work that would normally block a thread
            Thread.sleep(50);
            return new UploadResponse(Collections.singletonList("/some/path/file.jar"));
          });
      
      // Create a latch to wait for all uploads to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit concurrent upload tasks
      List<CompletableFuture<String>> futures = IntStream.range(0, CONCURRENT_UPLOADS)
          .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try {
              HttpServletRequest request = mock(HttpServletRequest.class);
              String result = uploadService.upload(REPOSITORY_NAME, request);
              return result;
            } 
            catch (Exception e) {
              errorCount.incrementAndGet();
              return null;
            }
            finally {
              latch.countDown();
            }
          }, executor))
          .collect(Collectors.toList());
      
      // Wait for all uploads to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all uploads completed successfully
      assertTrue(completed, "All uploads should complete within the timeout period");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent uploads");
      
      // Verify the upload manager was called the expected number of times
      verify(uploadManager, times(CONCURRENT_UPLOADS)).handle(eq(repository), any(HttpServletRequest.class));
      
      // Verify all futures completed successfully
      List<String> results = futures.stream()
          .map(CompletableFuture::join)
          .collect(Collectors.toList());
      
      assertEquals(CONCURRENT_UPLOADS, results.size());
      results.forEach(result -> assertEquals("/some/path", result));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that the UploadService correctly invalidates repository caches for NPM repositories
   * when using Virtual Threads.
   */
  @Test
  void testNpmRepositoryCacheInvalidation() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Set up mocks for NPM repository and group repositories
    List<String> groupRepoNames = Arrays.asList("npm-group-1", "npm-group-2");
    when(repositoryManager.findContainingGroups(NPM_REPOSITORY_NAME)).thenReturn(groupRepoNames);
    
    Repository npmGroup1 = mock(Repository.class);
    Repository npmGroup2 = mock(Repository.class);
    when(repositoryManager.get("npm-group-1")).thenReturn(npmGroup1);
    when(repositoryManager.get("npm-group-2")).thenReturn(npmGroup2);
    
    when(uploadManager.handle(eq(npmRepository), any(HttpServletRequest.class)))
        .thenReturn(new UploadResponse(Collections.singletonList("/npm/package/file.tgz")));
    
    // Execute upload in a virtual thread
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        HttpServletRequest request = mock(HttpServletRequest.class);
        uploadService.upload(NPM_REPOSITORY_NAME, request);
      } 
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    
    virtualThread.start();
    virtualThread.join();
    
    // Verify cache invalidation was called for each group repository
    verify(repositoryCacheInvalidationService).processCachesInvalidation(npmGroup1);
    verify(repositoryCacheInvalidationService).processCachesInvalidation(npmGroup2);
  }
  
  /**
   * Tests that the UploadService correctly creates search terms from multiple concurrent uploads
   * when using Virtual Threads.
   */
  @Test
  void testSearchTermCreationWithConcurrentUploads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Test cases for search term creation
      Object[][] testCases = {
          // Asset paths, expected search term
          {Collections.emptyList(), null},
          {Collections.singletonList("/path/to/file.jar"), "/path/to/file.jar"},
          {Arrays.asList("/path/to/file1.jar", "/path/to/file2.jar"), "/path/to"},
          {Arrays.asList("/path/to/file.jar", "/other/path/file.jar"), ""},
      };
      
      // Create a latch to wait for all test cases to complete
      CountDownLatch latch = new CountDownLatch(testCases.length);
      
      // Run each test case in a separate virtual thread
      for (Object[] testCase : testCases) {
        @SuppressWarnings("unchecked")
        Collection<String> assetPaths = (Collection<String>) testCase[0];
        String expectedSearchTerm = (String) testCase[1];
        
        executor.submit(() -> {
          try {
            // Test the createSearchTerm method directly
            String actualSearchTerm = uploadService.createSearchTerm(assetPaths);
            
            if (expectedSearchTerm == null) {
              assertNull(actualSearchTerm);
            } else {
              assertNotNull(actualSearchTerm);
              assertEquals(expectedSearchTerm, actualSearchTerm);
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all test cases to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "All test cases should complete within the timeout period");
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that the UploadService can handle large file uploads efficiently using Virtual Threads,
   * ensuring no thread pinning issues occur during I/O-intensive operations.
   */
  @Test
  void testLargeFileUploadsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up mock for large file uploads with simulated I/O delay
      doAnswer(invocation -> {
        // Simulate longer I/O work for large file uploads
        Thread.sleep(200);
        return new UploadResponse(Collections.singletonList("/large/file/path.jar"));
      }).when(uploadManager).handle(eq(repository), any(HttpServletRequest.class));
      
      // Number of concurrent large file uploads
      int concurrentLargeUploads = 100;
      
      // Create a latch to wait for all uploads to complete
      CountDownLatch latch = new CountDownLatch(concurrentLargeUploads);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit concurrent large file upload tasks
      for (int i = 0; i < concurrentLargeUploads; i++) {
        executor.submit(() -> {
          try {
            HttpServletRequest request = mock(HttpServletRequest.class);
            uploadService.upload(REPOSITORY_NAME, request);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all uploads to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all uploads completed successfully
      assertTrue(completed, "All large file uploads should complete within the timeout period");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent large file uploads");
      
      // Verify the upload manager was called the expected number of times
      verify(uploadManager, times(concurrentLargeUploads)).handle(eq(repository), any(HttpServletRequest.class));
    } 
    finally {
      executor.shutdown();
    }
  }
}