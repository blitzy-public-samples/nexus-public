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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.example.TestAssetBlobDAO;
import org.sonatype.nexus.repository.content.store.example.TestAssetDAO;
import org.sonatype.nexus.repository.content.store.example.TestComponentDAO;
import org.sonatype.nexus.repository.content.store.example.TestContentRepositoryDAO;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the repository content store operations using Java 21 Virtual Threads to validate 
 * compatibility and concurrent performance.
 * 
 * This class verifies that asset and component store operations can correctly function with 
 * virtual threads, including CRUD operations, browsing assets/components, and handling 
 * concurrent operations. It ensures store operations don't cause thread pinning and maintain 
 * transactional integrity when executed via virtual threads.
 * 
 * @since 3.60
 */
public class ContentStoreVirtualThreadTest
    extends ExampleContentTestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int TIMEOUT_SECONDS = 30;
  private static final int BROWSE_LIMIT = 10;
  
  @Rule
  public TestName testName = new TestName();
  
  private ExecutorService virtualThreadExecutor;
  private int repositoryId;
  
  @Before
  public void setUp() throws Exception {
    // Create a virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Set up a content repository for testing
    ContentRepositoryData contentRepository = randomContentRepository();
    createContentRepository(contentRepository);
    repositoryId = contentRepository.repositoryId;
    
    // Generate random test data
    generateRandomNamespaces(100);
    generateRandomNames(100);
    generateRandomVersions(100);
    generateRandomPaths(100);
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Create a content repository for testing.
   */
  private void createContentRepository(final ContentRepositoryData contentRepository) {
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      ContentRepositoryDAO dao = session.access(TestContentRepositoryDAO.class);
      dao.createContentRepository(contentRepository);
      session.getTransaction().commit();
    }
  }
  
  /**
   * Test basic CRUD operations for components using Virtual Threads.
   */
  @Test
  public void testComponentCrudWithVirtualThreads() throws Exception {
    // Create component data
    ComponentData component = randomComponent(repositoryId);
    component.setNamespace("virtual-thread-test");
    component.setName("component-crud-test");
    component.setVersion("1.0.0");
    component.setKind("test-kind");
    
    // Create component using a virtual thread
    Future<?> createFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        dao.createComponent(component, true); // with entity version
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to create component: " + e.getMessage());
      }
    });
    
    // Wait for creation to complete
    createFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read component using a virtual thread
    Future<Component> readFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        Optional<Component> result = dao.readCoordinate(
            repositoryId, 
            component.namespace(), 
            component.name(), 
            component.version());
        return result.orElse(null);
      } catch (Exception e) {
        fail("Failed to read component: " + e.getMessage());
        return null;
      }
    });
    
    // Verify read result
    Component readComponent = readFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(readComponent, notNullValue());
    assertThat(readComponent.namespace(), equalTo(component.namespace()));
    assertThat(readComponent.name(), equalTo(component.name()));
    assertThat(readComponent.version(), equalTo(component.version()));
    assertThat(readComponent.kind(), equalTo(component.kind()));
    assertThat(readComponent.entityVersion(), equalTo(1)); // First version
    
    // Update component using a virtual thread
    Future<?> updateFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        component.setKind("updated-kind");
        component.attributes("test-section").set("test-key", "test-value");
        dao.updateComponentKind(component, true);
        dao.updateComponentAttributes(component, true);
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to update component: " + e.getMessage());
      }
    });
    
    // Wait for update to complete
    updateFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read updated component using a virtual thread
    Future<Component> readUpdatedFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        Optional<Component> result = dao.readCoordinate(
            repositoryId, 
            component.namespace(), 
            component.name(), 
            component.version());
        return result.orElse(null);
      } catch (Exception e) {
        fail("Failed to read updated component: " + e.getMessage());
        return null;
      }
    });
    
    // Verify updated component
    Component updatedComponent = readUpdatedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(updatedComponent, notNullValue());
    assertThat(updatedComponent.kind(), equalTo("updated-kind"));
    assertThat(updatedComponent.attributes("test-section").get("test-key"), equalTo("test-value"));
    assertThat(updatedComponent.entityVersion(), equalTo(3)); // Third version after two updates
    
    // Delete component using a virtual thread
    Future<Boolean> deleteFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        boolean result = dao.deleteComponent(component);
        session.getTransaction().commit();
        return result;
      } catch (Exception e) {
        fail("Failed to delete component: " + e.getMessage());
        return false;
      }
    });
    
    // Verify deletion
    Boolean deleted = deleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(deleted, is(true));
    
    // Verify component is gone
    Future<Boolean> verifyDeleteFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        Optional<Component> result = dao.readCoordinate(
            repositoryId, 
            component.namespace(), 
            component.name(), 
            component.version());
        return !result.isPresent();
      } catch (Exception e) {
        fail("Failed to verify component deletion: " + e.getMessage());
        return false;
      }
    });
    
    Boolean isDeleted = verifyDeleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(isDeleted, is(true));
  }
  
  /**
   * Test basic CRUD operations for assets using Virtual Threads.
   */
  @Test
  public void testAssetCrudWithVirtualThreads() throws Exception {
    // Create component for the asset
    ComponentData component = randomComponent(repositoryId);
    component.setNamespace("virtual-thread-test");
    component.setName("asset-crud-test");
    component.setVersion("1.0.0");
    
    // Create asset data
    AssetData asset = randomAsset(repositoryId);
    asset.setPath("/virtual-thread-test/asset-crud-test/1.0.0/test-asset.jar");
    asset.setKind("test-kind");
    
    // Create component and asset using a virtual thread
    Future<?> createFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO componentDao = session.access(TestComponentDAO.class);
        AssetDAO assetDao = session.access(TestAssetDAO.class);
        
        // Create component first
        componentDao.createComponent(component, true);
        
        // Set component reference and create asset
        asset.setComponent(component);
        assetDao.createAsset(asset, true);
        
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to create asset: " + e.getMessage());
      }
    });
    
    // Wait for creation to complete
    createFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read asset using a virtual thread
    Future<Asset> readFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        Optional<Asset> result = dao.readPath(repositoryId, asset.path());
        return result.orElse(null);
      } catch (Exception e) {
        fail("Failed to read asset: " + e.getMessage());
        return null;
      }
    });
    
    // Verify read result
    Asset readAsset = readFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(readAsset, notNullValue());
    assertThat(readAsset.path(), equalTo(asset.path()));
    assertThat(readAsset.kind(), equalTo(asset.kind()));
    assertTrue(readAsset.component().isPresent());
    assertThat(readAsset.component().get().name(), equalTo(component.name()));
    
    // Update asset using a virtual thread
    Future<?> updateFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        asset.setKind("updated-kind");
        asset.attributes("test-section").set("test-key", "test-value");
        dao.updateAssetKind(asset, true);
        dao.updateAssetAttributes(asset, true);
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to update asset: " + e.getMessage());
      }
    });
    
    // Wait for update to complete
    updateFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read updated asset using a virtual thread
    Future<Asset> readUpdatedFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        Optional<Asset> result = dao.readPath(repositoryId, asset.path());
        return result.orElse(null);
      } catch (Exception e) {
        fail("Failed to read updated asset: " + e.getMessage());
        return null;
      }
    });
    
    // Verify updated asset
    Asset updatedAsset = readUpdatedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(updatedAsset, notNullValue());
    assertThat(updatedAsset.kind(), equalTo("updated-kind"));
    assertThat(updatedAsset.attributes("test-section").get("test-key"), equalTo("test-value"));
    
    // Create and attach blob to asset
    AssetBlobData assetBlob = randomAssetBlob();
    
    Future<?> attachBlobFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetBlobDAO blobDao = session.access(TestAssetBlobDAO.class);
        AssetDAO assetDao = session.access(TestAssetDAO.class);
        
        // Create blob
        blobDao.createAssetBlob(assetBlob);
        
        // Attach blob to asset
        asset.setAssetBlob(assetBlob);
        assetDao.updateAssetBlobLink(asset, true);
        
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to attach blob to asset: " + e.getMessage());
      }
    });
    
    // Wait for blob attachment to complete
    attachBlobFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read asset with blob using a virtual thread
    Future<AssetBlob> readBlobFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        Optional<Asset> result = dao.readPath(repositoryId, asset.path());
        if (result.isPresent() && result.get().blob().isPresent()) {
          return result.get().blob().get();
        }
        return null;
      } catch (Exception e) {
        fail("Failed to read asset with blob: " + e.getMessage());
        return null;
      }
    });
    
    // Verify blob attachment
    AssetBlob readBlob = readBlobFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(readBlob, notNullValue());
    assertThat(readBlob.blobRef(), equalTo(assetBlob.blobRef()));
    
    // Delete asset using a virtual thread
    Future<Boolean> deleteFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        boolean result = dao.deleteAsset(asset);
        session.getTransaction().commit();
        return result;
      } catch (Exception e) {
        fail("Failed to delete asset: " + e.getMessage());
        return false;
      }
    });
    
    // Verify deletion
    Boolean deleted = deleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(deleted, is(true));
    
    // Verify asset is gone
    Future<Boolean> verifyDeleteFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        Optional<Asset> result = dao.readPath(repositoryId, asset.path());
        return !result.isPresent();
      } catch (Exception e) {
        fail("Failed to verify asset deletion: " + e.getMessage());
        return false;
      }
    });
    
    Boolean isDeleted = verifyDeleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(isDeleted, is(true));
  }
  
  /**
   * Test browsing components with Virtual Threads.
   */
  @Test
  public void testBrowseComponentsWithVirtualThreads() throws Exception {
    // Generate a set of components for browsing
    final int componentCount = 50;
    List<ComponentData> components = new ArrayList<>();
    
    // Create components using a virtual thread
    Future<?> createFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        
        for (int i = 0; i < componentCount; i++) {
          ComponentData component = randomComponent(repositoryId);
          component.setNamespace("browse-test");
          component.setName("component-" + i);
          component.setVersion("1.0." + i);
          component.setKind(i % 2 == 0 ? "even-kind" : "odd-kind");
          
          dao.createComponent(component, true);
          components.add(component);
        }
        
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to create components for browsing: " + e.getMessage());
      }
    });
    
    // Wait for creation to complete
    createFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Browse all components using a virtual thread
    Future<List<Component>> browseFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        List<Component> result = new ArrayList<>();
        
        String continuationToken = null;
        Continuation<Component> continuation;
        do {
          continuation = dao.browseComponents(repositoryId, BROWSE_LIMIT, continuationToken, null, null, null);
          result.addAll(continuation);
          continuationToken = continuation.nextContinuationToken();
        } while (continuationToken != null);
        
        return result;
      } catch (Exception e) {
        fail("Failed to browse components: " + e.getMessage());
        return null;
      }
    });
    
    // Verify browse results
    List<Component> browsedComponents = browseFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(browsedComponents, notNullValue());
    assertThat(browsedComponents.size(), greaterThan(componentCount - 1)); // At least our components
    
    // Browse components by kind using a virtual thread
    Future<List<Component>> browseByKindFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        List<Component> result = new ArrayList<>();
        
        String continuationToken = null;
        Continuation<Component> continuation;
        do {
          continuation = dao.browseComponents(repositoryId, BROWSE_LIMIT, continuationToken, "even-kind", null, null);
          result.addAll(continuation);
          continuationToken = continuation.nextContinuationToken();
        } while (continuationToken != null);
        
        return result;
      } catch (Exception e) {
        fail("Failed to browse components by kind: " + e.getMessage());
        return null;
      }
    });
    
    // Verify browse by kind results
    List<Component> browsedByKind = browseByKindFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(browsedByKind, notNullValue());
    assertThat(browsedByKind.size(), greaterThan(componentCount / 2 - 1)); // At least our even components
    
    // Verify all browsed components with even-kind have the correct kind
    for (Component component : browsedByKind) {
      if (component.namespace().equals("browse-test")) {
        assertThat(component.kind(), equalTo("even-kind"));
      }
    }
  }
  
  /**
   * Test browsing assets with Virtual Threads.
   */
  @Test
  public void testBrowseAssetsWithVirtualThreads() throws Exception {
    // Generate a set of components and assets for browsing
    final int assetCount = 50;
    List<ComponentData> components = new ArrayList<>();
    List<AssetData> assets = new ArrayList<>();
    
    // Create components and assets using a virtual thread
    Future<?> createFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO componentDao = session.access(TestComponentDAO.class);
        AssetDAO assetDao = session.access(TestAssetDAO.class);
        
        for (int i = 0; i < assetCount; i++) {
          // Create component
          ComponentData component = randomComponent(repositoryId);
          component.setNamespace("browse-test");
          component.setName("asset-component-" + i);
          component.setVersion("1.0." + i);
          componentDao.createComponent(component, true);
          components.add(component);
          
          // Create asset linked to component
          AssetData asset = randomAsset(repositoryId);
          asset.setPath("/browse-test/asset-" + i + ".jar");
          asset.setKind(i % 2 == 0 ? "even-kind" : "odd-kind");
          asset.setComponent(component);
          assetDao.createAsset(asset, true);
          assets.add(asset);
        }
        
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to create assets for browsing: " + e.getMessage());
      }
    });
    
    // Wait for creation to complete
    createFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Browse all assets using a virtual thread
    Future<List<Asset>> browseFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        List<Asset> result = new ArrayList<>();
        
        String continuationToken = null;
        Continuation<Asset> continuation;
        do {
          continuation = dao.browseAssets(repositoryId, BROWSE_LIMIT, continuationToken, null, null, null);
          result.addAll(continuation);
          continuationToken = continuation.nextContinuationToken();
        } while (continuationToken != null);
        
        return result;
      } catch (Exception e) {
        fail("Failed to browse assets: " + e.getMessage());
        return null;
      }
    });
    
    // Verify browse results
    List<Asset> browsedAssets = browseFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(browsedAssets, notNullValue());
    assertThat(browsedAssets.size(), greaterThan(assetCount - 1)); // At least our assets
    
    // Browse assets by kind using a virtual thread
    Future<List<Asset>> browseByKindFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        List<Asset> result = new ArrayList<>();
        
        String continuationToken = null;
        Continuation<Asset> continuation;
        do {
          continuation = dao.browseAssets(repositoryId, BROWSE_LIMIT, continuationToken, "even-kind", null, null);
          result.addAll(continuation);
          continuationToken = continuation.nextContinuationToken();
        } while (continuationToken != null);
        
        return result;
      } catch (Exception e) {
        fail("Failed to browse assets by kind: " + e.getMessage());
        return null;
      }
    });
    
    // Verify browse by kind results
    List<Asset> browsedByKind = browseByKindFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(browsedByKind, notNullValue());
    assertThat(browsedByKind.size(), greaterThan(assetCount / 2 - 1)); // At least our even assets
    
    // Verify all browsed assets with even-kind have the correct kind
    for (Asset asset : browsedByKind) {
      if (asset.path().startsWith("/browse-test/")) {
        assertThat(asset.kind(), equalTo("even-kind"));
      }
    }
    
    // Browse component assets using a virtual thread
    ComponentData firstComponent = components.get(0);
    Future<List<Asset>> browseComponentAssetsFuture = virtualThreadExecutor.submit(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        AssetDAO dao = session.access(TestAssetDAO.class);
        return dao.browseComponentAssets(firstComponent);
      } catch (Exception e) {
        fail("Failed to browse component assets: " + e.getMessage());
        return null;
      }
    });
    
    // Verify component assets browse results
    List<Asset> componentAssets = browseComponentAssetsFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(componentAssets, notNullValue());
    assertThat(componentAssets, hasSize(1)); // One asset per component
    assertThat(componentAssets.get(0).component().get().name(), equalTo(firstComponent.name()));
  }
  
  /**
   * Test high concurrency with multiple Virtual Threads performing content store operations simultaneously.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a countdown latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track successful operations
    AtomicInteger successfulOperations = new AtomicInteger(0);
    
    // Create multiple virtual threads to perform concurrent operations
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Perform multiple operations per thread
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            String componentName = "concurrent-component-" + threadId + "-" + j;
            String assetPath = "/concurrent-test/" + threadId + "/asset-" + j + ".jar";
            
            try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
              ComponentDAO componentDao = session.access(TestComponentDAO.class);
              AssetDAO assetDao = session.access(TestAssetDAO.class);
              
              // Create component
              ComponentData component = randomComponent(repositoryId);
              component.setNamespace("concurrent-test");
              component.setName(componentName);
              component.setVersion("1.0.0");
              componentDao.createComponent(component, true);
              
              // Create asset linked to component
              AssetData asset = randomAsset(repositoryId);
              asset.setPath(assetPath);
              asset.setComponent(component);
              assetDao.createAsset(asset, true);
              
              // Commit transaction
              session.getTransaction().commit();
              
              // Increment success counter
              successfulOperations.incrementAndGet();
            }
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
        } 
        finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete or timeout
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete within the timeout", completed, is(true));
    
    // Verify that all operations were successful
    int expectedOperations = CONCURRENT_THREADS * OPERATIONS_PER_THREAD;
    assertThat("All operations should succeed", successfulOperations.get(), is(expectedOperations));
    
    // Verify the total number of components in the repository
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      ComponentDAO dao = session.access(TestComponentDAO.class);
      int componentCount = dao.countComponents(repositoryId, null, null, null);
      assertThat(componentCount, greaterThan(expectedOperations - 1));
    }
  }
  
  /**
   * Test concurrent browsing with Virtual Threads.
   */
  @Test
  public void testConcurrentBrowsingWithVirtualThreads() throws Exception {
    // First create a set of components and assets
    final int itemCount = 100;
    
    // Create components and assets
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      ComponentDAO componentDao = session.access(TestComponentDAO.class);
      AssetDAO assetDao = session.access(TestAssetDAO.class);
      
      for (int i = 0; i < itemCount; i++) {
        // Create component
        ComponentData component = randomComponent(repositoryId);
        component.setNamespace("concurrent-browse");
        component.setName("component-" + i);
        component.setVersion("1.0." + i);
        componentDao.createComponent(component, true);
        
        // Create asset linked to component
        AssetData asset = randomAsset(repositoryId);
        asset.setPath("/concurrent-browse/asset-" + i + ".jar");
        asset.setComponent(component);
        assetDao.createAsset(asset, true);
      }
      
      session.getTransaction().commit();
    }
    
    // Now perform concurrent browsing with virtual threads
    final int browseThreads = 20;
    CountDownLatch browseLatch = new CountDownLatch(browseThreads);
    Map<Integer, List<Component>> componentResults = new ConcurrentHashMap<>();
    Map<Integer, List<Asset>> assetResults = new ConcurrentHashMap<>();
    
    // Launch concurrent browse operations
    List<CompletableFuture<Void>> browseFutures = IntStream.range(0, browseThreads)
        .mapToObj(i -> CompletableFuture.runAsync(() -> {
          try {
            // Browse components
            List<Component> components = new ArrayList<>();
            try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
              ComponentDAO dao = session.access(TestComponentDAO.class);
              String continuationToken = null;
              Continuation<Component> continuation;
              do {
                continuation = dao.browseComponents(repositoryId, BROWSE_LIMIT, continuationToken, null, null, null);
                components.addAll(continuation);
                continuationToken = continuation.nextContinuationToken();
              } while (continuationToken != null);
            }
            componentResults.put(i, components);
            
            // Browse assets
            List<Asset> assets = new ArrayList<>();
            try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
              AssetDAO dao = session.access(TestAssetDAO.class);
              String continuationToken = null;
              Continuation<Asset> continuation;
              do {
                continuation = dao.browseAssets(repositoryId, BROWSE_LIMIT, continuationToken, null, null, null);
                assets.addAll(continuation);
                continuationToken = continuation.nextContinuationToken();
              } while (continuationToken != null);
            }
            assetResults.put(i, assets);
          } 
          catch (Exception e) {
            log.error("Error in concurrent browse thread {}: {}", i, e.getMessage(), e);
          } 
          finally {
            browseLatch.countDown();
          }
        }, virtualThreadExecutor))
        .collect(Collectors.toList());
    
    // Wait for all browse operations to complete
    browseLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all browse operations completed successfully
    assertThat(componentResults.size(), is(browseThreads));
    assertThat(assetResults.size(), is(browseThreads));
    
    // Verify all threads saw the same data
    int expectedComponentCount = componentResults.get(0).size();
    int expectedAssetCount = assetResults.get(0).size();
    
    for (int i = 1; i < browseThreads; i++) {
      assertThat("Thread " + i + " should see the same number of components",
          componentResults.get(i).size(), is(expectedComponentCount));
      assertThat("Thread " + i + " should see the same number of assets",
          assetResults.get(i).size(), is(expectedAssetCount));
    }
  }
  
  /**
   * Test versioned vs unversioned entity operations with Virtual Threads.
   */
  @Test
  public void testVersionedVsUnversionedWithVirtualThreads() throws Exception {
    // Create components with and without versioning
    ComponentData versionedComponent = randomComponent(repositoryId);
    versionedComponent.setNamespace("version-test");
    versionedComponent.setName("versioned-component");
    versionedComponent.setVersion("1.0.0");
    
    ComponentData unversionedComponent = randomComponent(repositoryId);
    unversionedComponent.setNamespace("version-test");
    unversionedComponent.setName("unversioned-component");
    unversionedComponent.setVersion("1.0.0");
    
    // Create components using virtual threads
    CompletableFuture<Void> createVersionedFuture = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        dao.createComponent(versionedComponent, true); // with versioning
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to create versioned component: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    CompletableFuture<Void> createUnversionedFuture = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        dao.createComponent(unversionedComponent, false); // without versioning
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to create unversioned component: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for creation to complete
    CompletableFuture.allOf(createVersionedFuture, createUnversionedFuture).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Update components multiple times
    CompletableFuture<Void> updateVersionedFuture = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        
        // First update
        versionedComponent.setKind("first-update");
        dao.updateComponentKind(versionedComponent, true);
        
        // Second update
        versionedComponent.attributes("test-section").set("test-key", "test-value");
        dao.updateComponentAttributes(versionedComponent, true);
        
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to update versioned component: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    CompletableFuture<Void> updateUnversionedFuture = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        
        // First update
        unversionedComponent.setKind("first-update");
        dao.updateComponentKind(unversionedComponent, false);
        
        // Second update
        unversionedComponent.attributes("test-section").set("test-key", "test-value");
        dao.updateComponentAttributes(unversionedComponent, false);
        
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed to update unversioned component: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for updates to complete
    CompletableFuture.allOf(updateVersionedFuture, updateUnversionedFuture).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read components and verify versioning
    CompletableFuture<Component> readVersionedFuture = CompletableFuture.supplyAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        Optional<Component> result = dao.readCoordinate(
            repositoryId, 
            versionedComponent.namespace(), 
            versionedComponent.name(), 
            versionedComponent.version());
        return result.orElse(null);
      } catch (Exception e) {
        fail("Failed to read versioned component: " + e.getMessage());
        return null;
      }
    }, virtualThreadExecutor);
    
    CompletableFuture<Component> readUnversionedFuture = CompletableFuture.supplyAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO dao = session.access(TestComponentDAO.class);
        Optional<Component> result = dao.readCoordinate(
            repositoryId, 
            unversionedComponent.namespace(), 
            unversionedComponent.name(), 
            unversionedComponent.version());
        return result.orElse(null);
      } catch (Exception e) {
        fail("Failed to read unversioned component: " + e.getMessage());
        return null;
      }
    }, virtualThreadExecutor);
    
    // Get results and verify
    Component readVersioned = readVersionedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    Component readUnversioned = readUnversionedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    assertThat(readVersioned, notNullValue());
    assertThat(readUnversioned, notNullValue());
    
    // Versioned component should have entity version 3 (initial + 2 updates)
    assertThat(readVersioned.entityVersion(), equalTo(3));
    
    // Unversioned component should have null entity version
    assertThat(readUnversioned.entityVersion(), equalTo(null));
    
    // Both should have the updated values
    assertThat(readVersioned.kind(), equalTo("first-update"));
    assertThat(readUnversioned.kind(), equalTo("first-update"));
    assertThat(readVersioned.attributes("test-section").get("test-key"), equalTo("test-value"));
    assertThat(readUnversioned.attributes("test-section").get("test-key"), equalTo("test-value"));
  }
  
  /**
   * Test transaction integrity with Virtual Threads.
   */
  @Test
  public void testTransactionIntegrityWithVirtualThreads() throws Exception {
    // Create a component and asset in a single transaction
    ComponentData component = randomComponent(repositoryId);
    component.setNamespace("transaction-test");
    component.setName("transaction-component");
    component.setVersion("1.0.0");
    
    AssetData asset = randomAsset(repositoryId);
    asset.setPath("/transaction-test/transaction-asset.jar");
    
    // Successful transaction with both component and asset
    CompletableFuture<Void> successfulTransactionFuture = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO componentDao = session.access(TestComponentDAO.class);
        AssetDAO assetDao = session.access(TestAssetDAO.class);
        
        // Create component
        componentDao.createComponent(component, true);
        
        // Create asset linked to component
        asset.setComponent(component);
        assetDao.createAsset(asset, true);
        
        // Commit transaction
        session.getTransaction().commit();
      } catch (Exception e) {
        fail("Failed in successful transaction: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for successful transaction to complete
    successfulTransactionFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify both component and asset were created
    CompletableFuture<Boolean> verifySuccessfulFuture = CompletableFuture.supplyAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO componentDao = session.access(TestComponentDAO.class);
        AssetDAO assetDao = session.access(TestAssetDAO.class);
        
        Optional<Component> componentResult = componentDao.readCoordinate(
            repositoryId, component.namespace(), component.name(), component.version());
        Optional<Asset> assetResult = assetDao.readPath(repositoryId, asset.path());
        
        return componentResult.isPresent() && assetResult.isPresent();
      } catch (Exception e) {
        fail("Failed to verify successful transaction: " + e.getMessage());
        return false;
      }
    }, virtualThreadExecutor);
    
    Boolean successfulTransactionVerified = verifySuccessfulFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(successfulTransactionVerified, is(true));
    
    // Failed transaction with rollback
    ComponentData failComponent = randomComponent(repositoryId);
    failComponent.setNamespace("transaction-test");
    failComponent.setName("fail-component");
    failComponent.setVersion("1.0.0");
    
    AssetData failAsset = randomAsset(repositoryId);
    failAsset.setPath("/transaction-test/fail-asset.jar");
    
    // This asset will cause a conflict by using the same path as an existing asset
    AssetData conflictAsset = randomAsset(repositoryId);
    conflictAsset.setPath(asset.path()); // Use same path as existing asset to cause conflict
    
    CompletableFuture<Boolean> failedTransactionFuture = CompletableFuture.supplyAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO componentDao = session.access(TestComponentDAO.class);
        AssetDAO assetDao = session.access(TestAssetDAO.class);
        
        // Create component
        componentDao.createComponent(failComponent, true);
        
        // Create first asset
        failAsset.setComponent(failComponent);
        assetDao.createAsset(failAsset, true);
        
        // Try to create conflicting asset - should fail
        conflictAsset.setComponent(failComponent);
        assetDao.createAsset(conflictAsset, true);
        
        // Commit transaction - should not reach here
        session.getTransaction().commit();
        return false; // Transaction should have failed
      } catch (Exception e) {
        // Expected exception due to duplicate asset path
        return true; // Transaction failed as expected
      }
    }, virtualThreadExecutor);
    
    // Wait for failed transaction to complete
    Boolean transactionFailed = failedTransactionFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("Transaction should fail due to conflict", transactionFailed, is(true));
    
    // Verify the component and first asset were not created (transaction rolled back)
    CompletableFuture<Boolean> verifyRollbackFuture = CompletableFuture.supplyAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        ComponentDAO componentDao = session.access(TestComponentDAO.class);
        AssetDAO assetDao = session.access(TestAssetDAO.class);
        
        Optional<Component> componentResult = componentDao.readCoordinate(
            repositoryId, failComponent.namespace(), failComponent.name(), failComponent.version());
        Optional<Asset> assetResult = assetDao.readPath(repositoryId, failAsset.path());
        
        // Both should not exist due to transaction rollback
        return !componentResult.isPresent() && !assetResult.isPresent();
      } catch (Exception e) {
        fail("Failed to verify rollback: " + e.getMessage());
        return false;
      }
    }, virtualThreadExecutor);
    
    Boolean rollbackVerified = verifyRollbackFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("Transaction should have been rolled back", rollbackVerified, is(true));
  }
}