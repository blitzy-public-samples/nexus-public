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
package org.sonatype.nexus.upgrade.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.testdb.DataSessionRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests {@link DistributedEventsUpgrade} with Java 21 Virtual Threads to ensure
 * database operations work correctly in a Virtual Thread execution context.
 */
@Category(VirtualThreadTestGroup.class)
public class DistributedEventsUpgradeVirtualThreadTest
    extends TestSupport
{
  public static final String TABLE_NAME = "distributed_events";

  @Rule
  public DataSessionRule sessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME);

  private ThreadFactory virtualThreadFactory;
  private ExecutorService executor;

  @Before
  public void setup() throws SQLException {
    // Create the test table
    try (Connection conn = sessionRule.openConnection("nexus")) {
      conn.prepareCall("CREATE TABLE distributed_events ( foo varchar );").execute();
    }
    
    // Set up virtual thread factory and executor
    virtualThreadFactory = Thread.ofVirtual().factory();
    executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }

  @Test
  public void shouldSkipMigrationWhenClusterEnabled() throws Exception {
    DistributedEventsUpgrade underTest = new DistributedEventsUpgrade(true);
    
    Future<?> future = executor.submit(() -> {
      try (Connection conn = sessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        assertThat(underTest.tableExists(conn, TABLE_NAME), is(true));

        Connection spyConn = spy(conn);
        underTest.migrate(spyConn);
        verifyNoInteractions(spyConn);

        assertThat(underTest.tableExists(conn, TABLE_NAME), is(true));
        return null;
      }
      catch (Exception e) {
        throw new RuntimeException("Test failed in virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete
    future.get();
  }

  @Test
  public void shouldDropTableWhenClusteringDisabled() throws Exception {
    DistributedEventsUpgrade underTest = new DistributedEventsUpgrade(false);
    
    Future<?> future = executor.submit(() -> {
      try (Connection conn = sessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        assertThat(underTest.tableExists(conn, TABLE_NAME), is(true));

        underTest.migrate(conn);

        assertThat(underTest.tableExists(conn, TABLE_NAME), is(false));
        return null;
      }
      catch (Exception e) {
        throw new RuntimeException("Test failed in virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete
    future.get();
  }

  @Test
  public void shouldNotFailWhenTableMissing() throws Exception {
    DistributedEventsUpgrade underTest = new DistributedEventsUpgrade(false);
    
    Future<?> future = executor.submit(() -> {
      try (Connection conn = sessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        dropTable(conn);
        // sanity check
        assertThat(underTest.tableExists(conn, TABLE_NAME), is(false));

        underTest.migrate(conn);

        // sonar is dumb, the test here is that we don't blow up
        assertThat(underTest.tableExists(conn, TABLE_NAME), is(false));
        return null;
      }
      catch (Exception e) {
        throw new RuntimeException("Test failed in virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete
    future.get();
  }

  private void dropTable(final Connection connection) throws Exception {
    Statement stmt = connection.createStatement();
    stmt.executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME);
  }
}