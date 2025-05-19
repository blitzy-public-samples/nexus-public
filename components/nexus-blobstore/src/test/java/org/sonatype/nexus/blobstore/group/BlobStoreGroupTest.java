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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.cache.Cache;
import javax.cache.configuration.MutableConfiguration;
import javax.inject.Provider;

import com.google.common.hash.HashCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("java21")
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
  void getWithNoMembers() throws Exception {
    config.setAttributes(buildAttributes(emptyList(), "test"));
    blobStore.init(config);
    blobStore.doStart();

    Blob foundBlob = blobStore.get(new BlobId("doesntexist"));

    assertThat(foundBlob, nullValue());
  }

  @Test
  void getWithTwoMembers() throws Exception {
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
    assertThat(foundBlob, is(blobOne));

    foundBlob = blobStore.get(new BlobId("in_two"));
    assertThat(foundBlob, is(blobTwo));

    foundBlob = blobStore.get(new BlobId("doesntexist"));
    assertThat(foundBlob, nullValue());
  }

  @Test
  void twoParamGet() throws Exception {
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
  void twoParamGetIncludeDeleted() throws Exception {
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
    assertThat(foundBlob, is(expectedBlob));
  }

  @Test
  void createWithStreamDelegatesToMemberChosenByFillPolicy() throws Exception {
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
  void createWithPathDelegatesToMemberChosenByFillPolicy() throws Exception {
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
    when(blob.getId()).thenReturn(new BlobId(STR."created-\{System.currentTimeMillis()}"));

    blobStore.create(path, new HashMap<>(), size, hashCode);

    verify(testFillPolicy).chooseBlobStore(blobStore, new HashMap<>());
    verify(two).create(path, new HashMap<>(), size, hashCode);
    verify(one, never()).create(any(), any());
  }

  @Test
  void getBlobStreamIdWithTwoBlobstores() throws Exception {
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
    assertThat(result, is(Arrays.asList("a", "b", "c", "d", "e", "f")));
  }

  @Test
  void deleteWithTwoMembers() throws Exception {
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
  void deleteHardWithTwoMembers() throws Exception {
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
    assertThat(deleted, is(expectedDeleted));
  }

  private void assertBlobStoreDelete(String blobId, boolean expectedDeleted) {
    boolean deleted = blobStore.delete(new BlobId(blobId), "just because");
    assertThat(deleted, is(expectedDeleted));
  }

  @Test
  void fallBackOnDefaultFillPolicyIfNamedPolicyNotFound() {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "nonExistentPolicy"));

    blobStore.init(config);

    assertThat(blobStore.fillPolicy, is(writeToFirstMemberFillPolicy));
  }

  @Test
  void itWillSearchWritableBlobStoresFirst() throws Exception {
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
    assertThat(locatedMember.get(), is(one));
    verify(cache).put(blobId, "one");
  }

  @Test
  void itWillOnlyCacheBlobIdsOfWritableBlobStores() throws Exception {
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
    assertThat(locatedMember.get(), is(two));
    verify(cache, never()).put(any(), any());
  }
  
  @Test
  void locateWithPatternMatching() throws Exception {
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    
    // Setup different blob types for pattern matching
    BlobId textBlobId = new BlobId("text-blob");
    BlobId imageBlobId = new BlobId("image-blob");
    BlobId unknownBlobId = new BlobId("unknown-blob");
    
    // Mock behavior based on blob type
    when(one.exists(textBlobId)).thenReturn(true);
    when(one.exists(imageBlobId)).thenReturn(false);
    when(one.exists(unknownBlobId)).thenReturn(false);
    when(two.exists(textBlobId)).thenReturn(false);
    when(two.exists(imageBlobId)).thenReturn(true);
    when(two.exists(unknownBlobId)).thenReturn(false);
    
    // Test pattern matching with different blob types
    Optional<BlobStore> textBlobStore = blobStore.locate(textBlobId);
    Optional<BlobStore> imageBlobStore = blobStore.locate(imageBlobId);
    Optional<BlobStore> unknownBlobStore = blobStore.locate(unknownBlobId);
    
    // Verify results using pattern matching
    assertThat(switch(textBlobStore.orElse(null)) {
      case BlobStore store when store == one -> "one";
      case BlobStore store when store == two -> "two";
      default -> "not found";
    }, is("one"));
    
    assertThat(switch(imageBlobStore.orElse(null)) {
      case BlobStore store when store == one -> "one";
      case BlobStore store when store == two -> "two";
      default -> "not found";
    }, is("two"));
    
    assertThat(switch(unknownBlobStore.orElse(null)) {
      case BlobStore store when store == one -> "one";
      case BlobStore store when store == two -> "two";
      default -> "not found";
    }, is("not found"));
  }
  
  @Test
  void concurrentBlobRetrievalWithVirtualThreads() throws Exception {
    // Setup blob store group with two members
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    blobStore.doStart();
    when(blobStoreManager.get("one")).thenReturn(one);
    when(blobStoreManager.get("two")).thenReturn(two);
    
    // Create blobs in different stores
    int blobCount = 100;
    for (int i = 0; i < blobCount; i++) {
      BlobId blobId = new BlobId(STR."blob-\{i}");
      // Even numbered blobs in store one, odd in store two
      if (i % 2 == 0) {
        when(one.exists(blobId)).thenReturn(true);
        when(one.get(blobId)).thenReturn(blobOne);
      } else {
        when(two.exists(blobId)).thenReturn(true);
        when(two.get(blobId)).thenReturn(blobTwo);
      }
    }
    
    // Create virtual thread executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(
        ThreadFactory.ofVirtual().factory());
    
    // Synchronization aids
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(blobCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Submit tasks to retrieve blobs concurrently
    for (int i = 0; i < blobCount; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          BlobId blobId = new BlobId(STR."blob-\{index}");
          Blob blob = blobStore.get(blobId);
          if (blob != null) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          logger.error("Error retrieving blob", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    executor.shutdown();
    
    // Verify all blobs were retrieved successfully
    assertThat(successCount.get(), is(blobCount));
  }
  
  @Test
  void extractAttributesWithRecordPatterns() throws Exception {
    // Create a record to represent group attributes
    record GroupAttributes(List<String> members, String fillPolicy) {}
    
    // Setup test data
    config.setAttributes(buildAttributes(Arrays.asList("one", "two"), "test"));
    blobStore.init(config);
    
    // Extract attributes using record patterns
    Map<String, Object> groupMap = blobStore.getConfiguration().attributes().get("group");
    if (groupMap instanceof Map<String, Object> map) {
      // Use record pattern to extract and validate attributes
      GroupAttributes attrs = new GroupAttributes(
          (List<String>) map.get("members"),
          (String) map.get("fillPolicy")
      );
      
      // Verify extracted attributes using pattern matching
      if (attrs instanceof GroupAttributes(var members, var policy)) {
        assertThat(members.size(), is(2));
        assertThat(members.get(0), is("one"));
        assertThat(members.get(1), is("two"));
        assertThat(policy, is("test"));
      }
    }
  }
}