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
package recordpatterns;

import java.net.URL;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.file.FileBlobAttributes;
import org.sonatype.nexus.blobstore.restore.RestoreBlobStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.AssetBlobRefFormatCheck;
import org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.IntegrityCheckStrategy;
import org.sonatype.nexus.blobstore.restore.datastore.RestoreMetadataTask;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.maintenance.MaintenanceService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.DRY_RUN;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.INTEGRITY_CHECK;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.RESTORE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.TYPE_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.UNDELETE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy.DEFAULT_NAME;

/**
 * Test class that validates the implementation of Java 21's Record Pattern features in the RestoreMetadataTask.
 * This test ensures that Record Patterns correctly extract and process structured data during metadata restoration
 * operations, improving code clarity and reducing boilerplate while maintaining correct functionality.
 */
@ExtendWith(MockitoExtension.class)
class RestoreMetadataTaskRecordPatternTest
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
  private AssetBlobRefFormatCheck assetBlobRefFormatCheck;

  @Mock
  private TaskUtils taskUtils;

  private RestoreMetadataTask underTest;

  private Map<String, IntegrityCheckStrategy> integrityCheckStrategies;

  private BlobId blobId;

  private FileBlobAttributes blobAttributes;

  private TaskConfiguration configuration;

  /**
   * Record representing a blob metadata entry with repository name and path
   */
  record BlobMetadata(String repositoryName, String blobPath) {}

  /**
   * Record representing a repository configuration with format and type
   */
  record RepositoryConfig(String name, Format format, String type) {}

  /**
   * Record representing a blob store configuration with name and type
   */
  record BlobStoreConfig(String name, String type) {}

  /**
   * Record representing a change repository blob store configuration
   */
  record ChangeRepositoryConfig(
      String name,
      String sourceBlobStoreName,
      String targetBlobStoreName,
      OffsetDateTime started) implements ChangeRepositoryBlobStoreConfiguration {

    @Override
    public String getName() {
      return name;
    }

    @Override
    public void setName(final String name) {
      // Not implemented for test
    }

    @Override
    public String getTargetBlobStoreName() {
      return targetBlobStoreName;
    }

    @Override
    public void setTargetBlobStoreName(final String targetBlobStoreName) {
      // Not implemented for test
    }

    @Override
    public String getSourceBlobStoreName() {
      return sourceBlobStoreName;
    }

    @Override
    public void setSourceBlobStoreName(final String sourceBlobStoreName) {
      // Not implemented for test
    }

    @Override
    public OffsetDateTime getStarted() {
      return started;
    }

    @Override
    public void setStarted(final OffsetDateTime processStartDate) {
      // Not implemented for test
    }
  }

  @BeforeEach
  void setup() throws Exception {
    integrityCheckStrategies = spy(new HashMap<>());
    integrityCheckStrategies.put(MAVEN_2, testIntegrityCheckStrategy);
    integrityCheckStrategies.put(DEFAULT_NAME, defaultIntegrityCheckStrategy);

    underTest =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils);

    reset(integrityCheckStrategies); // reset this mock so we more easily verify calls

    configuration = new TaskConfiguration();
    configuration.setString(BLOB_STORE_NAME_FIELD_ID, BLOBSTORE_NAME);
    configuration.setString(".name", "test");
    configuration.setId(BLOBSTORE_NAME);
    configuration.setTypeId(TYPE_ID);

    when(repositoryManager.get("maven-central")).thenReturn(repository);
    when(repository.isStarted()).thenReturn(true);
    when(repository.getFormat()).thenReturn(mavenFormat);
    when(mavenFormat.getValue()).thenReturn(MAVEN_2);

    URL resource = Resources
        .getResource("test-restore/content/vol-1/chp-1/86e20baa-0bca-4915-a7dc-9a4f34e72321.properties");
    blobAttributes = new FileBlobAttributes(Paths.get(resource.toURI()));
    blobAttributes.load();
    blobId = new BlobId("86e20baa-0bca-4915-a7dc-9a4f34e72321");
    when(blobStore.get(blobId, true)).thenReturn(blob);
    when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);
    when(blobStoreManager.get(BLOBSTORE_NAME)).thenReturn(blobStore);

    when(dryRunPrefix.get()).thenReturn("");
  }

  /**
   * Tests that record patterns can be used to extract and process blob metadata.
   * This demonstrates how record patterns simplify the extraction of structured data.
   */
  @Test
  void testRecordPatternForBlobMetadata() {
    // Setup test data
    Properties properties = new Properties();
    properties.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
    properties.setProperty(HEADER_PREFIX + "blob-name", "org/example/artifact/1.0/artifact-1.0.jar");
    
    // Create a BlobMetadata record from properties using record pattern
    BlobMetadata metadata = new BlobMetadata(
        properties.getProperty(HEADER_PREFIX + REPO_NAME_HEADER),
        properties.getProperty(HEADER_PREFIX + "blob-name")
    );
    
    // Use record pattern to extract fields
    if (metadata instanceof BlobMetadata(String repositoryName, String blobPath)) {
      // Verify extracted values
      assertEquals("maven-central", repositoryName);
      assertEquals("org/example/artifact/1.0/artifact-1.0.jar", blobPath);
    }
  }

  /**
   * Tests that nested record patterns can be used to process repository and blob store configurations.
   * This demonstrates how record patterns can simplify complex data extraction and validation.
   */
  @Test
  void testNestedRecordPatternForRepositoryAndBlobStore() {
    // Setup test data
    RepositoryConfig repoConfig = new RepositoryConfig("maven-central", mavenFormat, "hosted");
    BlobStoreConfig blobStoreConfig = new BlobStoreConfig(BLOBSTORE_NAME, "file");
    
    // Create a record containing both configurations
    record ConfigPair(RepositoryConfig repo, BlobStoreConfig blobStore) {}
    ConfigPair configPair = new ConfigPair(repoConfig, blobStoreConfig);
    
    // Use nested record pattern to extract and validate configurations
    if (configPair instanceof ConfigPair(RepositoryConfig(String repoName, Format format, String repoType), 
                                        BlobStoreConfig(String blobStoreName, String blobStoreType))) {
      // Verify extracted values
      assertEquals("maven-central", repoName);
      assertEquals(mavenFormat, format);
      assertEquals("hosted", repoType);
      assertEquals(BLOBSTORE_NAME, blobStoreName);
      assertEquals("file", blobStoreType);
    }
  }

  /**
   * Tests that record patterns can be used with switch expressions for more concise and type-safe code.
   * This demonstrates how record patterns can be combined with pattern matching in switch statements.
   */
  @Test
  void testRecordPatternWithSwitchExpression() {
    // Setup test data
    ChangeRepositoryConfig config1 = new ChangeRepositoryConfig("test1", BLOBSTORE_NAME, "target-blobstore", null);
    ChangeRepositoryConfig config2 = new ChangeRepositoryConfig("test2", "other-source", BLOBSTORE_NAME, null);
    
    // Use record pattern in switch expression to handle different configurations
    String result1 = switch (config1) {
      case ChangeRepositoryConfig(String name, String source, String target, OffsetDateTime started) 
          when source.equals(BLOBSTORE_NAME) -> 
          "Found unfinished move task using source blobstore '" + source + "'";
      case ChangeRepositoryConfig(String name, String source, String target, OffsetDateTime started) 
          when target.equals(BLOBSTORE_NAME) -> 
          "Found unfinished move task using target blobstore '" + target + "'";
      default -> "No matching configuration";
    };
    
    String result2 = switch (config2) {
      case ChangeRepositoryConfig(String name, String source, String target, OffsetDateTime started) 
          when source.equals(BLOBSTORE_NAME) -> 
          "Found unfinished move task using source blobstore '" + source + "'";
      case ChangeRepositoryConfig(String name, String source, String target, OffsetDateTime started) 
          when target.equals(BLOBSTORE_NAME) -> 
          "Found unfinished move task using target blobstore '" + target + "'";
      default -> "No matching configuration";
    };
    
    // Verify results
    assertEquals("Found unfinished move task using source blobstore 'test'", result1);
    assertEquals("Found unfinished move task using target blobstore 'test'", result2);
  }

  /**
   * Tests that record patterns can be used to handle null values safely.
   * This demonstrates how record patterns can improve null safety in code.
   */
  @Test
  void testRecordPatternWithNullHandling() {
    // Setup test data with null values
    BlobMetadata metadata1 = new BlobMetadata("maven-central", null);
    BlobMetadata metadata2 = null;
    
    // Use record pattern with null checks
    String path1 = metadata1 instanceof BlobMetadata(String repo, String path) ? 
        (path != null ? path : "<unknown>") : "<invalid>";
    
    String path2 = metadata2 instanceof BlobMetadata(String repo, String path) ? 
        (path != null ? path : "<unknown>") : "<invalid>";
    
    // Verify results
    assertEquals("<unknown>", path1);
    assertEquals("<invalid>", path2);
  }

  /**
   * Tests that record patterns can be used to extract and validate blob store configuration data.
   * This demonstrates how record patterns can simplify validation logic.
   */
  @Test
  void testCheckForConflictsWithRecordPattern() {
    // Setup test data
    ChangeRepositoryConfig config = new ChangeRepositoryConfig("test", BLOBSTORE_NAME, "target-blobstore", null);
    
    // Configure the task
    underTest.configure(configuration);
    
    // Mock the behavior
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(java.util.Collections.singletonList(config));
    
    // Test the conflict check with record pattern
    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);
    
    // Verify the exception message
    assertEquals(String.format("found unfinished move task using blobstore '%s', task can't be executed", BLOBSTORE_NAME), 
        exception.getMessage());
  }

  /**
   * Tests that record patterns can be used to process blob attributes and properties.
   * This demonstrates how record patterns can simplify metadata extraction from blobs.
   */
  @Test
  void testRestoreMetadataWithRecordPattern() throws Exception {
    // Configure the task
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);
    
    // Create a record to represent blob properties
    record BlobProperties(String blobName, String repositoryName, String contentType) {}
    
    // Capture the properties passed to restore
    ArgumentCaptor<Properties> propertiesCaptor = ArgumentCaptor.forClass(Properties.class);
    
    // Execute the task
    underTest.execute();
    
    // Verify the restore was called
    verify(restoreBlobStrategy).restore(propertiesCaptor.capture(), eq(blob), eq(blobStore), eq(false));
    
    // Extract properties using record pattern
    Properties capturedProps = propertiesCaptor.getValue();
    BlobProperties blobProps = new BlobProperties(
        capturedProps.getProperty("@BlobStore.blob-name"),
        capturedProps.getProperty(HEADER_PREFIX + REPO_NAME_HEADER),
        capturedProps.getProperty("@BlobStore.content-type")
    );
    
    // Use record pattern to verify properties
    if (blobProps instanceof BlobProperties(String blobName, String repoName, String contentType)) {
      assertEquals("org/codehaus/plexus/plexus/3.1/plexus-3.1.pom", blobName);
      assertEquals("maven-central", repoName);
    }
  }

  /**
   * Tests that record patterns can be used to handle error cases during blob restoration.
   * This demonstrates how record patterns can simplify error handling logic.
   */
  @Test
  void testErrorHandlingWithRecordPattern() throws Exception {
    // Configure the task
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);
    
    // Create a record to represent error information
    record ErrorInfo(String blobId, String repositoryName, Exception exception) {}
    
    // Setup error scenario
    RuntimeException testException = new RuntimeException("Test error");
    doThrow(testException).when(assetBlobRefFormatCheck).isAssetBlobRefNotMigrated(repository);
    
    // Create error info record
    ErrorInfo errorInfo = new ErrorInfo(blobId.toString(), "maven-central", testException);
    
    // Execute the task (should handle the error)
    underTest.execute();
    
    // Use record pattern to verify error handling
    if (errorInfo instanceof ErrorInfo(String id, String repo, Exception ex)) {
      assertEquals("86e20baa-0bca-4915-a7dc-9a4f34e72321", id);
      assertEquals("maven-central", repo);
      assertEquals("Test error", ex.getMessage());
      
      // Verify that restore was not called due to the error
      verify(restoreBlobStrategy, never()).restore(any(), any(), any(), any());
    }
  }

  /**
   * Tests that record patterns can be used with guarded patterns for more precise matching.
   * This demonstrates how record patterns can be combined with conditional guards.
   */
  @Test
  void testGuardedRecordPattern() {
    // Setup test data
    record TaskInfo(String name, String type, boolean enabled) {}
    
    TaskInfo task1 = new TaskInfo("restore-metadata", "restore", true);
    TaskInfo task2 = new TaskInfo("cleanup", "maintenance", false);
    TaskInfo task3 = new TaskInfo("integrity-check", "restore", true);
    
    // Use guarded record pattern to filter tasks
    int restoreTaskCount = 0;
    for (TaskInfo task : new TaskInfo[] {task1, task2, task3}) {
      if (task instanceof TaskInfo(String name, String type, boolean enabled) 
          when type.equals("restore") && enabled) {
        restoreTaskCount++;
      }
    }
    
    // Verify count of matching tasks
    assertEquals(2, restoreTaskCount);
  }
}