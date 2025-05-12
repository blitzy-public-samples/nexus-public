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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.sonatype.goodies.httpfixture.server.fluent.Behaviours;
import org.sonatype.goodies.httpfixture.server.fluent.Server;
import org.sonatype.goodies.httpfixture.server.jetty.behaviour.Content;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.common.net.PortAllocator;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.testsuite.helpers.ComponentAssetTestHelper;
import org.sonatype.nexus.testsuite.testsupport.NexusBaseITSupport;
import org.sonatype.nexus.testsuite.testsupport.blobstore.restore.BlobstoreRestoreTestHelper;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;
import static org.sonatype.nexus.testsuite.testsupport.system.RestTestHelper.hasStatus;

/**
 * Integration test for Raw repository blob restore functionality.
 * <p>
 * This test class validates the blob restore process for Raw repositories, ensuring that
 * metadata can be properly restored from blobs when database information is lost.
 * <p>
 * Updated for Java 21 compatibility with JUnit Jupiter and enhanced with tests for
 * virtual threads, pattern matching, record patterns, and string templates.
 */
@ExtendWith(MockitoExtension.class)
public class RawRestoreBlobIT
    extends NexusBaseITSupport
{
  private static final String TEST_CONTENT = "alphabet.txt";

  private Server proxyServer;

  private Repository hostedRepository;

  private Repository proxyRepository;

  private Map<String, BlobId> hostedPathsToBlobs;

  private Map<String, BlobId> proxyPathsToBlobs;

  @Inject
  private BlobstoreRestoreTestHelper restoreTestHelper;

  @Inject
  private ComponentAssetTestHelper componentAssetTestHelper;

  private String blobStoreName;

  @BeforeEach
  public void setup() throws Exception {
    testData.addDirectory(resolveBaseFile("target/it-resources/raw"));
    blobStoreName = testName.getMethodName();
    nexus.blobStores().create(blobStoreName);
    hostedRepository = nexus.repositories()
        .raw()
        .hosted(repoName("hosted"))
        .withBlobstore(blobStoreName)
        .create();

    proxyServer = Server.withPort(PortAllocator.nextFreePort()).start();
    proxyServer.serve("/" + TEST_CONTENT).withBehaviours(resolveFile(TEST_CONTENT));

    proxyRepository = nexus.repositories()
        .raw()
        .proxy(repoName("proxy"))
        .withRemoteUrl("http://localhost:" + proxyServer.getPort() + "/")
        .withBlobstore(blobStoreName)
        .create();

    File testFile = resolveTestFile(TEST_CONTENT);
    assertThat(nexus.rest()
        .put(path(hostedRepository, TEST_CONTENT),
            FileUtils.readFileToString(testFile, StandardCharsets.UTF_8), "admin", "admin123"),
        hasStatus(HttpStatus.CREATED));

    assertThat(nexus.rest().get(path(proxyRepository, TEST_CONTENT)), hasStatus(OK));

    hostedPathsToBlobs = restoreTestHelper.getAssetToBlobIds(hostedRepository);
    proxyPathsToBlobs = restoreTestHelper.getAssetToBlobIds(proxyRepository);
  }

  /**
   * Tests metadata restoration when both assets and components are missing.
   */
  @Test
  void metadataRestoreWhenBothAssetsAndComponentsAreMissing() throws Exception {
    verifyMetadataRestored(restoreTestHelper::simulateComponentAndAssetMetadataLoss);
  }

  /**
   * Tests metadata restoration when only assets are missing.
   */
  @Test
  void metadataRestoreWhenOnlyAssetsAreMissing() throws Exception {
    verifyMetadataRestored(restoreTestHelper::simulateAssetMetadataLoss);
  }

  /**
   * NEXUS-40244 - if the blobstore has not been compacted it may contain multiple revisions of the same asset. As such
   * we need to ensure that the most recent revision wins.
   */
  @Test
  void restoresMostRecentAsset() throws Exception {
    // We can't guarantee the order blobs will be processed, so for the test we want to create enough assets that
    // there is a low chance that the last blob is processed last which would mean our test verifies nothing.
    for (int i = 0; i < 20; i++) {
      assertThat(nexus.rest().put(path(hostedRepository, TEST_CONTENT), "test" + i, "admin", "admin123"),
          hasStatus(HttpStatus.CREATED));
    }
    hostedPathsToBlobs = restoreTestHelper.getAssetToBlobIds(hostedRepository);

    verifyMetadataRestored(restoreTestHelper::simulateAssetMetadataLoss);
  }

  /**
   * For Orient this tests restoring newdb assets, for newdb this tests restoring Orient assets
   */
  @Test
  void restoreFromOtherDatabase() throws Exception {
    verifyMetadataRestored(() -> {
      restoreTestHelper.rewriteBlobNames();
      restoreTestHelper.simulateAssetMetadataLoss();
    });
  }

  /**
   * Tests that dry run mode doesn't actually restore assets.
   */
  @Test
  void dryRunRestore() {
    assertTrue(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
    restoreTestHelper.simulateComponentAndAssetMetadataLoss();
    assertFalse(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
    restoreTestHelper.runRestoreMetadataTaskWithTimeout(blobStoreName, 10, true);
    assertFalse(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
  }

  /**
   * Tests that non-dry run mode actually restores assets.
   */
  @Test
  void notDryRunRestore() {
    assertTrue(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
    restoreTestHelper.simulateComponentAndAssetMetadataLoss();
    assertFalse(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
    restoreTestHelper.runRestoreMetadataTaskWithTimeout(blobStoreName, 10, false);
    assertTrue(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
    verityBlobsUnchanged();
  }

  /**
   * Verifies that metadata is properly restored after simulated loss.
   * 
   * @param metadataLossSimulation the runnable that simulates metadata loss
   */
  private void verifyMetadataRestored(final Runnable metadataLossSimulation) throws Exception {
    metadataLossSimulation.run();

    restoreTestHelper.runRestoreMetadataTask(blobStoreName);

    assertTrue(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
    assertTrue(componentAssetTestHelper.assetExists(hostedRepository, TEST_CONTENT));

    assertTrue(componentExists(hostedRepository, TEST_CONTENT));
    assertTrue(componentExists(proxyRepository, TEST_CONTENT));

    restoreTestHelper.assertAssetMatchesBlob(hostedRepository, TEST_CONTENT);
    restoreTestHelper.assertAssetMatchesBlob(proxyRepository, TEST_CONTENT);

    assertTrue(assetWithComponentExists(hostedRepository, TEST_CONTENT, "/", TEST_CONTENT));
    assertTrue(assetWithComponentExists(proxyRepository, TEST_CONTENT, "/", TEST_CONTENT));

    verityBlobsUnchanged();
    assertThat(nexus.rest().get(path(hostedRepository, TEST_CONTENT)).getStatus(), is(OK));
    assertThat(nexus.rest().get(path(proxyRepository, TEST_CONTENT)).getStatus(), is(OK));
  }

  /**
   * Verifies that the original blobs are attached to the assets, not copied.
   */
  private void verityBlobsUnchanged() {
    assertThat(restoreTestHelper.getAssetToBlobIds(hostedRepository), equalTo(hostedPathsToBlobs));
    assertThat(restoreTestHelper.getAssetToBlobIds(proxyRepository), equalTo(proxyPathsToBlobs));
  }

  /**
   * Checks if a component exists in the repository, handling path variations.
   * 
   * @param repository the repository to check
   * @param name the component name
   * @return true if the component exists, false otherwise
   */
  private boolean componentExists(final Repository repository, final String name) {
    return componentAssetTestHelper.componentExists(repository, name)
        || componentAssetTestHelper.componentExists(repository, prependIfMissing(name, "/"));
  }

  /**
   * Checks if an asset with a component exists in the repository.
   * 
   * @param repository the repository to check
   * @param path the asset path
   * @param group the component group
   * @param name the component name
   * @return true if the asset with component exists, false otherwise
   */
  private boolean assetWithComponentExists(
      final Repository repository,
      final String path,
      final String group,
      final String name)
  {
    return componentAssetTestHelper.assetWithComponentExists(repository, path, group, name)
        || componentAssetTestHelper.assetWithComponentExists(hostedRepository, prependIfMissing(path, "/"), group,
            prependIfMissing(name, "/"));
  }

  /**
   * Resolves a file from test data to a Content behavior.
   * 
   * @param filename the name of the file to resolve
   * @return the Content behavior for the file
   */
  private Content resolveFile(final String filename) {
    return Behaviours.file(testData.resolveFile(filename));
  }

  /**
   * Creates a repository name with a prefix and the current test method name.
   * 
   * @param prefix the prefix to use
   * @return the repository name
   */
  private String repoName(final String prefix) {
    return String.format("%s-%s", prefix, testName.getMethodName());
  }

  /**
   * Creates a path to an asset in a repository.
   * 
   * @param repository the repository
   * @param path the asset path
   * @return the full path to the asset
   */
  private String path(final Repository repository, final String path) {
    return "repository/" + repository.getName() + '/' + path;
  }
  /**
   * Tests blob restoration using virtual threads for concurrent processing.
   * This test demonstrates Java 21's virtual thread capabilities for improved concurrency.
   */
  @Test
  void concurrentRestoreWithVirtualThreads() throws Exception {
    // Simulate metadata loss
    restoreTestHelper.simulateComponentAndAssetMetadataLoss();
    
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Verify the restore operation completes within a reasonable timeout
      assertTimeout(Duration.ofSeconds(30), () -> {
        // Run multiple concurrent restore operations using virtual threads
        int taskCount = 5;
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < taskCount; i++) {
          executor.submit(() -> {
            try {
              restoreTestHelper.runRestoreMetadataTask(blobStoreName);
              successCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to complete
        latch.await(20, TimeUnit.SECONDS);
        
        // Verify at least one restore operation succeeded
        assertTrue(successCount.get() > 0, "At least one restore operation should succeed");
      });
      
      // Verify assets were restored
      assertTrue(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
      assertTrue(componentAssetTestHelper.assetExists(hostedRepository, TEST_CONTENT));
      verityBlobsUnchanged();
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests blob restoration with pattern matching for different repository types.
   * This test demonstrates Java 21's enhanced pattern matching capabilities.
   */
  @Test
  void restoreWithPatternMatching() {
    // Simulate metadata loss
    restoreTestHelper.simulateComponentAndAssetMetadataLoss();
    
    // Restore metadata
    restoreTestHelper.runRestoreMetadataTaskWithTimeout(blobStoreName, 10, false);
    
    // Verify restoration using pattern matching for different repository types
    Object[] repositories = new Object[] { hostedRepository, proxyRepository };
    
    for (Object repo : repositories) {
      boolean exists = switch (repo) {
        case Repository hostedRepo when hostedRepo.getName().contains("hosted") ->
          componentAssetTestHelper.assetExists(hostedRepo, TEST_CONTENT);
        case Repository proxyRepo when proxyRepo.getName().contains("proxy") ->
          componentAssetTestHelper.assetExists(proxyRepo, TEST_CONTENT);
        default -> false;
      };
      
      assertTrue(exists, "Asset should exist in repository: " + ((Repository) repo).getName());
    }
  }
  
  /**
   * Tests blob restoration with record patterns for asset metadata.
   * This test demonstrates Java 21's record pattern matching capabilities.
   */
  @Test
  void restoreWithRecordPatterns() {
    // Define a record to represent asset metadata
    record AssetMetadata(String path, String repositoryName, boolean exists) {}
    
    // Simulate metadata loss
    restoreTestHelper.simulateComponentAndAssetMetadataLoss();
    
    // Restore metadata
    restoreTestHelper.runRestoreMetadataTaskWithTimeout(blobStoreName, 10, false);
    
    // Create metadata records
    List<AssetMetadata> metadataList = new ArrayList<>();
    metadataList.add(new AssetMetadata(TEST_CONTENT, hostedRepository.getName(), true));
    metadataList.add(new AssetMetadata(TEST_CONTENT, proxyRepository.getName(), true));
    
    // Verify restoration using record patterns
    for (Object metadata : metadataList) {
      if (metadata instanceof AssetMetadata(String path, String repoName, boolean shouldExist)) {
        Repository repository = repoName.contains("hosted") ? hostedRepository : proxyRepository;
        boolean actualExists = componentAssetTestHelper.assetExists(repository, path);
        assertTrue(actualExists == shouldExist, 
            "Asset existence should match expected state for " + path + " in " + repoName);
      }
    }
  }
  
  /**
   * Tests blob restoration with string templates for logging.
   * This test demonstrates Java 21's string template capabilities.
   */
  @Test
  void restoreWithStringTemplates() {
    // Simulate metadata loss
    restoreTestHelper.simulateComponentAndAssetMetadataLoss();
    
    // Restore metadata
    restoreTestHelper.runRestoreMetadataTaskWithTimeout(blobStoreName, 10, false);
    
    // Verify restoration using string templates for logging
    String hostedStatus = STR."Asset \{TEST_CONTENT} in \{hostedRepository.getName()} exists: \{componentAssetTestHelper.assetExists(hostedRepository, TEST_CONTENT)}";
    String proxyStatus = STR."Asset \{TEST_CONTENT} in \{proxyRepository.getName()} exists: \{componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT)}";
    
    // Log the status strings (in a real scenario, we would use a logger)
    System.out.println(hostedStatus);
    System.out.println(proxyStatus);
    
    // Verify the assets exist
    assertTrue(componentAssetTestHelper.assetExists(hostedRepository, TEST_CONTENT));
    assertTrue(componentAssetTestHelper.assetExists(proxyRepository, TEST_CONTENT));
  }
  
  /**
   * Tests blob restoration with sequenced collections for processing assets.
   * This test demonstrates Java 21's sequenced collections capabilities.
   */
  @Test
  void restoreWithSequencedCollections() {
    // Simulate metadata loss
    restoreTestHelper.simulateComponentAndAssetMetadataLoss();
    
    // Restore metadata
    restoreTestHelper.runRestoreMetadataTaskWithTimeout(blobStoreName, 10, false);
    
    // Create a list of repositories to check
    List<Repository> repositories = new ArrayList<>();
    repositories.add(hostedRepository);
    repositories.add(proxyRepository);
    
    // Use sequenced collection methods to process repositories
    Repository first = repositories.getFirst();
    Repository last = repositories.getLast();
    
    // Verify first and last repositories have their assets restored
    assertTrue(componentAssetTestHelper.assetExists(first, TEST_CONTENT));
    assertTrue(componentAssetTestHelper.assetExists(last, TEST_CONTENT));
    
    // Reverse the list and verify again
    List<Repository> reversed = repositories.reversed();
    assertTrue(componentAssetTestHelper.assetExists(reversed.getFirst(), TEST_CONTENT));
    assertTrue(componentAssetTestHelper.assetExists(reversed.getLast(), TEST_CONTENT));
  }
}
