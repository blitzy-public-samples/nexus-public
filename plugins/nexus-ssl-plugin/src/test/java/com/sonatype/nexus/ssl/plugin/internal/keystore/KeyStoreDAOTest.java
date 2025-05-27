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
package com.sonatype.nexus.ssl.plugin.internal.keystore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.testdb.DataSessionRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

@Tag("java21")
public class KeyStoreDAOTest
{

  @RegisterExtension
  public DataSessionRule sessionRule = new DataSessionRule().access(KeyStoreDAO.class);

  private DataSession<?> session;

  private KeyStoreDAO dao;

  @BeforeEach
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(KeyStoreDAO.class);
  }

  @AfterEach
  public void cleanup() {
    session.close();
  }

  @Test
  public void testCreateReadUpdateDeleteOperations() {
    // Create a KeyStoreData entity
    KeyStoreData entity = new KeyStoreData();
    entity.setName("keystorename");
    entity.setBytes(new byte[]{1, 2, 3});
    // Save the KeyStoreData
    boolean saveResult = dao.save(entity);
    assertThat(saveResult, is(true));

    // Read back the KeyStoreData
    Optional<KeyStoreData> readBack = dao.load(entity.getName());
    assertThat(readBack.isPresent(), is(true));
    assertThat(readBack.get().getName(), is(entity.getName()));
    assertThat(readBack.get().getBytes(), is(entity.getBytes()));

    // Update the KeyStoreData
    entity.setBytes(new byte[]{4, 5, 6});
    boolean updateResult = dao.save(entity);
    assertThat(updateResult, is(true));

    // Read back the updated KeyStoreData
    Optional<KeyStoreData> updated = dao.load(entity.getName());
    assertThat(updated.isPresent(), is(true));
    assertThat(updated.get().getName(), is(entity.getName()));
    assertThat(updated.get().getBytes(), is(new byte[]{4, 5, 6}));

    // Delete the KeyStoreData
    boolean deleteResult = dao.delete(entity.getName());
    assertThat(deleteResult, is(true));

    // Verify the KeyStoreData does not exist anymore
    Optional<KeyStoreData> deleted = dao.load(entity.getName());
    assertThat(deleted.isPresent(), is(false));
  }

  @Test
  @Tag("virtualthread")
  public void testConcurrentOperationsWithVirtualThreads() throws InterruptedException, ExecutionException {
    final int threadCount = 50;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    final List<Thread> threads = new ArrayList<>();
    
    // Create and start virtual threads to perform concurrent operations
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread vThread = Thread.ofVirtual().name("vt-keystore-" + index).start(() -> {
        try {
          // Create a unique KeyStoreData entity for each thread
          KeyStoreData entity = new KeyStoreData();
          entity.setName("keystore-vt-" + index);
          entity.setBytes(new byte[]{(byte) index, (byte) (index + 1), (byte) (index + 2)});
          
          // Save the KeyStoreData
          boolean saveResult = dao.save(entity);
          assertThat(saveResult, is(true));
          
          // Read back the KeyStoreData
          Optional<KeyStoreData> readBack = dao.load(entity.getName());
          assertThat(readBack.isPresent(), is(true));
          assertThat(readBack.get().getName(), is(entity.getName()));
          
          // Update the KeyStoreData
          entity.setBytes(new byte[]{(byte) (index + 3), (byte) (index + 4), (byte) (index + 5)});
          boolean updateResult = dao.save(entity);
          assertThat(updateResult, is(true));
          
          // Delete the KeyStoreData
          boolean deleteResult = dao.delete(entity.getName());
          assertThat(deleteResult, is(true));
          
          successCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
      threads.add(vThread);
    }
    
    // Wait for all threads to complete
    latch.await();
    
    // Verify all operations completed successfully
    assertThat(successCount.get(), is(threadCount));
    
    // Verify all threads are virtual threads
    for (Thread thread : threads) {
      assertThat(thread.isVirtual(), is(true));
    }
  }
}