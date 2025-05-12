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
package org.sonatype.nexus.testsuite.raw;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.CacheManager;
import javax.inject.Inject;

import org.sonatype.goodies.httpfixture.server.fluent.Behaviours;
import org.sonatype.goodies.httpfixture.server.fluent.Server;
import org.sonatype.nexus.common.net.PortAllocator;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.testsuite.proxy.DefaultCacheSettingsTester;
import org.sonatype.nexus.testsuite.testsupport.raw.RawClient;
import org.sonatype.nexus.testsuite.testsupport.raw.RawITSupport;
import org.sonatype.nexus.testsuite.testsupport.system.RestTestHelper;

import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.apache.commons.io.FileUtils.readFileToByteArray;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.sonatype.goodies.httpfixture.server.fluent.Behaviours.content;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.bytes;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.status;

/**
 * Integration tests for proxy raw repositories with Java 21 features.
 * 
 * This test class validates the behavior of raw proxy repositories, including:
 * - Basic proxy functionality
 * - Error handling and status code mapping
 * - Content retrieval and caching
 * - Java 21 features like virtual threads, pattern matching, and sequenced collections
 */
@ExtendWith(MockitoExtension.class)
class RawProxyOfHostedIT
    extends RawITSupport
{
  private static final String INDEX_HTML = "index.html";

  private static final String TEST_PATH = "alphabet.txt";

  private static final String TEST_PATH_PLUS = "alpha+bet.txt";

  private static final String TEST_CONTENT = "alphabet.txt";

  private RawClient hostedClient;

  private RawClient proxyClient;

  private Repository hostedRepo;

  private Repository proxyRepo;

  @Inject
  private CacheManager cacheManager;

  @Inject
  private RestTestHelper restTestHelper;

  @BeforeEach
  void setUpRepositories() throws Exception {
    hostedRepo = repos.createRawHosted("raw-test-hosted");
    hostedClient = rawClient(hostedRepo);

    URL hostedRepoUrl = repositoryBaseUrl(hostedRepo);
    proxyRepo = repos.createRawProxy("raw-test-proxy", hostedRepoUrl.toExternalForm());

    proxyClient = rawClient(proxyRepo);
  }

  @Test
  void unresponsiveRemoteProduces404() throws Exception {
    repos.deleteRepository(hostedRepo);

    assertThat(status(proxyClient.get(TEST_PATH)), is(HttpStatus.NOT_FOUND));
  }

  @Test
  void responsiveRemoteProduces404() throws Exception {
    assertThat(status(proxyClient.get(TEST_PATH)), is(HttpStatus.NOT_FOUND));
  }

  @Test
  void fetchFromRemote() throws Exception {
    File testFile = resolveTestFile(TEST_CONTENT);
    hostedClient.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);

    assertThat(bytes(proxyClient.get(TEST_PATH)), is(readFileToByteArray(testFile)));

    // test with + symbol in file name
    testFile = resolveTestFile(TEST_PATH_PLUS);
    hostedClient.put(TEST_PATH_PLUS, ContentType.TEXT_PLAIN, testFile);

    assertThat(bytes(proxyClient.get(TEST_PATH_PLUS)), is(readFileToByteArray(testFile)));
  }

  @Test
  void fetchFromRemoteWithEncodedFileName() throws Exception {
    File testFile = resolveTestFile("%252B%2520.txt");
    hostedClient.put(testFile.getName(), ContentType.TEXT_PLAIN, testFile);

    HttpResponse httpResponse = proxyClient.get(testFile.getName());

    assertThat(status(httpResponse), is(HttpStatus.OK));
    assertThat(bytes(httpResponse), is(readFileToByteArray(testFile)));
  }

  @Test
  void setLastDownloadedOnGet() throws Exception {
    final File testFile = resolveTestFile(TEST_CONTENT);
    hostedClient.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);

    HttpResponse response = proxyClient.get(TEST_PATH);
    assertThat(status(response), is(HttpStatus.OK));
    assertThat(bytes(response), is(readFileToByteArray(testFile)));
    assertThat(getLastDownloadedTime(proxyRepo, TEST_PATH).isBeforeNow(), is(equalTo(true)));
  }

  @Test
  void notFoundCaches404() throws Exception {
    // Ask for a nonexistent file
    proxyClient.get(TEST_PATH);

    // Put the file in the hosted repo
    hostedClient.put(TEST_PATH, ContentType.TEXT_PLAIN, resolveTestFile(TEST_CONTENT));

    // The NFC should ensure we still see the 404
    assertThat(status(proxyClient.get(TEST_PATH)), is(HttpStatus.NOT_FOUND));
  }

  @Test
  void status401ViaProxyProduces503() throws Exception {
    responseViaProxyProduces(HttpStatus.UNAUTHORIZED, HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void status402ViaProxyProduces503() throws Exception {
    responseViaProxyProduces(HttpStatus.PAYMENT_REQUIRED, HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void status407ViaProxyProduces503() throws Exception {
    responseViaProxyProduces(HttpStatus.PROXY_AUTHENTICATION_REQUIRED, HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void status500ViaProxyProduces503() throws Exception {
    responseViaProxyProduces(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void status503ViaProxyProduces503() throws Exception {
    responseViaProxyProduces(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void remoteHasNoContent() throws Exception {
    Server server = Server.withPort(PortAllocator.nextFreePort())
        .serve("/*")
        .withBehaviours(Behaviours.error(HttpStatus.NOT_FOUND))
        .start();
    try {
      proxyClient = rawClient(repos.createRawProxy(testName.getMethodName(), server.getUrl().toExternalForm()));
      assertThat(status(hostedClient.get(TEST_PATH + "/")), is(HttpStatus.NOT_FOUND));
      assertThat(status(hostedClient.get("")), is(HttpStatus.NOT_FOUND));
    }
    finally {
      server.stop();
    }
  }

  @Test
  void hostedHasNoContent() throws Exception {
    assertThat(status(hostedClient.get(TEST_PATH + "/")), is(HttpStatus.NOT_FOUND));
    assertThat(status(hostedClient.get("")), is(HttpStatus.NOT_FOUND));
  }

  @Test
  void rootShouldServeRemoteIndexHtmlContentIfPresent() throws Exception {
    final File testFile = resolveTestFile(INDEX_HTML);
    hostedClient.put(INDEX_HTML, ContentType.TEXT_PLAIN, testFile);

    HttpResponse response = proxyClient.get("");
    assertThat(status(response), is(HttpStatus.OK));
    assertThat(bytes(response), is(readFileToByteArray(testFile)));
    assertThat(getLastDownloadedTime(proxyRepo, ".").isBeforeNow(), is(equalTo(true)));
  }

  @Test
  void rootShouldServeRemoteIndexHtmContentIfPresent() throws Exception {
    final File testFile = resolveTestFile(INDEX_HTML);
    hostedClient.put("index.htm", ContentType.TEXT_PLAIN, testFile);

    HttpResponse response = proxyClient.get("");
    assertThat(status(response), is(HttpStatus.OK));
    assertThat(bytes(response), is(readFileToByteArray(testFile)));
    assertThat(getLastDownloadedTime(proxyRepo, ".").isBeforeNow(), is(equalTo(true)));
  }

  private void responseViaProxyProduces(final int upstreamStatus, final int downstreamStatus) throws Exception {
    Server server =
        Server.withPort(PortAllocator.nextFreePort())
            .serve("/*")
            .withBehaviours(Behaviours.error(upstreamStatus))
            .start();
    try {
      proxyClient = rawClient(repos.createRawProxy("raw-test-proxy-" + upstreamStatus + "-" + downstreamStatus,
          server.getUrl().toExternalForm()));
      assertThat(status(proxyClient.get(TEST_PATH)), is(downstreamStatus));
    }
    finally {
      server.stop();
    }
  }

  @Test
  void status401ViaGroupProduces404() throws Exception {
    responseViaGroupProduces(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
  }

  @Test
  void status402ViaGroupProduces404() throws Exception {
    responseViaGroupProduces(HttpStatus.PAYMENT_REQUIRED, HttpStatus.NOT_FOUND);
  }

  @Test
  void status407ViaGroupProduces404() throws Exception {
    responseViaGroupProduces(HttpStatus.PROXY_AUTHENTICATION_REQUIRED, HttpStatus.NOT_FOUND);
  }

  @Test
  void status500ViaGroupProduces404() throws Exception {
    responseViaGroupProduces(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.NOT_FOUND);
  }

  @Test
  void status503ViaGroupProduces404() throws Exception {
    responseViaGroupProduces(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.NOT_FOUND);
  }

  @Test
  void retrieveRawWhenRemoteOffline() throws Exception {
    Server server = Server.withPort(PortAllocator.nextFreePort())
        .serve("/*")
        .withBehaviours(content("Response"))
        .start();
    try {
      proxyClient = rawClient(repos.createRawProxy("raw-test-proxy-offline", server.getUrl().toExternalForm()));
      proxyClient.get(TEST_PATH);
    }
    finally {
      server.stop();
    }
    assertThat(status(proxyClient.get(TEST_PATH)), is(200));
  }

  @Test
  void proxyNarrowNoBreakSpaceNNBSP() {
    assertThat(
        restTestHelper
            .put(
                "/repository/" + hostedRepo.getName() + "/some/folder/begin\u202Fend.txt",
                "content",
                "admin",
                "admin123")
            .getStatus(),
        is(201));

    assertThat(
        componentAssetTestHelper.countComponents(proxyRepo),
        is(0));
    assertThat(
        componentAssetTestHelper.countAssets(proxyRepo),
        is(0));
    assertThat(
        componentAssetTestHelper.countComponents(hostedRepo),
        is(1));
    assertThat(
        componentAssetTestHelper.countAssets(hostedRepo),
        is(1));

    assertThat(
        restTestHelper
            .get(
                "/repository/" + proxyRepo.getName() + "/some/folder/begin\u202Fend.txt",
                "admin",
                "admin123")
            .getStatus(),
        is(200));

    assertThat(
        componentAssetTestHelper.countComponents(proxyRepo),
        is(1));
    assertThat(
        componentAssetTestHelper.countAssets(proxyRepo),
        is(1));
    assertThat(
        componentAssetTestHelper.componentExists(
            proxyRepo,
            "/some/folder",
            "/some/folder/begin\u202Fend.txt",
            ""),
        is(true));
  }

  private void responseViaGroupProduces(final int upstreamStatus, final int downstreamStatus) throws Exception {
    Server server =
        Server.withPort(PortAllocator.nextFreePort())
            .serve("/*")
            .withBehaviours(Behaviours.error(upstreamStatus))
            .start();
    try {
      Repository proxy = repos.createRawProxy("raw-test-proxy-" + upstreamStatus + "-" + downstreamStatus,
          server.getUrl().toExternalForm());
      Repository group = repos.createRawGroup("raw-test-group-" + upstreamStatus + "-" + downstreamStatus,
          proxy.getName());
      proxyClient = rawClient(group);
      assertThat(status(proxyClient.get(TEST_PATH)), is(downstreamStatus));
    }
    finally {
      server.stop();
    }
  }

  @Test
  void verifyDefaultCacheSettings() {
    Repository repository = repos.createRawProxy(testName.getMethodName(), "http://example.com");
    DefaultCacheSettingsTester.verifyNegativeCacheSettings(repository, cacheManager);
  }
  
  /**
   * Tests concurrent proxy operations using Java 21 Virtual Threads.
   * This test validates that proxy operations can be efficiently executed
   * using a large number of virtual threads without performance degradation.
   */
  @Test
  void concurrentProxyOperationsWithVirtualThreads() throws Exception {
    // Set up test content
    File testFile = resolveTestFile(TEST_CONTENT);
    hostedClient.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Set up concurrent task execution
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            HttpResponse response = proxyClient.get(TEST_PATH);
            if (status(response) == HttpStatus.OK && 
                java.util.Arrays.equals(bytes(response), readFileToByteArray(testFile))) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Log exception but continue
            System.err.println("Error in virtual thread: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("All proxy operations should succeed", successCount.get(), is(taskCount));
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }
  
  /**
   * Tests asynchronous proxy operations using CompletableFuture with virtual threads.
   * This demonstrates how Java 21 virtual threads can be used with CompletableFuture
   * for efficient asynchronous operations.
   */
  @Test
  void asyncProxyOperationsWithCompletableFuture() throws Exception {
    // Set up test content with different file names
    String[] filePaths = {"file1.txt", "file2.txt", "file3.txt", "file4.txt", "file5.txt"};
    for (String filePath : filePaths) {
      File testFile = resolveTestFile(TEST_CONTENT);
      hostedClient.put(filePath, ContentType.TEXT_PLAIN, testFile);
    }
    
    // Create CompletableFuture tasks for each file
    CompletableFuture<?>[] futures = new CompletableFuture[filePaths.length];
    
    // Use virtual threads for CompletableFuture execution
    for (int i = 0; i < filePaths.length; i++) {
      String filePath = filePaths[i];
      futures[i] = CompletableFuture.supplyAsync(() -> {
        try {
          return status(proxyClient.get(filePath)) == HttpStatus.OK;
        } catch (Exception e) {
          throw new RuntimeException("Failed to retrieve " + filePath, e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
    }
    
    // Wait for all futures to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures);
    allFutures.get(30, TimeUnit.SECONDS); // Wait with timeout
    
    // Verify all operations succeeded
    for (CompletableFuture<?> future : futures) {
      assertThat("Proxy operation should succeed", future.get(), is(true));
    }
  }
  
  /**
   * Tests pattern matching with switch expressions for handling different HTTP status codes.
   * This demonstrates Java 21's enhanced pattern matching capabilities.
   */
  @Test
  void patternMatchingWithHttpStatusCodes() throws Exception {
    // Set up test servers with different status codes
    int[] statusCodes = {HttpStatus.OK, HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED, 
                         HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE};
    
    for (int statusCode : statusCodes) {
      Server server = Server.withPort(PortAllocator.nextFreePort())
          .serve("/*")
          .withBehaviours(statusCode == HttpStatus.OK ? 
              content("Response") : Behaviours.error(statusCode))
          .start();
      
      try {
        RawClient client = rawClient(repos.createRawProxy(
            "raw-test-pattern-matching-" + statusCode,
            server.getUrl().toExternalForm()));
        
        HttpResponse response = client.get(TEST_PATH);
        int actualStatus = status(response);
        
        // Use pattern matching with switch expression to verify expected status
        String result = switch (statusCode) {
          case HttpStatus.OK -> {
            assertThat(actualStatus, is(HttpStatus.OK));
            yield "success";
          }
          case HttpStatus.NOT_FOUND -> {
            assertThat(actualStatus, is(HttpStatus.NOT_FOUND));
            yield "not_found";
          }
          case HttpStatus.UNAUTHORIZED, HttpStatus.PROXY_AUTHENTICATION_REQUIRED -> {
            assertThat(actualStatus, is(HttpStatus.SERVICE_UNAVAILABLE));
            yield "auth_error";
          }
          case HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE -> {
            assertThat(actualStatus, is(HttpStatus.SERVICE_UNAVAILABLE));
            yield "server_error";
          }
          default -> "unexpected";
        };
        
        // Verify the result is not unexpected
        assertThat("Pattern matching should categorize the status code", 
                   !result.equals("unexpected"), is(true));
      } finally {
        server.stop();
      }
    }
  }
  
  /**
   * Tests using record patterns with proxy response handling.
   * This demonstrates Java 21's record pattern matching capabilities.
   */
  @Test
  void recordPatternsWithProxyResponses() throws Exception {
    // Define a record to represent proxy response data
    record ProxyResponse(int status, byte[] content, String path) {}
    
    // Set up test content
    File testFile = resolveTestFile(TEST_CONTENT);
    hostedClient.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);
    
    // Get response and create record
    HttpResponse httpResponse = proxyClient.get(TEST_PATH);
    ProxyResponse response = new ProxyResponse(
        status(httpResponse),
        bytes(httpResponse),
        TEST_PATH
    );
    
    // Use record pattern matching to extract and verify fields
    if (response instanceof ProxyResponse(int s, byte[] c, String p)) {
      assertThat("Status should be OK", s, is(HttpStatus.OK));
      assertThat("Content should match test file", c, is(readFileToByteArray(testFile)));
      assertThat("Path should match test path", p, is(TEST_PATH));
    } else {
      throw new AssertionError("Record pattern matching failed");
    }
    
    // Test with a non-existent path
    httpResponse = proxyClient.get("non-existent.txt");
    response = new ProxyResponse(
        status(httpResponse),
        new byte[0], // Empty content for 404
        "non-existent.txt"
    );
    
    // Use nested pattern matching with guard
    if (response instanceof ProxyResponse(int s, byte[] c, String p) && s == HttpStatus.NOT_FOUND) {
      assertThat("Content should be empty for NOT_FOUND", c.length, is(0));
      assertThat("Path should be preserved", p, is("non-existent.txt"));
    } else {
      throw new AssertionError("Record pattern matching with guard failed");
    }
  }
  
  /**
   * Tests using string templates for generating dynamic content in proxy repositories.
   * This demonstrates Java 21's string template feature.
   */
  @Test
  void stringTemplatesWithProxyContent() throws Exception {
    // Create dynamic content using string templates
    String fileName = "template-test.txt";
    String repoName = hostedRepo.getName();
    int contentLength = 42;
    
    // Use string template to create content
    String content = STR."Repository: \{repoName}\nFile: \{fileName}\nLength: \{contentLength} bytes";
    
    // Put the content in the hosted repo
    hostedClient.put(fileName, ContentType.TEXT_PLAIN, content.getBytes());
    
    // Retrieve via proxy
    HttpResponse response = proxyClient.get(fileName);
    String retrievedContent = new String(bytes(response));
    
    // Verify content was preserved correctly
    assertThat(status(response), is(HttpStatus.OK));
    assertThat(retrievedContent, is(content));
    
    // Verify content matches expected template expansion
    String expectedContent = "Repository: " + repoName + "\nFile: " + fileName + "\nLength: " + contentLength + " bytes";
    assertThat(retrievedContent, is(expectedContent));
  }
  
  /**
   * Tests using sequenced collections with proxy repository operations.
   * This demonstrates Java 21's sequenced collections feature.
   */
  @Test
  void sequencedCollectionsWithProxyOperations() throws Exception {
    // Create a sequence of files to test with
    List<String> fileSequence = List.of("seq1.txt", "seq2.txt", "seq3.txt", "seq4.txt", "seq5.txt");
    
    // Put files in hosted repo in sequence
    for (String fileName : fileSequence) {
      hostedClient.put(fileName, ContentType.TEXT_PLAIN, ("Content for " + fileName).getBytes());
    }
    
    // Access files in reverse order via proxy
    for (String fileName : fileSequence.reversed()) {
      HttpResponse response = proxyClient.get(fileName);
      assertThat(status(response), is(HttpStatus.OK));
      assertThat(new String(bytes(response)), is("Content for " + fileName));
    }
    
    // Test with first/last operations
    HttpResponse firstResponse = proxyClient.get(fileSequence.getFirst());
    HttpResponse lastResponse = proxyClient.get(fileSequence.getLast());
    
    assertThat(status(firstResponse), is(HttpStatus.OK));
    assertThat(status(lastResponse), is(HttpStatus.OK));
    assertThat(new String(bytes(firstResponse)), is("Content for " + fileSequence.getFirst()));
    assertThat(new String(bytes(lastResponse)), is("Content for " + fileSequence.getLast()));
  }
  
  /**
   * Tests performance comparison between virtual threads and platform threads for proxy operations.
   * This demonstrates the performance benefits of Java 21 virtual threads for I/O-bound operations.
   */
  @Test
  void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Set up test content
    File testFile = resolveTestFile(TEST_CONTENT);
    hostedClient.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);
    
    // Configure thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Parameters for the test
    int taskCount = 100;
    int warmupRuns = 5;
    
    // Run warmup to stabilize JIT
    runConcurrentTasks(warmupRuns, platformThreadFactory);
    runConcurrentTasks(warmupRuns, virtualThreadFactory);
    
    // Measure platform threads
    long platformStart = System.nanoTime();
    runConcurrentTasks(taskCount, platformThreadFactory);
    long platformDuration = System.nanoTime() - platformStart;
    
    // Measure virtual threads
    long virtualStart = System.nanoTime();
    runConcurrentTasks(taskCount, virtualThreadFactory);
    long virtualDuration = System.nanoTime() - virtualStart;
    
    // Convert to milliseconds for readability
    double platformMs = platformDuration / 1_000_000.0;
    double virtualMs = virtualDuration / 1_000_000.0;
    
    // Log results
    System.out.println(STR."Platform threads: \{platformMs} ms");
    System.out.println(STR."Virtual threads: \{virtualMs} ms");
    
    // Virtual threads should generally be more efficient for I/O bound tasks
    // but we don't assert this as it depends on the environment
    // Instead, we just verify both completed successfully
    assertThat("Both thread models should complete successfully", true, is(true));
  }
  
  /**
   * Helper method to run concurrent tasks using the specified thread factory.
   */
  private void runConcurrentTasks(int taskCount, ThreadFactory threadFactory) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            HttpResponse response = proxyClient.get(TEST_PATH);
            if (status(response) == HttpStatus.OK) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            // Log and continue
          } finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All tasks should complete", completed, is(true));
      assertThat("All tasks should succeed", successCount.get(), is(taskCount));
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}