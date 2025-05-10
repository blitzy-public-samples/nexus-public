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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WriteToFirstMemberFillPolicyTest
    extends TestSupport
{
  private final WriteToFirstMemberFillPolicy underTest = new WriteToFirstMemberFillPolicy();

  static Stream<Arguments> blobStoreAvailabilityData() {
    return Stream.of(
        Arguments.of(false, false, "three"),
        Arguments.of(false, true, "three"),
        Arguments.of(true, false, "three"),
        Arguments.of(true, true, "one")
    );
  }

  @ParameterizedTest
  @MethodSource("blobStoreAvailabilityData")
  void itShouldSkipNonAvailableAndNonWritableMembers(boolean available, boolean writable, String chosenBlobStoreName) {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> mockedMembers =
        Arrays.asList(mockMember("one", available, writable), mockMember("two", available, writable),
            mockMember("three", true, true));
    when(blobStoreGroup.getMembers()).thenReturn(mockedMembers);
    
    BlobStore selectedBlobStore = underTest.chooseBlobStore(blobStoreGroup, new HashMap<>());
    assertNotNull(selectedBlobStore, "Selected blob store should not be null");
    assertEquals(chosenBlobStoreName, selectedBlobStore.getBlobStoreConfiguration().getName(),
        "Should select the correct blob store based on availability and writability");
  }

  @Test
  void testConcurrentBlobStoreSelectionWithVirtualThreads() throws Exception {
    // Create a blob store group with multiple members
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    List<BlobStore> mockedMembers = Arrays.asList(
        mockMember("one", true, true),
        mockMember("two", true, true),
        mockMember("three", true, true)
    );
    when(blobStoreGroup.getMembers()).thenReturn(mockedMembers);
    
    // Use virtual threads for concurrent operations
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            BlobStore selectedBlobStore = underTest.chooseBlobStore(blobStoreGroup, new HashMap<>());
            if (selectedBlobStore != null && "one".equals(selectedBlobStore.getBlobStoreConfiguration().getName())) {
              successCount.incrementAndGet();
            } else {
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
      
      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent blob store selection");
      assertEquals(taskCount, successCount.get(), "All operations should successfully select the first blob store");
    } finally {
      executor.shutdown();
    }
  }

  private BlobStore mockMember(final String name, final boolean available, final boolean writable) {
    BlobStore member = mock(BlobStore.class);
    BlobStoreConfiguration blobStoreConfiguration = mock(BlobStoreConfiguration.class);
    when(member.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStoreConfiguration.getName()).thenReturn(name);
    when(member.isStorageAvailable()).thenReturn(available);
    when(member.isWritable()).thenReturn(writable);
    return member;
  }
}