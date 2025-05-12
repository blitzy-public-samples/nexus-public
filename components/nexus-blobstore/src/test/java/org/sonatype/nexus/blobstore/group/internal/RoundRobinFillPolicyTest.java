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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaResult;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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

  @Test
  public void testVirtualThreadBehavior() throws Exception {
    // Create a BlobStoreGroup with multiple members
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMemberWithWritable("one", true),
        mockMemberWithWritable("two", true),
        mockMemberWithWritable("three", true),
        mockMemberWithWritable("four", true));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    // Reset sequence to ensure predictable starting point
    roundRobinFillPolicy.sequence.set(0);

    // Create and run a virtual thread to select a blob store
    Thread virtualThread = Thread.ofVirtual().name("virtual-thread-test").start(() -> {
      BlobStore blobStore = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
      assertEquals("one", blobStore.getBlobStoreConfiguration().getName());
    });

    // Wait for the virtual thread to complete
    virtualThread.join();

    // Verify the sequence was incremented
    assertEquals(1, roundRobinFillPolicy.nextIndex());
  }

  @Test
  public void testConcurrentMemberSelection() throws Exception {
    // Create a BlobStoreGroup with multiple members
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> members = Arrays.asList(
        mockMemberWithWritable("one", true),
        mockMemberWithWritable("two", true),
        mockMemberWithWritable("three", true),
        mockMemberWithWritable("four", true));
    when(blobStoreGroup.getMembers()).thenReturn(members);

    // Reset sequence to ensure predictable starting point
    roundRobinFillPolicy.sequence.set(0);

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Number of concurrent tasks
    int taskCount = 10;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Each thread should get a valid blob store
            BlobStore blobStore = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
            // Verify the blob store is not null
            if (blobStore == null || blobStore.getBlobStoreConfiguration() == null) {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);

      // Verify no errors occurred
      assertEquals(0, errorCount.get());

      // Verify the sequence was incremented correctly
      assertEquals(taskCount % members.size(), roundRobinFillPolicy.sequence.get() % members.size());
    } finally {
      executor.shutdown();
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
}