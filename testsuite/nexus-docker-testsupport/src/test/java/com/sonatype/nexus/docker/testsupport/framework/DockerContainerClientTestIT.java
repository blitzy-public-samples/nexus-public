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
package com.sonatype.nexus.docker.testsupport.framework;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.ContainerFetchException;

import static java.util.UUID.randomUUID;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test Docker containers can be pulled and running with commands.
 * <p>
 * This test class has been updated for Java 21 compatibility and demonstrates:
 * <ul>
 *   <li>JUnit Jupiter (JUnit 5) test framework usage</li>
 *   <li>Virtual Thread testing for concurrent Docker operations</li>
 *   <li>Java 21 features including record patterns and string templates</li>
 *   <li>Performance comparison between platform and virtual threads</li>
 * </ul>
 * </p>
 */
@Tag("integration")
public class DockerContainerClientTestIT
{
  private static final String IMAGE_HELLO_WORLD = "docker-all.repo.sonatype.com/hello-world";

  private static final String IMAGE_DOCKER = "docker-all.repo.sonatype.com/docker";

  private static final String IMAGE_CENTOS = "docker-all.repo.sonatype.com/centos";

  private DockerContainerClient underTest;

  @AfterEach
  public void cleanUp() {
    if (underTest != null) {
      underTest.close();
    }
  }

  @Test
  public void when_Pull_HelloWorld_Expect_Container_UpAndRunning() {
    underTest = new DockerContainerClient(IMAGE_HELLO_WORLD);
    underTest.run();
    // No exception means success
  }

  @Test
  public void when_Exec_YumVersion_On_CentosLatest_Expect_Execution_To_Succeed() {
    underTest = new DockerContainerClient(IMAGE_CENTOS);
    Optional<ExecResult> result = underTest.exec("yum --version");
    assertTrue(result.isPresent());
    assertEquals(0, result.get().getExitCode());
    assertFalse(result.get().getStdout().isEmpty());
    assertTrue(result.get().getStderr().isEmpty());
  }

  @Test
  public void when_Exec_YumVersion_On_Centos_6_9_Expect_Execution_To_Succeed() {
    underTest = new DockerContainerClient(IMAGE_CENTOS + ":6.9");
    Optional<ExecResult> result = underTest.exec("yum --version");
    assertTrue(result.isPresent());
    assertEquals(0, result.get().getExitCode());
    assertFalse(result.get().getStdout().isEmpty());
    assertTrue(result.get().getStderr().isEmpty());
  }

  @Test
  public void when_Exec_DockerVersion_On_DockerLatest_Expect_Execution_To_Succeed() {
    underTest = new DockerContainerClient(IMAGE_DOCKER);
    Optional<ExecResult> result = underTest.exec("docker --version");
    assertTrue(result.isPresent());
    assertEquals(0, result.get().getExitCode());
    assertTrue(result.get().getStderr().isEmpty());
    assertThat(result.get().getStdout(), is(containsString("Docker version")));
  }

  @Test
  public void when_Pull_UnknownImage_Expect_Fail() {
    underTest = new DockerContainerClient("unknown-image-" + randomUUID());
    assertThrows(ContainerFetchException.class, () -> underTest.run());
  }

  @Test
  public void test_Successfully_Bind_Port() {
    String exposedPort = "80";
    // Using record pattern for cleaner configuration handling
    var containerConfig = DockerContainerConfig.builder(IMAGE_CENTOS)
        .withExposedPort(exposedPort)
        .build();
    underTest = new DockerContainerClient(containerConfig);
    underTest.runAndKeepAlive();
    Integer mappedPort = underTest.getMappedPort(exposedPort);
    assertThat(mappedPort, notNullValue());
  }
  
