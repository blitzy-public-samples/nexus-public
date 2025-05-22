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
package org.sonatype.nexus.script.plugin.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.testsuite.testsupport.Java21TestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

@ExtendWith(MockitoExtension.class)
@Category({SQLTestGroup.class, Java21TestGroup.class})
public class ScriptDAOTest
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

  @Test
  public void createReadUpdateDelete() {
    ScriptData script = new ScriptData();
    script.setName("hello");
    script.setContent("log.info('hello')");

    dao.create(script);

    Script read = dao.read(script.getName()).orElse(null);

    assertThat(read, is(notNullValue()));
    assertThat(read.getName(), is(script.getName()));
    assertThat(read.getType(), is(script.getType()));
    assertThat(read.getContent(), is(script.getContent()));

    script.setContent("log.info('world')");
    dao.update(script);

    Script update = dao.read(script.getName()).orElse(null);

    assertThat(update, is(notNullValue()));
    assertThat(update.getName(), is(script.getName()));
    assertThat(update.getType(), is(script.getType()));
    assertThat(update.getContent(), is(script.getContent()));

    dao.delete(script.getName());

    assertThat(dao.read(script.getName()).isPresent(), is(false));
  }
  
  @Test
  public void concurrentDatabaseOperationsWithVirtualThreads() throws Exception {
    // Use virtual threads for concurrent operations
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int operationCount = 100;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> scriptNames = new ArrayList<>();
    
    try {
      // Create multiple scripts concurrently using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String scriptName = "script-" + index;
            scriptNames.add(scriptName);
            
            ScriptData script = new ScriptData();
            script.setName(scriptName);
            script.setContent("log.info('Script " + index + "')");
            
            dao.create(script);
            
            // Verify script was created
            Script read = dao.read(scriptName).orElse(null);
            if (read == null || !read.getName().equals(scriptName)) {
              errorCount.incrementAndGet();
            }
            
            // Update script
            script.setContent("log.info('Updated Script " + index + "')");
            dao.update(script);
          } 
          catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All operations should complete without errors", errorCount.get(), is(0));
      
      // Verify all scripts exist and can be read
      for (String name : scriptNames) {
        Script script = dao.read(name).orElse(null);
        assertThat("Script " + name + " should exist", script, is(notNullValue()));
        assertThat(script.getContent(), containsString("Updated Script"));
      }
      
      // Clean up - delete all scripts
      for (String name : scriptNames) {
        dao.delete(name);
      }
      
    } finally {
      executor.shutdown();
    }
  }
}