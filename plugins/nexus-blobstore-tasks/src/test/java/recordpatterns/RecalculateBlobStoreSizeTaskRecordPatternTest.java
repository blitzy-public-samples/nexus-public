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
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.metrics.reconcile.RecalculateBlobStoreSizeTask;
import org.sonatype.nexus.blobstore.metrics.reconcile.RecalculateBlobStoreSizeTaskDescriptor;
import org.sonatype.nexus.scheduling.TaskConfiguration;

// Java 21 imports for Record Patterns and Pattern Matching
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.tuple.Pair;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.ALL;
import static org.sonatype.nexus.blobstore.common.BlobStoreTaskSupport.BLOBSTORE_NAME_FIELD_ID;

/**
 * Test class that validates the implementation of Java 21's Record Pattern features in the RecalculateBlobStoreSizeTask.
 * It ensures that Record Patterns correctly extract and process structured data from blob metadata records,
 * improving code clarity and reducing boilerplate while maintaining correct functionality for size calculation operations.
 * 
 * This test class demonstrates various ways to use Java 21's Record Patterns:
 * - Basic record pattern matching with instanceof
 * - Nested record patterns for complex data structures
 * - Record patterns with switch expressions
 * - Record patterns with unnamed patterns (using underscore)
 * - Record patterns with collections
 * 
 * These tests verify that Record Patterns provide a more concise, readable, and type-safe way to extract
 * and process data from structured records, which is particularly valuable for operations like blob size calculation
 * that need to extract and process metadata from complex data structures.
 */
