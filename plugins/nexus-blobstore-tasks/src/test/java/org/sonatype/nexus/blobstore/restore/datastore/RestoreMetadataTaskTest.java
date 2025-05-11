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
package org.sonatype.nexus.blobstore.restore.datastore;

import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.file.FileBlobAttributes;
import org.sonatype.nexus.blobstore.restore.RestoreBlobStrategy;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.maintenance.MaintenanceService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreConfiguration;
import org.sonatype.nexus.repository.move.ChangeRepositoryBlobStoreStore;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.sonatype.nexus.blobstore.api.BlobAttributesConstants.HEADER_PREFIX;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.BLOB_STORE_NAME_FIELD_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.DRY_RUN;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.INTEGRITY_CHECK;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.RESTORE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.SINCE_DAYS;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.TYPE_ID;
import static org.sonatype.nexus.blobstore.restore.BaseRestoreMetadataTaskDescriptor.UNDELETE_BLOBS;
import static org.sonatype.nexus.blobstore.restore.datastore.DefaultIntegrityCheckStrategy.DEFAULT_NAME;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21")
public class RestoreMetadataTaskTest
    extends TestSupport
{
  public static final String BLOBSTORE_NAME = "test";

  public static final String MAVEN_2 = "maven2";

  @Mock
  BlobStoreManager blobStoreManager;

  @Mock
  ChangeRepositoryBlobStoreStore changeBlobstoreStore;

  @Mock
  RepositoryManager repositoryManager;

  @Mock
  RestoreBlobStrategy restoreBlobStrategy;

  @Mock
  Repository repository;

  @Mock
  BlobStore blobStore;

  @Mock
  Blob blob;

  @Mock
  Format mavenFormat;

  @Mock
  BlobStoreUsageChecker blobstoreUsageChecker;

  @Mock
  DryRunPrefix dryRunPrefix;

  @Mock
  DefaultIntegrityCheckStrategy defaultIntegrityCheckStrategy;

  @Mock
  IntegrityCheckStrategy testIntegrityCheckStrategy;

  @Mock
  MaintenanceService maintenanceService;

  @Mock
  private AssetBlobRefFormatCheck assetBlobRefFormatCheck;

  @Mock
  TaskUtils taskUtils;
  
  @Captor
  private ArgumentCaptor<Properties> propertiesArgumentCaptor;

  RestoreMetadataTask underTest;

  Map<String, IntegrityCheckStrategy> integrityCheckStrategies;

  BlobId blobId;

  FileBlobAttributes blobAttributes;

  TaskConfiguration configuration;

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
    when(blobStore.getBlobIdStream()).thenReturn(Stream.of(blobId));
    when(blobStore.getBlobIdUpdatedSinceStream(any(Duration.class))).thenReturn(Stream.of(blobId));
    when(blobStoreManager.get(BLOBSTORE_NAME)).thenReturn(blobStore);

    when(blobStore.get(blobId, true)).thenReturn(blob);
    when(blobStore.getBlobAttributes(blobId)).thenReturn(blobAttributes);

    when(dryRunPrefix.get()).thenReturn("");
  }

  @Test
  void checkForConflictsThrowsExceptionIfConflictingTaskIsRunning() {
    underTest.configure(configuration);

    doThrow(new IllegalStateException("conflicting task"))
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals("conflicting task", exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
  }

  @Test
  void checkForConflictsThrowsExceptionIfMoveTaskIsUnfinished() {
    ChangeRepositoryBlobStoreConfiguration record = getRecord("test" , BLOBSTORE_NAME, "target-blobstore");

    underTest.configure(configuration);

    doNothing()
        .when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.singletonList(record));

    IllegalStateException exception = assertThrows(IllegalStateException.class, underTest::checkForConflicts);

    assertEquals(String.format("found unfinished move task using blobstore '%s', task can't be executed", BLOBSTORE_NAME), exception.getMessage());
    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    verify(changeBlobstoreStore, times(1)).findByBlobStoreName(eq(BLOBSTORE_NAME));
  }

  @Test
  void checkForConflictsRunsIfNoConflictingTasks() {
    underTest.configure(configuration);

    doNothing().when(taskUtils).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
    when(changeBlobstoreStore.findByBlobStoreName(anyString())).thenReturn(Collections.emptyList());

    underTest.checkForConflicts();

    verify(taskUtils, times(1)).checkForConflictingTasks(anyString(), anyString(), any(List.class), any(Map.class));
  }

  private ChangeRepositoryBlobStoreConfiguration getRecord(final String name , final String sourceBlobStoreName , final String targetBlobStoreName) {
    return new ChangeRepositoryBlobStoreConfiguration()
    {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public void setName(final String name) {

      }

      @Override
      public String getTargetBlobStoreName() {
        return targetBlobStoreName;
      }

      @Override
      public void setTargetBlobStoreName(final String targetBlobStoreName) {

      }

      @Override
      public String getSourceBlobStoreName() {
        return sourceBlobStoreName;
      }

      @Override
      public void setSourceBlobStoreName(final String sourceBlobStoreName) {

      }

      @Override
      public OffsetDateTime getStarted() {
        return null;
      }

      @Override
      public void setStarted(final OffsetDateTime processStartDate) {

      }
    };
  }

  @Test
  void restoreMetadataShouldProcessBlobsCorrectly() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy).restore(propertiesArgumentCaptor.capture(), eq(blob), eq(blobStore), eq(false));
    verify(blobStore).undelete(blobstoreUsageChecker, blobId, blobAttributes, false);
    Properties properties = propertiesArgumentCaptor.getValue();

    assertThat(properties.getProperty("@BlobStore.blob-name"), is("org/codehaus/plexus/plexus/3.1/plexus-3.1.pom"));
  }

  @Test
  void shouldNotRestoreMetadataWhenAssetBlobRefNotMigrated() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);
    when(assetBlobRefFormatCheck.isAssetBlobRefNotMigrated(repository)).thenReturn(true);

    underTest.execute();

    verify(restoreBlobStrategy, never()).restore(any(), eq(blob), eq(blobStore), eq(false));
    verify(blobStore, never()).undelete(blobstoreUsageChecker, blobId, blobAttributes, false);
  }

  @Test
  void shouldNotRestoreMetadataWhenExceptionDeterminingAssetBlobRefStatus() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);
    doThrow(new RuntimeException("Error!!!")).when(assetBlobRefFormatCheck).isAssetBlobRefNotMigrated(repository);

    underTest.execute();

    verify(restoreBlobStrategy, never()).restore(any(), eq(blob), eq(blobStore), eq(false));
    verify(blobStore, never()).undelete(blobstoreUsageChecker, blobId, blobAttributes, false);
  }

  @Test
  void restoreMetadataWithoutUndeleteOption() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy).restore(any(), eq(blob), eq(blobStore), eq(false));
    verify(blobStore, never()).undelete(any(), any(), any(), eq(false));
  }

  @SuppressWarnings("deprecation")
  @Test
  void restoreMetadataWhenBlobIsMarkedAsDeleted() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    blobAttributes.setDeleted(true);

    underTest.execute();

    verify(restoreBlobStrategy, never()).restore(any(), any(), any());
    verify(blobStore).undelete(any(), any(), any(), eq(false));
  }

  @Test
  void noOperationsWhenAllOptionsDisabled() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, false);

    underTest.configure(configuration);

    underTest.execute();

    verifyNoInteractions(blobStore);
  }

  @Test
  void integrityCheckSkippedWhenBlobStoreDoesNotExist() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(blobStoreManager.get(anyString())).thenReturn(null);

    underTest.execute();

    verifyNoInteractions(repositoryManager);
  }

  @Test
  void integrityCheckSkipsGroupRepositories() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(repository.getType()).thenReturn(new GroupType());
    when(repositoryManager.browseForBlobStore(any())).thenReturn(singletonList(repository));

    underTest.execute();

    verifyNoInteractions(integrityCheckStrategies);
  }

  @Test
  void integrityCheckHandlesEmptyRepositoryList() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(repositoryManager.browseForBlobStore(any())).thenReturn(Collections.emptyList());

    underTest.execute();

    verifyNoInteractions(integrityCheckStrategies);
  }

  @Test
  void integrityCheckHandlesNullRepository() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    List<Repository> repositories = singletonList(null);
    when(repositoryManager.browseForBlobStore(any())).thenReturn(repositories);

    underTest.execute();

    verifyNoInteractions(integrityCheckStrategies);
  }

  @Test
  void integrityCheckSkipsNotStartedRepositories() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(repository.isStarted()).thenReturn(false);
    when(repositoryManager.browseForBlobStore(any())).thenReturn(singletonList(repository));

    underTest.execute();

    verifyNoInteractions(integrityCheckStrategies);
  }

  @Test
  void integrityCheckUsesDefaultStrategyForUnknownFormat() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(repositoryManager.browseForBlobStore(any())).thenReturn(singletonList(repository));

    // this should return the DefaultIntegrityCheckStrategy
    when(mavenFormat.getValue()).thenReturn("foo");

    underTest.execute();

    verify(defaultIntegrityCheckStrategy).check(any(), any(), any(), anyInt(), any());
    verifyNoInteractions(testIntegrityCheckStrategy);
  }

  @Test
  void integrityCheckUsesFormatSpecificStrategy() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, false);
    configuration.setBoolean(INTEGRITY_CHECK, true);
    underTest.configure(configuration);

    when(repositoryManager.browseForBlobStore(any())).thenReturn(singletonList(repository));

    underTest.execute();

    verifyNoInteractions(defaultIntegrityCheckStrategy);
    verify(testIntegrityCheckStrategy).check(eq(repository), eq(blobStore), any(), anyInt(), any());
  }

  @Test
  void shouldUpdateAfterAssetsWhenCallAfter() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy).after(true, repository);
  }

  @Test
  void shouldNotUpdateAfterAssetsWhenDryRun() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    configuration.setBoolean(DRY_RUN, true);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy, never()).after(false, repository);
  }

  @Test
  void shouldNotUpdateAfterAssetsWhenRestoreFalse() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, false);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    configuration.setBoolean(DRY_RUN, true);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy, never()).after(false, repository);
  }

  @Test
  void afterCallShouldBeCancelable() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);

    RestoreMetadataTask underTest =
        new RestoreMetadataTask(blobStoreManager, changeBlobstoreStore, repositoryManager,
            ImmutableMap.of(MAVEN_2, restoreBlobStrategy),
            blobstoreUsageChecker, dryRunPrefix, integrityCheckStrategies, maintenanceService, assetBlobRefFormatCheck,
            taskUtils)
        {
          @Override
          public boolean isCanceled() {
            return true;
          }
        };

    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy, never()).after(true, repository);
  }

  @Test
  void afterCallWillNotRunForUnknownFormat() throws Exception {
    when(mavenFormat.getValue()).thenReturn("unknownFormat");

    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy, never()).after(true, repository);
  }

  @Test
  void afterIsCalledForEachRepositoryWhenBlobsFromDifferentRepositories() throws Exception {
    BlobAttributes blobAttributes2 = mock(BlobAttributes.class);
    Properties properties = mock(Properties.class);
    Repository repository2 = mock(Repository.class);

    BlobId blobId2 = new BlobId("86e20baa-0bca-4915-a7dc-9a4f34e72322");
    when(blobStore.get(blobId2, true)).thenReturn(blob);
    when(blobStore.getBlobAttributes(blobId2)).thenReturn(blobAttributes2);
    when(blobAttributes2.getProperties()).thenReturn(properties);
    when(properties.getProperty(HEADER_PREFIX + REPO_NAME_HEADER)).thenReturn("maven-central2");
    when(repositoryManager.get("maven-central2")).thenReturn(repository2);
    when(repository2.getFormat()).thenReturn(mavenFormat);

    when(blobStore.getBlobIdStream()).thenReturn(Stream.of(blobId, blobId2));

    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy).after(true, repository);
    verify(restoreBlobStrategy).after(true, repository2);
  }

  @Test
  void taskContinuesProcessingIfOneBlobThrowsException() throws Exception {
    BlobAttributes blobAttributes2 = mock(BlobAttributes.class);
    Properties properties2 = mock(Properties.class);
    Repository repository2 = mock(Repository.class);

    BlobId exceptionBlobId = new BlobId("86e20baa-0bca-4915-a7dc-9a4f34e72322");
    Blob exceptionBlob = mock(Blob.class);
    when(blobStore.get(exceptionBlobId, true)).thenReturn(exceptionBlob);
    when(blobStore.getBlobAttributes(exceptionBlobId)).thenReturn(blobAttributes2);
    BlobStoreConfiguration blobStoreConfiguration = mock(BlobStoreConfiguration.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobAttributes2.getProperties()).thenReturn(properties2);
    when(properties2.getProperty(HEADER_PREFIX + REPO_NAME_HEADER)).thenReturn("maven-central2");
    when(repositoryManager.get("maven-central2")).thenReturn(repository2);
    when(repository2.getFormat()).thenReturn(mavenFormat);

    doThrow(new RuntimeException())
        .when(restoreBlobStrategy)
        .restore(properties2, exceptionBlob, blobStore, false);

    BlobAttributes blobAttributes3 = mock(BlobAttributes.class);
    Properties properties3 = mock(Properties.class);
    Repository repository3 = mock(Repository.class);

    BlobId blobId3 = new BlobId("86e20baa-0bca-4915-a7dc-9a4f34e72324");
    when(blobStore.get(blobId3, true)).thenReturn(blob);
    when(blobStore.getBlobAttributes(blobId3)).thenReturn(blobAttributes3);
    when(blobAttributes3.getProperties()).thenReturn(properties3);
    when(properties3.getProperty(HEADER_PREFIX + REPO_NAME_HEADER)).thenReturn("maven-central2");
    when(repositoryManager.get("maven-central2")).thenReturn(repository3);
    when(repository3.getFormat()).thenReturn(mavenFormat);

    when(blobStore.getBlobIdStream()).thenReturn(Stream.of(blobId, exceptionBlobId, blobId3));

    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    underTest.execute();

    verify(restoreBlobStrategy).restore(properties2, exceptionBlob, blobStore, false);
    verify(restoreBlobStrategy).after(true, repository);
    verify(restoreBlobStrategy, times(0)).after(true, repository2);
    verify(restoreBlobStrategy).after(true, repository3);
  }

  @Test
  void shouldGetAllBlobsToRestoreWhenSinceDaysSetToNegativeNumber() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    configuration.setInteger(SINCE_DAYS, -4);
    underTest.configure(configuration);

    underTest.execute();

    verify(blobStore, never()).getBlobIdUpdatedSinceStream(any(Duration.class));
    verify(blobStore).getBlobIdStream();
  }

  @Test
  void shouldGetAllBlobsToRestoreWhenSinceDaysNotSet() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);

    underTest.execute();

    verify(blobStore, never()).getBlobIdUpdatedSinceStream(any(Duration.class));
    verify(blobStore).getBlobIdStream();
  }

  @Test
  void shouldGetRecentBlobsWhenSinceDaysConfigured() throws Exception {
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    configuration.setInteger(SINCE_DAYS, 2);
    underTest.configure(configuration);
    when(blobStore.getBlobIdUpdatedSinceStream(Duration.ofDays(2L))).thenReturn(Stream.empty());

    underTest.execute();

    verify(blobStore).getBlobIdUpdatedSinceStream(Duration.ofDays(2L));
    verify(blobStore, never()).getBlobIdStream();
  }
  
  /**
   * Test to validate that RestoreMetadataTask can effectively use virtual threads
   * for concurrent blob processing, demonstrating Java 21 compatibility.
   */
  @Test
  @org.junit.jupiter.api.Tag("VirtualThread")
  void concurrentBlobProcessingWithVirtualThreads() throws Exception {
    // Configure the task
    configuration.setBoolean(RESTORE_BLOBS, true);
    configuration.setBoolean(UNDELETE_BLOBS, true);
    configuration.setBoolean(INTEGRITY_CHECK, false);
    underTest.configure(configuration);
    
    // Create a large number of test blobs
    int blobCount = 1000;
    BlobId[] blobIds = new BlobId[blobCount];
    for (int i = 0; i < blobCount; i++) {
      blobIds[i] = new BlobId("test-blob-" + i);
    }
    
    // Setup virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Setup test tracking
      CountDownLatch latch = new CountDownLatch(blobCount);
      AtomicInteger processedCount = new AtomicInteger(0);
      
      // Mock blob store to return our test blob IDs
      when(blobStore.getBlobIdStream()).thenReturn(Stream.of(blobIds));
      
      // For each blob ID, set up the necessary mocks
      for (BlobId testBlobId : blobIds) {
        Blob testBlob = mock(Blob.class);
        BlobAttributes testAttributes = mock(BlobAttributes.class);
        Properties testProperties = new Properties();
        testProperties.setProperty(HEADER_PREFIX + REPO_NAME_HEADER, "maven-central");
        
        when(blobStore.get(testBlobId, true)).thenReturn(testBlob);
        when(blobStore.getBlobAttributes(testBlobId)).thenReturn(testAttributes);
        when(testAttributes.getProperties()).thenReturn(testProperties);
        when(testAttributes.isDeleted()).thenReturn(false);
        
        // Set up the restore strategy to count processed blobs
        doAnswer(invocation -> {
          // Simulate some processing time
          Thread.sleep(5);
          processedCount.incrementAndGet();
          latch.countDown();
          return null;
        }).when(restoreBlobStrategy).restore(eq(testProperties), eq(testBlob), eq(blobStore), eq(false));
      }
      
      // Execute the task
      long startTime = System.currentTimeMillis();
      underTest.execute();
      long endTime = System.currentTimeMillis();
      
      // Verify all blobs were processed
      assertThat(processedCount.get(), is(blobCount));
      
      // Verify the task completed in a reasonable time (should be much faster with virtual threads)
      long executionTime = endTime - startTime;
      // If using virtual threads effectively, processing should be much faster than sequential
      // Sequential would be ~5ms * 1000 = 5000ms
      assertThat(executionTime, lessThan(5000L));
      
      // Verify after was called for the repository
      verify(restoreBlobStrategy).after(true, repository);
    } finally {
      executor.shutdown();
    }
  }
}
