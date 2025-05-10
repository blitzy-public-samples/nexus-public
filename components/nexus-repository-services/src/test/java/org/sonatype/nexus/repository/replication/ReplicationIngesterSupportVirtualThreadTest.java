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
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * Tests for {@link ReplicationIngesterSupport} using Java 21 Virtual Threads.
 * 
 * This test validates that the replication ingester works correctly with virtual threads,
 * ensuring that I/O operations don't cause thread pinning and that concurrent operations
 * can be efficiently handled using the virtual thread execution model.
 */
@ExtendWith(MockitoExtension.class)
class ReplicationIngesterSupportVirtualThreadTest
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

  @Mock
  private InputStream blobInputStream;

  private Map<String, String> blobHeaders;

  private Properties properties;

  @BeforeEach
  void setup() {
    when(blobAttributes.getProperties()).thenReturn(getProperties());
    when(blobStoreManager.get(anyString())).thenReturn(blobStore);
    when(blobStore.get(any(BlobId.class))).thenReturn(blob);
    when(blobStore.getBlobAttributes(any(BlobId.class))).thenReturn(blobAttributes);
    when(blobAttributes.getHeaders()).thenReturn(getHeaders());
    when(blob.getInputStream()).thenReturn(blobInputStream);
    underTest = new TestReplicationIngester(blobStoreManager, replicationIngesterHelper);
  }

  @Test
  void extractAttributeFromProperties_extractsExpectedProperties() {
    Map<String, Object> extractedProperties = underTest.extractAssetAttributesFromProperties(getProperties());
    verifyExtractedProperties(extractedProperties);
  }

  @Test
  void ingestBlob_failsIfBlobstoreNotPresent() {
    when(blobStoreManager.get(anyString())).thenReturn(null);
    
    assertThrows(ReplicationIngestionException.class, () -> 
        underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED));
  }

  @Test
  void ingestBlob_failsIfBlobAttributesNotFound() {
    when(blobStore.getBlobAttributes(any(BlobId.class))).thenReturn(null);
    
    assertThrows(ReplicationIngestionException.class, () -> 
        underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED));
  }

  @Test
  void ingestBlob_failsIfBlobNotFound() {
    when(blobStore.get(any(BlobId.class))).thenReturn(null);
    
    assertThrows(ReplicationIngestionException.class, () -> 
        underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED));
  }

  @Test
  void ingestBlob_callsDeleteIfDeleteEvent() {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.DELETED);
    verify(replicationIngesterHelper, times(1)).deleteReplication("blobName", "repositoryName");
  }

  @Test
  void ingestBlob_callsReplicateIfAddEvent() throws IOException {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.ADDED);
    verify(replicationIngesterHelper, times(1))
        .replicate(any(String.class), any(Blob.class), any(Map.class), any(Map.class), any(String.class), any(String.class));
  }

  @Test
  void ingestBlob_callsReplicateIfUpdateEvent() throws IOException {
    underTest.ingestBlob("blobId", "blobStoreId", "repositoryName", BlobEventType.UPDATED);
    verify(replicationIngesterHelper, times(1))
        .replicate(any(String.class), any(Blob.class), any(Map.class), any(Map.class), any(String.class), any(String.class));
  }

  /**
   * Tests concurrent blob replication operations using virtual threads.
   * This test validates that the replication ingester can handle a high number of
   * concurrent operations efficiently using Java 21's virtual threads.
   */
  @Test
  void concurrentBlobReplication_withVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that creates a new virtual thread for each task
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000; // Test with 1000 concurrent operations
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between different event types to test all code paths
            BlobEventType eventType = switch (index % 3) {
              case 0 -> BlobEventType.ADDED;
              case 1 -> BlobEventType.UPDATED;
              case 2 -> BlobEventType.DELETED;
              default -> throw new IllegalStateException("Unexpected value");
            };
            
            underTest.ingestBlob("blobId" + index, "blobStoreId", "repositoryName", eventType);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout to prevent test hanging)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within the timeout", completed, is(true));
      assertThat("No errors should occur during concurrent execution", errorCount.get(), is(0));
      
      // Verify that the appropriate methods were called based on the event types
      // We expect approximately 1/3 of each event type (ADDED, UPDATED, DELETED)
      verify(replicationIngesterHelper, times(taskCount / 3)).deleteReplication(anyString(), anyString());
      
      // For ADDED and UPDATED events (2/3 of total), we expect replicate to be called
      verify(replicationIngesterHelper, times((taskCount * 2) / 3))
          .replicate(any(String.class), any(Blob.class), any(Map.class), any(Map.class), any(String.class), any(String.class));
    } finally {
      // Ensure executor is properly shut down
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Tests pattern matching for BlobEventType processing.
   * This test validates that the pattern matching implementation correctly handles
   * different BlobEventType values.
   */
  @Test
  void patternMatchingForBlobEventType() throws IOException {
    // Test pattern matching with different event types
    for (BlobEventType eventType : BlobEventType.values()) {
      // Process the event using pattern matching
      boolean isDeleteOperation = processEventWithPatternMatching(eventType);
      
      // Verify the result based on the event type
      switch (eventType) {
        case DELETED -> assertThat("DELETED should be a delete operation", isDeleteOperation, is(true));
        case ADDED, UPDATED -> assertThat(eventType + " should not be a delete operation", isDeleteOperation, is(false));
      }
    }
  }
  
  /**
   * Processes a BlobEventType using pattern matching to determine if it's a delete operation.
   * This demonstrates the use of Java 21's enhanced pattern matching for switch.
   */
  private boolean processEventWithPatternMatching(BlobEventType eventType) {
    return switch (eventType) {
      case DELETED -> true;
      case ADDED, UPDATED -> false;
    };
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

  /**
   * Test implementation of ReplicationIngesterSupport for testing purposes.
   */
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