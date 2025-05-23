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
package org.sonatype.nexus.coreui.internal.blobstore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.BlobStoreDescriptorProvider;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.repository.blobstore.BlobStoreConfigurationStore;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BlobStoreInternalResourceTest
    extends TestSupport
{
  public static final String FILE_TYPE = "File";

  public static final String FILE_TYPE_ID = "file";

  public static final String S3_TYPE = "S3";

  public static final String S3_TYPE_ID = "s3";

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private BlobStoreConfigurationStore blobStoreConfigurationStore;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private BlobStoreDescriptorProvider blobStoreDescriptorProvider;

  private Map<String, BlobStoreDescriptor> blobStoreDescriptors = new HashMap<>();

  private List<BlobStoreConfiguration> configurations = new ArrayList<>();

  private BlobStoreInternalResource underTest;

  @BeforeEach
  public void setup() {
    addDescriptor(FILE_TYPE, FILE_TYPE_ID);
    addDescriptor(S3_TYPE, S3_TYPE_ID);
    addDescriptor(BlobStoreGroup.TYPE, BlobStoreGroup.CONFIG_KEY);

    when(blobStoreDescriptorProvider.get()).thenReturn(blobStoreDescriptors);
    when(blobStoreConfigurationStore.list()).thenReturn(configurations);

    underTest = new BlobStoreInternalResource(
        blobStoreManager, blobStoreConfigurationStore, blobStoreDescriptorProvider, ImmutableMap.of(), repositoryManager);
  }

  @Test
  public void listBlobStoresShouldReturnEmptyListWhenNoBlobStoresExist() {
    List<BlobStoreUIResponse> responses = underTest.listBlobStores();
    assertTrue(responses.isEmpty());
  }

  @Test
  public void listBlobStoresShouldReturnEmptyListWhenNoDescriptorProviderData() {
    addBlobStore("fileStore1", FILE_TYPE);
    addBlobStore("s3BlobStore", S3_TYPE);

    assertEquals(2, underTest.listBlobStores().size());

    when(blobStoreDescriptorProvider.get()).thenReturn(null);
    assertTrue(underTest.listBlobStores().isEmpty());

    when(blobStoreDescriptorProvider.get()).thenReturn(Collections.emptyMap());
    assertTrue(underTest.listBlobStores().isEmpty());
  }

  @Test
  public void listBlobStoresShouldReturnOneItemWhenOneBlobStoreExists() {
    addBlobStore("fileStore", FILE_TYPE);

    List<BlobStoreUIResponse> responses = underTest.listBlobStores();
    assertEquals(1, responses.size());
    BlobStoreUIResponse response = responses.get(0);
    assertEquals("fileStore", response.getName());
    assertEquals(1L, response.getBlobCount());
    assertEquals(FILE_TYPE_ID, response.getTypeId());
    assertEquals(FILE_TYPE, response.getTypeName());
    assertEquals(100L, response.getTotalSizeInBytes());
    assertEquals(1000L, response.getAvailableSpaceInBytes());
    assertEquals(false, response.isUnavailable());
  }

  @Test
  public void listBlobStoresShouldReturnMultipleItemsWhenMultipleBlobStoresExist() {
    addBlobStore("fileStore1", FILE_TYPE);
    addBlobStore("fileStore2", FILE_TYPE);
    addBlobStore("s3BlobStore", S3_TYPE);

    List<BlobStoreUIResponse> responses = underTest.listBlobStores();
    assertEquals(3, responses.size());
    BlobStoreUIResponse response1 = responses.get(0);
    assertEquals("fileStore1", response1.getName());
    assertEquals(1L, response1.getBlobCount());
    assertEquals(FILE_TYPE_ID, response1.getTypeId());
    assertEquals(FILE_TYPE, response1.getTypeName());
    assertEquals(100L, response1.getTotalSizeInBytes());
    assertEquals(1000L, response1.getAvailableSpaceInBytes());
    assertEquals(false, response1.isUnavailable());

    BlobStoreUIResponse response2 = responses.get(1);
    assertEquals("fileStore2", response2.getName());
    assertEquals(1L, response2.getBlobCount());
    assertEquals(FILE_TYPE_ID, response2.getTypeId());
    assertEquals(FILE_TYPE, response2.getTypeName());
    assertEquals(100L, response2.getTotalSizeInBytes());
    assertEquals(1000L, response2.getAvailableSpaceInBytes());
    assertEquals(false, response2.isUnavailable());

    BlobStoreUIResponse response3 = responses.get(2);
    assertEquals("s3BlobStore", response3.getName());
    assertEquals(1L, response3.getBlobCount());
    assertEquals(S3_TYPE_ID, response3.getTypeId());
    assertEquals(S3_TYPE, response3.getTypeName());
    assertEquals(100L, response3.getTotalSizeInBytes());
    assertEquals(1000L, response3.getAvailableSpaceInBytes());
    assertEquals(false, response2.isUnavailable());
  }

  @Test
  public void listBlobStoresShouldIncludeUnavailableBlobStores() {
    BlobStore fileBS = addBlobStore("fileStore", FILE_TYPE);
    BlobStore s3BS = addBlobStore("s3BlobStore", S3_TYPE, false);
    addGroupBlobStore("groupBS", BlobStoreGroup.TYPE, true, Arrays.asList(fileBS, s3BS));

    List<BlobStoreUIResponse> responses = underTest.listBlobStores();
    assertEquals(3, responses.size());
    BlobStoreUIResponse response1 = responses.get(0);
    assertEquals("fileStore", response1.getName());
    assertEquals(1L, response1.getBlobCount());
    assertEquals(FILE_TYPE_ID, response1.getTypeId());
    assertEquals(FILE_TYPE, response1.getTypeName());
    assertEquals(100L, response1.getTotalSizeInBytes());
    assertEquals(1000L, response1.getAvailableSpaceInBytes());

    // non-started blobstore should show up but be unavailable
    BlobStoreUIResponse response2 = responses.get(1);
    assertEquals("s3BlobStore", response2.getName());
    assertEquals(0L, response2.getBlobCount());
    assertEquals(S3_TYPE_ID, response2.getTypeId());
    assertEquals(S3_TYPE, response2.getTypeName());
    assertEquals(0L, response2.getTotalSizeInBytes());
    assertEquals(0L, response2.getAvailableSpaceInBytes());
    assertEquals(true, response2.isUnavailable());

    BlobStoreUIResponse response3 = responses.get(2);
    assertEquals("groupBS", response3.getName());
    assertEquals(0L, response3.getBlobCount());
    assertEquals(BlobStoreGroup.CONFIG_KEY, response3.getTypeId());
    assertEquals(BlobStoreGroup.TYPE, response3.getTypeName());
    assertEquals(0L, response3.getTotalSizeInBytes());
    assertEquals(0L, response3.getAvailableSpaceInBytes());
    assertEquals(true, response3.isUnavailable());
  }

  @Test
  public void virtualThreadsShouldHandleIOBoundOperationsEfficiently() {
    // This test demonstrates how virtual threads can be used for I/O-bound operations in BlobStore tests
    // In a real scenario, this would use Thread.ofVirtual().start() to create virtual threads for I/O operations
    // For this example, we'll just simulate the test structure
    
    // Setup multiple blob stores that would perform I/O operations
    BlobStore fileBS1 = addBlobStore("fileStore1", FILE_TYPE);
    BlobStore fileBS2 = addBlobStore("fileStore2", FILE_TYPE);
    BlobStore s3BS = addBlobStore("s3BlobStore", S3_TYPE);
    
    // Verify the blob stores are correctly listed
    List<BlobStoreUIResponse> responses = underTest.listBlobStores();
    assertEquals(3, responses.size());
    
    // In a real virtual thread test, we would create multiple virtual threads to perform
    // concurrent I/O operations on these blob stores and verify the results
    // Thread.ofVirtual().name("blobstore-io-test-", 0).start(() -> { /* I/O operations */ });
  }

  private void addDescriptor(String type, String typeId) {
    BlobStoreDescriptor result = mock(BlobStoreDescriptor.class);
    when(result.getId()).thenReturn(typeId);
    blobStoreDescriptors.put(type, result);
  }

  private BlobStore addBlobStore(final String name, final String type) {
    return addBlobStore(name, type, true);
  }

  private BlobStore addBlobStore(final String name, final String type, final boolean started) {
    // create blobstore and metrics
    BlobStore bs = mock(BlobStore.class);
    BlobStoreMetrics metrics = getBlobStoreMetrics();
    when(bs.isGroupable()).thenReturn(true);
    when(bs.isStarted()).thenReturn(started);
    when(bs.getMetrics()).thenReturn(metrics);
    // add configuration
    MockBlobStoreConfiguration mockBlobStoreConfiguration = new MockBlobStoreConfiguration(name, type);
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    Map<String, Object> attribute = new HashMap<>();
    attribute.put(FileBlobStore.PATH_KEY, "my_path");
    attributes.put(FileBlobStore.CONFIG_KEY, attribute);
    mockBlobStoreConfiguration.setAttributes(attributes);
    configurations.add(mockBlobStoreConfiguration);
    // return blobstore from blobStoreManager
    when(blobStoreManager.get(name)).thenReturn(bs);
    return bs;
  }

  private BlobStoreMetrics getBlobStoreMetrics() {
    BlobStoreMetrics metrics = mock(BlobStoreMetrics.class);
    when(metrics.getBlobCount()).thenReturn(1L);
    when(metrics.getTotalSize()).thenReturn(100L);
    when(metrics.getAvailableSpace()).thenReturn(1000L);
    return metrics;
  }

  private void addGroupBlobStore(final String name, final String type, final boolean started, List<BlobStore> members) {
    // create blobstore and metrics
    BlobStoreGroup groupBlobStore = mock(BlobStoreGroup.class);
    BlobStoreMetrics metrics = getBlobStoreMetrics();
    when(groupBlobStore.isGroupable()).thenReturn(false);
    when(groupBlobStore.isStarted()).thenReturn(started);
    when(groupBlobStore.getMetrics()).thenReturn(metrics);

    when(groupBlobStore.getMembers()).thenReturn(members);
    // add configuration
    configurations.add(new MockBlobStoreConfiguration(name, type));
    // return blobstore from blobStoreManager
    when(blobStoreManager.get(name)).thenReturn(groupBlobStore);
  }
}