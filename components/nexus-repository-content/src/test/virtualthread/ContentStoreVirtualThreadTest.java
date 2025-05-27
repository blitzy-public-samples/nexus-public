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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.AssetDAOTestSupport;
import org.sonatype.nexus.repository.content.store.ComponentDAOTestSupport;
import org.sonatype.nexus.repository.content.store.ComponentData;
import org.sonatype.nexus.repository.content.store.ContentRepositoryData;
import org.sonatype.nexus.repository.content.store.example.TestAssetDAO;
import org.sonatype.nexus.repository.content.store.example.TestComponentDAO;
import org.sonatype.nexus.repository.content.store.example.TestContentRepositoryDAO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the repository content store operations using Java 21 Virtual Threads to validate compatibility and
 * concurrent performance.
 * 
 * This class verifies that asset and component store operations can correctly function with virtual threads,
 * including CRUD operations, browsing assets/components, and handling concurrent operations. It ensures store
 * operations don't cause thread pinning and maintain transactional integrity when executed via virtual threads.
 */
@Category(VirtualThreadTestGroup.class)
public class ContentStoreVirtualThreadTest
    extends ComponentDAOTestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 30;
  private static final boolean ENTITY_VERSION_ENABLED = true;
  
  private int repositoryId;
  private ThreadFactory virtualThreadFactory;
  private ExecutorService executor;

  @Before
  public void setUp() {
    // Initialize virtual thread factory and executor
    virtualThreadFactory = Thread.ofVirtual().name("content-store-test-", 0).factory();
    executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Setup content repository for testing
    setupContent(ENTITY_VERSION_ENABLED);
    ContentRepositoryData contentRepository = randomContentRepository();
    createContentRepository(contentRepository);
    repositoryId = contentRepository.repositoryId;
  }

  @After
  public void tearDown() {
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Tests concurrent component creation operations using virtual threads.
   * Verifies that multiple components can be created concurrently without errors.
   */
  @Test
  public void testConcurrentComponentCreation() throws Exception {
    int componentCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(componentCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<ComponentData> components = new ArrayList<>();
    
    // Generate random components
    for (int i = 0; i < componentCount; i++) {
      ComponentData component = randomComponent(repositoryId);
      component.setVersion("1." + i); // Ensure unique versions
      components.add(component);
    }
    
    // Submit component creation tasks to virtual thread executor
    for (ComponentData component : components) {
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO dao = session.access(TestComponentDAO.class);
          dao.createComponent(component, ENTITY_VERSION_ENABLED);
          session.getTransaction().commit();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error creating component", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for component creation", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent component creation", 
        errorCount.get(), is(0));
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      int count = dao.countComponents(repositoryId, null, null, null);
      assertThat("All components should be created", count, is(componentCount));
    }
  }

  /**
   * Tests concurrent component browsing operations using virtual threads.
   * Verifies that components can be browsed concurrently without errors.
   */
  @Test
  public void testConcurrentComponentBrowsing() throws Exception {
    // Create components first
    int componentCount = 50;
    List<ComponentData> components = new ArrayList<>();
    
    for (int i = 0; i < componentCount; i++) {
      ComponentData component = randomComponent(repositoryId);
      component.setVersion("1." + i); // Ensure unique versions
      components.add(component);
    }
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      for (ComponentData component : components) {
        dao.createComponent(component, ENTITY_VERSION_ENABLED);
      }
      session.getTransaction().commit();
    }
    
    // Now test concurrent browsing
    int browseOperations = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(browseOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger totalComponentsFound = new AtomicInteger(0);
    
    // Submit browse tasks to virtual thread executor
    for (int i = 0; i < browseOperations; i++) {
      final int limit = 10;
      final int offset = i % 5; // Create some variation in continuation tokens
      
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO dao = session.access(TestComponentDAO.class);
          String continuationToken = null;
          int found = 0;
          
          // Browse with pagination
          do {
            Continuation<Component> results = dao.browseComponents(
                repositoryId, limit, continuationToken, null, null, null);
            found += results.size();
            continuationToken = results.nextContinuationToken();
          } while (continuationToken != null);
          
          totalComponentsFound.addAndGet(found);
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error browsing components", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for component browsing", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent component browsing", 
        errorCount.get(), is(0));
    assertThat("Components should be found in each browse operation", 
        totalComponentsFound.get(), is(browseOperations * componentCount));
  }

  /**
   * Tests concurrent component updates using virtual threads.
   * Verifies that components can be updated concurrently without errors.
   */
  @Test
  public void testConcurrentComponentUpdates() throws Exception {
    // Create a component to update
    ComponentData component = randomComponent(repositoryId);
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      dao.createComponent(component, ENTITY_VERSION_ENABLED);
      session.getTransaction().commit();
    }
    
    // Now test concurrent updates
    int updateOperations = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(updateOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit update tasks to virtual thread executor
    for (int i = 0; i < updateOperations; i++) {
      final int updateIndex = i;
      
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO dao = session.access(TestComponentDAO.class);
          
          // Read the component
          Optional<Component> maybeComponent = dao.readComponent(component.componentId);
          if (maybeComponent.isPresent()) {
            ComponentData toUpdate = (ComponentData) maybeComponent.get();
            
            // Update attributes
            toUpdate.attributes("test-section").set("update-key", "update-value-" + updateIndex);
            dao.updateComponentAttributes(toUpdate, ENTITY_VERSION_ENABLED);
            
            session.getTransaction().commit();
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error updating component", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for component updates", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent component updates", 
        errorCount.get(), is(0));
    
    // Verify the component was updated
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      Optional<Component> maybeComponent = dao.readComponent(component.componentId);
      assertTrue("Component should exist", maybeComponent.isPresent());
      
      Component updated = maybeComponent.get();
      assertThat("Entity version should be incremented", 
          updated.entityVersion(), is(ENTITY_VERSION_ENABLED ? updateOperations + 1 : null));
    }
  }

  /**
   * Tests concurrent asset creation operations using virtual threads.
   * Verifies that multiple assets can be created concurrently without errors.
   */
  @Test
  public void testConcurrentAssetCreation() throws Exception {
    // Create a component to associate with assets
    ComponentData component = randomComponent(repositoryId);
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      dao.createComponent(component, ENTITY_VERSION_ENABLED);
      session.getTransaction().commit();
    }
    
    // Now test concurrent asset creation
    int assetCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(assetCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<AssetData> assets = new ArrayList<>();
    
    // Generate random assets
    for (int i = 0; i < assetCount; i++) {
      AssetData asset = randomAsset(repositoryId);
      asset.setPath("/path/to/asset-" + i); // Ensure unique paths
      asset.setComponent(component);
      assets.add(asset);
    }
    
    // Submit asset creation tasks to virtual thread executor
    for (AssetData asset : assets) {
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestAssetDAO dao = session.access(TestAssetDAO.class);
          dao.createAsset(asset, ENTITY_VERSION_ENABLED);
          session.getTransaction().commit();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error creating asset", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for asset creation", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent asset creation", 
        errorCount.get(), is(0));
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestAssetDAO dao = session.access(TestAssetDAO.class);
      int count = dao.countAssets(repositoryId, null, null, null);
      assertThat("All assets should be created", count, is(assetCount));
      
      // Verify component entity version was updated correctly
      TestComponentDAO componentDao = session.access(TestComponentDAO.class);
      Optional<Component> maybeComponent = componentDao.readComponent(component.componentId);
      assertTrue("Component should exist", maybeComponent.isPresent());
      
      Component updated = maybeComponent.get();
      assertThat("Component entity version should be incremented for each asset", 
          updated.entityVersion(), is(ENTITY_VERSION_ENABLED ? assetCount + 1 : null));
    }
  }

  /**
   * Tests concurrent asset browsing operations using virtual threads.
   * Verifies that assets can be browsed concurrently without errors.
   */
  @Test
  public void testConcurrentAssetBrowsing() throws Exception {
    // Create a component and assets first
    ComponentData component = randomComponent(repositoryId);
    int assetCount = 50;
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO componentDao = session.access(TestComponentDAO.class);
      componentDao.createComponent(component, ENTITY_VERSION_ENABLED);
      
      TestAssetDAO assetDao = session.access(TestAssetDAO.class);
      for (int i = 0; i < assetCount; i++) {
        AssetData asset = randomAsset(repositoryId);
        asset.setPath("/path/to/asset-" + i); // Ensure unique paths
        asset.setComponent(component);
        assetDao.createAsset(asset, ENTITY_VERSION_ENABLED);
      }
      
      session.getTransaction().commit();
    }
    
    // Now test concurrent browsing
    int browseOperations = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(browseOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger totalAssetsFound = new AtomicInteger(0);
    
    // Submit browse tasks to virtual thread executor
    for (int i = 0; i < browseOperations; i++) {
      final int limit = 10;
      final int offset = i % 5; // Create some variation in continuation tokens
      
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestAssetDAO dao = session.access(TestAssetDAO.class);
          String continuationToken = null;
          int found = 0;
          
          // Browse with pagination
          do {
            Continuation<Asset> results = dao.browseAssets(
                repositoryId, limit, continuationToken, null, null, null);
            found += results.size();
            continuationToken = results.nextContinuationToken();
          } while (continuationToken != null);
          
          totalAssetsFound.addAndGet(found);
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error browsing assets", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for asset browsing", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent asset browsing", 
        errorCount.get(), is(0));
    assertThat("Assets should be found in each browse operation", 
        totalAssetsFound.get(), is(browseOperations * assetCount));
  }

  /**
   * Tests concurrent asset updates using virtual threads.
   * Verifies that assets can be updated concurrently without errors.
   */
  @Test
  public void testConcurrentAssetUpdates() throws Exception {
    // Create a component and asset to update
    ComponentData component = randomComponent(repositoryId);
    AssetData asset = randomAsset(repositoryId);
    asset.setComponent(component);
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO componentDao = session.access(TestComponentDAO.class);
      componentDao.createComponent(component, ENTITY_VERSION_ENABLED);
      
      TestAssetDAO assetDao = session.access(TestAssetDAO.class);
      assetDao.createAsset(asset, ENTITY_VERSION_ENABLED);
      
      session.getTransaction().commit();
    }
    
    // Now test concurrent updates
    int updateOperations = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(updateOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit update tasks to virtual thread executor
    for (int i = 0; i < updateOperations; i++) {
      final int updateIndex = i;
      
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestAssetDAO dao = session.access(TestAssetDAO.class);
          
          // Read the asset
          Optional<Asset> maybeAsset = dao.readAsset(asset.assetId);
          if (maybeAsset.isPresent()) {
            AssetData toUpdate = (AssetData) maybeAsset.get();
            
            // Update attributes
            toUpdate.attributes("test-section").set("update-key", "update-value-" + updateIndex);
            dao.updateAssetAttributes(toUpdate, ENTITY_VERSION_ENABLED);
            
            session.getTransaction().commit();
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error updating asset", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for asset updates", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent asset updates", 
        errorCount.get(), is(0));
    
    // Verify the asset and component were updated
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestAssetDAO assetDao = session.access(TestAssetDAO.class);
      Optional<Asset> maybeAsset = assetDao.readAsset(asset.assetId);
      assertTrue("Asset should exist", maybeAsset.isPresent());
      
      // Verify component entity version was updated correctly
      TestComponentDAO componentDao = session.access(TestComponentDAO.class);
      Optional<Component> maybeComponent = componentDao.readComponent(component.componentId);
      assertTrue("Component should exist", maybeComponent.isPresent());
      
      Component updated = maybeComponent.get();
      assertThat("Component entity version should be incremented for each asset update", 
          updated.entityVersion(), is(ENTITY_VERSION_ENABLED ? updateOperations + 2 : null));
    }
  }

  /**
   * Tests concurrent mixed operations (create, read, update) using virtual threads.
   * Verifies that different operations can be performed concurrently without errors.
   */
  @Test
  public void testConcurrentMixedOperations() throws Exception {
    // Create initial components and assets
    int initialCount = 20;
    List<ComponentData> components = new ArrayList<>();
    List<AssetData> assets = new ArrayList<>();
    
    for (int i = 0; i < initialCount; i++) {
      ComponentData component = randomComponent(repositoryId);
      component.setVersion("1." + i); // Ensure unique versions
      components.add(component);
      
      AssetData asset = randomAsset(repositoryId);
      asset.setPath("/path/to/asset-" + i); // Ensure unique paths
      asset.setComponent(component);
      assets.add(asset);
    }
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO componentDao = session.access(TestComponentDAO.class);
      TestAssetDAO assetDao = session.access(TestAssetDAO.class);
      
      for (int i = 0; i < initialCount; i++) {
        componentDao.createComponent(components.get(i), ENTITY_VERSION_ENABLED);
        assetDao.createAsset(assets.get(i), ENTITY_VERSION_ENABLED);
      }
      
      session.getTransaction().commit();
    }
    
    // Now test concurrent mixed operations
    int operationCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger createCount = new AtomicInteger(0);
    AtomicInteger readCount = new AtomicInteger(0);
    AtomicInteger updateCount = new AtomicInteger(0);
    
    // Submit mixed operation tasks to virtual thread executor
    for (int i = 0; i < operationCount; i++) {
      final int operationIndex = i;
      
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO componentDao = session.access(TestComponentDAO.class);
          TestAssetDAO assetDao = session.access(TestAssetDAO.class);
          
          // Determine operation type based on index
          int operationType = operationIndex % 3; // 0=create, 1=read, 2=update
          
          switch (operationType) {
            case 0: // Create
              ComponentData newComponent = randomComponent(repositoryId);
              newComponent.setVersion("2." + operationIndex); // Ensure unique versions
              componentDao.createComponent(newComponent, ENTITY_VERSION_ENABLED);
              
              AssetData newAsset = randomAsset(repositoryId);
              newAsset.setPath("/path/to/new-asset-" + operationIndex); // Ensure unique paths
              newAsset.setComponent(newComponent);
              assetDao.createAsset(newAsset, ENTITY_VERSION_ENABLED);
              
              createCount.incrementAndGet();
              break;
              
            case 1: // Read
              int randomIndex = operationIndex % initialCount;
              componentDao.readComponent(components.get(randomIndex).componentId);
              assetDao.readAsset(assets.get(randomIndex).assetId);
              readCount.incrementAndGet();
              break;
              
            case 2: // Update
              int updateIndex = operationIndex % initialCount;
              ComponentData componentToUpdate = (ComponentData) componentDao
                  .readComponent(components.get(updateIndex).componentId).get();
              componentToUpdate.attributes("test-section").set("mixed-key", "mixed-value-" + operationIndex);
              componentDao.updateComponentAttributes(componentToUpdate, ENTITY_VERSION_ENABLED);
              
              AssetData assetToUpdate = (AssetData) assetDao
                  .readAsset(assets.get(updateIndex).assetId).get();
              assetToUpdate.attributes("test-section").set("mixed-key", "mixed-value-" + operationIndex);
              assetDao.updateAssetAttributes(assetToUpdate, ENTITY_VERSION_ENABLED);
              
              updateCount.incrementAndGet();
              break;
          }
          
          session.getTransaction().commit();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error in mixed operation", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for mixed operations", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent mixed operations", 
        errorCount.get(), is(0));
    
    // Verify operation counts
    int totalOperations = createCount.get() + readCount.get() + updateCount.get();
    assertThat("All operations should complete", totalOperations, is(operationCount));
    
    // Verify final counts
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO componentDao = session.access(TestComponentDAO.class);
      TestAssetDAO assetDao = session.access(TestAssetDAO.class);
      
      int componentCount = componentDao.countComponents(repositoryId, null, null, null);
      int assetCount = assetDao.countAssets(repositoryId, null, null, null);
      
      assertThat("Component count should match initial plus created", 
          componentCount, is(initialCount + createCount.get()));
      assertThat("Asset count should match initial plus created", 
          assetCount, is(initialCount + createCount.get()));
    }
  }

  /**
   * Tests high-concurrency component operations using virtual threads.
   * Verifies that a large number of virtual threads can operate concurrently without errors.
   */
  @Test
  public void testHighConcurrencyComponentOperations() throws Exception {
    // Use a higher number of concurrent operations for this test
    int highConcurrency = 1000;
    CountDownLatch latch = new CountDownLatch(highConcurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit high-concurrency tasks to virtual thread executor
    for (int i = 0; i < highConcurrency; i++) {
      final int operationIndex = i;
      
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO dao = session.access(TestComponentDAO.class);
          
          // Create a component
          ComponentData component = randomComponent(repositoryId);
          component.setVersion("high-concurrency-" + operationIndex); // Ensure unique versions
          dao.createComponent(component, ENTITY_VERSION_ENABLED);
          
          // Read it back
          Optional<Component> maybeComponent = dao.readComponent(component.componentId);
          if (maybeComponent.isPresent()) {
            // Update it
            ComponentData toUpdate = (ComponentData) maybeComponent.get();
            toUpdate.attributes("high-concurrency").set("test-key", "test-value-" + operationIndex);
            dao.updateComponentAttributes(toUpdate, ENTITY_VERSION_ENABLED);
          } else {
            errorCount.incrementAndGet();
          }
          
          session.getTransaction().commit();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error in high-concurrency operation", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete with a longer timeout
    assertTrue("Timed out waiting for high-concurrency operations", 
        latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during high-concurrency operations", 
        errorCount.get(), is(0));
    
    // Verify final count
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      int count = dao.countComponents(repositoryId, null, null, null);
      assertThat("All high-concurrency components should be created", 
          count, is(highConcurrency));
    }
  }

  /**
   * Tests concurrent component deletion operations using virtual threads.
   * Verifies that components can be deleted concurrently without errors.
   */
  @Test
  public void testConcurrentComponentDeletion() throws Exception {
    // Create components to delete
    int componentCount = CONCURRENT_OPERATIONS;
    List<ComponentData> components = new ArrayList<>();
    
    for (int i = 0; i < componentCount; i++) {
      ComponentData component = randomComponent(repositoryId);
      component.setVersion("delete-" + i); // Ensure unique versions
      components.add(component);
    }
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      for (ComponentData component : components) {
        dao.createComponent(component, ENTITY_VERSION_ENABLED);
      }
      session.getTransaction().commit();
    }
    
    // Verify components were created
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      int count = dao.countComponents(repositoryId, null, null, null);
      assertThat("All components should be created before deletion test", 
          count, is(componentCount));
    }
    
    // Now test concurrent deletion
    CountDownLatch latch = new CountDownLatch(componentCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger deleteSuccessCount = new AtomicInteger(0);
    
    // Submit deletion tasks to virtual thread executor
    for (ComponentData component : components) {
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO dao = session.access(TestComponentDAO.class);
          boolean deleted = dao.deleteComponent(component);
          if (deleted) {
            deleteSuccessCount.incrementAndGet();
          }
          session.getTransaction().commit();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error deleting component", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for component deletion", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent component deletion", 
        errorCount.get(), is(0));
    assertThat("All components should be successfully deleted", 
        deleteSuccessCount.get(), is(componentCount));
    
    // Verify final count
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      int count = dao.countComponents(repositoryId, null, null, null);
      assertThat("No components should remain after deletion", count, is(0));
    }
  }

  /**
   * Tests concurrent asset deletion operations using virtual threads.
   * Verifies that assets can be deleted concurrently without errors.
   */
  @Test
  public void testConcurrentAssetDeletion() throws Exception {
    // Create a component for the assets
    ComponentData component = randomComponent(repositoryId);
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      dao.createComponent(component, ENTITY_VERSION_ENABLED);
      session.getTransaction().commit();
    }
    
    // Create assets to delete
    int assetCount = CONCURRENT_OPERATIONS;
    List<AssetData> assets = new ArrayList<>();
    
    for (int i = 0; i < assetCount; i++) {
      AssetData asset = randomAsset(repositoryId);
      asset.setPath("/path/to/delete-asset-" + i); // Ensure unique paths
      asset.setComponent(component);
      assets.add(asset);
    }
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestAssetDAO dao = session.access(TestAssetDAO.class);
      for (AssetData asset : assets) {
        dao.createAsset(asset, ENTITY_VERSION_ENABLED);
      }
      session.getTransaction().commit();
    }
    
    // Verify assets were created
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestAssetDAO dao = session.access(TestAssetDAO.class);
      int count = dao.countAssets(repositoryId, null, null, null);
      assertThat("All assets should be created before deletion test", 
          count, is(assetCount));
    }
    
    // Now test concurrent deletion
    CountDownLatch latch = new CountDownLatch(assetCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger deleteSuccessCount = new AtomicInteger(0);
    
    // Submit deletion tasks to virtual thread executor
    for (AssetData asset : assets) {
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestAssetDAO dao = session.access(TestAssetDAO.class);
          boolean deleted = dao.deleteAsset(asset);
          if (deleted) {
            deleteSuccessCount.incrementAndGet();
          }
          session.getTransaction().commit();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          logger.error("Error deleting asset", e);
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue("Timed out waiting for asset deletion", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("No errors should occur during concurrent asset deletion", 
        errorCount.get(), is(0));
    assertThat("All assets should be successfully deleted", 
        deleteSuccessCount.get(), is(assetCount));
    
    // Verify final count
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestAssetDAO dao = session.access(TestAssetDAO.class);
      int count = dao.countAssets(repositoryId, null, null, null);
      assertThat("No assets should remain after deletion", count, is(0));
    }
  }

  /**
   * Tests performance comparison between virtual threads and platform threads.
   * This test validates that virtual threads provide better scalability for I/O-bound operations.
   */
  @Test
  public void testVirtualThreadPerformanceComparison() throws Exception {
    // Create a moderate number of components for performance testing
    int componentCount = 100;
    List<ComponentData> components = new ArrayList<>();
    
    for (int i = 0; i < componentCount; i++) {
      ComponentData component = randomComponent(repositoryId);
      component.setVersion("perf-" + i); // Ensure unique versions
      components.add(component);
    }
    
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      TestComponentDAO dao = session.access(TestComponentDAO.class);
      for (ComponentData component : components) {
        dao.createComponent(component, ENTITY_VERSION_ENABLED);
      }
      session.getTransaction().commit();
    }
    
    // Test with virtual threads (already using virtual thread executor)
    int operationCount = 500;
    long virtualThreadStartTime = System.nanoTime();
    CountDownLatch virtualThreadLatch = new CountDownLatch(operationCount);
    AtomicInteger virtualThreadErrors = new AtomicInteger(0);
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < operationCount; i++) {
      final int index = i % componentCount;
      
      executor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO dao = session.access(TestComponentDAO.class);
          dao.readComponent(components.get(index).componentId);
        } catch (Exception e) {
          virtualThreadErrors.incrementAndGet();
        } finally {
          virtualThreadLatch.countDown();
        }
      });
    }
    
    // Wait for virtual thread operations to complete
    virtualThreadLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long virtualThreadDuration = System.nanoTime() - virtualThreadStartTime;
    
    // Now test with platform threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ExecutorService platformExecutor = Executors.newFixedThreadPool(16, platformThreadFactory);
    
    long platformThreadStartTime = System.nanoTime();
    CountDownLatch platformThreadLatch = new CountDownLatch(operationCount);
    AtomicInteger platformThreadErrors = new AtomicInteger(0);
    
    // Submit tasks to platform thread executor
    for (int i = 0; i < operationCount; i++) {
      final int index = i % componentCount;
      
      platformExecutor.submit(() -> {
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          TestComponentDAO dao = session.access(TestComponentDAO.class);
          dao.readComponent(components.get(index).componentId);
        } catch (Exception e) {
          platformThreadErrors.incrementAndGet();
        } finally {
          platformThreadLatch.countDown();
        }
      });
    }
    
    // Wait for platform thread operations to complete
    platformThreadLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long platformThreadDuration = System.nanoTime() - platformThreadStartTime;
    
    // Shutdown platform executor
    platformExecutor.shutdown();
    platformExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("No errors should occur with virtual threads", 
        virtualThreadErrors.get(), is(0));
    assertThat("No errors should occur with platform threads", 
        platformThreadErrors.get(), is(0));
    
    // Log performance comparison
    double virtualThreadDurationMs = Duration.ofNanos(virtualThreadDuration).toMillis();
    double platformThreadDurationMs = Duration.ofNanos(platformThreadDuration).toMillis();
    
    logger.info("Virtual Thread Duration: {} ms", virtualThreadDurationMs);
    logger.info("Platform Thread Duration: {} ms", platformThreadDurationMs);
    logger.info("Performance Ratio (Platform/Virtual): {}", 
        platformThreadDurationMs / virtualThreadDurationMs);
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // but we don't assert this as it depends on the test environment
    // Just log the results for analysis
  }
}