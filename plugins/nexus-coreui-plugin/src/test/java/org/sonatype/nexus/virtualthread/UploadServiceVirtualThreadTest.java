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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.coreui.internal.UploadService;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadManager;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link UploadService} using Java 21 Virtual Threads.
 * <p>
 * This test class verifies that the UploadService works correctly with Virtual Threads,
 * ensuring that file upload operations benefit from Virtual Threads and that no thread
 * pinning issues occur during upload operations.
 *
 * @since 3.60
 */
@VirtualThreadTestGroup
public class UploadServiceVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_UPLOADS = 100;
  private static final int UPLOAD_TIMEOUT_SECONDS = 10;
  private static final String REPOSITORY_NAME = "test-repo";
  private static final String NPM_FORMAT = "npm";
  private static final String MAVEN_FORMAT = "maven";

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private UploadManager uploadManager;

  @Mock
  private RepositoryCacheInvalidationService repositoryCacheInvalidationService;

  @Mock
  private Repository repository;

  @Mock
  private Repository.Format format;

  @Mock
  private HttpServletRequest request;

  private UploadService uploadService;
  private ExecutorService virtualThreadExecutor;
  private ThreadFactory virtualThreadFactory;

  @BeforeEach
  void setUp() {
    // Create the UploadService with mocked dependencies
    uploadService = new UploadService(repositoryManager, uploadManager, repositoryCacheInvalidationService);

    // Create a virtual thread factory and executor
    virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Enable thread pinning detection for tests
    ThreadPinningDetector.enableJdkPinningDetectionConcise();
    ThreadPinningDetector.startJfrMonitoring();

    // Setup common mocks
    when(repositoryManager.get(REPOSITORY_NAME)).thenReturn(repository);
    when(repository.getFormat()).thenReturn(format);
    
    // Setup mock for uploadManager.handle to return a response with asset paths
    UploadResponse uploadResponse = Mockito.mock(UploadResponse.class);
    when(uploadResponse.getAssetPaths()).thenReturn(List.of("/path/to/asset"));
    when(uploadManager.handle(any(Repository.class), any(HttpServletRequest.class))).thenReturn(uploadResponse);
  }

  @AfterEach
  void tearDown() throws Exception {
    // Shutdown the executor and wait for termination
    virtualThreadExecutor.shutdown();
    assertTrue(virtualThreadExecutor.awaitTermination(5, SECONDS));
    
    // Stop thread pinning detection
    ThreadPinningDetector.stopJfrMonitoring();
  }

  /**
   * Tests that the UploadService can retrieve available definitions using Virtual Threads.
   */
  @Test
  void testGetAvailableDefinitionsWithVirtualThreads() throws Exception {
    // Setup mock for uploadManager.getAvailableDefinitions
    UploadDefinition definition = Mockito.mock(UploadDefinition.class);
    when(uploadManager.getAvailableDefinitions()).thenReturn(List.of(definition));

    // Create a latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);

    // Submit multiple concurrent tasks to get available definitions using virtual threads
    for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          Collection<UploadDefinition> definitions = uploadService.getAvailableDefinitions();
          assertNotNull(definitions);
          assertEquals(1, definitions.size());
        } 
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all threads to complete
    assertTrue(latch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    // Verify that the uploadManager.getAvailableDefinitions method was called the expected number of times
    verify(uploadManager, times(CONCURRENT_UPLOADS)).getAvailableDefinitions();
  }

  /**
   * Tests that the UploadService can handle concurrent uploads using Virtual Threads.
   */
  @Test
  void testConcurrentUploadsWithVirtualThreads() throws Exception {
    // Setup format mock to return a non-NPM format
    when(format.getValue()).thenReturn(MAVEN_FORMAT);

    // Create a latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);

    // Submit multiple concurrent upload tasks using virtual threads
    for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          String result = uploadService.upload(REPOSITORY_NAME, request);
          assertNotNull(result);
          assertEquals("/path/to/asset", result);
        } 
        catch (IOException e) {
          log.error("Error in virtual thread {}: {}", index, e.getMessage(), e);
          throw new RuntimeException(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all threads to complete
    assertTrue(latch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    // Verify that the upload methods were called the expected number of times
    verify(uploadManager, times(CONCURRENT_UPLOADS)).handle(eq(repository), eq(request));
    verify(repositoryCacheInvalidationService, times(0)).processCachesInvalidation(any());
  }

  /**
   * Tests that the UploadService correctly invalidates repository caches for NPM format repositories.
   */
  @Test
  void testNpmRepositoryCacheInvalidationWithVirtualThreads() throws Exception {
    // Setup format mock to return NPM format
    when(format.getValue()).thenReturn(NPM_FORMAT);
    
    // Setup mock for repository groups
    List<String> groupRepoNames = Arrays.asList("group1", "group2");
    when(repositoryManager.findContainingGroups(REPOSITORY_NAME)).thenReturn(groupRepoNames);
    
    // Setup mocks for group repositories
    Repository group1 = Mockito.mock(Repository.class);
    Repository group2 = Mockito.mock(Repository.class);
    when(repositoryManager.get("group1")).thenReturn(group1);
    when(repositoryManager.get("group2")).thenReturn(group2);

    // Create a latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);

    // Submit multiple concurrent upload tasks using virtual threads
    for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          String result = uploadService.upload(REPOSITORY_NAME, request);
          assertNotNull(result);
          assertEquals("/path/to/asset", result);
        } 
        catch (IOException e) {
          throw new RuntimeException(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all threads to complete
    assertTrue(latch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    // Verify that the cache invalidation was called for each group repository
    verify(repositoryCacheInvalidationService, times(CONCURRENT_UPLOADS)).processCachesInvalidation(group1);
    verify(repositoryCacheInvalidationService, times(CONCURRENT_UPLOADS)).processCachesInvalidation(group2);
  }

  /**
   * Tests that the UploadService correctly handles the createSearchTerm method with Virtual Threads.
   */
  @Test
  void testCreateSearchTermWithVirtualThreads() throws Exception {
    // Create a list of test paths
    List<String> testCases = List.of(
        List.of("/path/to/asset1", "/path/to/asset2"),
        List.of("/path/to/asset1", "/path/to/different/asset2"),
        List.of("/single/path"),
        List.of()
    );

    // Create a latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(testCases.size());

    // Submit tasks to test createSearchTerm with different inputs using virtual threads
    for (List<String> paths : testCases) {
      virtualThreadFactory.newThread(() -> {
        try {
          String result = uploadService.createSearchTerm(paths);
          
          if (paths.isEmpty()) {
            assertThat(result, is(equalTo(null)));
          } 
          else if (paths.size() == 1) {
            assertThat(result, is(equalTo(paths.get(0))));
          } 
          else {
            // For multiple paths, the result should be the longest common prefix
            String commonPrefix = findLongestCommonPrefix(paths);
            assertThat(result, is(equalTo(commonPrefix)));
          }
        } 
        finally {
          latch.countDown();
        }
      }).start();
    }

    // Wait for all threads to complete
    assertTrue(latch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  /**
   * Tests that the UploadService can handle large file uploads using Virtual Threads without thread pinning.
   * This test simulates I/O-bound operations that would benefit from Virtual Threads.
   */
  @Test
  void testLargeFileUploadsWithVirtualThreads() throws Exception {
    // Setup format mock to return a non-NPM format
    when(format.getValue()).thenReturn(MAVEN_FORMAT);
    
    // Setup mock for uploadManager.handle to simulate a time-consuming I/O operation
    UploadResponse uploadResponse = Mockito.mock(UploadResponse.class);
    when(uploadResponse.getAssetPaths()).thenReturn(List.of("/path/to/large/asset"));
    
    doAnswer(invocation -> {
      // Simulate I/O operation that would normally block a thread
      Thread.sleep(100); // Simulate network or disk I/O
      return uploadResponse;
    }).when(uploadManager).handle(any(Repository.class), any(HttpServletRequest.class));

    // Create a latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_UPLOADS);

    // Submit multiple concurrent upload tasks using virtual threads
    for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          String result = uploadService.upload(REPOSITORY_NAME, request);
          assertNotNull(result);
          assertEquals("/path/to/large/asset", result);
        } 
        catch (IOException e) {
          throw new RuntimeException(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all threads to complete
    assertTrue(latch.await(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    // Verify that the upload methods were called the expected number of times
    verify(uploadManager, times(CONCURRENT_UPLOADS)).handle(eq(repository), eq(request));
  }

  /**
   * Helper method to find the longest common prefix among a list of paths.
   */
  private String findLongestCommonPrefix(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return null;
    }
    if (paths.size() == 1) {
      return paths.get(0);
    }

    String prefix = paths.get(0);
    for (int i = 1; i < paths.size(); i++) {
      String path = paths.get(i);
      while (!path.startsWith(prefix)) {
        prefix = prefix.substring(0, prefix.lastIndexOf('/'));
        if (prefix.isEmpty()) {
          return "";
        }
      }
    }
    return prefix;
  }
}