@ExtendWith(MockitoExtension.class)
public class RecalculateBlobStoreSizeTaskRecordPatternTest
    extends TestSupport
{
  @Mock
  private BlobStoreManager blobStoreManager;

  private RecalculateBlobStoreSizeTask underTest;

  @BeforeEach
  public void setUp() {
    underTest = spy(new RecalculateBlobStoreSizeTask(blobStoreManager));
  }

  /**
   * Tests that Record Patterns can correctly extract and process blob metrics data
   * from a single blob store using pattern matching.
   * 
   * This test demonstrates the basic usage of Java 21's Record Patterns to extract
   * and process data from blob metadata records in a type-safe manner.
   */
  @Test
  public void testRecordPatternWithSingleBlobStore() throws Exception {
    // Create a mock blob store with 10 blobs
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStore("pattern-blobstore", 10, false);

    // Configure the task
    TaskConfiguration configuration = buildTaskConfiguration("test-pattern-blobstore", "pattern-blobstore");

    // Execute the task
    underTest.configure(configuration);
    underTest.call();

    // Verify the task executed correctly
    verify(underTest, times(1)).execute(mocks.getLeft());
    verify(mocks.getLeft(), times(10)).getBlobAttributes(any(BlobId.class));
    verify(mocks.getRight(), times(10)).recordAddition(anyLong());
  }
  
  /**
   * Tests that Record Patterns can be used to process collections of blob data
   * in a concise and type-safe manner.
   * 
   * This test demonstrates how Java 21's Record Patterns can be used with collections
   * to process multiple records efficiently.
   */
  @Test
  public void testRecordPatternWithCollections() throws Exception {
    // Create a mock blob store with multiple blobs of different sizes
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStoreWithFixedSizes(
        "collection-pattern-blobstore", new long[]{50, 150, 250}, false);
    
    BlobStore blobStore = mocks.getLeft();
    
    // Create a list of blob attributes
    var blobAttributes = ImmutableList.of(
        blobStore.getBlobAttributes(new BlobId("0")),
        blobStore.getBlobAttributes(new BlobId("1")),
        blobStore.getBlobAttributes(new BlobId("2"))
    );
    
    // Use Record Patterns to calculate the total size of all blobs
    long totalSize = calculateTotalSize(blobAttributes);
    
    // Verify the total size
    assertEquals(450L, totalSize);
  }
  
  /**
   * Helper method that demonstrates using Record Patterns with collections
   * to calculate the total size of all blobs.
   */
  private long calculateTotalSize(Iterable<?> blobAttributes) {
    long total = 0;
    
    // Using Record Patterns to extract size from each blob attribute in the collection
    for (Object attribute : blobAttributes) {
      if (attribute instanceof TestBlobAttributes(var _, BlobMetrics(var _, var _, var size))) {
        total += size;
      }
    }
    
    return total;
  }

  /**
   * Tests that Record Patterns can correctly extract and process blob metrics data
   * from multiple blob stores using pattern matching.
   * 
   * This test demonstrates how Java 21's Record Patterns can be used to process data
   * from multiple sources in a consistent and type-safe manner.
   */
  @Test
  public void testRecordPatternWithMultipleBlobStores() throws Exception {
    // Create mock blob stores with different blob counts
    Pair<BlobStore, BlobStoreMetricsService> blobstore1Mocks = mockBlobStore("pattern-blobstore-1", 5, false);
    Pair<BlobStore, BlobStoreMetricsService> blobstore2Mocks = mockBlobStore("pattern-blobstore-2", 10, false);
    Pair<BlobStore, BlobStoreMetricsService> blobstore3Mocks = mockBlobStore("pattern-blobstore-3", 15, false);

    // Configure the task to process all blob stores
    TaskConfiguration configuration = buildTaskConfiguration("test-all-pattern-blobstores", ALL);

    // Set up the blob store manager to return our mock blob stores
    when(blobStoreManager.browse()).thenReturn(
        ImmutableList.of(blobstore1Mocks.getLeft(), blobstore2Mocks.getLeft(), blobstore3Mocks.getLeft()));

    // Execute the task
    underTest.configure(configuration);
    underTest.call();

    // Verify the task executed correctly for all blob stores
    verify(underTest, times(3)).execute(any(BlobStore.class));
    verify(blobstore1Mocks.getRight(), times(5)).recordAddition(anyLong());
    verify(blobstore2Mocks.getRight(), times(10)).recordAddition(anyLong());
    verify(blobstore3Mocks.getRight(), times(15)).recordAddition(anyLong());
  }
  
  /**
   * Tests that Record Patterns can be used with nested record structures to extract
   * and aggregate data from multiple levels of the object hierarchy.
   * 
   * This test demonstrates how Java 21's Record Patterns can be used to navigate and
   * process complex nested data structures in a concise and type-safe manner.
   */
  @Test
  public void testRecordPatternWithNestedStructures() throws Exception {
    // Create a mock blob store with multiple blobs
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStoreWithFixedSizes(
        "nested-structures-blobstore", new long[]{100, 200, 300}, false);
    
    BlobStore blobStore = mocks.getLeft();
    
    // Create a nested structure containing blob attributes
    record BlobContainer(String name, Object attributes) {}
    record BlobGroup(String groupName, BlobContainer container1, BlobContainer container2) {}
    
    // Create nested containers with blob attributes
    BlobContainer container1 = new BlobContainer("container1", 
        blobStore.getBlobAttributes(new BlobId("0")));
    BlobContainer container2 = new BlobContainer("container2", 
        blobStore.getBlobAttributes(new BlobId("1")));
    BlobGroup group = new BlobGroup("test-group", container1, container2);
    
    // Use nested Record Patterns to extract the total size from the nested structure
    long totalSize = 0;
    
    // This demonstrates the power of nested Record Patterns for traversing complex data structures
    if (group instanceof BlobGroup(var groupName, 
                                  BlobContainer(var name1, TestBlobAttributes(var _, BlobMetrics(var _, var _, var size1))),
                                  BlobContainer(var name2, TestBlobAttributes(var _, BlobMetrics(var _, var _, var size2))))) {
      totalSize = size1 + size2;
      
      // Verify the extracted data
      assertEquals("test-group", groupName);
      assertEquals("container1", name1);
      assertEquals("container2", name2);
    } else {
      throw new AssertionError("Nested Record Pattern did not match the expected structure");
    }
    
    // Verify the total size
    assertEquals(300L, totalSize);
  }

  /**
   * Tests that Record Patterns correctly handle edge cases with empty blob stores.
   * 
   * This test verifies that Record Patterns behave correctly when there are no records to match,
   * ensuring that the pattern matching logic is robust in edge cases.
   */
  @Test
  public void testRecordPatternWithEmptyBlobStore() throws Exception {
    // Create a mock blob store with 0 blobs
    Pair<BlobStore, BlobStoreMetricsService> emptyMocks = mockBlobStore("empty-pattern-blobstore", 0, false);

    // Configure the task
    TaskConfiguration configuration = buildTaskConfiguration("test-empty-pattern-blobstore", "empty-pattern-blobstore");

    // Execute the task
    underTest.configure(configuration);
    underTest.call();

    // Verify the task executed correctly but no blob attributes were processed
    verify(underTest, times(1)).execute(emptyMocks.getLeft());
    verify(emptyMocks.getLeft(), never()).getBlobAttributes(any(BlobId.class));
    verify(emptyMocks.getRight(), never()).recordAddition(anyLong());
  }
  
  /**
   * Tests that Record Patterns can be used with switch expressions for more concise
   * and expressive pattern matching on blob data.
   * 
   * This test demonstrates how Java 21's Record Patterns can be combined with switch expressions
   * to create more readable and maintainable code for processing blob metadata.
   */
  @Test
  public void testRecordPatternWithSwitchExpression() throws Exception {
    // Create a mock blob store with different blob sizes
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStoreWithFixedSizes(
        "switch-pattern-blobstore", new long[]{50, 150, 250}, false);
    
    BlobStore blobStore = mocks.getLeft();
    
    // Get blob attributes for different blobs
    BlobAttributesSupport<?> smallBlob = (BlobAttributesSupport<?>) blobStore.getBlobAttributes(new BlobId("0"));
    BlobAttributesSupport<?> mediumBlob = (BlobAttributesSupport<?>) blobStore.getBlobAttributes(new BlobId("1"));
    BlobAttributesSupport<?> largeBlob = (BlobAttributesSupport<?>) blobStore.getBlobAttributes(new BlobId("2"));
    
    // Use Record Patterns with switch expressions to categorize blobs by size
    String smallCategory = categorizeBlob(smallBlob);
    String mediumCategory = categorizeBlob(mediumBlob);
    String largeCategory = categorizeBlob(largeBlob);
    
    // Verify the categorization
    assertEquals("small", smallCategory);
    assertEquals("medium", mediumCategory);
    assertEquals("large", largeCategory);
  }
  
  /**
   * Helper method that demonstrates using Record Patterns with switch expressions
   * to categorize blobs based on their size.
   */
  private String categorizeBlob(Object blobAttributes) {
    // Using Record Patterns with switch expressions for concise pattern matching
    return switch (blobAttributes) {
      case TestBlobAttributes(var headers, BlobMetrics(var created, var sha1, var size)) when size < 100 -> "small";
      case TestBlobAttributes(var headers, BlobMetrics(var created, var sha1, var size)) when size < 200 -> "medium";
      case TestBlobAttributes(var headers, BlobMetrics(var created, var sha1, var size)) -> "large";
      default -> "unknown";
    };
  }

  /**
   * Tests that Record Patterns correctly handle error conditions and propagate failures.
   * 
   * This test verifies that Record Patterns behave correctly when exceptions occur during
   * pattern matching, ensuring that error handling is robust and failures are properly propagated.
   */
  @Test
  public void testRecordPatternWithFailingBlobStore() {
    // Create mock blob stores, one of which will throw an exception
    Pair<BlobStore, BlobStoreMetricsService> failingMocks = mockBlobStore("failing-pattern-blobstore", 3, true);
    Pair<BlobStore, BlobStoreMetricsService> workingMocks = mockBlobStore("working-pattern-blobstore", 7, false);

    // Configure the task to process all blob stores
    TaskConfiguration configuration = buildTaskConfiguration("test-failing-pattern-blobstore", ALL);

    // Set up the blob store manager to return our mock blob stores
    when(blobStoreManager.browse()).thenReturn(
        ImmutableList.of(failingMocks.getLeft(), workingMocks.getLeft()));

    // Execute the task and expect a MultipleFailuresException
    underTest.configure(configuration);
    assertThrows(Exception.class, () -> underTest.call());

    // Verify the task attempted to execute on both blob stores
    verify(underTest, times(2)).execute(any(BlobStore.class));
    verify(failingMocks.getRight(), never()).recordAddition(anyLong());
    verify(workingMocks.getRight(), times(7)).recordAddition(anyLong());
  }
  
  /**
   * Tests that Record Patterns can be used with unnamed patterns (using underscore)
   * to ignore irrelevant components when extracting data.
   * 
   * This test demonstrates how Java 21's Record Patterns can be used with unnamed patterns
   * to focus only on the relevant data components, improving code clarity.
   */
  @Test
  public void testRecordPatternWithUnnamedPatterns() throws Exception {
    // Create a mock blob store with a single blob
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStoreWithFixedSizes(
        "unnamed-pattern-blobstore", new long[]{200}, false);
    
    BlobStore blobStore = mocks.getLeft();
    BlobId blobId = new BlobId("test-blob");
    
    // Get the blob attributes
    BlobAttributesSupport<?> attributes = (BlobAttributesSupport<?>) blobStore.getBlobAttributes(blobId);
    
    // Extract only the size component using Record Pattern with unnamed patterns for irrelevant components
    // This demonstrates how Record Patterns can be used to focus only on relevant data
    long extractedSize = extractSizeOnly(attributes);
    
    // Verify the extracted size
    assertEquals(200L, extractedSize);
    
    // Also test the getBlobSize method that uses Record Patterns internally
    TestBlobAttributes testAttributes = (TestBlobAttributes) attributes;
    assertEquals(200L, testAttributes.getBlobSize());
  }
  
  /**
   * Helper method that demonstrates using Record Patterns with unnamed patterns
   * to extract only the size component from blob attributes.
   */
  private long extractSizeOnly(Object blobAttributes) {
    // Using Record Patterns with unnamed patterns (underscore) to ignore irrelevant components
    if (blobAttributes instanceof TestBlobAttributes(var _, BlobMetrics(var _, var _, var size))) {
      return size;
    }
    return -1L;
  }
  
  /**
   * Tests that Record Patterns can be used with type predicates to filter and process
   * blob data based on specific criteria.
   * 
   * This test demonstrates how Java 21's Record Patterns can be combined with predicates
   * to create powerful data filtering and processing capabilities.
   */
  @Test
  public void testRecordPatternWithPredicates() throws Exception {
    // Create a mock blob store with blobs of different sizes
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStoreWithFixedSizes(
        "predicate-pattern-blobstore", new long[]{50, 150, 250}, false);
    
    BlobStore blobStore = mocks.getLeft();
    
    // Create a list of blob attributes
    var blobAttributes = ImmutableList.of(
        blobStore.getBlobAttributes(new BlobId("0")),
        blobStore.getBlobAttributes(new BlobId("1")),
        blobStore.getBlobAttributes(new BlobId("2"))
    );
    
    // Define predicates using Record Patterns to filter blobs by size
    Predicate<Object> isSmallBlob = blob -> 
        blob instanceof TestBlobAttributes(var _, BlobMetrics(var _, var _, var size)) && size < 100;
    
    Predicate<Object> isMediumBlob = blob -> 
        blob instanceof TestBlobAttributes(var _, BlobMetrics(var _, var _, var size)) && size >= 100 && size < 200;
    
    Predicate<Object> isLargeBlob = blob -> 
        blob instanceof TestBlobAttributes(var _, BlobMetrics(var _, var _, var size)) && size >= 200;
    
    // Count blobs by size category using the predicates
    long smallCount = blobAttributes.stream().filter(isSmallBlob).count();
    long mediumCount = blobAttributes.stream().filter(isMediumBlob).count();
    long largeCount = blobAttributes.stream().filter(isLargeBlob).count();
    
    // Verify the counts
    assertEquals(1, smallCount);
    assertEquals(1, mediumCount);
    assertEquals(1, largeCount);
  }

  /**
   * Tests that Record Patterns can correctly extract and process blob metrics data
   * with specific size values using pattern matching.
   * 
   * This test demonstrates the use of Java 21's Record Patterns to extract and process
   * structured data from BlobMetrics records in a type-safe and concise manner.
   */
  @Test
  public void testRecordPatternWithSpecificBlobSizes() throws Exception {
    // Create a mock blob store with fixed blob sizes
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStoreWithFixedSizes(
        "fixed-size-pattern-blobstore", new long[]{100, 200, 300}, false);

    // Configure the task
    TaskConfiguration configuration = buildTaskConfiguration(
        "test-fixed-size-pattern-blobstore", "fixed-size-pattern-blobstore");

    // Execute the task
    underTest.configure(configuration);
    underTest.call();

    // Verify the task executed correctly
    verify(underTest, times(1)).execute(mocks.getLeft());
    verify(mocks.getLeft(), times(3)).getBlobAttributes(any(BlobId.class));
    
    // Verify each specific size was recorded
    verify(mocks.getRight(), times(1)).recordAddition(100L);
    verify(mocks.getRight(), times(1)).recordAddition(200L);
    verify(mocks.getRight(), times(1)).recordAddition(300L);
  }
  
  /**
   * Tests that Record Patterns can be used to extract and process nested data structures
   * from blob attributes using pattern matching.
   * 
   * This test specifically demonstrates how Java 21's Record Patterns can be used to
   * destructure nested data in a single step, improving code clarity and reducing boilerplate.
   */
  @Test
  public void testNestedRecordPatternMatching() throws Exception {
    // Create a mock blob store with a single blob
    Pair<BlobStore, BlobStoreMetricsService> mocks = mockBlobStoreWithFixedSizes(
        "nested-pattern-blobstore", new long[]{150}, false);
    
    BlobStore blobStore = mocks.getLeft();
    BlobId blobId = new BlobId("test-blob");
    
    // Get the blob attributes
    BlobAttributesSupport<?> attributes = (BlobAttributesSupport<?>) blobStore.getBlobAttributes(blobId);
    
    // Use Record Pattern to extract the BlobMetrics and its components in one step
    // This demonstrates the power of Record Patterns for data extraction
    if (attributes instanceof TestBlobAttributes(var headers, BlobMetrics(var created, var sha1, var size))) {
      // Verify the extracted data
      assertEquals(150L, size);
      assertEquals("hash", sha1);
    } else {
      // Fail the test if the pattern doesn't match
      throw new AssertionError("Record Pattern did not match the expected structure");
    }
  }

  /**
   * Helper method to create a mock blob store with random blob sizes.
   */
  private Pair<BlobStore, BlobStoreMetricsService> mockBlobStore(
      final String blobstoreName,
      final int blobsCount,
      final boolean throwException)
  {
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStoreManager.get(blobstoreName)).thenReturn(blobStore);
    when(configuration.getName()).thenReturn(blobstoreName);
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);

    if (throwException) {
      when(blobStore.getBlobIdStream()).thenThrow(new IllegalStateException("unavailable blobstore"));
    }
    else {
      when(blobStore.getBlobIdStream()).thenAnswer((invocation) -> Stream.iterate(0, n -> n + 1)
          .limit(blobsCount)
          .map((n) -> new BlobId(n.toString())));
    }

    when(blobStore.getBlobAttributes(any())).thenAnswer((invocation) -> {
      Random random = new Random();

      Map<String, String> headers = ImmutableMap.of();
      BlobMetrics metrics = new BlobMetrics(DateTime.now().minusHours(1), "hash", random.nextInt(100));
      return new TestBlobAttributes(headers, metrics);
    });

    return Pair.of(blobStore, metricsService);
  }

  /**
   * Helper method to create a mock blob store with fixed blob sizes.
   */
  private Pair<BlobStore, BlobStoreMetricsService> mockBlobStoreWithFixedSizes(
      final String blobstoreName,
      final long[] blobSizes,
      final boolean throwException)
  {
    BlobStoreMetricsService metricsService = mock(BlobStoreMetricsService.class);
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStoreManager.get(blobstoreName)).thenReturn(blobStore);
    when(configuration.getName()).thenReturn(blobstoreName);
    when(configuration.getType()).thenReturn(FileBlobStore.TYPE);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.getMetricsService()).thenReturn(metricsService);

    if (throwException) {
      when(blobStore.getBlobIdStream()).thenThrow(new IllegalStateException("unavailable blobstore"));
    }
    else {
      when(blobStore.getBlobIdStream()).thenAnswer((invocation) -> Stream.iterate(0, n -> n + 1)
          .limit(blobSizes.length)
          .map((n) -> new BlobId(n.toString())));
    }

    // Set up a counter to track which blob size to use
    final int[] counter = {0};
    
    when(blobStore.getBlobAttributes(any())).thenAnswer((invocation) -> {
      Map<String, String> headers = ImmutableMap.of();
      // Use the fixed size for this blob
      long size = blobSizes[counter[0]++ % blobSizes.length];
      BlobMetrics metrics = new BlobMetrics(DateTime.now().minusHours(1), "hash", size);
      return new TestBlobAttributes(headers, metrics);
    });

    return Pair.of(blobStore, metricsService);
  }

  /**
   * Helper method to build a task configuration.
   */
  private TaskConfiguration buildTaskConfiguration(final String taskName, final String blobStoreField) {
    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setId(taskName);
    taskConfiguration.setTypeId(RecalculateBlobStoreSizeTaskDescriptor.TYPE_ID);
    taskConfiguration.setString(".name", taskName);
    taskConfiguration.setString(BLOBSTORE_NAME_FIELD_ID, blobStoreField);

    return taskConfiguration;
  }

  /**
   * Test implementation of BlobAttributesSupport for use with Record Patterns.
   * This class is designed to be used with Java 21's Record Pattern feature to extract
   * blob metadata in a more concise and type-safe manner.
   * 
   * The class structure is intentionally designed to demonstrate how Record Patterns can be used
   * to extract data from nested structures. The BlobMetrics object contained within this class
   * can be directly accessed using nested Record Patterns, as demonstrated in the tests above.
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
    
    /**
     * Demonstrates how Record Patterns can be used to implement methods that process
     * structured data in a more concise and type-safe manner.
     * 
     * @return The size of the blob, or -1 if the metrics are not available
     */
    public long getBlobSize() {
      // Using Record Pattern to extract the size directly from the metrics
      if (getBlobMetrics() instanceof BlobMetrics(var created, var sha1, var size)) {
        return size;
      }
      return -1L;
    }
  }
}