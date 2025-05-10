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
package org.sonatype.nexus.blobstore.group;

import com.google.common.hash.HashCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.common.Time;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.group.internal.WriteToFirstMemberFillPolicy;
import org.sonatype.nexus.cache.CacheHelper;

import javax.cache.Cache;
import javax.cache.configuration.MutableConfiguration;
import javax.inject.Provider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BlobStoreGroupTest
    extends TestSupport
{
  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private FillPolicy testFillPolicy;

  @Mock
  private Cache<BlobId, String> cache;

  @Mock
  private Provider<CacheHelper> cacheHelperProvider;

  @Mock
  private CacheHelper cacheHelper;

  @Mock
  private Blob blobOne;

  @Mock
  private Blob blobTwo;

  @Mock
  private BlobStore one;

  @Mock
  private BlobStore two;

  private BlobStoreGroup blobStore;

  private final BlobStoreConfiguration config = new MockBlobStoreConfiguration();

  private final WriteToFirstMemberFillPolicy writeToFirstMemberFillPolicy = new WriteToFirstMemberFillPolicy();

  @BeforeEach
  public void setUp() {
    when(cacheHelperProvider.get()).thenReturn(cacheHelper);
    when(cacheHelper.maybeCreateCache(anyString(), any(MutableConfiguration.class))).thenReturn(cache);
    when(one.getBlobStoreConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(two.getBlobStoreConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(one.getBlobStoreConfiguration().getName()).thenReturn("one");
    when(two.getBlobStoreConfiguration().getName()).thenReturn("two");

    Map<String, Provider<FillPolicy>> fillPolicyFactories = new HashMap<>();
    fillPolicyFactories.put("writeToFirst", () -> writeToFirstMemberFillPolicy);
    fillPolicyFactories.put("test", () -> testFillPolicy);

    blobStore =
        new BlobStoreGroup(blobStoreManager, fillPolicyFactories, cacheHelperProvider, new Time(2, TimeUnit.DAYS));
  }

  private Map<String, Map<String, Object>> buildAttributes(List<String> memberNames, String fillPolicyName) {
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> group = new HashMap<>();
    group.put("members", memberNames);
    group.put("fillPolicy", fillPolicyName);
    attributes.put("group", group);
    return attributes;
  }

  @Test
  public void getWithNoMembers() throws Exception {
    config.setAttributes(buildAttributes(emptyList(), "test"));
    blobStore.init(config);
    blobStore.doStart();

    Blob foundBlob = blobStore.get(new BlobId("doesntexist"));

    assertNull(foundBlob);
  }

  @Test
  public void getWithTwoMembers() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    when(one.exists(any())).thenAnswer(invocation -> invocation.getArgument(0).equals(new BlobId("in_one")));
    when(two.exists(any())).thenAnswer(invocation -> invocation.getArgument(0).equals(new BlobId("in_two")));
    when(one.get(new BlobId("in_one"))).thenReturn(blobOne);
    when(two.get(new BlobId("in_two"))).thenReturn(blobTwo);

    Blob foundBlob = blobStore.get(new BlobId("in_one"));
    assertEquals(blobOne, foundBlob);

    foundBlob = blobStore.get(new BlobId("in_two"));
    assertEquals(blobTwo, foundBlob);

    foundBlob = blobStore.get(new BlobId("doesntexist"));
    assertNull(foundBlob);
  }

  @Test
  public void twoParamGet() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    when(one.exists(any())).thenAnswer(invocation -> invocation.getArgument(0).equals(new BlobId("in_one")));
    when(two.exists(any())).thenAnswer(invocation -> invocation.getArgument(0).equals(new BlobId("in_two")));
    when(one.get(new BlobId("in_one"), false)).thenReturn(blobOne);
    when(two.get(new BlobId("in_two"), false)).thenReturn(blobTwo);
    when(two.get(new BlobId("in_one"), true)).thenReturn(blobOne);

    assertBlobStoreGet("doesntexists", false, null);
    assertBlobStoreGet("in_one", false, blobOne);
    assertBlobStoreGet("in_two", false, blobTwo);
  }

  @Test
  public void twoParamGetIncludeDeleted() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    when(one.exists(any())).thenAnswer(invocation -> invocation.getArgument(0).equals(new BlobId("in_one")));
    when(two.exists(any())).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.equals(new BlobId("in_two")) || id.equals(new BlobId("deleted_in_two"));
    });
    when(one.get(new BlobId("in_one"), false)).thenReturn(blobOne);
    when(two.get(new BlobId("in_two"), false)).thenReturn(blobTwo);
    when(one.get(new BlobId("in_one"), true)).thenReturn(blobOne);
    when(two.get(new BlobId("in_two"), true)).thenReturn(blobTwo);
    when(two.get(new BlobId("deleted_in_two"), true)).thenReturn(blobTwo);

    assertBlobStoreGet("doesntexists", false, null);
    assertBlobStoreGet("in_one", false, blobOne);
    assertBlobStoreGet("in_two", false, blobTwo);
    assertBlobStoreGet("deleted_in_two", false, null);
    assertBlobStoreGet("doesntexists", true, null);
    assertBlobStoreGet("in_one", true, blobOne);
    assertBlobStoreGet("in_two", true, blobTwo);
    assertBlobStoreGet("deleted_in_two", true, blobTwo);
  }

  private void assertBlobStoreGet(String blobId, boolean includeDeleted, Blob expectedBlob) {
    Blob foundBlob = blobStore.get(new BlobId(blobId), includeDeleted);
    assertEquals(expectedBlob, foundBlob);
  }

  @Test
  public void createWithStreamDelegatesToMemberChosenByFillPolicy() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    ByteArrayInputStream byteStream = new ByteArrayInputStream("".getBytes());
    Blob blob = mock(Blob.class);

    when(testFillPolicy.chooseBlobStore(blobStore, new HashMap<>())).thenReturn(two);
    when(two.create(byteStream, new HashMap<>(), null)).thenReturn(blob);
    when(blob.getId()).thenReturn(new BlobId("created"));

    blobStore.create(byteStream, new HashMap<>());

    verify(testFillPolicy).chooseBlobStore(blobStore, new HashMap<>());
    verify(two).create(byteStream, new HashMap<>(), null);
    verify(one, never()).create(any(), any(), any());
  }

  @Test
  public void createWithPathDelegatesToMemberChosenByFillPolicy() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    Path path = new File(".").toPath();
    long size = 0L;
    HashCode hashCode = HashCode.fromInt(0);
    Blob blob = mock(Blob.class);

    when(testFillPolicy.chooseBlobStore(blobStore, new HashMap<>())).thenReturn(two);
    when(two.create(path, new HashMap<>(), size, hashCode)).thenReturn(blob);
    when(blob.getId()).thenReturn(new BlobId("created"));

    blobStore.create(path, new HashMap<>(), size, hashCode);

    verify(testFillPolicy).chooseBlobStore(blobStore, new HashMap<>());
    verify(two).create(path, new HashMap<>(), size, hashCode);
    verify(one, never()).create(any(), any());
  }

  @Test
  public void getBlobStreamIdWithTwoBlobstores() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    when(one.getBlobIdStream()).thenReturn(Stream.of(new BlobId("a"), new BlobId("b"), new BlobId("c")));
    when(two.getBlobIdStream()).thenReturn(Stream.of(new BlobId("d"), new BlobId("e"), new BlobId("f")));

    blobStore.init(config);
    blobStore.doStart();

    Stream<BlobId> stream = blobStore.getBlobIdStream();

    List<String> result = stream.map(BlobId::toString).collect(Collectors.toList());
    assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f"), result);
  }

  @Test
  public void deleteWithTwoMembers() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    when(one.exists(any())).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.equals(new BlobId("in_one")) || id.equals(new BlobId("in_both"));
    });
    when(two.exists(any())).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.equals(new BlobId("in_two")) || id.equals(new BlobId("in_both"));
    });
    when(one.delete(eq(new BlobId("in_one")), anyString())).thenReturn(true);
    when(one.delete(eq(new BlobId("in_both")), anyString())).thenReturn(true);
    when(two.delete(eq(new BlobId("in_two")), anyString())).thenReturn(true);
    when(two.delete(eq(new BlobId("in_both")), anyString())).thenReturn(false);

    assertBlobStoreDelete("doesntexists", false);
    assertBlobStoreDelete("in_one", true);
    assertBlobStoreDelete("in_two", true);
    assertBlobStoreDelete("in_both", false);
  }

  @Test
  public void deleteHardWithTwoMembers() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    when(one.exists(any())).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.equals(new BlobId("in_one")) || id.equals(new BlobId("in_both"));
    });
    when(two.exists(any())).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.equals(new BlobId("in_two")) || id.equals(new BlobId("in_both"));
    });
    when(one.deleteHard(new BlobId("in_one"))).thenReturn(true);
    when(one.deleteHard(new BlobId("in_both"))).thenReturn(true);
    when(two.deleteHard(new BlobId("in_two"))).thenReturn(true);
    when(two.deleteHard(new BlobId("in_both"))).thenReturn(false);

    assertBlobStoreDeleteHard("doesntexists", false);
    assertBlobStoreDeleteHard("in_one", true);
    assertBlobStoreDeleteHard("in_two", true);
    assertBlobStoreDeleteHard("in_both", false);
  }

  private void assertBlobStoreDeleteHard(String blobId, boolean expectedDeleted) {
    boolean deleted = blobStore.deleteHard(new BlobId(blobId));
    assertEquals(expectedDeleted, deleted);
  }

  private void assertBlobStoreDelete(String blobId, boolean expectedDeleted) {
    boolean deleted = blobStore.delete(new BlobId(blobId), "just because");
    assertEquals(expectedDeleted, deleted);
  }

  @Test
  public void fallBackOnDefaultFillPolicyIfNamedPolicyNotFound() {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "nonExistentPolicy"));

    blobStore.init(config);

    assertEquals(writeToFirstMemberFillPolicy, blobStore.fillPolicy);
  }

  @Test
  public void itWillSearchWritableBlobStoresFirst() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("writableMember", "nonWritableMember"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("writableMember")).thenReturn(one);
    when(blobStoreManager.get("nonWritableMember")).thenReturn(two);
    when(one.isWritable()).thenReturn(true);
    when(two.isWritable()).thenReturn(false);

    BlobId blobId = new BlobId("BLOB_ID_VALUE");

    when(one.exists(blobId)).thenReturn(true);

    Optional<BlobStore> locatedMember = blobStore.locate(blobId);

    verify(one).exists(blobId);
    verify(two, never()).exists(blobId);
    assertEquals(one, locatedMember.get());
    verify(cache).put(blobId, "one");
  }

  @Test
  public void itWillOnlyCacheBlobIdsOfWritableBlobStores() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("writableMember", "nonWritableMember"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("writableMember")).thenReturn(one);
    when(blobStoreManager.get("nonWritableMember")).thenReturn(two);
    when(one.isWritable()).thenReturn(true);
    when(two.isWritable()).thenReturn(false);

    BlobId blobId = new BlobId("BLOB_ID_VALUE");

    when(one.exists(blobId)).thenReturn(false);
    when(two.exists(blobId)).thenReturn(true);

    Optional<BlobStore> locatedMember = blobStore.locate(blobId);

    verify(one).exists(blobId);
    verify(two).exists(blobId);
    assertEquals(two, locatedMember.get());
    verify(cache, never()).put(any(), any());
  }
  
  @Test
  public void concurrentBlobStoreGroupOperationsWithVirtualThreads() throws Exception {
    // Setup BlobStoreGroup with two members
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    
    // Configure mock behavior for exists and get operations
    when(one.exists(any())).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.toString().startsWith("one-");
    });
    when(two.exists(any())).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.toString().startsWith("two-");
    });
    
    // Setup blob retrieval behavior
    when(one.get(any(BlobId.class))).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.toString().startsWith("one-") ? blobOne : null;
    });
    when(two.get(any(BlobId.class))).thenAnswer(invocation -> {
      BlobId id = invocation.getArgument(0);
      return id.toString().startsWith("two-") ? blobTwo : null;
    });
    
    // Create virtual thread executor
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
      // Submit concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between accessing blobs from different stores
            String prefix = index % 2 == 0 ? "one-" : "two-";
            BlobId blobId = new BlobId(prefix + index);
            Blob blob = blobStore.get(blobId);
            
            // Verify correct blob is returned
            if (prefix.equals("one-")) {
              assertEquals(blobOne, blob);
            } else {
              assertEquals(blobTwo, blob);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent operations");
      
      // Verify the expected number of calls to each blob store
      verify(one, atLeast(taskCount / 2)).exists(any(BlobId.class));
      verify(two, atLeast(taskCount / 2)).exists(any(BlobId.class));
    }
  }
}