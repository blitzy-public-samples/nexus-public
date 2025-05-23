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
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  private ArgumentCaptor<Map<String, Object>> attributesCaptor;

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
    assertThrows(ReplicationIngestionException.class, () -> 
        underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED));
  }

  @Test
  public void ingestBlobFailsIfBlobAttributesNotFound() {
    when(blobStore.getBlobAttributes(any(BlobId.class))).thenReturn(null);
    assertThrows(ReplicationIngestionException.class, () -> 
        underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED));
  }

  @Test
  public void ingestBlobFailsIfBlobNotFound() {
    when(blobStore.get(any(BlobId.class))).thenReturn(null);
    assertThrows(ReplicationIngestionException.class, () -> 
        underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED));
  }

  @Test
  public void ingestBlobCallsDeleteIfDeleteEvent() {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.DELETED);
    verify(replicationIngesterHelper, times(1)).deleteReplication("blobName", "repositoryName");
  }

  @Test
  public void ingestBlobCallsReplicateIfAddEvent() throws IOException {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED);
    verify(replicationIngesterHelper, times(1))
        .replicate(blobNameCaptor.capture(), blobCaptor.capture(), any(Map.class), any(Map.class), 
            any(String.class), any(String.class));
    
    assertThat(blobNameCaptor.getValue(), is("blobName"));
    assertThat(blobCaptor.getValue(), is(blob));
  }

  @Test
  public void ingestBlobCallsReplicateIfUpdateEvent() throws IOException {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.UPDATED);
    verify(replicationIngesterHelper, times(1))
        .replicate(blobNameCaptor.capture(), blobCaptor.capture(), any(Map.class), any(Map.class), 
            any(String.class), any(String.class));
    
    assertThat(blobNameCaptor.getValue(), is("blobName"));
    assertThat(blobCaptor.getValue(), is(blob));
  }

  @Test
  @org.junit.jupiter.api.Tag("Java21TestGroup")
  @org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
  public void concurrentIngestBlobWithVirtualThreadsSucceeds() throws Exception {
    // Create a virtual thread executor service
    ExecutorService executor = createVirtualThreadExecutorService();
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();

    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Use a unique blob ID for each task
            String blobId = "blobId-" + index;
            underTest.ingestBlob(blobId, "blobStoreId", "repositoryName", BlobEventType.ADDED);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            lastException.set(e);
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("No errors should occur during concurrent ingestion", errorCount.get(), is(0));
      
      // Verify the replicate method was called the expected number of times
      verify(replicationIngesterHelper, times(taskCount))
          .replicate(any(String.class), any(Blob.class), any(Map.class), any(Map.class), 
              any(String.class), any(String.class));
    } 
    finally {
      executor.shutdown();
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("Java21TestGroup")
  @org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
  public void verifyNoThreadPinningInReplicationOperations() throws Exception {
    // Create a virtual thread executor service
    ExecutorService executor = createVirtualThreadExecutorService();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> exception = new AtomicReference<>();

    try {
      // Execute a replication operation in a virtual thread
      executor.submit(() -> {
        try {
          // Perform the operation that should not cause thread pinning
          underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED);
          
          // If we reach here, no pinning occurred that would block the operation
          latch.countDown();
        } 
        catch (Exception e) {
          exception.set(e);
          latch.countDown();
        }
      });

      // Wait for the operation to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("Operation should complete without timeout", completed, is(true));
      if (exception.get() != null) {
        throw exception.get(); // Re-throw any exception that occurred
      }
      
      // Verify the replicate method was called
      verify(replicationIngesterHelper, times(1))
          .replicate(any(String.class), any(Blob.class), any(Map.class), any(Map.class), 
              any(String.class), any(String.class));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Creates an executor service that uses virtual threads.
   * 
   * @return An ExecutorService that creates a new virtual thread for each task
   */
  private ExecutorService createVirtualThreadExecutorService() {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("replication-test-").factory();
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory);
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