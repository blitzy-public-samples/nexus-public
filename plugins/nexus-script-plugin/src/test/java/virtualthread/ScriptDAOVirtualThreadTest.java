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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.experimental.categories.Category;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.plugin.internal.ScriptDAO;
import org.sonatype.nexus.script.plugin.internal.ScriptData;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.testdb.DataSessionExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the {@link ScriptDAO} database operations using Java 21 Virtual Threads.
 * This test ensures that database access for script storage remains reliable when executed
 * with virtual threads, which is critical for maintaining performance during high-concurrency scenarios.
 */
@ExtendWith(DataSessionExtension.class)
@Category({SQLTestGroup.class, VirtualThreadTestGroup.class})
public class ScriptDAOVirtualThreadTest
    extends TestSupport
{
  private DataSessionRule sessionRule = new DataSessionRule().access(ScriptDAO.class);

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
   * Tests creating, reading, updating, and deleting a script entity using a virtual thread.
   * This verifies that database operations work correctly when executed in a virtual thread context.
   */
  @Test
  @DisplayName("Test CRUD operations in a virtual thread")
  public void testCreateReadUpdateDeleteInVirtualThread() throws Exception {
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    // Reference to hold any exception that might occur in the virtual thread
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    
    // Start a virtual thread to perform the database operations
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Create a new script
        ScriptData script = new ScriptData();
        script.setName("virtualThreadTest");
        script.setContent("log.info('Hello from virtual thread')");

        // Create the script in the database
        dao.create(script);

        // Read the script back
        Script read = dao.read(script.getName()).orElse(null);

        // Verify the read operation
        assertThat(read, is(notNullValue()));
        assertThat(read.getName(), is(script.getName()));
        assertThat(read.getType(), is(script.getType()));
        assertThat(read.getContent(), is(script.getContent()));

        // Update the script
        script.setContent("log.info('Updated from virtual thread')");
        dao.update(script);

        // Read the updated script
        Script update = dao.read(script.getName()).orElse(null);

        // Verify the update operation
        assertThat(update, is(notNullValue()));
        assertThat(update.getName(), is(script.getName()));
        assertThat(update.getType(), is(script.getType()));
        assertThat(update.getContent(), is(script.getContent()));

        // Delete the script
        dao.delete(script.getName());

        // Verify the delete operation
        assertThat(dao.read(script.getName()).isPresent(), is(false));
      }
      catch (Throwable t) {
        // Store any exception that occurs
        exceptionRef.set(t);
      }
      finally {
        // Signal that the virtual thread has completed
        latch.countDown();
      }
    });

    // Wait for the virtual thread to complete
    latch.await();
    
    // If an exception occurred in the virtual thread, rethrow it
    if (exceptionRef.get() != null) {
      throw new AssertionError("Exception in virtual thread", exceptionRef.get());
    }
  }

  /**
   * Tests multiple concurrent CRUD operations using virtual threads.
   * This verifies that the database can handle multiple concurrent operations
   * when executed by virtual threads.
   */
  @Test
  @DisplayName("Test concurrent CRUD operations with multiple virtual threads")
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    final int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    
    // Create multiple virtual threads to perform concurrent operations
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.startVirtualThread(() -> {
        try {
          // Create a unique script for this thread
          ScriptData script = new ScriptData();
          script.setName("virtualThreadTest" + threadId);
          script.setContent("log.info('Thread " + threadId + "')");

          // Perform CRUD operations
          dao.create(script);
          
          Script read = dao.read(script.getName()).orElse(null);
          assertThat(read, is(notNullValue()));
          assertThat(read.getName(), is(script.getName()));
          
          script.setContent("log.info('Updated Thread " + threadId + "')");
          dao.update(script);
          
          Script updated = dao.read(script.getName()).orElse(null);
          assertThat(updated, is(notNullValue()));
          assertThat(updated.getContent(), is(script.getContent()));
          
          dao.delete(script.getName());
          assertThat(dao.read(script.getName()).isPresent(), is(false));
        }
        catch (Throwable t) {
          // Store the first exception that occurs
          exceptionRef.compareAndSet(null, t);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all virtual threads to complete
    latch.await();
    
    // If any exception occurred, rethrow it
    if (exceptionRef.get() != null) {
      throw new AssertionError("Exception in concurrent virtual threads", exceptionRef.get());
    }
  }

  /**
   * Tests transaction behavior in virtual threads by attempting to create a script
   * with a duplicate name, which should fail due to unique constraint violation.
   */
  @Test
  @DisplayName("Test transaction behavior in virtual threads")
  public void testTransactionBehaviorInVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> transactionWorked = new AtomicReference<>(false);
    
    // First create a script outside the virtual thread
    ScriptData originalScript = new ScriptData();
    originalScript.setName("transactionTest");
    originalScript.setContent("log.info('Original')");
    dao.create(originalScript);
    
    // Now try to create a script with the same name in a virtual thread
    Thread.startVirtualThread(() -> {
      try {
        // Create a script with the same name, which should fail
        ScriptData duplicateScript = new ScriptData();
        duplicateScript.setName("transactionTest"); // Same name as original
        duplicateScript.setContent("log.info('Duplicate')");
        
        try {
          dao.create(duplicateScript);
          // If we get here, the unique constraint didn't work
          transactionWorked.set(false);
        }
        catch (Exception e) {
          // Expected exception due to unique constraint violation
          transactionWorked.set(true);
        }
        
        // Verify the original script is still intact and wasn't modified
        Script original = dao.read("transactionTest").orElse(null);
        assertThat(original, is(notNullValue()));
        assertThat(original.getContent(), is("log.info('Original')"));
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await();
    
    // Clean up
    dao.delete("transactionTest");
    
    // Verify that the transaction behavior worked as expected
    assertThat("Transaction should have failed with unique constraint violation", 
               transactionWorked.get(), is(true));
  }
}