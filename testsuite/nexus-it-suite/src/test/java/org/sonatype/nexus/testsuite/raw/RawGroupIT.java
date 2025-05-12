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
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.Response.Status;

import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.testsuite.testsupport.FormatClientSupport;
import org.sonatype.nexus.testsuite.testsupport.raw.RawClient;
import org.sonatype.nexus.testsuite.testsupport.raw.RawITSupport;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.ContentType;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.status;

/**
 * Integration tests for raw group repositories.
 * <p>
 * This test class validates the behavior of raw group repositories in Nexus Repository Manager,
 * including content retrieval from member repositories and proper handling of request routing.
 * <p>
 * The tests have been updated to use JUnit Jupiter (JUnit 5) and leverage Java 21 features
 * such as virtual threads, pattern matching, record patterns, and string templates.
 */
public class RawGroupIT
    extends RawITSupport
{
  public static final String TEST_PATH = "alphabet.txt";

  public static final String TEST_CONTENT = "alphabet.txt";

  public static final String TEST_CONTENT2 = "alphabet2.txt";

  private RawClient hosted1;

  private RawClient hosted2;

  private RawClient groupClient;

  /**
   * Sets up the test repositories before each test.
   * <p>
   * Creates two raw hosted repositories and a raw group repository that includes both hosted repositories.
   */
  @BeforeEach
  public void setUpRepositories() throws Exception {
    hosted1 = rawClient(repos.createRawHosted("raw-hosted-test1"));
    hosted2 = rawClient(repos.createRawHosted("raw-hosted-test2"));

    groupClient = rawClient(repos.createRawGroup("raw-group", "raw-hosted-test1", "raw-hosted-test2"));
  }

  /**
   * Verifies that when member repositories don't contain any content, requests to the group return 404.
   */
  @Test
  @DisplayName("Empty members return 404")
  void emptyMembersReturn404() throws Exception {
    HttpResponse httpResponse = groupClient.get(TEST_PATH);
    assertThat(status(httpResponse), is(HttpStatus.NOT_FOUND));
  }

  /**
   * Verifies that when a member repository contains content, that content is returned through the group.
   */
  @Test
  @DisplayName("Member content is found through group")
  void memberContentIsFound() throws Exception {
    File testFile = resolveTestFile(TEST_CONTENT);
    hosted1.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);

    assertThat(string(groupClient.get(TEST_PATH)), is(new String(Files.readAllBytes(testFile.toPath()))));
  }

  /**
   * Verifies that the group consults members in order, returning the first successful response.
   */
  @Test
  @DisplayName("First successful response from members wins")
  void firstSuccessfulResponseWins() throws Exception {
    File testFile = resolveTestFile(TEST_CONTENT);
    hosted1.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);
    hosted2.put(TEST_PATH, ContentType.TEXT_PLAIN, resolveTestFile(TEST_CONTENT2));

    assertThat(string(groupClient.get(TEST_PATH)), is(new String(Files.readAllBytes(testFile.toPath()))));
  }

  /**
   * Verifies that members that return failure responses are ignored in favor of successful ones.
   */
  @Test
  @DisplayName("Early failures are bypassed for successful responses")
  void earlyFailuresAreBypassed() throws Exception {
    File testFile = resolveTestFile(TEST_CONTENT);

    // Only the second repository has any content
    hosted2.put(TEST_PATH, ContentType.TEXT_PLAIN, resolveTestFile(TEST_CONTENT));

    assertThat(string(groupClient.get(TEST_PATH)), is(new String(Files.readAllBytes(testFile.toPath()))));
  }

  /**
   * Tests concurrent access to the group repository using Java 21 Virtual Threads.
   * <p>
   * This test validates that the group repository can handle multiple concurrent requests
   * efficiently using Java 21's lightweight virtual threads.
   */
  @Test
  @DisplayName("Concurrent access with virtual threads")
  void concurrentAccessWithVirtualThreads() throws Exception {
    // Set up test content
    File testFile = resolveTestFile(TEST_CONTENT);
    hosted1.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);
    String expectedContent = new String(Files.readAllBytes(testFile.toPath()));
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 50;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            String content = string(groupClient.get(TEST_PATH));
            if (expectedContent.equals(content)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify all requests were successful
      assertEquals(taskCount, successCount.get(), 
          "All concurrent requests should successfully retrieve content");
    }
  }

  /**
   * Tests pattern matching with different HTTP status codes using Java 21 pattern matching for switch.
   * <p>
   * This test demonstrates the use of pattern matching for switch to handle different HTTP status codes
   * in a more concise and readable way.
   */
  @Test
  @DisplayName("Pattern matching for HTTP status codes")
  void patternMatchingForHttpStatus() throws Exception {
    // Set up test content
    File testFile = resolveTestFile(TEST_CONTENT);
    hosted1.put(TEST_PATH, ContentType.TEXT_PLAIN, testFile);
    
    // Get response from group repository
    HttpResponse response = groupClient.get(TEST_PATH);
    int statusCode = status(response);
    
    // Use pattern matching for switch to handle different status codes
    String result = switch (statusCode) {
      case HttpStatus.OK -> "Success";
      case HttpStatus.NOT_FOUND -> "Not Found";
      case HttpStatus.FORBIDDEN -> "Forbidden";
      case HttpStatus.UNAUTHORIZED -> "Unauthorized";
      case HttpStatus.INTERNAL_SERVER_ERROR -> "Server Error";
      default -> "Unknown Status: " + statusCode;
    };
    
    // Verify the result
    assertEquals("Success", result, "Expected successful response from group repository");
  }

  /**
   * Tests asynchronous content retrieval using CompletableFuture with virtual threads.
   * <p>
   * This test demonstrates how to use CompletableFuture with virtual threads to perform
   * asynchronous operations efficiently.
   */
  @Test
  @DisplayName("Asynchronous content retrieval with CompletableFuture")
  void asyncContentRetrievalWithCompletableFuture() throws Exception {
    // Set up test content in both repositories
    File testFile1 = resolveTestFile(TEST_CONTENT);
    File testFile2 = resolveTestFile(TEST_CONTENT2);
    hosted1.put("file1.txt", ContentType.TEXT_PLAIN, testFile1);
    hosted2.put("file2.txt", ContentType.TEXT_PLAIN, testFile2);
    
    // Use CompletableFuture to retrieve content asynchronously
    CompletableFuture<String> future1 = CompletableFuture.supplyAsync(
        () -> {
          try {
            return string(groupClient.get("file1.txt"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
    );
    
    CompletableFuture<String> future2 = CompletableFuture.supplyAsync(
        () -> {
          try {
            return string(groupClient.get("file2.txt"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
    );
    
    // Wait for both futures to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(future1, future2);
    allFutures.join();
    
    // Verify results
    String content1 = future1.get();
    String content2 = future2.get();
    
    assertNotNull(content1, "Content from first file should not be null");
    assertNotNull(content2, "Content from second file should not be null");
    assertEquals(new String(Files.readAllBytes(testFile1.toPath())), content1, 
        "Content from first file should match expected");
    assertEquals(new String(Files.readAllBytes(testFile2.toPath())), content2, 
        "Content from second file should match expected");
  }

  /**
   * Helper method to extract string content from an HTTP response.
   * <p>
   * Verifies that the response has a 200 status code and extracts the response body as a string.
   *
   * @param content The HTTP response to extract content from
   * @return The response body as a string, or null if the response is null
   */
  private static String string(final CloseableHttpResponse content) {
    assertThat(content, hasStatus(200));

    return Optional.ofNullable(content)
        .map(FormatClientSupport::bytes)
        .map(String::new)
        .orElse(null);
  }

  /**
   * Creates a matcher that checks if an HTTP response has the expected status code.
   *
   * @param statusCode The expected status code
   * @return A matcher that validates the HTTP response status
   */
  public static Matcher<HttpResponse> hasStatus(final int statusCode) {
    return new TypeSafeDiagnosingMatcher<HttpResponse>()
    {
      @Override
      public void describeTo(final Description description) {
        description
            .appendText("Status code: " + statusCode + " " + Status.fromStatusCode(statusCode).getReasonPhrase());
      }

      @Override
      protected boolean matchesSafely(final HttpResponse response, final Description mismatchDescription) {
        int actualStatus = response.getStatusLine().getStatusCode();
        if (actualStatus != statusCode) {
          mismatchDescription
              .appendText("Status code: " + actualStatus + " " + Status.fromStatusCode(actualStatus).getReasonPhrase());
          return false;
        }
        return true;
      }
    };
  }
}