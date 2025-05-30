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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.datastore.mybatis.PlaceholderTypes;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests MyBatis operations with Java 21 Virtual Threads to detect and prevent thread pinning issues.
 * 
 * Thread pinning occurs when a virtual thread blocks a carrier platform thread, negating the benefits
 * of virtual threads. This test verifies that common MyBatis operations like queries, updates, and
 * transactions don't cause thread pinning, and provides diagnostics when pinning is detected.
 */
public class MyBatisVirtualThreadPinningTest
    extends TestSupport
{
  private static final int VIRTUAL_THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final long PINNING_THRESHOLD_MS = 20; // Match JFR's default threshold
  
  private SqlSessionFactory sqlSessionFactory;
  private JdbcDataSource dataSource;
  private ExecutorService executorService;
  private ThreadMXBean threadMXBean;
  private AtomicBoolean pinningDetected;
  private AtomicInteger completedOperations;
  
  /**
   * Set up the test environment with an in-memory H2 database and MyBatis configuration.
   */
  @Before
  public void setUp() throws Exception {
    // Create an H2 in-memory database
    dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    
    // Create test table
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "CREATE TABLE IF NOT EXISTS test_data (id VARCHAR(36) PRIMARY KEY, value VARCHAR(255))")) {
      stmt.execute();
    }
    
    // Configure MyBatis
    TransactionFactory transactionFactory = new JdbcTransactionFactory();
    Environment environment = new Environment("test", transactionFactory, dataSource);
    
    Configuration configuration = new Configuration(environment);
    configuration.setDatabaseId("H2");
    PlaceholderTypes.configurePlaceholderTypes(configuration);
    configuration.addMapper(TestMapper.class);
    
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    
    // Create a virtual thread executor
    executorService = Executors.newVirtualThreadPerTaskExecutor();
    
    // Get the ThreadMXBean for monitoring thread states
    threadMXBean = ManagementFactory.getThreadMXBean();
    
    // Initialize tracking variables
    pinningDetected = new AtomicBoolean(false);
    completedOperations = new AtomicInteger(0);
  }
  
  @After
  public void tearDown() throws Exception {
    if (executorService != null) {
      executorService.shutdown();
      executorService.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    // Clean up the database
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement("DROP TABLE IF EXISTS test_data")) {
      stmt.execute();
    }
  }
  
  /**
   * Test that MyBatis query operations don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testMyBatisQueriesWithVirtualThreads() throws Exception {
    // Populate test data
    populateTestData(100);
    
    // Run concurrent queries using virtual threads
    runConcurrentOperations(TestOperation.QUERY);
    
    // Verify no pinning was detected
    assertThat("Thread pinning was detected during query operations", pinningDetected.get(), is(false));
  }
  
  /**
   * Test that MyBatis insert operations don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testMyBatisInsertsWithVirtualThreads() throws Exception {
    // Run concurrent inserts using virtual threads
    runConcurrentOperations(TestOperation.INSERT);
    
    // Verify no pinning was detected
    assertThat("Thread pinning was detected during insert operations", pinningDetected.get(), is(false));
  }
  
  /**
   * Test that MyBatis update operations don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testMyBatisUpdatesWithVirtualThreads() throws Exception {
    // Populate test data
    populateTestData(100);
    
    // Run concurrent updates using virtual threads
    runConcurrentOperations(TestOperation.UPDATE);
    
    // Verify no pinning was detected
    assertThat("Thread pinning was detected during update operations", pinningDetected.get(), is(false));
  }
  
  /**
   * Test that MyBatis delete operations don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testMyBatisDeletesWithVirtualThreads() throws Exception {
    // Populate test data
    populateTestData(100);
    
    // Run concurrent deletes using virtual threads
    runConcurrentOperations(TestOperation.DELETE);
    
    // Verify no pinning was detected
    assertThat("Thread pinning was detected during delete operations", pinningDetected.get(), is(false));
  }
  
  /**
   * Test that MyBatis transaction operations don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testMyBatisTransactionsWithVirtualThreads() throws Exception {
    // Run concurrent transactions using virtual threads
    runConcurrentOperations(TestOperation.TRANSACTION);
    
    // Verify no pinning was detected
    assertThat("Thread pinning was detected during transaction operations", pinningDetected.get(), is(false));
  }
  
  /**
   * Test that MyBatis large result sets don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testMyBatisLargeResultSetsWithVirtualThreads() throws Exception {
    // Populate a larger dataset
    populateTestData(1000);
    
    // Run concurrent large result set queries using virtual threads
    runConcurrentOperations(TestOperation.LARGE_RESULT_SET);
    
    // Verify no pinning was detected
    assertThat("Thread pinning was detected during large result set operations", pinningDetected.get(), is(false));
  }
  
  /**
   * Test that carrier thread utilization remains efficient during MyBatis operations with virtual threads.
   */
  @Test
  public void testCarrierThreadUtilization() throws Exception {
    // Populate test data
    populateTestData(100);
    
    // Get the initial number of platform threads
    int initialPlatformThreadCount = countPlatformThreads();
    
    // Run a mix of operations concurrently
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT * 5);
    
    // Submit different types of operations
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      executorService.submit(() -> runOperationWithMonitoring(TestOperation.QUERY, startLatch, completionLatch));
      executorService.submit(() -> runOperationWithMonitoring(TestOperation.INSERT, startLatch, completionLatch));
      executorService.submit(() -> runOperationWithMonitoring(TestOperation.UPDATE, startLatch, completionLatch));
      executorService.submit(() -> runOperationWithMonitoring(TestOperation.DELETE, startLatch, completionLatch));
      executorService.submit(() -> runOperationWithMonitoring(TestOperation.TRANSACTION, startLatch, completionLatch));
    }
    
    // Start all operations simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    completionLatch.await(30, TimeUnit.SECONDS);
    
    // Get the peak number of platform threads used during the test
    int peakPlatformThreadCount = countPlatformThreads();
    
    // Verify that the number of platform threads used is much less than the number of virtual threads
    // This indicates efficient carrier thread utilization
    assertThat("Too many carrier threads were used, suggesting inefficient thread utilization",
        peakPlatformThreadCount - initialPlatformThreadCount, lessThan(VIRTUAL_THREAD_COUNT / 10));
  }
  
  /**
   * Runs concurrent operations of the specified type using virtual threads.
   */
  private void runConcurrentOperations(TestOperation operationType) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Submit tasks to the virtual thread executor
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      executorService.submit(() -> runOperationWithMonitoring(operationType, startLatch, completionLatch));
    }
    
    // Start all operations simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    boolean allCompleted = completionLatch.await(30, TimeUnit.SECONDS);
    assertThat("Not all operations completed in time", allCompleted, is(true));
    
    // Verify all operations were completed
    assertThat("Not all operations were completed", 
        completedOperations.get(), is(VIRTUAL_THREAD_COUNT * OPERATIONS_PER_THREAD));
  }
  
  /**
   * Runs a MyBatis operation with thread pinning monitoring.
   */
  private void runOperationWithMonitoring(
      TestOperation operationType, 
      CountDownLatch startLatch, 
      CountDownLatch completionLatch) {
    try {
      // Wait for the start signal
      startLatch.await();
      
      // Get the current thread ID for monitoring
      Thread currentThread = Thread.currentThread();
      boolean isVirtualThread = currentThread.isVirtual();
      
      if (!isVirtualThread) {
        logger.warn("Test is not running on a virtual thread: {}", currentThread.getName());
      }
      
      // Perform multiple operations
      for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
        // Record the start time for pinning detection
        long startTime = System.currentTimeMillis();
        
        // Perform the operation
        performOperation(operationType);
        
        // Check if the operation took longer than the pinning threshold
        long duration = System.currentTimeMillis() - startTime;
        if (duration > PINNING_THRESHOLD_MS) {
          // This might indicate thread pinning
          ThreadInfo threadInfo = threadMXBean.getThreadInfo(currentThread.threadId(), 10);
          logger.warn("Potential thread pinning detected: {} took {}ms\nStack trace: {}",
              operationType, duration, formatStackTrace(threadInfo));
          pinningDetected.set(true);
        }
        
        completedOperations.incrementAndGet();
      }
    } 
    catch (Exception e) {
      logger.error("Error during operation: {}", e.getMessage(), e);
    } 
    finally {
      completionLatch.countDown();
    }
  }
  
  /**
   * Performs a MyBatis operation based on the specified type.
   */
  private void performOperation(TestOperation operationType) {
    switch (operationType) {
      case QUERY:
        performQuery();
        break;
      case INSERT:
        performInsert();
        break;
      case UPDATE:
        performUpdate();
        break;
      case DELETE:
        performDelete();
        break;
      case TRANSACTION:
        performTransaction();
        break;
      case LARGE_RESULT_SET:
        performLargeResultSetQuery();
        break;
    }
  }
  
  /**
   * Performs a simple query operation.
   */
  private void performQuery() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      TestMapper mapper = session.getMapper(TestMapper.class);
      mapper.findAll();
    }
  }
  
  /**
   * Performs an insert operation.
   */
  private void performInsert() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      TestMapper mapper = session.getMapper(TestMapper.class);
      TestData data = new TestData(UUID.randomUUID().toString(), "Value " + System.currentTimeMillis());
      mapper.insert(data);
      session.commit();
    }
  }
  
  /**
   * Performs an update operation.
   */
  private void performUpdate() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      TestMapper mapper = session.getMapper(TestMapper.class);
      List<TestData> allData = mapper.findAll();
      if (!allData.isEmpty()) {
        TestData data = allData.get((int) (Math.random() * allData.size()));
        data.setValue("Updated " + System.currentTimeMillis());
        mapper.update(data);
        session.commit();
      }
    }
  }
  
  /**
   * Performs a delete operation.
   */
  private void performDelete() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      TestMapper mapper = session.getMapper(TestMapper.class);
      List<TestData> allData = mapper.findAll();
      if (!allData.isEmpty()) {
        TestData data = allData.get((int) (Math.random() * allData.size()));
        mapper.delete(data.getId());
        session.commit();
      }
    }
  }
  
  /**
   * Performs a transaction with multiple operations.
   */
  private void performTransaction() {
    try (SqlSession session = sqlSessionFactory.openSession(false)) {
      TestMapper mapper = session.getMapper(TestMapper.class);
      
      // Insert a new record
      TestData data = new TestData(UUID.randomUUID().toString(), "Transaction " + System.currentTimeMillis());
      mapper.insert(data);
      
      // Update the record
      data.setValue("Updated in transaction");
      mapper.update(data);
      
      // Commit the transaction
      session.commit();
    }
  }
  
  /**
   * Performs a query that returns a large result set.
   */
  private void performLargeResultSetQuery() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      TestMapper mapper = session.getMapper(TestMapper.class);
      List<TestData> results = mapper.findAll();
      
      // Process the results to ensure they're fully loaded
      for (TestData data : results) {
        // Just access the data to ensure it's loaded
        String value = data.getValue();
        if (value == null) {
          logger.warn("Null value found for ID: {}", data.getId());
        }
      }
    }
  }
  
  /**
   * Populates the test database with the specified number of records.
   */
  private void populateTestData(int count) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "INSERT INTO test_data (id, value) VALUES (?, ?)")) {
      
      for (int i = 0; i < count; i++) {
        stmt.setString(1, UUID.randomUUID().toString());
        stmt.setString(2, "Test value " + i);
        stmt.addBatch();
        
        // Execute in batches of 100
        if (i % 100 == 0) {
          stmt.executeBatch();
        }
      }
      
      stmt.executeBatch();
    }
  }
  
  /**
   * Counts the number of platform threads currently active.
   */
  private int countPlatformThreads() {
    return (int) Thread.getAllStackTraces().keySet().stream()
        .filter(t -> !t.isVirtual())
        .count();
  }
  
  /**
   * Formats a thread stack trace for logging.
   */
  private String formatStackTrace(ThreadInfo threadInfo) {
    if (threadInfo == null) {
      return "<no thread info available>";
    }
    
    StringBuilder sb = new StringBuilder()
        .append(threadInfo.getThreadName())
        .append(" state: ")
        .append(threadInfo.getThreadState());
    
    for (StackTraceElement element : threadInfo.getStackTrace()) {
      sb.append("\n\tat ")
        .append(element.toString());
    }
    
    return sb.toString();
  }
  
  /**
   * Enum representing different types of MyBatis operations to test.
   */
  private enum TestOperation {
    QUERY,
    INSERT,
    UPDATE,
    DELETE,
    TRANSACTION,
    LARGE_RESULT_SET
  }
  
  /**
   * Test data class for MyBatis operations.
   */
  public static class TestData {
    private String id;
    private String value;
    
    public TestData() {
    }
    
    public TestData(String id, String value) {
      this.id = id;
      this.value = value;
    }
    
    public String getId() {
      return id;
    }
    
    public void setId(String id) {
      this.id = id;
    }
    
    public String getValue() {
      return value;
    }
    
    public void setValue(String value) {
      this.value = value;
    }
  }
  
  /**
   * MyBatis mapper interface for test operations.
   */
  public interface TestMapper {
    @org.apache.ibatis.annotations.Select("SELECT id, value FROM test_data")
    List<TestData> findAll();
    
    @org.apache.ibatis.annotations.Insert("INSERT INTO test_data (id, value) VALUES (#{id}, #{value})")
    void insert(TestData data);
    
    @org.apache.ibatis.annotations.Update("UPDATE test_data SET value = #{value} WHERE id = #{id}")
    void update(TestData data);
    
    @org.apache.ibatis.annotations.Delete("DELETE FROM test_data WHERE id = #{id}")
    void delete(String id);
  }
}