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
package org.sonatype.nexus.datastore.virtualthread;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ManagedLifecycleManager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests database connection handling with Java 21 Virtual Threads using HikariCP connection pool.
 * 
 * This test verifies that connections can be acquired, used, and released properly in a highly
 * concurrent virtual thread environment without leaking resources. It validates proper thread
 * context propagation, stress-tests the connection pool with many virtual threads, and ensures
 * compatibility between HikariCP, JDBC drivers, and virtual threads.
 */
public class DatabaseConnectionVirtualThreadTest
    extends TestSupport
{
    private static final String TEST_TABLE = "virtual_thread_test";
    private static final String CREATE_TABLE_SQL = 
        "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (id INT PRIMARY KEY, name VARCHAR(255))";
    private static final String INSERT_SQL = 
        "INSERT INTO " + TEST_TABLE + " (id, name) VALUES (?, ?)";
    private static final String SELECT_SQL = 
        "SELECT name FROM " + TEST_TABLE + " WHERE id = ?";
    private static final String DROP_TABLE_SQL = 
        "DROP TABLE IF EXISTS " + TEST_TABLE;
    
    private static final int MAX_POOL_SIZE = 10;
    private static final int CONCURRENT_THREADS = 100;
    private static final int TIMEOUT_SECONDS = 30;
    
    @Mock
    private ApplicationDirectories directories;
    
    @Mock
    private ManagedLifecycleManager managedLifecycleManager;
    
    private HikariDataSource dataSource;
    
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(directories.getWorkDirectory("test-db")).thenReturn(util.createTempDir());
        
        // Configure HikariCP for H2 in-memory database
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(10000);
        config.setMaxLifetime(30000);
        config.setPoolName("VirtualThreadTestPool");
        
        dataSource = new HikariDataSource(config);
        
        // Create test table
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_TABLE_SQL)) {
            stmt.execute();
        }
        
        // Insert some test data
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            for (int i = 1; i <= 10; i++) {
                stmt.setInt(1, i);
                stmt.setString(2, "Test Name " + i);
                stmt.executeUpdate();
            }
        }
    }
    
    @After
    public void tearDown() throws Exception {
        // Drop test table
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DROP_TABLE_SQL)) {
            stmt.execute();
        }
        
        // Close the data source
        if (dataSource != null) {
            dataSource.close();
        }
    }
    
    /**
     * Tests basic connection acquisition and release with a virtual thread.
     */
    @Test
    public void testBasicConnectionWithVirtualThread() throws Exception {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-vt-", 0).factory();
        Thread virtualThread = virtualThreadFactory.newThread(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
                stmt.setInt(1, 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next(), is(true));
                    assertThat(rs.getString(1), equalTo("Test Name 1"));
                }
            }
            catch (SQLException e) {
                throw new RuntimeException("Failed to execute query", e);
            }
        });
        
        virtualThread.start();
        virtualThread.join();
    }
    
    /**
     * Tests that thread context is properly propagated through database operations.
     */
    @Test
    public void testThreadContextPropagation() throws Exception {
        ThreadLocal<String> contextValue = new ThreadLocal<>();
        AtomicReference<String> capturedValue = new AtomicReference<>();
        
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("context-vt-", 0).factory();
        Thread virtualThread = virtualThreadFactory.newThread(() -> {
            // Set context value
            contextValue.set("CONTEXT_VALUE");
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
                stmt.setInt(1, 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    // Capture the context value during database operation
                    capturedValue.set(contextValue.get());
                }
            }
            catch (SQLException e) {
                throw new RuntimeException("Failed to execute query", e);
            }
        });
        
        virtualThread.start();
        virtualThread.join();
        
        // Verify context was preserved
        assertThat(capturedValue.get(), equalTo("CONTEXT_VALUE"));
    }
    
    /**
     * Tests connection handling with interrupted virtual threads.
     */
    @Test
    public void testInterruptedVirtualThread() throws Exception {
        AtomicBoolean connectionClosed = new AtomicBoolean(false);
        CountDownLatch operationStarted = new CountDownLatch(1);
        
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("interrupt-vt-", 0).factory();
        Thread virtualThread = virtualThreadFactory.newThread(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // Signal that we've acquired the connection
                operationStarted.countDown();
                
                // Simulate long-running operation
                try {
                    Thread.sleep(10000); // Should be interrupted
                }
                catch (InterruptedException e) {
                    // Expected
                }
                
                // Check if connection is still valid
                try {
                    connectionClosed.set(conn.isClosed());
                }
                catch (SQLException e) {
                    throw new RuntimeException("Failed to check connection state", e);
                }
            }
            catch (SQLException e) {
                throw new RuntimeException("Failed to acquire connection", e);
            }
        });
        
        virtualThread.start();
        
        // Wait for operation to start
        assertThat("Operation did not start in time", 
                operationStarted.await(5, TimeUnit.SECONDS), is(true));
        
        // Interrupt the thread
        virtualThread.interrupt();
        virtualThread.join();
        
        // Verify connection was properly closed despite interruption
        assertThat("Connection should not be closed by interruption", 
                connectionClosed.get(), is(false));
    }
    
    /**
     * Tests high concurrency with many virtual threads accessing the connection pool simultaneously.
     */
    @Test
    public void testHighConcurrencyWithVirtualThreads() throws Exception {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("concurrent-vt-", 0).factory();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        
        // Submit multiple concurrent tasks
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int id = (i % 10) + 1; // Use IDs 1-10
            
            executor.submit(() -> {
                try {
                    // Track concurrent execution
                    int current = currentConcurrent.incrementAndGet();
                    int max = maxConcurrent.get();
                    while (current > max) {
                        if (maxConcurrent.compareAndSet(max, current)) {
                            break;
                        }
                        max = maxConcurrent.get();
                    }
                    
                    // Perform database operation
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
                        stmt.setInt(1, id);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                String name = rs.getString(1);
                                if (name != null && name.equals("Test Name " + id)) {
                                    successCount.incrementAndGet();
                                }
                            }
                        }
                    }
                }
                catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Error in virtual thread operation", e);
                }
                finally {
                    currentConcurrent.decrementAndGet();
                    completionLatch.countDown();
                }
            });
        }
        
        // Wait for all tasks to complete
        assertThat("Not all tasks completed in time",
                completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
        
        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify results
        assertThat("All operations should succeed", failureCount.get(), is(0));
        assertThat("All operations should complete successfully", 
                successCount.get(), is(CONCURRENT_THREADS));
        
        // Verify we didn't exceed connection pool size by too much
        // Note: Some overhead is expected due to the nature of connection acquisition
        log.info("Max concurrent connections used: {}", maxConcurrent.get());
        assertThat("Max concurrent connections should not greatly exceed pool size",
                maxConcurrent.get(), lessThanOrEqualTo(MAX_POOL_SIZE * 2));
    }
    
    /**
     * Tests that connections are properly released back to the pool after use.
     */
    @Test
    public void testConnectionReleaseWithVirtualThreads() throws Exception {
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("release-vt-", 0).factory();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
        
        // First, use all connections from the pool
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < MAX_POOL_SIZE; i++) {
            Connection conn = dataSource.getConnection();
            connections.add(conn);
        }
        
        // Release all connections
        for (Connection conn : connections) {
            conn.close();
        }
        connections.clear();
        
        // Now run a bunch of virtual threads that each get and release a connection
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            executor.submit(() -> {
                try (Connection conn = dataSource.getConnection()) {
                    // Just verify we got a valid connection
                    assertThat(conn, notNullValue());
                    assertThat(conn.isClosed(), is(false));
                    
                    // Small delay to simulate work
                    Thread.sleep(10);
                }
                catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Failed to get or use connection", e);
                }
                finally {
                    completionLatch.countDown();
                }
            });
        }
        
        // Wait for all tasks to complete
        assertThat("Not all tasks completed in time",
                completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
        
        // Shutdown executor
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // Verify no failures occurred
        assertThat("All operations should succeed", failureCount.get(), is(0));
        
        // Verify we can still get MAX_POOL_SIZE connections from the pool
        connections = new ArrayList<>();
        for (int i = 0; i < MAX_POOL_SIZE; i++) {
            Connection conn = dataSource.getConnection();
            connections.add(conn);
            assertThat("Connection should be valid", conn.isValid(1), is(true));
        }
        
        // Release all connections
        for (Connection conn : connections) {
            conn.close();
        }
    }
}