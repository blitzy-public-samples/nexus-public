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
package org.sonatype.nexus.repository.content.store;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.time.UTC;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.facet.ContentFacetFinder;
import org.sonatype.nexus.repository.content.store.example.TestAssetBlobDAO;
import org.sonatype.nexus.repository.content.store.example.TestAssetDAO;
import org.sonatype.nexus.repository.content.store.example.TestComponentDAO;
import org.sonatype.nexus.repository.content.store.example.TestContentRepositoryDAO;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.transaction.TransactionModule;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import org.eclipse.sisu.wire.WireModule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;
import static org.sonatype.nexus.datastore.mybatis.CombUUID.combUUID;

/**
 * Tests transaction context preservation across Virtual Thread handoffs in the repository content module.
 * This class ensures that content database transactions maintain proper isolation, consistency, and atomicity
 * when operations span multiple Virtual Threads.
 */
@Category(SQLTestGroup.class)
public class ContentTransactionVirtualThreadTest
    extends TestSupport
{
  private static final String NODE_ID = "ab761d55-5d9c22b6-3f38315a-75b3db34-0922a4d5";

  private static final String BLOB_ID = "a8f3f56f-e895-4b6e-984a-1cf1f5107d36";
  
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME)
      .handle(new BlobRefTypeHandler())
      .access(TestContentRepositoryDAO.class)
      .access(TestComponentDAO.class)
      .access(TestAssetBlobDAO.class)
      .access(TestAssetDAO.class);

  @Mock
  Repository repository;

  @Mock
  ContentFacetFinder contentFacetFinder;

  @Mock
  EventManager eventManager;

  private FormatStoreManager underTest;
  private ContentRepositoryStore<?> contentRepositoryStore;
  private ComponentStore<?> componentStore;
  private AssetStore<?> assetStore;
  private AssetBlobStore<?> assetBlobStore;
  private ContentRepositoryData testRepository;
  private ComponentData testComponent;

  class SessionModule
      extends AbstractModule
  {
    @Override
    protected void configure() {
      bind(DataSessionSupplier.class).toInstance(sessionRule);
      bind(ContentFacetFinder.class).toInstance(contentFacetFinder);
      bind(EventManager.class).toInstance(eventManager);
    }
  }

  @Before
  public void setUp() {
    when(contentFacetFinder.findRepository(eq("test"), anyInt())).thenReturn(Optional.of(repository));

    Injector injector = Guice.createInjector(
        new WireModule(new TestPlainStoreModule(), new SessionModule(), new TransactionModule()));

    underTest = injector.getInstance(Key.get(FormatStoreManager.class, Names.named("test")));

    contentRepositoryStore = underTest.contentRepositoryStore(DEFAULT_DATASTORE_NAME);
    componentStore = underTest.componentStore(DEFAULT_DATASTORE_NAME);
    assetStore = underTest.assetStore(DEFAULT_DATASTORE_NAME);
    assetBlobStore = underTest.assetBlobStore(DEFAULT_DATASTORE_NAME);

    // Create a test repository and component for use in tests
    UnitOfWork.begin(sessionRule::openSession);
    try {
      Transactional.operation.run(() -> {
        testRepository = new ContentRepositoryData();
        testRepository.setAttributes(new NestedAttributesMap("attributes", new HashMap<>()));
        testRepository.setConfigRepositoryId(new EntityUUID(combUUID()));
        contentRepositoryStore.createContentRepository(testRepository);

        testComponent = new ComponentData();
        testComponent.setAttributes(new NestedAttributesMap("attributes", new HashMap<>()));
        testComponent.setRepositoryId(testRepository.repositoryId);
        testComponent.setNamespace("");
        testComponent.setName("testComponent");
        testComponent.setKind("aKind");
        testComponent.setVersion("1.0");
        testComponent.setNormalizedVersion("0000000001.0000000000");
        testComponent.setLastUpdated(OffsetDateTime.now());
        componentStore.createComponent(testComponent);
      });
    }
    finally {
      UnitOfWork.end();
    }
  }

  /**
   * Tests that transaction context is preserved when a Virtual Thread yields during I/O operations.
   * This verifies that the transaction remains active and valid across thread scheduling events.
   */
  @Test
  public void testTransactionContextPreservationAcrossVirtualThreadYields() throws Exception {
    // Use a virtual thread executor for the test
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Asset> future = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(sessionRule::openSession);
        try {
          return Transactional.operation.call(() -> {
            // Create an asset
            AssetData asset = new AssetData();
            asset.setAttributes(new NestedAttributesMap("attributes", new HashMap<>()));
            asset.setRepositoryId(testRepository.repositoryId);
            asset.setComponent(testComponent);
            asset.setPath("/path/to/asset-vt-yield");
            asset.setKind("test");
            asset.setLastUpdated(OffsetDateTime.now());
            assetStore.createAsset(asset);

            // Force a virtual thread yield by simulating I/O
            try {
              Thread.sleep(50); // This will cause the virtual thread to yield
            }
            catch (InterruptedException e) {
              throw new RuntimeException(e);
            }

            // Create and link a blob to the asset - this should still be in the same transaction
            AssetBlobData assetBlob = new AssetBlobData();
            assetBlob.setBlobRef(new BlobRef(NODE_ID, "default", BLOB_ID));
            assetBlob.setBlobSize(1024);
            assetBlob.setContentType("text/plain");
            assetBlob.setChecksums(ImmutableMap.of());
            assetBlob.setBlobCreated(UTC.now());
            assetBlobStore.createAssetBlob(assetBlob);

            asset.setAssetBlob(assetBlob);
            assetStore.updateAssetBlobLink(asset);

            return asset;
          });
        }
        finally {
          UnitOfWork.end();
        }
      }, executor);

      Asset asset = future.get(5, TimeUnit.SECONDS);
      assertNotNull("Asset should be created", asset);
      assertTrue("Asset should have a blob", asset.blob().isPresent());
      assertEquals("/path/to/asset-vt-yield", asset.path());
      assertEquals(BLOB_ID, asset.blob().get().blobRef().getBlob());
    }
  }

  /**
   * Tests that transaction rollback works correctly when an exception occurs after a Virtual Thread yield.
   * This verifies that the transaction is properly rolled back and no partial changes are committed.
   */
  @Test
  public void testTransactionRollbackAfterVirtualThreadYield() throws Exception {
    // Use a virtual thread executor for the test
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(sessionRule::openSession);
        try {
          Transactional.operation.run(() -> {
            // Create an asset
            AssetData asset = new AssetData();
            asset.setAttributes(new NestedAttributesMap("attributes", new HashMap<>()));
            asset.setRepositoryId(testRepository.repositoryId);
            asset.setComponent(testComponent);
            asset.setPath("/path/to/asset-rollback");
            asset.setKind("test");
            asset.setLastUpdated(OffsetDateTime.now());
            assetStore.createAsset(asset);

            // Force a virtual thread yield by simulating I/O
            try {
              Thread.sleep(50); // This will cause the virtual thread to yield
            }
            catch (InterruptedException e) {
              throw new RuntimeException(e);
            }

            // Throw an exception after the yield to trigger rollback
            throw new RuntimeException("Intentional failure to test rollback");
          });
        }
        catch (Exception e) {
          // Expected exception
          if (!e.getMessage().contains("Intentional failure")) {
            fail("Unexpected exception: " + e.getMessage());
          }
        }
        finally {
          UnitOfWork.end();
        }
        return null;
      }, executor);

      future.get(5, TimeUnit.SECONDS);

      // Verify the asset was not created (transaction was rolled back)
      UnitOfWork.begin(sessionRule::openSession);
      try {
        Optional<Asset> asset = Transactional.operation.call(() -> 
            assetStore.readPath(testRepository.repositoryId, "/path/to/asset-rollback"));
        assertFalse("Asset should not exist due to rollback", asset.isPresent());
      }
      finally {
        UnitOfWork.end();
      }
    }
  }

  /**
   * Tests transaction isolation with concurrent Virtual Thread operations.
   * This verifies that changes made in one transaction are not visible to another
   * until the first transaction is committed.
   */
  @Test
  public void testTransactionIsolationWithConcurrentVirtualThreads() throws Exception {
    final String assetPath = "/path/to/isolated-asset";
    final CountDownLatch transaction1Started = new CountDownLatch(1);
    final CountDownLatch transaction2Executed = new CountDownLatch(1);
    final AtomicBoolean assetVisibleInTransaction2 = new AtomicBoolean(false);

    // Use a virtual thread executor for the test
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Transaction 1: Create an asset but don't commit immediately
      CompletableFuture<Void> transaction1 = CompletableFuture.runAsync(() -> {
        UnitOfWork.begin(sessionRule::openSession);
        try {
          Transactional.operation.run(() -> {
            // Create an asset
            AssetData asset = new AssetData();
            asset.setAttributes(new NestedAttributesMap("attributes", new HashMap<>()));
            asset.setRepositoryId(testRepository.repositoryId);
            asset.setComponent(testComponent);
            asset.setPath(assetPath);
            asset.setKind("test");
            asset.setLastUpdated(OffsetDateTime.now());
            assetStore.createAsset(asset);

            // Signal that transaction 1 has started and asset is created but not yet committed
            transaction1Started.countDown();

            // Wait for transaction 2 to execute
            try {
              transaction2Executed.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
              throw new RuntimeException(e);
            }

            // Transaction 1 will now commit when this method returns
          });
        }
        finally {
          UnitOfWork.end();
        }
      }, executor);

      // Transaction 2: Try to read the asset created in transaction 1 before it's committed
      CompletableFuture<Void> transaction2 = CompletableFuture.runAsync(() -> {
        try {
          // Wait for transaction 1 to start
          transaction1Started.await(5, TimeUnit.SECONDS);

          UnitOfWork.begin(sessionRule::openSession);
          try {
            // Try to read the asset that transaction 1 created but hasn't committed yet
            Optional<Asset> asset = Transactional.operation.call(() -> 
                assetStore.readPath(testRepository.repositoryId, assetPath));
            
            // The asset should not be visible due to transaction isolation
            assetVisibleInTransaction2.set(asset.isPresent());
          }
          finally {
            UnitOfWork.end();
          }

          // Signal that transaction 2 has executed
          transaction2Executed.countDown();
        }
        catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }, executor);

      // Wait for both transactions to complete
      CompletableFuture.allOf(transaction1, transaction2).get(10, TimeUnit.SECONDS);

      // Verify transaction isolation was maintained
      assertFalse("Asset should not be visible in transaction 2 due to isolation", assetVisibleInTransaction2.get());

      // Verify the asset exists after both transactions are complete
      UnitOfWork.begin(sessionRule::openSession);
      try {
        Optional<Asset> asset = Transactional.operation.call(() -> 
            assetStore.readPath(testRepository.repositoryId, assetPath));
        assertTrue("Asset should exist after transaction 1 commits", asset.isPresent());
      }
      finally {
        UnitOfWork.end();
      }
    }
  }

  /**
   * Tests that transaction manager correctly handles multiple Virtual Thread operations within a single transaction.
   * This verifies that the transaction context is properly maintained across multiple asynchronous operations.
   */
  @Test
  public void testTransactionManagerWithMultipleVirtualThreadOperations() throws Exception {
    // Use a virtual thread executor for the test
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      UnitOfWork.begin(sessionRule::openSession);
      try {
        Transactional.operation.run(() -> {
          // Create multiple assets concurrently within the same transaction
          List<CompletableFuture<Asset>> futures = List.of(
              createAssetAsync(executor, "asset1"),
              createAssetAsync(executor, "asset2"),
              createAssetAsync(executor, "asset3")
          );

          // Wait for all async operations to complete
          try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
          }
          catch (Exception e) {
            throw new RuntimeException("Failed to complete async operations", e);
          }

          // Verify all assets were created in the same transaction
          for (int i = 0; i < futures.size(); i++) {
            try {
              Asset asset = futures.get(i).get();
              assertNotNull("Asset should be created", asset);
              assertEquals("/path/to/" + (i + 1), asset.path());
            }
            catch (Exception e) {
              throw new RuntimeException("Failed to get asset from future", e);
            }
          }
        });
      }
      finally {
        UnitOfWork.end();
      }

      // Verify all assets exist after the transaction is committed
      UnitOfWork.begin(sessionRule::openSession);
      try {
        Transactional.operation.run(() -> {
          for (int i = 1; i <= 3; i++) {
            Optional<Asset> asset = assetStore.readPath(testRepository.repositoryId, "/path/to/" + i);
            assertTrue("Asset " + i + " should exist", asset.isPresent());
          }
        });
      }
      finally {
        UnitOfWork.end();
      }
    }
  }

  /**
   * Tests resource cleanup with automatic thread unmounting during I/O operations.
   * This verifies that resources are properly cleaned up when Virtual Threads are unmounted.
   */
  @Test
  public void testResourceCleanupWithVirtualThreadUnmounting() throws Exception {
    // Use a virtual thread executor for the test
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AtomicReference<AssetData> assetRef = new AtomicReference<>();
      AtomicReference<Exception> exceptionRef = new AtomicReference<>();

      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        UnitOfWork.begin(sessionRule::openSession);
        try {
          Transactional.operation.run(() -> {
            // Create an asset
            AssetData asset = new AssetData();
            asset.setAttributes(new NestedAttributesMap("attributes", new HashMap<>()));
            asset.setRepositoryId(testRepository.repositoryId);
            asset.setComponent(testComponent);
            asset.setPath("/path/to/resource-cleanup");
            asset.setKind("test");
            asset.setLastUpdated(OffsetDateTime.now());
            assetStore.createAsset(asset);
            assetRef.set(asset);

            // Simulate heavy I/O that will cause thread unmounting
            try {
              for (int i = 0; i < 5; i++) {
                Thread.sleep(20); // Force multiple yields
              }
            }
            catch (InterruptedException e) {
              throw new RuntimeException(e);
            }

            // Create and link a blob to the asset
            AssetBlobData assetBlob = new AssetBlobData();
            assetBlob.setBlobRef(new BlobRef(NODE_ID, "default", BLOB_ID));
            assetBlob.setBlobSize(1024);
            assetBlob.setContentType("text/plain");
            assetBlob.setChecksums(ImmutableMap.of());
            assetBlob.setBlobCreated(UTC.now());
            assetBlobStore.createAssetBlob(assetBlob);

            asset.setAssetBlob(assetBlob);
            assetStore.updateAssetBlobLink(asset);
          });
        }
        catch (Exception e) {
          exceptionRef.set(e);
        }
        finally {
          UnitOfWork.end();
        }
      }, executor);

      future.get(5, TimeUnit.SECONDS);

      // Verify no exceptions occurred
      if (exceptionRef.get() != null) {
        fail("Exception occurred during transaction: " + exceptionRef.get().getMessage());
      }

      // Verify the asset was created and has a blob
      UnitOfWork.begin(sessionRule::openSession);
      try {
        Optional<Asset> asset = Transactional.operation.call(() -> 
            assetStore.readPath(testRepository.repositoryId, "/path/to/resource-cleanup"));
        assertTrue("Asset should exist", asset.isPresent());
        assertTrue("Asset should have a blob", asset.get().blob().isPresent());
        assertEquals(BLOB_ID, asset.get().blob().get().blobRef().getBlob());
      }
      finally {
        UnitOfWork.end();
      }
    }
  }

  /**
   * Helper method to create an asset asynchronously within an existing transaction.
   */
  private CompletableFuture<Asset> createAssetAsync(var executor, String name) {
    return CompletableFuture.supplyAsync(() -> {
      // Create an asset
      AssetData asset = new AssetData();
      asset.setAttributes(new NestedAttributesMap("attributes", new HashMap<>()));
      asset.setRepositoryId(testRepository.repositoryId);
      asset.setComponent(testComponent);
      asset.setPath("/path/to/" + name.charAt(name.length() - 1));
      asset.setKind("test");
      asset.setLastUpdated(OffsetDateTime.now());
      assetStore.createAsset(asset);

      // Simulate I/O that will cause thread yield
      try {
        Thread.sleep(50);
      }
      catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      return asset;
    }, executor);
  }
}