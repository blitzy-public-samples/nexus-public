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
package org.sonatype.nexus.blobstore.restore.datastore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.content.store.AssetBlobStore;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;

import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.DATA_STORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;

/**
 * Test for {@link AssetBlobRefFormatCheck} that verifies functionality when executed with Java 21's Virtual Threads.
 */
public class AssetBlobRefFormatCheckTest
    extends TestSupport
{
  public static final String MAVEN_2 = "maven2";

  public static final String DATASTORE_NAME = "nexus";

  @Mock
  private Format format;

  @Mock
  private Repository repository;

  @Mock
  private FormatStoreManager formatStoreManager;

  @Mock
  private AssetBlobStore<?> assetBlobStore;

  private AssetBlobRefFormatCheck underTest;

  @Before
  public void setup() {
    Map<String, FormatStoreManager> formatStoreManagers = new HashMap<>();
    formatStoreManagers.put(MAVEN_2, formatStoreManager);

    underTest = new AssetBlobRefFormatCheck(formatStoreManagers);
  }

  /**
   * Tests that {@link AssetBlobRefFormatCheck#isAssetBlobRefNotMigrated} returns true when the asset blob ref has a node ID,
   * when executed in a Virtual Thread context.
   */
  @Test
  public void shouldBeTrueWhenAssetBlobRefHasNodeId() throws ExecutionException, InterruptedException {
    mockRepository();
    mockAssetBlobStore(true);

    AtomicBoolean isVirtualThread = new AtomicBoolean(false);
    
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      // Verify we're running in a virtual thread
      isVirtualThread.set(Thread.currentThread().isVirtual());
      
      // Execute the actual test
      return underTest.isAssetBlobRefNotMigrated(repository);
    }, CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS, 
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()));

    // Get the result and verify it
    Boolean result = future.get();
    
    // Verify the test was executed in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the actual test result
    assertThat(result, is(true));
  }

  /**
   * Tests that {@link AssetBlobRefFormatCheck#isAssetBlobRefNotMigrated} returns false when the asset blob ref does not have a node ID,
   * when executed in a Virtual Thread context.
   */
  @Test
  public void shouldBeFalseWhenAssetBlobRefDoesNotHaveNodeId() throws ExecutionException, InterruptedException {
    mockRepository();
    mockAssetBlobStore(false);

    AtomicBoolean isVirtualThread = new AtomicBoolean(false);
    
    // Create and start a virtual thread to run the test
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      // Verify we're running in a virtual thread
      isVirtualThread.set(Thread.currentThread().isVirtual());
      
      // Execute the actual test
      return underTest.isAssetBlobRefNotMigrated(repository);
    }, CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS, 
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()));

    // Get the result and verify it
    Boolean result = future.get();
    
    // Verify the test was executed in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the actual test result
    assertThat(result, is(false));
  }

  /**
   * Tests that {@link AssetBlobRefFormatCheck#isAssetBlobRefNotMigrated} works correctly with an alternative
   * virtual thread creation method.
   */
  @Test
  public void shouldWorkWithAlternativeVirtualThreadCreation() throws ExecutionException, InterruptedException {
    mockRepository();
    mockAssetBlobStore(true);

    AtomicBoolean isVirtualThread = new AtomicBoolean(false);
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    
    // Create and start a virtual thread using Thread.ofVirtual().start()
    Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      try {
        // Verify we're running in a virtual thread
        isVirtualThread.set(Thread.currentThread().isVirtual());
        
        // Execute the actual test
        boolean result = underTest.isAssetBlobRefNotMigrated(repository);
        future.complete(result);
      } 
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });

    // Get the result and verify it
    Boolean result = future.get();
    
    // Verify the test was executed in a virtual thread
    assertThat("Test should run in a virtual thread", isVirtualThread.get(), is(true));
    
    // Verify the actual test result
    assertThat(result, is(true));
  }

  private void mockRepository() {
    when(repository.getFormat()).thenReturn(format);
    when(format.getValue()).thenReturn(MAVEN_2);


    Configuration configuration = new ConfigurationData();
    Map<String, Map<String, Object>> attributes = new HashMap<>();
    attributes.put(STORAGE, ImmutableMap.of(DATA_STORE_NAME, DATASTORE_NAME));
    configuration.setAttributes(attributes);
    when(repository.getConfiguration()).thenReturn(configuration);
  }

  private void mockAssetBlobStore(final boolean notMigrated) {
    when(formatStoreManager.assetBlobStore(DATASTORE_NAME)).thenReturn(assetBlobStore);
    when(assetBlobStore.notMigratedAssetBlobRefsExists()).thenReturn(notMigrated);
  }
}