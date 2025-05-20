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
package org.sonatype.nexus.repository.replication;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.testcommon.Java21TestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.runner.RunWith;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;

@ExtendWith(MockitoExtension.class)
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class ReplicationIngesterSupportTest
    extends TestSupport
{
  private TestReplicationIngester underTest;

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private ReplicationIngesterHelper replicationIngesterHelper;

  @Mock
  private BlobStore blobStore;

  @Mock
  private Blob blob;

  @Mock
  private BlobAttributes blobAttributes;
  
  @Captor
  private ArgumentCaptor<String> blobNameCaptor;
  
  @Captor
  private ArgumentCaptor<Blob> blobCaptor;
  
  @Captor
  private ArgumentCaptor<Map<String, Object>> assetAttributesCaptor;
  
  @Captor
  private ArgumentCaptor<Map<String, String>> headerCaptor;
  
  @Captor
  private ArgumentCaptor<String> repositoryNameCaptor;
  
  @Captor
  private ArgumentCaptor<String> blobStoreIdCaptor;

  private Map<String, String> blobHeaders;

  private Properties properties;

  @BeforeEach
  public void setup() {
    when(blobAttributes.getProperties()).thenReturn(getProperties());
    when(blobStoreManager.get(anyString())).thenReturn(blobStore);
    when(blobStore.get(any(BlobId.class))).thenReturn(blob);
    when(blobStore.getBlobAttributes(any(BlobId.class))).thenReturn(blobAttributes);
    when(blobAttributes.getHeaders()).thenReturn(getHeaders());
    underTest = new TestReplicationIngester(blobStoreManager, replicationIngesterHelper);
  }

  @Test
  public void extractAttributeFromPropertiesExtractsExpectedProperties() {
    Map<String, Object> extractedProperties = underTest.extractAssetAttributesFromProperties(getProperties());
    verifyExtractedProperties(extractedProperties);
  }

  @Test
  public void ingestBlobFailsIfBlobstoreNotPresent() {
    when(blobStoreManager.get(anyString())).thenReturn(null);
    assertThrows(ReplicationIngestionException.class, () -> {
      underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED);
    });
  }

  @Test
  public void ingestBlobFailsIfBlobAttributesNotFound() {
    when(blobStore.getBlobAttributes(any(BlobId.class))).thenReturn(null);
    assertThrows(ReplicationIngestionException.class, () -> {
      underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED);
    });
  }

  @Test
  public void ingestBlobFailsIfBlobNotFound() {
    when(blobStore.get(any(BlobId.class))).thenReturn(null);
    assertThrows(ReplicationIngestionException.class, () -> {
      underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED);
    });
  }

  @Test
  public void ingestBlobCallsDeleteIfDeleteEvent() {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.DELETED);
    verify(replicationIngesterHelper, times(1)).deleteReplication(blobNameCaptor.capture(), repositoryNameCaptor.capture());
    assertThat(blobNameCaptor.getValue(), is("blobName"));
    assertThat(repositoryNameCaptor.getValue(), is("repositoryName"));
  }

  @Test
  public void ingestBlobCallsReplicateIfAddEvent() throws IOException {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED);
    verify(replicationIngesterHelper, times(1))
        .replicate(blobNameCaptor.capture(), blobCaptor.capture(), assetAttributesCaptor.capture(), 
                  headerCaptor.capture(), repositoryNameCaptor.capture(), blobStoreIdCaptor.capture());
    
    assertThat(blobNameCaptor.getValue(), is("blobName"));
    assertThat(blobCaptor.getValue(), is(blob));
    assertThat(repositoryNameCaptor.getValue(), is("repositoryName"));
    assertThat(blobStoreIdCaptor.getValue(), is("blobStoreId"));
  }

  @Test
  public void ingestBlobCallsReplicateIfUpdateEvent() throws IOException {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.UPDATED);
    verify(replicationIngesterHelper, times(1))
        .replicate(blobNameCaptor.capture(), blobCaptor.capture(), assetAttributesCaptor.capture(), 
                  headerCaptor.capture(), repositoryNameCaptor.capture(), blobStoreIdCaptor.capture());
    
    assertThat(blobNameCaptor.getValue(), is("blobName"));
    assertThat(blobCaptor.getValue(), is(blob));
    assertThat(repositoryNameCaptor.getValue(), is("repositoryName"));
    assertThat(blobStoreIdCaptor.getValue(), is("blobStoreId"));
  }

  private void verifyExtractedProperties(final Map<String, Object> extractedProperties) {
    assertThat(extractedProperties.size(), is(3));
    assertThat(((Map<String, Object>) extractedProperties.get("checksum")).size(), is(4));
    assertThat(((Map<String, Object>) extractedProperties.get("content")).size(), is(1));
    assertThat(((Map<String, Object>) extractedProperties.get("provenance")).size(), is(1));
    assertThat(((Map<String, Object>) extractedProperties.get("checksum")).get("md5").toString(), is("md5hash"));
    assertThat(((Map<String, Object>) extractedProperties.get("checksum")).get("sha256").toString(), is("sha256hash"));
    assertThat(((Map<String, Object>) extractedProperties.get("checksum")).get("sha1").toString(), is("sha1hash"));
    assertThat(((Map<String, Object>) extractedProperties.get("checksum")).get("sha512").toString(), is("sha512hash"));
    assertThat(((Map<String, Object>) extractedProperties.get("content")).get("last_modified").toString(),
        is("Mon May 03 21:32:25 COT 2021"));
    assertThat(((Map<String, Object>) extractedProperties.get("provenance")).get("hashes_not_verified").toString(),
        is("false"));
  }
  
  /**
   * Creates an ExecutorService that uses virtual threads.
   * 
   * @return ExecutorService using virtual threads
   */
  private ExecutorService createVirtualThreadExecutorService() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @Test
  public void concurrentIngestBlobWithVirtualThreadsSucceeds() throws Exception {
    // Setup for concurrent operations
    int concurrentTasks = 50;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentTasks);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    // Use virtual threads for concurrent operations
    ExecutorService executor = createVirtualThreadExecutorService();
    
    try {
      // Submit concurrent tasks
      for (int i = 0; i < concurrentTasks; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Wait for all tasks to be ready
            startLatch.await();
            
            // Perform replication
            underTest.ingestBlob("blobId-" + taskId, "blobStoreId", "repositoryName", BlobEventType.ADDED);
            
            // Track successful completion
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            // Record first exception
            firstException.compareAndSet(null, e);
          }
          finally {
            // Signal task completion
            completionLatch.countDown();
          }
        });
      }
      
      // Start all tasks simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      completionLatch.await(10, TimeUnit.SECONDS);
      
      // Check results
      if (firstException.get() != null) {
        throw firstException.get();
      }
      
      assertThat(successCount.get(), is(concurrentTasks));
      
      // Verify replication was called the expected number of times
      verify(replicationIngesterHelper, times(concurrentTasks))
          .replicate(any(String.class), any(Blob.class), any(Map.class), 
                    any(Map.class), any(String.class), any(String.class));
    }
    finally {
      executor.shutdown();
    }
  }
  
  @Test
  public void verifyNoThreadPinningInReplicationOperations() throws Exception {
    // This test verifies that replication operations don't cause thread pinning
    // when using virtual threads. Thread pinning occurs when a virtual thread
    // becomes bound to its carrier thread, limiting scalability.
    
    // Setup for concurrent operations with potential for thread pinning
    int concurrentTasks = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentTasks);
    
    // Use virtual threads for concurrent operations
    ExecutorService executor = createVirtualThreadExecutorService();
    
    try {
      // Submit concurrent tasks that would reveal thread pinning issues
      for (int i = 0; i < concurrentTasks; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Wait for all tasks to be ready
            startLatch.await();
            
            // Perform replication - if thread pinning occurs, this would block carrier threads
            // and prevent all tasks from completing in a timely manner
            underTest.ingestBlob("blobId-" + taskId, "blobStoreId", "repositoryName", BlobEventType.ADDED);
          }
          catch (Exception e) {
            // Ignore exceptions for this test
          }
          finally {
            // Signal task completion
            completionLatch.countDown();
          }
        });
      }
      
      // Start all tasks simultaneously
      startLatch.countDown();
      
      // If thread pinning occurs, this await would time out as carrier threads become exhausted
      boolean allTasksCompleted = completionLatch.await(5, TimeUnit.SECONDS);
      
      // Verify all tasks completed, indicating no thread pinning issues
      assertThat("All concurrent tasks should complete without thread pinning", allTasksCompleted, is(true));
    }
    finally {
      executor.shutdown();
    }
  }

  private Map<String, String> getHeaders() {
    if (blobHeaders == null) {
      blobHeaders = new HashMap<>();
      blobHeaders.put(BLOB_NAME_HEADER, "blobName");
    }
    return blobHeaders;
  }

  private Properties getProperties() {
    if (properties == null) {
      properties = new Properties();
      properties.put("@attributes.asset.checksum.md5", "md5hash");
      properties.put("@attributes.asset.checksum.sha256", "sha256hash");
      properties.put("@attributes.asset.checksum.sha1", "sha1hash");
      properties.put("@attributes.asset.checksum.sha512", "sha512hash");
      properties.put("@attributes.asset.content.last_modified", "Mon May 03 21:32:25 COT 2021");
      properties.put("@attributes.asset.provenance.hashes_not_verified", "false");
    }
    return properties;
  }

  private static class TestReplicationIngester extends ReplicationIngesterSupport {
    @Override
    public String getFormat() {
      return "TEST";
    }

    public TestReplicationIngester(final BlobStoreManager blobstoreManager,
                                   final ReplicationIngesterHelper replicationIngesterHelper)
    {
      super(blobstoreManager, replicationIngesterHelper);
    }
  }
}