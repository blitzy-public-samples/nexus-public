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

import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.BlobAttributesSupport;
import org.sonatype.nexus.blobstore.api.*;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.metrics.reconcile.RecalculateBlobStoreSizeTask;
import org.sonatype.nexus.blobstore.metrics.reconcile.RecalculateBlobStoreSizeTaskDescriptor;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.ALL;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.BLOBSTORE_NAME_FIELD_ID;

/**
 * Test class that validates the implementation of Java 21's Record Pattern features in the
 * RecalculateBlobStoreSizeTask. It ensures that Record Patterns correctly extract and process
 * structured data from blob metadata records.
 */
public class RecalculateBlobStoreSizeTaskRecordPatternTest
    extends TestSupport
{
  @Mock
  private BlobStoreManager blobStoreManager;

  private RecalculateBlobStoreSizeTask underTest;
  
  // Record classes for testing Record Patterns
  record BlobMetricsRecord(DateTime created, String sha1Hash, long contentSize) {}
  record BlobAttributesRecord(Map<String, String> headers, BlobMetricsRecord metrics) {}
  record BlobStoreRecord(String name, String type, BlobStoreMetricsService metricsService) {}
  record BlobIdWithSize(BlobId id, long size) {}
  
  // Nested record for testing complex pattern matching
  record NestedBlobData(BlobAttributesRecord attributes, BlobId id) {}

  @Before
  public void setUp() {
    underTest = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
  }

  /**
   * Tests that Record Patterns can correctly extract size metrics from blob metadata.
   */
  @Test
  public void testRecordPatternExtractionOfSizeMetrics() throws Exception {
    // Setup a blob store with record pattern support
    String blobStoreName = "record-pattern-blobstore";
    int blobCount = 5;
    long expectedSize = 500L; // Each blob will be 100 bytes
    
    // Create a mock blob store that returns records
    BlobStore blobStore = mockBlobStoreWithRecords(blobStoreName, blobCount, expectedSize);
    BlobStoreMetricsService metricsService = blobStore.getMetricsService();
    
    // Configure and execute the task
    TaskConfiguration configuration = buildTaskConfiguration("test-record-patterns", blobStoreName);
    underTest.configure(configuration);
    underTest.call();
    
    // Verify that the size metrics were correctly extracted and processed
    verify(underTest, times(1)).execute(blobStore);
    verify(blobStore, times(blobCount)).getBlobAttributes(any(BlobId.class));
    verify(metricsService, times(blobCount)).recordAddition(anyLong());
    
    // Verify each size was correctly extracted using record patterns
    for (int i = 0; i < blobCount; i++) {
      verify(metricsService).recordAddition(eq(100L)); // Each blob is 100 bytes
    }
  }

  /**
   * Tests that nested Record Patterns can correctly process complex structured data.
   */
  @Test
  public void testNestedRecordPatternProcessing() throws Exception {
    // Setup a blob store with nested record pattern support
    String blobStoreName = "nested-record-pattern-blobstore";
    BlobStore blobStore = mockBlobStoreWithNestedRecords(blobStoreName);
    BlobStoreMetricsService metricsService = blobStore.getMetricsService();
    
    // Configure and execute the task
    TaskConfiguration configuration = buildTaskConfiguration("test-nested-patterns", blobStoreName);
    underTest.configure(configuration);
    underTest.call();
    
    // Verify that the nested record patterns were correctly processed
    verify(underTest, times(1)).execute(blobStore);
    verify(metricsService, times(3)).recordAddition(anyLong());
    
    // Verify specific sizes were extracted from nested records
    verify(metricsService).recordAddition(eq(150L));
    verify(metricsService).recordAddition(eq(250L));
    verify(metricsService).recordAddition(eq(350L));
  }

  /**
   * Tests that Record Patterns handle edge cases correctly, such as empty or null values.
   */
  @Test
  public void testRecordPatternEdgeCases() throws Exception {
    // Setup a blob store with edge case records
    String blobStoreName = "edge-case-blobstore";
    BlobStore blobStore = mockBlobStoreWithEdgeCases(blobStoreName);
    BlobStoreMetricsService metricsService = blobStore.getMetricsService();
    
    // Configure and execute the task
    TaskConfiguration configuration = buildTaskConfiguration("test-edge-cases", blobStoreName);
    underTest.configure(configuration);
    underTest.call();
    
    // Verify that edge cases were handled correctly
    verify(underTest, times(1)).execute(blobStore);
    verify(metricsService, times(2)).recordAddition(anyLong()); // Only 2 valid records
    verify(metricsService).recordAddition(eq(100L));
    verify(metricsService).recordAddition(eq(200L));
    // The zero-size blob should be skipped
    verify(metricsService, never()).recordAddition(eq(0L));
  }

  /**
   * Tests that Record Patterns maintain type safety while reducing boilerplate code.
   */
  @Test
  public void testRecordPatternTypeSafety() throws Exception {
    // Setup a blob store with type safety test records
    String blobStoreName = "type-safety-blobstore";
    BlobStore blobStore = mockBlobStoreForTypeSafetyTest(blobStoreName);
    BlobStoreMetricsService metricsService = blobStore.getMetricsService();
    
    // Configure and execute the task
    TaskConfiguration configuration = buildTaskConfiguration("test-type-safety", blobStoreName);
    underTest.configure(configuration);
    
    // This should throw a ClassCastException due to type mismatch in the record pattern
    assertThrows(ClassCastException.class, () -> underTest.call());
    
    // Verify that no metrics were recorded due to the type safety error
    verify(metricsService, never()).recordAddition(anyLong());
  }

  /**
   * Creates a mock BlobStore that returns record-based blob attributes.
   */
  private BlobStore mockBlobStoreWithRecords(
      final String blobStoreName,
      final int blobCount,
      final long totalSize)
  {
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    
    when(blobStoreManager.get(blobStoreName)).thenReturn(blobStore);
    when(configuration.getName()).thenReturn(blobStoreName);
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);
    
    // Generate a stream of BlobIds
    when(blobStore.getBlobIdStream()).thenAnswer((invocation) -> Stream.iterate(0, n -> n + 1)
        .limit(blobCount)
        .map((n) -> new BlobId(n.toString())));
    
    // For each BlobId, return a BlobAttributesRecord with a fixed size of 100 bytes
    when(blobStore.getBlobAttributes(any())).thenAnswer((invocation) -> {
      Map<String, String> headers = ImmutableMap.of();
      BlobMetricsRecord metricsRecord = new BlobMetricsRecord(
          DateTime.now().minusHours(1),
          "hash" + System.currentTimeMillis(),
          100L); // Each blob is 100 bytes
      
      return new TestBlobAttributes(headers, new BlobMetrics(
          metricsRecord.created(),
          metricsRecord.sha1Hash(),
          metricsRecord.contentSize()));
    });
    
    return blobStore;
  }

  /**
   * Creates a mock BlobStore that returns nested record structures.
   */
  private BlobStore mockBlobStoreWithNestedRecords(final String blobStoreName) {
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    
    when(blobStoreManager.get(blobStoreName)).thenReturn(blobStore);
    when(configuration.getName()).thenReturn(blobStoreName);
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);
    
    // Create three BlobIds with nested record structures
    BlobId id1 = new BlobId("nested1");
    BlobId id2 = new BlobId("nested2");
    BlobId id3 = new BlobId("nested3");
    
    when(blobStore.getBlobIdStream()).thenReturn(Stream.of(id1, id2, id3));
    
    // Create nested record structures with different sizes
    when(blobStore.getBlobAttributes(eq(id1))).thenAnswer((invocation) -> {
      BlobMetricsRecord metricsRecord = new BlobMetricsRecord(
          DateTime.now().minusHours(1),
          "hash1",
          150L);
      
      return new TestBlobAttributes(ImmutableMap.of(), new BlobMetrics(
          metricsRecord.created(),
          metricsRecord.sha1Hash(),
          metricsRecord.contentSize()));
    });
    
    when(blobStore.getBlobAttributes(eq(id2))).thenAnswer((invocation) -> {
      BlobMetricsRecord metricsRecord = new BlobMetricsRecord(
          DateTime.now().minusHours(2),
          "hash2",
          250L);
      
      return new TestBlobAttributes(ImmutableMap.of(), new BlobMetrics(
          metricsRecord.created(),
          metricsRecord.sha1Hash(),
          metricsRecord.contentSize()));
    });
    
    when(blobStore.getBlobAttributes(eq(id3))).thenAnswer((invocation) -> {
      BlobMetricsRecord metricsRecord = new BlobMetricsRecord(
          DateTime.now().minusHours(3),
          "hash3",
          350L);
      
      return new TestBlobAttributes(ImmutableMap.of(), new BlobMetrics(
          metricsRecord.created(),
          metricsRecord.sha1Hash(),
          metricsRecord.contentSize()));
    });
    
    return blobStore;
  }

  /**
   * Creates a mock BlobStore that returns edge case records (null, empty, zero values).
   */
  private BlobStore mockBlobStoreWithEdgeCases(final String blobStoreName) {
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    
    when(blobStoreManager.get(blobStoreName)).thenReturn(blobStore);
    when(configuration.getName()).thenReturn(blobStoreName);
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);
    
    // Create BlobIds for edge cases
    BlobId id1 = new BlobId("valid1");
    BlobId id2 = new BlobId("valid2");
    BlobId id3 = new BlobId("zero-size");
    
    when(blobStore.getBlobIdStream()).thenReturn(Stream.of(id1, id2, id3));
    
    // Valid blob with size 100
    when(blobStore.getBlobAttributes(eq(id1))).thenAnswer((invocation) -> {
      BlobMetricsRecord metricsRecord = new BlobMetricsRecord(
          DateTime.now().minusHours(1),
          "hash1",
          100L);
      
      return new TestBlobAttributes(ImmutableMap.of(), new BlobMetrics(
          metricsRecord.created(),
          metricsRecord.sha1Hash(),
          metricsRecord.contentSize()));
    });
    
    // Valid blob with size 200
    when(blobStore.getBlobAttributes(eq(id2))).thenAnswer((invocation) -> {
      BlobMetricsRecord metricsRecord = new BlobMetricsRecord(
          DateTime.now().minusHours(2),
          "hash2",
          200L);
      
      return new TestBlobAttributes(ImmutableMap.of(), new BlobMetrics(
          metricsRecord.created(),
          metricsRecord.sha1Hash(),
          metricsRecord.contentSize()));
    });
    
    // Zero-size blob (should be skipped)
    when(blobStore.getBlobAttributes(eq(id3))).thenAnswer((invocation) -> {
      BlobMetricsRecord metricsRecord = new BlobMetricsRecord(
          DateTime.now().minusHours(3),
          "hash3",
          0L);
      
      return new TestBlobAttributes(ImmutableMap.of(), new BlobMetrics(
          metricsRecord.created(),
          metricsRecord.sha1Hash(),
          metricsRecord.contentSize()));
    });
    
    return blobStore;
  }

  /**
   * Creates a mock BlobStore that will trigger a type safety error when using record patterns.
   */
  private BlobStore mockBlobStoreForTypeSafetyTest(final String blobStoreName) {
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    
    when(blobStoreManager.get(blobStoreName)).thenReturn(blobStore);
    when(configuration.getName()).thenReturn(blobStoreName);
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);
    
    // Create a BlobId for the type safety test
    BlobId id = new BlobId("type-safety-test");
    
    when(blobStore.getBlobIdStream()).thenReturn(Stream.of(id));
    
    // Return a String instead of a BlobMetrics object to trigger a ClassCastException
    when(blobStore.getBlobAttributes(eq(id))).thenReturn(null);
    
    return blobStore;
  }

  private TaskConfiguration buildTaskConfiguration(final String taskName, final String blobStoreField) {
    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setId(taskName);
    taskConfiguration.setTypeId(RecalculateBlobStoreSizeTaskDescriptor.TYPE_ID);
    taskConfiguration.setString(".name", taskName);
    taskConfiguration.setString(BLOBSTORE_NAME_FIELD_ID, blobStoreField);

    return taskConfiguration;
  }

  /**
   * Test implementation of BlobAttributesSupport for use with record patterns.
   */
  private static class TestBlobAttributes
      extends BlobAttributesSupport<Properties>
  {
    public Properties properties;

    public TestBlobAttributes(final Map<String, String> headers, final BlobMetrics blobMetrics) {
      super(new Properties(), headers, blobMetrics);
      this.properties = propertiesFile;
    }

    @Override
    public void store() {
      writeTo(properties);
    }

    @Override
    public void writeProperties() {
      writeTo(propertiesFile);
    }
  }
}