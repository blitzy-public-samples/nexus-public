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
package org.sonatype.patternmatching;

import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.file.FileBlobAttributes;
import org.sonatype.nexus.blobstore.restore.RestoreBlobStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.IntegrityCheckStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.RestoreMetadataTask;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.maintenance.MaintenanceService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.DRY_RUN;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.INTEGRITY_CHECK;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.RESTORE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.SINCE_DAYS;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.UNDELETE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy.DEFAULT_NAME;

/**
 * Test class demonstrating how Java 21's Pattern Matching for switch can improve the RestoreMetadataTask implementation.
 * This class shows how Pattern Matching makes the code more concise and easier to understand compared to traditional
 * if-else chains or switch statements.
 */
@ExtendWith(MockitoExtension.class)
public class RestoreMetadataTaskPatternMatchingTest
    extends TestSupport
{
  private static final String BLOBSTORE_NAME = "test";
  private static final String MAVEN_2 = "maven2";

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private RestoreBlobStrategy restoreBlobStrategy;

  @Mock
  private Repository repository;

  @Mock
  private BlobStore blobStore;

  @Mock
  private Blob blob;

  @Mock
  private Format mavenFormat;

  @Mock
  private BlobStoreUsageChecker blobstoreUsageChecker;

  @Mock
  private DryRunPrefix dryRunPrefix;

  @Mock
  private DefaultIntegrityCheckStrategy defaultIntegrityCheckStrategy;

  @Mock
  private IntegrityCheckStrategy testIntegrityCheckStrategy;

  @Mock
  private MaintenanceService maintenanceService;

  @Mock
  private TaskUtils taskUtils;

  private RestoreMetadataTask underTest;

  private Map<String, IntegrityCheckStrategy> integrityCheckStrategies;

  private BlobId blobId;

  private FileBlobAttributes blobAttributes;

  private TaskConfiguration configuration;

  @BeforeEach
  public void setup() throws Exception {
    integrityCheckStrategies = spy(new HashMap<>());
    integrityCheckStrategies.put(MAVEN_2, testIntegrityCheckStrategy);
    integrityCheckStrategies.put(DEFAULT_NAME, defaultIntegrityCheckStrategy);

    underTest = new RestoreMetadataTask(
        blobStoreManager,
        changeBlobstoreStore,
        repositoryManager,
        ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
        blobstoreUsageChecker,
        dryRunPrefix,
        integrityCheckStrategies,
        maintenanceService,
        mock(AssetBlobRefFormatCheck.class),
        taskUtils);

    configuration = new TaskConfiguration();
    configuration.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
    configuration.setString(".name", "test");
    configuration.setId(BLOBSTORE_NAME);

    when(repositoryManager.get("maven-central")).thenReturn(repository);
    when(repository.isStarted()).thenReturn(true);
    when(repository.getFormat()).thenReturn(mavenFormat);
    when(mavenFormat.getValue()).thenReturn(MAVEN_2);

    URL resource = Resources.getResource(
        "test-restore/content/vol-1/chp-1/86e20baa-0bca-4915-a7dc-9a4f34e72321.properties");
    blobAttributes = new FileBlobAttributes(Paths.get(resource.toURI()));
    blobAttributes.load();
    blobId = new BlobId("86e20baa-0bca-4915-a7dc-9a4f34e72321");
    when(blobStore.getBlobIdStream()).thenReturn(java.util.stream.Stream.of(blobId));
    when(blobStore.getBlobIdUpdatedSinceStream(any(Duration.class))).thenReturn(java.util.stream.Stream.of(blobId));
    when(blobStoreManager.get(BLOBSTORE_NAME)).thenReturn(blobStore);

    when(blobStore.get(blobId, true)).thenReturn(blob);
    when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);

    when(dryRunPrefix.get()).thenReturn("");
  }

  /**
   * This test demonstrates how Pattern Matching for switch can simplify task configuration handling.
   * It shows the traditional approach with if-else statements and the improved approach with Pattern Matching.
   */
  @Test
  public void demonstrateTaskConfigurationPatternMatching() {
    // Test different task configurations
    testTaskConfiguration(true, true, false); // Restore and undelete, no integrity check
    testTaskConfiguration(true, false, false); // Restore only, no integrity check
    testTaskConfiguration(false, true, false); // Undelete only, no integrity check
    testTaskConfiguration(false, false, true); // Integrity check only
    testTaskConfiguration(false, false, false); // No operations
  }

  private void testTaskConfiguration(boolean restore, boolean undelete, boolean integrityCheck) {
    // Configure the task
    configuration.setBoolean(RESTORE_BLOBS, restore);
    configuration.setBoolean(UNDELETE_BLOBS, undelete);
    configuration.setBoolean(INTEGRITY_CHECK, integrityCheck);
    underTest.configure(configuration);

    // Traditional approach with if-else statements
    String traditionalResult = getTaskOperationTraditional(configuration);
    
    // Improved approach with Pattern Matching for switch
    String patternMatchingResult = getTaskOperationWithPatternMatching(configuration);
    
    // Both approaches should yield the same result
    assertEquals(traditionalResult, patternMatchingResult);
    
    // Verify the result matches the expected operation based on configuration
    if (restore && undelete) {
      assertEquals("Restore and Undelete", traditionalResult);
    }
    else if (restore) {
      assertEquals("Restore Only", traditionalResult);
    }
    else if (undelete) {
      assertEquals("Undelete Only", traditionalResult);
    }
    else if (integrityCheck) {
      assertEquals("Integrity Check Only", traditionalResult);
    }
    else {
      assertEquals("No Operation", traditionalResult);
    }
  }

  /**
   * Traditional approach using if-else statements to determine task operation.
   */
  private String getTaskOperationTraditional(TaskConfiguration config) {
    boolean restore = config.getBoolean(RESTORE_BLOBS, false);
    boolean undelete = config.getBoolean(UNDELETE_BLOBS, false);
    boolean integrityCheck = config.getBoolean(INTEGRITY_CHECK, false);
    
    if (restore && undelete) {
      return "Restore and Undelete";
    }
    else if (restore) {
      return "Restore Only";
    }
    else if (undelete) {
      return "Undelete Only";
    }
    else if (integrityCheck) {
      return "Integrity Check Only";
    }
    else {
      return "No Operation";
    }
  }

  /**
   * Improved approach using Pattern Matching for switch to determine task operation.
   * This demonstrates how Java 21's Pattern Matching makes the code more concise and readable.
   */
  private String getTaskOperationWithPatternMatching(TaskConfiguration config) {
    record TaskOptions(boolean restore, boolean undelete, boolean integrityCheck) {}
    
    TaskOptions options = new TaskOptions(
        config.getBoolean(RESTORE_BLOBS, false),
        config.getBoolean(UNDELETE_BLOBS, false),
        config.getBoolean(INTEGRITY_CHECK, false));
    
    return switch (options) {
      case TaskOptions(boolean restore, boolean undelete, boolean _) when restore && undelete -> "Restore and Undelete";
      case TaskOptions(boolean restore, boolean _, boolean _) when restore -> "Restore Only";
      case TaskOptions(boolean _, boolean undelete, boolean _) when undelete -> "Undelete Only";
      case TaskOptions(boolean _, boolean _, boolean integrityCheck) when integrityCheck -> "Integrity Check Only";
      default -> "No Operation";
    };
  }

  /**
   * This test demonstrates how Pattern Matching for switch can simplify blob metadata extraction.
   * It shows the traditional approach with if-else statements and the improved approach with Pattern Matching.
   */
  @Test
  public void demonstrateBlobMetadataPatternMatching() {
    // Set up test blob attributes
    Properties properties = new Properties();
    properties.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
    properties.setProperty(HEADER_PREFIX + "content-type", "application/xml");
    properties.setProperty(HEADER_PREFIX + "blob-name", "org/example/artifact/1.0/artifact-1.0.pom");
    when(blobAttributes.getProperties()).thenReturn(properties);
    
    // Test both approaches
    String traditionalResult = extractBlobInfoTraditional(blobAttributes);
    String patternMatchingResult = extractBlobInfoWithPatternMatching(blobAttributes);
    
    // Both approaches should yield the same result
    assertEquals(traditionalResult, patternMatchingResult);
    assertEquals("Repository: maven-central, Type: application/xml, Name: org/example/artifact/1.0/artifact-1.0.pom", traditionalResult);
  }

  /**
   * Traditional approach using if-else statements to extract blob metadata.
   */
  private String extractBlobInfoTraditional(BlobAttributes attributes) {
    Properties props = attributes.getProperties();
    String repository = props.getProperty(HEADER_PREFIX + REPO_NAME_HEADER, "unknown");
    String contentType = props.getProperty(HEADER_PREFIX + "content-type", "unknown");
    String blobName = props.getProperty(HEADER_PREFIX + "blob-name", "unknown");
    
    return String.format("Repository: %s, Type: %s, Name: %s", repository, contentType, blobName);
  }

  /**
   * Improved approach using Pattern Matching to extract blob metadata.
   * This demonstrates how Java 21's Pattern Matching makes the code more concise and readable.
   */
  private String extractBlobInfoWithPatternMatching(BlobAttributes attributes) {
    Properties props = attributes.getProperties();
    
    return switch (props) {
      case Properties p when p.containsKey(HEADER_PREFIX + REPO_NAME_HEADER) && 
                           p.containsKey(HEADER_PREFIX + "content-type") && 
                           p.containsKey(HEADER_PREFIX + "blob-name") -> 
          String.format("Repository: %s, Type: %s, Name: %s",
              p.getProperty(HEADER_PREFIX + REPO_NAME_HEADER),
              p.getProperty(HEADER_PREFIX + "content-type"),
              p.getProperty(HEADER_PREFIX + "blob-name"));
      default -> "Incomplete blob metadata";
    };
  }

  /**
   * This test demonstrates how Pattern Matching for switch can simplify handling of different restore scenarios.
   */
  @Test
  public void demonstrateRestoreScenarioPatternMatching() {
    // Test different restore scenarios
    testRestoreScenario(true, false); // Normal blob
    testRestoreScenario(true, true);  // Deleted blob
  }

  private void testRestoreScenario(boolean blobExists, boolean isDeleted) {
    // Set up the test scenario
    blobAttributes.setDeleted(isDeleted);
    when(blobStore.get(blobId, true)).thenReturn(blobExists ? blob : null);
    
    // Configure the task for restore and undelete
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);
    
    // Traditional approach
    boolean shouldRestoreTraditional = shouldRestoreTraditional(blobExists, isDeleted);
    boolean shouldUndeleteTraditional = shouldUndeleteTraditional(blobExists, isDeleted);
    
    // Pattern Matching approach
    record BlobState(boolean exists, boolean isDeleted) {}
    BlobState state = new BlobState(blobExists, isDeleted);
    
    boolean shouldRestorePatternMatching = switch (state) {
      case BlobState(true, false) -> true;  // Blob exists and is not deleted
      default -> false;                     // All other cases
    };
    
    boolean shouldUndeletePatternMatching = switch (state) {
      case BlobState(true, true) -> true;   // Blob exists and is deleted
      default -> false;                     // All other cases
    };
    
    // Both approaches should yield the same result
    assertEquals(shouldRestoreTraditional, shouldRestorePatternMatching);
    assertEquals(shouldUndeleteTraditional, shouldUndeletePatternMatching);
    
    // Verify the expected behavior based on the scenario
    if (blobExists && !isDeleted) {
      assertTrue(shouldRestorePatternMatching, "Should restore a normal blob");
      assertFalse(shouldUndeletePatternMatching, "Should not undelete a normal blob");
    }
    else if (blobExists && isDeleted) {
      assertFalse(shouldRestorePatternMatching, "Should not restore a deleted blob");
      assertTrue(shouldUndeletePatternMatching, "Should undelete a deleted blob");
    }
    else {
      assertFalse(shouldRestorePatternMatching, "Should not restore a non-existent blob");
      assertFalse(shouldUndeletePatternMatching, "Should not undelete a non-existent blob");
    }
  }

  /**
   * Traditional approach to determine if a blob should be restored.
   */
  private boolean shouldRestoreTraditional(boolean blobExists, boolean isDeleted) {
    return blobExists && !isDeleted;
  }

  /**
   * Traditional approach to determine if a blob should be undeleted.
   */
  private boolean shouldUndeleteTraditional(boolean blobExists, boolean isDeleted) {
    return blobExists && isDeleted;
  }

  /**
   * This test demonstrates how Pattern Matching for switch can simplify integrity check strategy selection.
   */
  @Test
  public void demonstrateIntegrityCheckStrategyPatternMatching() {
    // Configure for integrity check
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);
    
    // Test with different format values
    testIntegrityCheckStrategy(MAVEN_2, testIntegrityCheckStrategy);  // Known format
    testIntegrityCheckStrategy("unknown", defaultIntegrityCheckStrategy);  // Unknown format
  }

  private void testIntegrityCheckStrategy(String formatValue, IntegrityCheckStrategy expectedStrategy) {
    when(mavenFormat.getValue()).thenReturn(formatValue);
    
    // Traditional approach with if-else
    IntegrityCheckStrategy traditionalStrategy = getIntegrityCheckStrategyTraditional(formatValue);
    
    // Pattern Matching approach
    IntegrityCheckStrategy patternMatchingStrategy = getIntegrityCheckStrategyWithPatternMatching(formatValue);
    
    // Both approaches should yield the same result
    assertEquals(traditionalStrategy, patternMatchingStrategy);
    assertEquals(expectedStrategy, patternMatchingStrategy);
  }

  /**
   * Traditional approach to select integrity check strategy based on format.
   */
  private IntegrityCheckStrategy getIntegrityCheckStrategyTraditional(String formatValue) {
    if (integrityCheckStrategies.containsKey(formatValue)) {
      return integrityCheckStrategies.get(formatValue);
    }
    else {
      return integrityCheckStrategies.get(DEFAULT_NAME);
    }
  }

  /**
   * Improved approach using Pattern Matching to select integrity check strategy based on format.
   */
  private IntegrityCheckStrategy getIntegrityCheckStrategyWithPatternMatching(String formatValue) {
    return switch (formatValue) {
      case String s when integrityCheckStrategies.containsKey(s) -> integrityCheckStrategies.get(s);
      default -> integrityCheckStrategies.get(DEFAULT_NAME);
    };
  }

  /**
   * This test demonstrates how Pattern Matching for switch can simplify handling of since days configuration.
   */
  @Test
  public void demonstrateSinceDaysPatternMatching() {
    // Test different since days configurations
    testSinceDaysConfiguration(null);  // Not set
    testSinceDaysConfiguration(-1);    // Negative value
    testSinceDaysConfiguration(0);     // Zero
    testSinceDaysConfiguration(7);     // Positive value
  }

  private void testSinceDaysConfiguration(Integer sinceDays) {
    // Configure the task
    if (sinceDays != null) {
      configuration.setInteger(SINCE_DAYS, sinceDays);
    }
    
    // Traditional approach
    boolean shouldUseAllBlobsTraditional = shouldUseAllBlobsTraditional(sinceDays);
    
    // Pattern Matching approach
    boolean shouldUseAllBlobsPatternMatching = shouldUseAllBlobsWithPatternMatching(sinceDays);
    
    // Both approaches should yield the same result
    assertEquals(shouldUseAllBlobsTraditional, shouldUseAllBlobsPatternMatching);
    
    // Execute the task and verify the correct method was called
    underTest.configure(configuration);
    underTest.execute();
    
    if (shouldUseAllBlobsPatternMatching) {
      verify(blobStore).getBlobIdStream();
      verify(blobStore, never()).getBlobIdUpdatedSinceStream(any(Duration.class));
    }
    else {
      verify(blobStore, never()).getBlobIdStream();
      verify(blobStore).getBlobIdUpdatedSinceStream(any(Duration.class));
    }
  }

  /**
   * Traditional approach to determine if all blobs should be used based on since days configuration.
   */
  private boolean shouldUseAllBlobsTraditional(Integer sinceDays) {
    return sinceDays == null || sinceDays < 0;
  }

  /**
   * Improved approach using Pattern Matching to determine if all blobs should be used based on since days configuration.
   */
  private boolean shouldUseAllBlobsWithPatternMatching(Integer sinceDays) {
    return switch (sinceDays) {
      case null -> true;                // Not set
      case Integer i when i < 0 -> true; // Negative value
      default -> false;                 // Zero or positive value
    };
  }

  /**
   * Mock class for AssetBlobRefFormatCheck to satisfy dependencies.
   */
  interface AssetBlobRefFormatCheck {
    boolean isAssetBlobRefNotMigrated(Repository repository);
  }
}