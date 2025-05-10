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
package org.sonatype.nexus.blobstore.group.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.InjectMocks;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class RoundRobinFillPolicyTest
    extends TestSupport
{

  @Mock
  private BlobStoreQuotaService blobStoreQuotaService;

  @InjectMocks
  private RoundRobinFillPolicy roundRobinFillPolicy;

  private BlobStoreQuotaResult blobStoreQuotaResult;

  @BeforeEach
  public void setup() {
    blobStoreQuotaResult = new BlobStoreQuotaResult(true, "", "");
  }

  @Test
  public void nextIndexGivesExpectedValueWhenStartingAtInitialValue() {
    roundRobinFillPolicy.sequence.set(0);
    assertEquals(0, roundRobinFillPolicy.nextIndex());
    assertEquals(1, roundRobinFillPolicy.nextIndex());

    roundRobinFillPolicy.sequence.set(Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, roundRobinFillPolicy.nextIndex());
    assertEquals(0, roundRobinFillPolicy.nextIndex());
  }

  @Test
  public void itWillSkipBlobStoresThatAreNotWritable() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMemberWithAvailability("one", false),
        mockMemberWithAvailability("two", false),
        mockMemberWithAvailability("three", true),
        mockMemberWithAvailability("four", true));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    BlobStore blobStore = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
    assertEquals("three", blobStore.getBlobStoreConfiguration().getName());
    assertEquals(1, roundRobinFillPolicy.nextIndex());
  }

  @Test
  public void itWillReturnNullIfNoMembersAreWritable() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMemberWithAvailability("one", false),
        mockMemberWithAvailability("two", false));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.sequence.set(0);
    assertNull(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap()));
    assertEquals(1, roundRobinFillPolicy.nextIndex());

    roundRobinFillPolicy.sequence.set(1);
    assertNull(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap()));
    assertEquals(2, roundRobinFillPolicy.nextIndex());
  }

  @Test
  public void itWillReturnNullIfTheGroupHasNoMember() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    when(blobStoreGroup.getMembers()).thenReturn(Collections.emptyList());

    assertNull(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap()));
  }

  @Test
  public void itWillSkipReadOnlyMembers() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMemberWithWritable("one", false),
        mockMemberWithWritable("two", true),
        mockMemberWithWritable("three", false),
        mockMemberWithWritable("four", false),
        mockMemberWithWritable("five", true));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.sequence.set(0);
    assertEquals("two", roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName());

    roundRobinFillPolicy.sequence.set(1);
    assertEquals("five", roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName());
  }

  @Test
  public void itWillNotSkipMembersWithQuotaViolation() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMember("One", blobStoreQuotaResult),
        mockMember("Two", null),
        mockMember("Three", blobStoreQuotaResult));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    BlobStore store = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
    assertEquals("One", store.getBlobStoreConfiguration().getName());
  }

  @Test
  public void itWillSkipAllMembersWithQuotaViolation() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMember("One", blobStoreQuotaResult),
        mockMember("Three", blobStoreQuotaResult));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.skipOnSoftQuotaViolation = true;
    assertNull(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap()));
  }

  @Test
  public void itWillSkipMembersWithQuotaViolation() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMember("One", blobStoreQuotaResult),
        mockMember("Two", null),
        mockMember("Three", blobStoreQuotaResult),
        mockMember("Four", null));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.skipOnSoftQuotaViolation = true;
    assertEquals("Two", roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName());
    assertEquals("Four", roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName());
  }

  private BlobStore mockMemberWithWritable(final String name, final boolean writable) {
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.isStorageAvailable()).thenReturn(true);
    when(blobStore.isWritable()).thenReturn(writable);
    BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
    when(config.getName()).thenReturn(name);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    return blobStore;
  }

  private BlobStore mockMemberWithAvailability(final String name, final boolean availability) {
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.isStorageAvailable()).thenReturn(availability);
    when(blobStore.isWritable()).thenReturn(true);
    BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
    when(config.getName()).thenReturn(name);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    return blobStore;
  }

  private BlobStore mockMember(final String name, final BlobStoreQuotaResult result) {
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.isStorageAvailable()).thenReturn(true);
    when(blobStore.isWritable()).thenReturn(true);
    BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
    when(config.getName()).thenReturn(name);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(blobStoreQuotaService.checkQuota(blobStore)).thenReturn(result);
    return blobStore;
  }
  
  /**
   * Test to verify that the fill policy works correctly with virtual threads.
   * This test creates a virtual thread and verifies that the round-robin selection
   * works as expected when executed in a virtual thread context.
   */
  @Test
  public void virtualThreadShouldSelectBlobStoresCorrectly() throws Exception {
    // Create a blob store group with multiple members
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMemberWithWritable("one", true),
        mockMemberWithWritable("two", true),
        mockMemberWithWritable("three", true));
    when(blobStoreGroup.getMembers()).thenReturn(members);
    
    // Reset the sequence counter
    roundRobinFillPolicy.sequence.set(0);
    
    // Create and use a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> result = executor.submit(() -> {
        BlobStore selected = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
        return selected.getBlobStoreConfiguration().getName();
      });
      
      // Verify the result from the virtual thread
      assertEquals("one", result.get());
      assertEquals(1, roundRobinFillPolicy.nextIndex());
      
      // Run another selection to verify round-robin behavior continues correctly
      Future<String> secondResult = executor.submit(() -> {
        BlobStore selected = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
        return selected.getBlobStoreConfiguration().getName();
      });
      
      assertEquals("two", secondResult.get());
      assertEquals(2, roundRobinFillPolicy.nextIndex());
    }
  }
  
  /**
   * Test to validate concurrent member selection behavior with virtual threads.
   * This test creates multiple virtual threads that concurrently select blob stores
   * and verifies that the round-robin selection works correctly under concurrent conditions.
   */
  @Test
  public void concurrentVirtualThreadsShouldSelectBlobStoresCorrectly() throws Exception {
    // Create a blob store group with multiple members
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMemberWithWritable("one", true),
        mockMemberWithWritable("two", true),
        mockMemberWithWritable("three", true));
    when(blobStoreGroup.getMembers()).thenReturn(members);
    
    // Reset the sequence counter
    roundRobinFillPolicy.sequence.set(0);
    
    // Number of concurrent threads to use
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a list to collect the results
    List<String> results = Collections.synchronizedList(new ArrayList<>());
    
    // Create and use a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple tasks
      List<Future<Void>> futures = IntStream.range(0, threadCount)
          .mapToObj(i -> executor.submit(() -> {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Select a blob store
            BlobStore selected = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
            String name = selected.getBlobStoreConfiguration().getName();
            results.add(name);
            successCount.incrementAndGet();
            return null;
          }))
          .collect(Collectors.toList());
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<Void> future : futures) {
        future.get();
      }
      
      // Verify all threads successfully selected a blob store
      assertEquals(threadCount, successCount.get());
      
      // Verify the distribution of selections
      // Each member should be selected approximately threadCount/3 times
      // but we can't guarantee exact distribution due to concurrent access
      long oneCount = results.stream().filter(name -> name.equals("one")).count();
      long twoCount = results.stream().filter(name -> name.equals("two")).count();
      long threeCount = results.stream().filter(name -> name.equals("three")).count();
      
      // Verify all members were selected at least once
      assertTrue(oneCount > 0, "Member 'one' should be selected at least once");
      assertTrue(twoCount > 0, "Member 'two' should be selected at least once");
      assertTrue(threeCount > 0, "Member 'three' should be selected at least once");
      
      // Verify total count matches expected
      assertEquals(threadCount, oneCount + twoCount + threeCount);
    }
  }
}