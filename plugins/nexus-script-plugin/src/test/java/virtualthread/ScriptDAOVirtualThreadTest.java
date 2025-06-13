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
package virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.experimental.categories.Category;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.plugin.internal.ScriptDAO;
import org.sonatype.nexus.script.plugin.internal.ScriptData;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.content.testsuite.groups.Java21TestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the ScriptDAO database operations using Java 21 Virtual Threads to verify that
 * CRUD operations work correctly in a virtual thread environment.
 * 
 * This test ensures that database access for script storage remains reliable when executed
 * with virtual threads, which is critical for maintaining performance during high-concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
@Category({SQLTestGroup.class, Java21TestGroup.class})
public class ScriptDAOVirtualThreadTest
    extends TestSupport
{
  @RegisterExtension
  public DataSessionRule sessionRule = new DataSessionRule().access(ScriptDAO.class);

  private DataSession<?> session;

  private ScriptDAO dao;

  @BeforeEach
  public void setUp() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(ScriptDAO.class);
  }

  @AfterEach
  public void tearDown() {
    session.close();
  }

  /**
   * Tests basic CRUD operations (create, read, update, delete) for a script entity
   * when executed within a virtual thread.
   */
  @Test
  public void testCrudOperationsInVirtualThread() throws Exception {
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create atomic references to capture results and exceptions from the virtual thread
    AtomicReference<Exception> threadException = new AtomicReference<>();
    AtomicReference<Script> readResult = new AtomicReference<>();
    AtomicReference<Script> updateResult = new AtomicReference<>();
    AtomicReference<Boolean> deleteResult = new AtomicReference<>();
    
    // Create and start a virtual thread to perform CRUD operations
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Create a new script
        ScriptData script = new ScriptData();
        script.setName("virtual-thread-test");
        script.setContent("log.info('Testing virtual threads')");
        
        dao.create(script);
        
        // Read the script
        Script read = dao.read(script.getName()).orElse(null);
        readResult.set(read);
        
        // Update the script
        script.setContent("log.info('Updated in virtual thread')");
        dao.update(script);
        
        // Read the updated script
        Script updated = dao.read(script.getName()).orElse(null);
        updateResult.set(updated);
        
        // Delete the script
        dao.delete(script.getName());
        
        // Verify deletion
        boolean deleted = !dao.read(script.getName()).isPresent();
        deleteResult.set(deleted);
      }
      catch (Exception e) {
        threadException.set(e);
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    assertThat("Virtual thread operation timed out", 
        latch.await(10, TimeUnit.SECONDS), is(true));
    
    // Check if any exception occurred in the virtual thread
    if (threadException.get() != null) {
      throw new AssertionError("Exception in virtual thread", threadException.get());
    }
    
    // Verify the results of the CRUD operations
    Script read = readResult.get();
    assertThat("Read operation in virtual thread failed", read, is(notNullValue()));
    assertThat(read.getName(), is("virtual-thread-test"));
    assertThat(read.getContent(), is("log.info('Testing virtual threads')"));
    
    Script updated = updateResult.get();
    assertThat("Update operation in virtual thread failed", updated, is(notNullValue()));
    assertThat(updated.getName(), is("virtual-thread-test"));
    assertThat(updated.getContent(), is("log.info('Updated in virtual thread')"));
    
    Boolean deleted = deleteResult.get();
    assertThat("Delete operation in virtual thread failed", deleted, is(true));
  }

  /**
   * Tests concurrent database operations using multiple virtual threads.
   * This test creates, reads, updates, and deletes multiple script entities concurrently
   * to verify that the database operations remain reliable under high concurrency.
   */
  @Test
  public void testConcurrentDatabaseOperationsWithVirtualThreads() throws Exception {
    int operationCount = 50;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> scriptNames = new ArrayList<>();
    List<Thread> virtualThreads = new ArrayList<>();
    
    // Create multiple scripts concurrently using virtual threads
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      String scriptName = "vt-script-" + index;
      scriptNames.add(scriptName);
      
      Thread virtualThread = Thread.startVirtualThread(() -> {
        try {
          // Create script
          ScriptData script = new ScriptData();
          script.setName(scriptName);
          script.setContent("log.info('Virtual Thread Script " + index + "')");
          
          dao.create(script);
          
          // Verify script was created
          Script read = dao.read(scriptName).orElse(null);
          if (read == null || !read.getName().equals(scriptName)) {
            logger.error("Failed to read script {} after creation", scriptName);
            errorCount.incrementAndGet();
          }
          
          // Update script
          script.setContent("logger.info('Updated Virtual Thread Script " + index + "')");
          dao.update(script);
          
          // Verify update
          Script updated = dao.read(scriptName).orElse(null);
          if (updated == null || !updated.getContent().contains("Updated")) {
            logger.error("Failed to update script {}", scriptName);
            errorCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error in virtual thread operation for script {}", scriptName, e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
      
      virtualThreads.add(virtualThread);
    }
    
    // Wait for all operations to complete
    assertThat("Virtual thread operations timed out", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify results
    assertThat("All operations should complete without errors", 
        errorCount.get(), is(0));
    
    // Verify all scripts exist and can be read
    for (String name : scriptNames) {
      Script script = dao.read(name).orElse(null);
      assertThat("Script " + name + " should exist", script, is(notNullValue()));
      assertThat(script.getContent(), containsString("Updated Virtual Thread Script"));
    }
    
    // Clean up - delete all scripts using virtual threads
    CountDownLatch deleteLatch = new CountDownLatch(scriptNames.size());
    AtomicInteger deleteErrorCount = new AtomicInteger(0);
    
    for (String name : scriptNames) {
      Thread.startVirtualThread(() -> {
        try {
          dao.delete(name);
          
          // Verify deletion
          if (dao.read(name).isPresent()) {
            logger.error("Failed to delete script {}", name);
            deleteErrorCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          logger.error("Error deleting script {}", name, e);
          deleteErrorCount.incrementAndGet();
        } 
        finally {
          deleteLatch.countDown();
        }
      });
    }
    
    // Wait for all deletions to complete
    assertThat("Virtual thread deletion operations timed out", 
        deleteLatch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify all deletions were successful
    assertThat("All deletion operations should complete without errors", 
        deleteErrorCount.get(), is(0));
  }
  
  /**
   * Tests transaction isolation in virtual threads by performing conflicting operations
   * on the same script entity from multiple virtual threads.
   */
  @Test
  public void testTransactionIsolationInVirtualThreads() throws Exception {
    // Create initial script
    ScriptData initialScript = new ScriptData();
    initialScript.setName("transaction-test");
    initialScript.setContent("logger.info('Initial content')");
    dao.create(initialScript);
    
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Thread> virtualThreads = new ArrayList<>();
    
    // Create multiple virtual threads that will try to update the same script
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread virtualThread = Thread.startVirtualThread(() -> {
        try {
          // Wait for the signal to start (ensures threads compete for the update)
          startLatch.await();
          
          // Read the script
          Script script = dao.read("transaction-test").orElse(null);
          if (script != null) {
            // Update with thread-specific content
            ScriptData updateData = new ScriptData();
            updateData.setName(script.getName());
            updateData.setContent("logger.info('Updated by thread " + index + "')");
            
            // Attempt to update
            dao.update(updateData);
            
            // If we got here without exception, count as success
            successCount.incrementAndGet();
          }
        } 
        catch (Exception e) {
          // Expected that some threads may fail due to concurrent modification
          logger.debug("Expected concurrent modification in thread {}", index, e);
        } 
        finally {
          completionLatch.countDown();
        }
      });
      
      virtualThreads.add(virtualThread);
    }
    
    // Signal all threads to start simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertThat("Virtual thread operations timed out", 
        completionLatch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify that at least one thread succeeded in updating the script
    assertThat("At least one thread should succeed in updating the script", 
        successCount.get(), is(greaterThan(0)));
    
    // Verify that the script exists and has been updated
    Script finalScript = dao.read("transaction-test").orElse(null);
    assertThat("Script should exist after concurrent updates", finalScript, is(notNullValue()));
    assertThat(finalScript.getContent(), containsString("Updated by thread"));
    
    // Clean up
    dao.delete("transaction-test");
  }
}