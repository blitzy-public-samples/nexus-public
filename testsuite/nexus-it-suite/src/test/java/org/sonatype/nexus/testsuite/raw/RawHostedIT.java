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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.capability.GlobalRepositorySettings;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.raw.ContentDisposition;
import org.sonatype.nexus.repository.raw.ContentDispositionHandler;
import org.sonatype.nexus.testsuite.testsupport.raw.RawClient;
import org.sonatype.nexus.testsuite.testsupport.raw.RawITSupport;

import org.apache.http.HttpResponse;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.lang.Thread.sleep;
import static org.apache.http.entity.ContentType.TEXT_PLAIN;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.repository.http.HttpStatus.BAD_REQUEST;
import static org.sonatype.nexus.repository.http.HttpStatus.CREATED;
import static org.sonatype.nexus.repository.http.HttpStatus.NO_CONTENT;
import static org.sonatype.nexus.repository.http.HttpStatus.NOT_FOUND;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.bytes;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.status;

/**
 * Integration tests for hosted raw repositories using JUnit Jupiter (JUnit 5).
 * 
 * This test class validates basic functionality of raw hosted repositories and demonstrates
 * Java 21 features including virtual threads, pattern matching, record patterns, and string templates.
 */
@DisplayName("Raw Hosted Repository Integration Tests")
public class RawHostedIT
    extends RawITSupport
{
  public static final String HOSTED_REPO = "raw-test-hosted";

  public static final String TEST_CONTENT = "alphabet.txt";

  @Inject
  private GlobalRepositorySettings repositorySettings;

  private Duration originalLastDownloadedInterval;
  
  private RawClient rawClient;

  @BeforeEach
  void createHostedRepository() throws Exception {
    // Save original interval for restoration after tests
    originalLastDownloadedInterval = repositorySettings.getLastDownloadedInterval();
    
    // Create a raw hosted repository for testing
    rawClient = rawClient(repos.createRawHosted(HOSTED_REPO));
  }

  @Test
  @DisplayName("Upload and download content from a raw hosted repository")
  void uploadAndDownload() throws Exception {
    uploadAndDownload(rawClient, TEST_CONTENT);
  }

  @Test
  @DisplayName("Redeploy content to a raw hosted repository")
  void redeploy() throws Exception {
    uploadAndDownload(rawClient, TEST_CONTENT);
    uploadAndDownload(rawClient, TEST_CONTENT);
  }

  @Test
  @DisplayName("Fail when redeployment is not allowed")
  void failWhenRedeployNotAllowed() throws Exception {
    rawClient = rawClient(repos.createRawHosted(getClass().getSimpleName() + "-" + 
        Thread.currentThread().getStackTrace()[1].getMethodName(), "ALLOW_ONCE"));

    File testFile = resolveTestFile(TEST_CONTENT);

    assertThat(rawClient.put(TEST_CONTENT, TEXT_PLAIN, testFile), is(CREATED));

    assertThat(rawClient.put(TEST_CONTENT, TEXT_PLAIN, testFile), is(BAD_REQUEST));
  }

  @Test
  @DisplayName("Set last downloaded timestamp on GET but not on PUT")
  void setLastDownloadOnGetNotPut() throws Exception {
    Repository repository = repos.createRawHosted(getClass().getSimpleName() + "-" + 
        Thread.currentThread().getStackTrace()[1].getMethodName(), "ALLOW_ONCE");

    rawClient = rawClient(repository);

    File testFile = resolveTestFile(TEST_CONTENT);

    assertThat(rawClient.put(TEST_CONTENT, TEXT_PLAIN, testFile), is(CREATED));
    assertThat(getLastDownloadedTime(repository, testFile.getName()), is(equalTo(null)));

    HttpResponse response = rawClient.get(TEST_CONTENT);

    assertAll(
        () -> assertThat(status(response), is(OK)),
        () -> assertThat(bytes(response), is(Files.readAllBytes(testFile.toPath()))),
        () -> assertThat(getLastDownloadedTime(repository, testFile.getName()).isBeforeNow(), is(equalTo(true)))
    );
  }

  @Test
  @DisplayName("Last downloaded timestamp is updated when frequency is configured")
  void lastDownloadedIsUpdatedWhenFrequencyConfigured() throws Exception {
    // Set a short interval to ensure timestamp is updated
    repositorySettings.setLastDownloadedInterval(Duration.ofSeconds(1));

    verifyLastDownloadedTime((newDate, initialDate) -> assertThat(newDate, is(greaterThan(initialDate))));
    
    // Restore original interval
    repositorySettings.setLastDownloadedInterval(originalLastDownloadedInterval);
  }

  @Test
  @DisplayName("Last downloaded timestamp is not updated when frequency is not exceeded")
  void lastDownloadedIsNotUpdatedWhenFrequencyNotExceeded() throws Exception {
    // Set a long interval to ensure timestamp is not updated
    repositorySettings.setLastDownloadedInterval(Duration.ofSeconds(10));

    verifyLastDownloadedTime((newDate, initialDate) -> assertThat(newDate, is(equalTo(initialDate))));
    
    // Restore original interval
    repositorySettings.setLastDownloadedInterval(originalLastDownloadedInterval);
  }

  @Test
  @DisplayName("Inline content disposition sets appropriate header")
  void inlineContentDispositionSetsHeader() throws Exception {
    Configuration configuration = repos.createHosted(getClass().getSimpleName() + "-" + 
        Thread.currentThread().getStackTrace()[1].getMethodName(), "raw-hosted", "ALLOW_ONCE", true);
    configuration.attributes("raw")
        .set(ContentDispositionHandler.CONTENT_DISPOSITION_CONFIG_KEY, ContentDisposition.INLINE.name());

    Repository repository = repos.createRepository(configuration);

    rawClient = rawClient(repository);

    File testFile = resolveTestFile(TEST_CONTENT);

    assertThat(rawClient.put(TEST_CONTENT, TEXT_PLAIN, testFile), is(CREATED));
    assertThat(getLastDownloadedTime(repository, testFile.getName()), is(equalTo(null)));

    HttpResponse response = rawClient.get(TEST_CONTENT);
    assertThat(response.getFirstHeader("Content-Disposition").getValue(), is("inline"));
  }

  @Test
  @DisplayName("Attachment content disposition sets appropriate header")
  void attachmentContentDispositionSetsHeader() throws Exception {
    Configuration configuration = repos.createHosted(getClass().getSimpleName() + "-" + 
        Thread.currentThread().getStackTrace()[1].getMethodName(), "raw-hosted", "ALLOW_ONCE", true);
    configuration.attributes("raw")
        .set(ContentDispositionHandler.CONTENT_DISPOSITION_CONFIG_KEY, ContentDisposition.ATTACHMENT.name());

    Repository repository = repos.createRepository(configuration);

    rawClient = rawClient(repository);

    File testFile = resolveTestFile(TEST_CONTENT);

    assertThat(rawClient.put(TEST_CONTENT, TEXT_PLAIN, testFile), is(CREATED));
    assertThat(getLastDownloadedTime(repository, testFile.getName()), is(equalTo(null)));

    HttpResponse response = rawClient.get(TEST_CONTENT);
    assertThat(response.getFirstHeader("Content-Disposition").getValue(), is("attachment"));
  }
  
  /**
   * Test demonstrating Java 21 Virtual Threads for concurrent operations on a raw hosted repository.
   * This test creates multiple virtual threads to concurrently upload and download content,
   * showcasing the scalability improvements possible with Java 21.
   */
  @Test
  @DisplayName("Concurrent operations using Java 21 Virtual Threads")
  void concurrentOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("raw-test-", 0).factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int taskCount = 50;
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a repository for this test
      Repository repository = repos.createRawHosted(getClass().getSimpleName() + "-virtual-threads");
      RawClient client = rawClient(repository);
      File testFile = resolveTestFile(TEST_CONTENT);
      
      // Create CompletableFuture tasks for concurrent operations
      CompletableFuture<?>[] futures = new CompletableFuture<?>[taskCount];
      
      for (int i = 0; i < taskCount; i++) {
        final String fileName = TEST_CONTENT + "-" + i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Upload file
            int putStatus = client.put(fileName, TEXT_PLAIN, testFile);
            if (putStatus == CREATED) {
              // Download file
              HttpResponse response = client.get(fileName);
              if (status(response) == OK && 
                  java.util.Arrays.equals(bytes(response), Files.readAllBytes(testFile.toPath()))) {
                // Delete file
                int deleteStatus = client.delete(fileName);
                if (deleteStatus == NO_CONTENT) {
                  // Verify deletion
                  if (status(client.get(fileName)) == NOT_FOUND) {
                    successCount.incrementAndGet();
                  }
                }
              }
            }
          } catch (Exception e) {
            // Log exception but don't fail the test
            System.err.println("Error in virtual thread operation: " + e.getMessage());
          }
        }, executor);
      }
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures).join();
      
      // Verify results
      assertThat("All virtual thread operations should succeed", 
          successCount.get(), is(taskCount));
    }
  }
  
  /**
   * Test demonstrating Java 21 Pattern Matching for switch statements.
   * This test uses pattern matching to handle different types of repository configurations.
   */
  @Test
  @DisplayName("Repository configuration handling with Java 21 Pattern Matching")
  void repositoryConfigurationWithPatternMatching() throws Exception {
    // Create test objects
    Repository hostedRepo = repos.createRawHosted(getClass().getSimpleName() + "-pattern-matching-hosted");
    Configuration hostedConfig = hostedRepo.getConfiguration();
    
    // Use pattern matching with switch to determine repository type and verify configuration
    String repoType = switch (hostedConfig.getRepositoryName()) {
      case String name when name.contains("-hosted") -> "hosted";
      case String name when name.contains("-proxy") -> "proxy";
      case String name when name.contains("-group") -> "group";
      case String s -> "unknown";
      default -> "invalid";
    };
    
    assertThat("Repository should be identified as hosted", repoType, is("hosted"));
    
    // Use pattern matching to extract and verify repository attributes
    Object rawAttributes = hostedConfig.attributes("raw");
    String contentDisposition = switch (rawAttributes) {
      case null -> "not-set";
      case Object obj when hostedConfig.attributes("raw").contains(ContentDispositionHandler.CONTENT_DISPOSITION_CONFIG_KEY) ->
          hostedConfig.attributes("raw").get(ContentDispositionHandler.CONTENT_DISPOSITION_CONFIG_KEY).toString();
      default -> "default";
    };
    
    // The default should be "default" since we didn't set a specific content disposition
    assertThat("Content disposition should have default value", contentDisposition, is("default"));
  }
  
  /**
   * Test demonstrating Java 21 Record Patterns for structured data extraction.
   * This test uses record patterns to extract and validate repository information.
   */
  @Test
  @DisplayName("Repository data extraction with Java 21 Record Patterns")
  void repositoryDataWithRecordPatterns() throws Exception {
    // Define records for repository data
    record RepositoryInfo(String name, String format, String type) {}
    record RepositoryData(RepositoryInfo info, Configuration config) {}
    
    // Create test repository
    Repository hostedRepo = repos.createRawHosted(getClass().getSimpleName() + "-record-patterns");
    Configuration config = hostedRepo.getConfiguration();
    
    // Create structured data
    RepositoryData repoData = new RepositoryData(
        new RepositoryInfo(config.getRepositoryName(), config.getRecipeName(), "hosted"),
        config
    );
    
    // Use record pattern to extract and validate data
    if (repoData instanceof RepositoryData(RepositoryInfo(String name, String format, var type), var cfg)) {
      assertAll(
          () -> assertTrue(name.contains("record-patterns"), "Repository name should contain 'record-patterns'"),
          () -> assertEquals("raw-hosted", format, "Repository format should be 'raw-hosted'"),
          () -> assertEquals("hosted", type, "Repository type should be 'hosted'"),
          () -> assertNotNull(cfg, "Configuration should not be null")
      );
    } else {
      throw new AssertionError("Record pattern matching failed");
    }
  }
  
  /**
   * Test demonstrating Java 21 String Templates for dynamic string creation.
   * This test uses string templates to generate repository paths and validate responses.
   */
  @Test
  @DisplayName("Dynamic content paths with Java 21 String Templates")
  void dynamicContentPathsWithStringTemplates() throws Exception {
    // Create test repository
    Repository repository = repos.createRawHosted(getClass().getSimpleName() + "-string-templates");
    RawClient client = rawClient(repository);
    File testFile = resolveTestFile(TEST_CONTENT);
    
    // Create nested directory structure using string templates
    String baseDir = "test-dir";
    int levels = 3;
    
    for (int i = 1; i <= levels; i++) {
      // Use string template to create path
      String path = STR."\{baseDir}/level-\{i}/\{TEST_CONTENT}";
      
      // Upload file to this path
      assertThat(client.put(path, TEXT_PLAIN, testFile), is(CREATED));
      
      // Download and verify
      HttpResponse response = client.get(path);
      assertAll(
          () -> assertThat(status(response), is(OK)),
          () -> assertThat(bytes(response), is(Files.readAllBytes(testFile.toPath())))
      );
      
      // Use string template for logging
      System.out.println(STR."Successfully verified path: \{path} at level \{i}");
    }
    
    // Verify all paths exist
    for (int i = 1; i <= levels; i++) {
      String path = STR."\{baseDir}/level-\{i}/\{TEST_CONTENT}";
      assertThat(status(client.get(path)), is(OK));
    }
  }

  /**
   * Helper method to verify last downloaded time behavior.
   */
  private void verifyLastDownloadedTime(final BiConsumer<DateTime, DateTime> matcher) throws Exception {
    Repository repository = repos.createRawHosted(getClass().getSimpleName() + "-" + 
        Thread.currentThread().getStackTrace()[1].getMethodName(), "ALLOW_ONCE");

    RawClient rawClient = rawClient(repository);

    File testFile = resolveTestFile(TEST_CONTENT);
    assertThat(rawClient.put(TEST_CONTENT, TEXT_PLAIN, testFile), is(CREATED));

    rawClient.get(TEST_CONTENT);
    DateTime firstLastDownloadedTime = getLastDownloadedTime(repository, testFile.getName());

    sleep(2000);

    rawClient.get(TEST_CONTENT);
    DateTime newLastDownloadedTime = getLastDownloadedTime(repository, testFile.getName());

    matcher.accept(newLastDownloadedTime, firstLastDownloadedTime);
  }
}