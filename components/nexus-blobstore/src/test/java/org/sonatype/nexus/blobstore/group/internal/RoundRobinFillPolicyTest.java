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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link RoundRobinFillPolicy} with JUnit Jupiter and Java 21 features.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class RoundRobinFillPolicyTest
    extends TestSupport
{
  // Record for test data to demonstrate record patterns
  record BlobStoreTestData(String name, boolean available, boolean writable, BlobStoreQuotaResult quotaResult) {}

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
    assertThat(roundRobinFillPolicy.nextIndex(), is(0));
    assertThat(roundRobinFillPolicy.nextIndex(), is(1));

    roundRobinFillPolicy.sequence.set(Integer.MAX_VALUE);
    assertThat(roundRobinFillPolicy.nextIndex(), is(Integer.MAX_VALUE));
    assertThat(roundRobinFillPolicy.nextIndex(), is(0));
  }

  @Test
  public void itWillSkipBlobStoresThatAreNotWritable() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    
    // Using record patterns for test data definition
    List<BlobStoreTestData> testData = Arrays.asList(
        new BlobStoreTestData("one", false, true, null),
        new BlobStoreTestData("two", false, true, null),
        new BlobStoreTestData("three", true, true, null),
        new BlobStoreTestData("four", true, true, null));
    
    // Create mocks using the record pattern data
    List<BlobStore> members = testData.stream()
        .map(data -> mockMemberWithAvailability(data.name(), data.available(), data.writable()))
        .toList();
    
    when(blobStoreGroup.getMembers()).thenReturn(members);

    BlobStore blobStore = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
    assertThat(blobStore.getBlobStoreConfiguration().getName(), is("three"));
    assertThat(roundRobinFillPolicy.nextIndex(), is(1));
  }

  @Test
  public void itWillReturnNullIfNoMembersAreWritable() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    
    // Using record patterns for test data definition
    List<BlobStoreTestData> testData = Arrays.asList(
        new BlobStoreTestData("one", false, true, null),
        new BlobStoreTestData("two", false, true, null));
    
    // Create mocks using the record pattern data
    List<BlobStore> members = testData.stream()
        .map(data -> mockMemberWithAvailability(data.name(), data.available(), data.writable()))
        .toList();
    
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.sequence.set(0);
    assertNull(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap()));
    assertThat(roundRobinFillPolicy.nextIndex(), is(1));

    roundRobinFillPolicy.sequence.set(1);
    assertNull(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap()));
    assertThat(roundRobinFillPolicy.nextIndex(), is(2));
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
    
    // Using record patterns for test data definition
    List<BlobStoreTestData> testData = Arrays.asList(
        new BlobStoreTestData("one", true, false, null),
        new BlobStoreTestData("two", true, true, null),
        new BlobStoreTestData("three", true, false, null),
        new BlobStoreTestData("four", true, false, null),
        new BlobStoreTestData("five", true, true, null));
    
    // Create mocks using the record pattern data
    List<BlobStore> members = testData.stream()
        .map(data -> mockMemberWithAvailability(data.name(), data.available(), data.writable()))
        .toList();
    
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.sequence.set(0);
    assertThat(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName(), is("two"));

    roundRobinFillPolicy.sequence.set(1);
    assertThat(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName(), is("five"));
  }

  @Test
  public void itWillNotSkipMembersWithQuotaViolation() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    
    // Using record patterns for test data definition
    List<BlobStoreTestData> testData = Arrays.asList(
        new BlobStoreTestData("One", true, true, blobStoreQuotaResult),
        new BlobStoreTestData("Two", true, true, null),
        new BlobStoreTestData("Three", true, true, blobStoreQuotaResult));
    
    // Create mocks using the record pattern data
    List<BlobStore> members = testData.stream()
        .map(data -> mockMember(data.name(), data.quotaResult()))
        .toList();
    
    when(blobStoreGroup.getMembers()).thenReturn(members);

    BlobStore store = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
    assertThat(store.getBlobStoreConfiguration().getName(), is("One"));
  }

  @Test
  public void itWillSkipAllMembersWithQuotaViolation() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    
    // Using record patterns for test data definition
    List<BlobStoreTestData> testData = Arrays.asList(
        new BlobStoreTestData("One", true, true, blobStoreQuotaResult),
        new BlobStoreTestData("Three", true, true, blobStoreQuotaResult));
    
    // Create mocks using the record pattern data
    List<BlobStore> members = testData.stream()
        .map(data -> mockMember(data.name(), data.quotaResult()))
        .toList();
    
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.skipOnSoftQuotaViolation = true;
    assertNull(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap()));
  }

  @Test
  public void itWillSkipMembersWithQuotaViolation() {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    
    // Using record patterns for test data definition
    List<BlobStoreTestData> testData = Arrays.asList(
        new BlobStoreTestData("One", true, true, blobStoreQuotaResult),
        new BlobStoreTestData("Two", true, true, null),
        new BlobStoreTestData("Three", true, true, blobStoreQuotaResult),
        new BlobStoreTestData("Four", true, true, null));
    
    // Create mocks using the record pattern data
    List<BlobStore> members = testData.stream()
        .map(data -> mockMember(data.name(), data.quotaResult()))
        .toList();
    
    when(blobStoreGroup.getMembers()).thenReturn(members);

    roundRobinFillPolicy.skipOnSoftQuotaViolation = true;
    assertThat(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName(), is("Two"));
    assertThat(roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap())
        .getBlobStoreConfiguration()
        .getName(), is("Four"));
  }
  
  /**
   * Test to verify virtual thread handling during fill policy selection.
   * This test demonstrates that the RoundRobinFillPolicy works correctly when accessed from virtual threads.
   */
  @Test
  public void virtualThreadHandlingDuringFillPolicySelection() throws Exception {
    BlobStoreGroup blobStoreGroup = mock(BlobStoreGroup.class);
    
    // Using record patterns for test data definition
    List<BlobStoreTestData> testData = Arrays.asList(
        new BlobStoreTestData("One", true, true, null),
        new BlobStoreTestData("Two", true, true, null));
    
    // Create mocks using the record pattern data
    List<BlobStore> members = testData.stream()
        .map(data -> mockMember(data.name(), data.quotaResult()))
        .toList();
    
    when(blobStoreGroup.getMembers()).thenReturn(members);

    // Use virtual threads to test concurrent access to the fill policy
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to select blob stores concurrently using virtual threads
      Future<String> future1 = executor.submit(() -> {
        roundRobinFillPolicy.sequence.set(0);
        BlobStore selected = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
        return selected.getBlobStoreConfiguration().getName();
      });
      
      Future<String> future2 = executor.submit(() -> {
        BlobStore selected = roundRobinFillPolicy.chooseBlobStore(blobStoreGroup, Collections.emptyMap());
        return selected.getBlobStoreConfiguration().getName();
      });
      
      // Verify results from virtual threads
      String result1 = future1.get();
      String result2 = future2.get();
      
      // Verify that we got different blob stores due to the round-robin selection
      assertThat(result1, is("One"));
      assertThat(result2, is("Two"));
    }
  }

  private BlobStore mockMemberWithAvailability(final String name, final boolean availability, final boolean writable) {
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.isStorageAvailable()).thenReturn(availability);
    when(blobStore.isWritable()).thenReturn(writable);
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