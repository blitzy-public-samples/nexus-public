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
package org.sonatype.nexus.content.testsupport.raw;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.sonatype.nexus.content.testsupport.FormatClientSupport;
import org.sonatype.nexus.content.testsupport.NexusITSupport;
import org.sonatype.nexus.content.testsupport.fixtures.RepositoryRule;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.apache.http.entity.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Support class for Raw repository integration tests.
 * <p>
 * Updated for Java 21 compatibility with support for Virtual Threads testing.
 * This class provides utilities for testing Raw repositories with both platform
 * and virtual threads to validate concurrent operations.
 *
 * @since 3.60
 */
public class RawITSupport
    extends NexusITSupport
{
  protected static final String SLASH_REPO_SLASH = "/repository/";

  @Inject
  protected RepositoryManager repositoryManager;

  @RegisterExtension
  public RepositoryRule repos = createRepositoryRule();

  public RawITSupport() {
    testData.addDirectory(resolveBaseFile("target/it-resources/raw"));
  }

  protected RepositoryRule createRepositoryRule() {
    return new RepositoryRule(() -> repositoryManager);
  }

  @Nonnull
  protected URL repositoryBaseUrl(final Repository repository) {
    return resolveUrl(nexusUrl, SLASH_REPO_SLASH + repository.getName() + "/");
  }

  @Nonnull
  protected RawClient rawClient(final Repository repository) throws Exception {
    checkNotNull(repository);
    return rawClient(repositoryBaseUrl(repository));
  }

  protected RawClient rawClient(final URL repositoryUrl) throws Exception {
    return new RawClient(
        clientBuilder(repositoryUrl).build(),
        clientContext(),
        repositoryUrl.toURI());
  }

  /**
   * Uploads, downloads, and then deletes a file using the provided RawClient.
   * 
   * @param rawClient the client to use for operations
   * @param file the file path to test with
   * @throws Exception if any operation fails
   */
  protected void uploadAndDownload(final RawClient rawClient, final String file) throws Exception {
    final File testFile = resolveTestFile(file);
    final int response = rawClient.put(file, ContentType.TEXT_PLAIN, testFile);
    MatcherAssert.assertThat(response, Matchers.is(HttpStatus.CREATED));

    MatcherAssert.assertThat(FormatClientSupport.bytes(rawClient.get(file)), is(Files.readAllBytes(testFile.toPath())));

    MatcherAssert.assertThat(FormatClientSupport.status(rawClient.delete(file)), Matchers.is(HttpStatus.NO_CONTENT));

    assertThat("content should be deleted", FormatClientSupport.status(rawClient.get(file)),
        Matchers.is(HttpStatus.NOT_FOUND));
  }
  
  /**
   * Creates a Virtual Thread factory for use in concurrent testing.
   * 
   * @return a ThreadFactory that creates virtual threads
   */
  protected ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }
  
  /**
   * Creates a Platform Thread factory for use in concurrent testing.
   * 
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates platform threads
   */
  protected ThreadFactory createPlatformThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread t = Thread.ofPlatform().name(namePrefix + "-" + counter.incrementAndGet()).build();
      t.setDaemon(true);
      return t;
    };
  }
  
  /**
   * Creates an ExecutorService using Virtual Threads.
   * 
   * @return an ExecutorService that creates a new virtual thread for each task
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Performs concurrent upload and download operations using Virtual Threads.
   * This method is useful for testing repository performance and correctness under concurrent load.
   * 
   * @param rawClient the client to use for operations
   * @param file the file path to test with
   * @param concurrentOperations the number of concurrent operations to perform
   * @throws Exception if any operation fails
   */
  protected void concurrentUploadAndDownload(final RawClient rawClient, final String file, int concurrentOperations) 
      throws Exception {
    final File testFile = resolveTestFile(file);
    
    // First upload the file once
    final int response = rawClient.put(file, ContentType.TEXT_PLAIN, testFile);
    MatcherAssert.assertThat(response, Matchers.is(HttpStatus.CREATED));
    
    try {
      // Then perform concurrent downloads using virtual threads
      ExecutorService executor = createVirtualThreadExecutor();
      try {
        CompletableFuture<?>[] futures = new CompletableFuture[concurrentOperations];
        
        for (int i = 0; i < concurrentOperations; i++) {
          futures[i] = CompletableFuture.runAsync(() -> {
            try {
              byte[] downloadedBytes = FormatClientSupport.bytes(rawClient.get(file));
              byte[] originalBytes = Files.readAllBytes(testFile.toPath());
              assertThat(downloadedBytes, is(originalBytes));
            } 
            catch (Exception e) {
              throw new RuntimeException("Failed to download file", e);
            }
          }, executor);
        }
        
        // Wait for all operations to complete
        CompletableFuture.allOf(futures).join();
      } 
      finally {
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
      }
    } 
    finally {
      // Clean up by deleting the file
      MatcherAssert.assertThat(FormatClientSupport.status(rawClient.delete(file)), 
          Matchers.is(HttpStatus.NO_CONTENT));
    }
  }
}