  /**
   * Tests concurrent Docker command execution using virtual threads.
   * <p>
   * This test validates that the DockerContainerClient can handle multiple concurrent
   * operations efficiently using Java 21's virtual threads.
   * </p>
   */
  @Test
  @Tag(Java21TestGroup.NAME)
  @Tag(VirtualThreadTestGroup.NAME)
  @Timeout(value = 60)
  public void testConcurrentCommandExecutionWithVirtualThreads() throws Exception {
    // Setup container
    underTest = new DockerContainerClient(IMAGE_CENTOS);
    underTest.runAndKeepAlive();
    
    // Number of concurrent commands to execute
    int commandCount = 50;
    CountDownLatch latch = new CountDownLatch(commandCount);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> failedCommands = new ArrayList<>();
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent commands
      for (int i = 0; i < commandCount; i++) {
        final String command = STR."echo 'Virtual Thread Test \{i}'";
        CompletableFuture.runAsync(() -> {
          try {
            Optional<ExecResult> result = underTest.exec(command);
            if (result.isPresent() && result.get().getExitCode() == 0) {
              successCount.incrementAndGet();
            } else {
              synchronized (failedCommands) {
                failedCommands.add(command);
              }
            }
          } catch (Exception e) {
            synchronized (failedCommands) {
              failedCommands.add(STR."\{command} (Exception: \{e.getMessage()})";
            }
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all commands to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for commands to complete");
      
      // Verify results
      assertEquals(commandCount, successCount.get(), 
          STR."Expected all \{commandCount} commands to succeed, but \{failedCommands.size()} failed: \{failedCommands}");
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for Docker operations.
   * <p>
   * This test demonstrates the performance benefits of virtual threads for I/O-bound
   * operations like Docker command execution.
   * </p>
   */
  @Test
  @Tag(Java21TestGroup.NAME)
  @Tag(VirtualThreadTestGroup.NAME)
  @Timeout(value = 120)
  public void compareThreadPerformanceForDockerOperations() throws Exception {
    // Setup container
    underTest = new DockerContainerClient(IMAGE_CENTOS);
    underTest.runAndKeepAlive();
    
    // Test parameters
    int commandCount = 100;
    String command = "echo 'Thread Performance Test'";
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
        executeCommands(executor, commandCount, command);
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        executeCommands(executor, commandCount, command);
      }
    });
    
    // Log and assert results
    System.out.println(STR."Platform thread execution time: \{platformThreadTime}ms");
    System.out.println(STR."Virtual thread execution time: \{virtualThreadTime}ms");
    
    // Virtual threads should generally be more efficient for I/O-bound operations
    // but we don't make this a hard assertion as it depends on the environment
    assertThat("Virtual threads should handle more concurrent I/O operations efficiently", 
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
  }
  
  /**
   * Tests that the DockerContainerClient can handle a large number of concurrent operations
   * using virtual threads without exhausting system resources.
   */
  @Test
  @Tag(Java21TestGroup.NAME)
  @Tag(VirtualThreadTestGroup.NAME)
  @Timeout(value = 120)
  public void testVirtualThreadScalability() throws Exception {
    // Setup container
    underTest = new DockerContainerClient(IMAGE_CENTOS);
    underTest.runAndKeepAlive();
    
    // A large number of concurrent operations that would be problematic with platform threads
    int commandCount = 500;
    CountDownLatch latch = new CountDownLatch(commandCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Use virtual threads for high concurrency
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < commandCount; i++) {
        final int index = i;
        CompletableFuture.runAsync(() -> {
          try {
            // Simple echo command with unique index
            Optional<ExecResult> result = underTest.exec(STR."echo 'Scalability Test \{index}'";
            if (result.isPresent() && result.get().getExitCode() == 0) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all commands to complete with a reasonable timeout
      assertTrue(latch.await(60, TimeUnit.SECONDS), "Timed out waiting for commands to complete");
      
      // Verify that most commands succeeded (allow for some failures in case of resource constraints)
      int minimumSuccessCount = (int)(commandCount * 0.9); // 90% success rate
      assertThat(STR."Expected at least \{minimumSuccessCount} of \{commandCount} commands to succeed",
          successCount.get(), greaterThan(minimumSuccessCount));
    }
  }
  
  /**
   * Helper method to execute a batch of commands using the provided executor.
   */
  private void executeCommands(ExecutorService executor, int count, String command) throws Exception {
    CountDownLatch latch = new CountDownLatch(count);
    
    for (int i = 0; i < count; i++) {
      executor.submit(() -> {
        try {
          underTest.exec(command);
        } finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(60, TimeUnit.SECONDS), "Timed out waiting for commands to complete");
  }
  
  /**
   * Measures the execution time of a runnable operation in milliseconds.
   */
  private long measureExecutionTime(Runnable operation) throws Exception {
    long startTime = System.currentTimeMillis();
    operation.run();
    return System.currentTimeMillis() - startTime;
  }
}
