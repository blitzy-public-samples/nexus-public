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
package org.sonatype.nexus.script.plugin.internal.security;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.SecurityConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ScriptSecurityContributor} when executed within Java 21 Virtual Threads.
 * 
 * This test ensures that security privileges for scripts are correctly configured and returned
 * even when the contributor is running in a lightweight virtual thread context.
 */
public class ScriptSecurityContributorTest
    extends TestSupport
{
  private ScriptSecurityContributor underTest;

  @BeforeEach
  public void setup() {
    underTest = new ScriptSecurityContributor();
  }

  /**
   * Verifies that the security contributor returns the expected configuration
   * when executed in the main thread.
   */
  @Test
  public void testGetContribution() {
    SecurityConfiguration config = underTest.getContribution();
    assertThat(config.getUsers().size(), is(0));
    assertThat(config.getUserRoleMappings().size(), is(0));
    assertThat(config.getRoles().size(), is(0));
    assertThat(config.getPrivileges().stream().map(CPrivilege::getId).collect(toList()),
        containsInAnyOrder("nx-script-*-*", "nx-script-*-browse", "nx-script-*-read", "nx-script-*-edit",
            "nx-script-*-add", "nx-script-*-delete", "nx-script-*-run"));
  }

  /**
   * Verifies that the security contributor returns the expected configuration
   * when executed in a Java 21 Virtual Thread.
   */
  @Test
  public void testGetContributionInVirtualThread() throws Exception {
    // Create and start a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("security-contributor-test").start(() -> {
      SecurityConfiguration config = underTest.getContribution();
      
      // Verify the configuration is correct
      assertThat("Virtual Thread should return empty users", 
          config.getUsers().size(), is(0));
      assertThat("Virtual Thread should return empty user role mappings", 
          config.getUserRoleMappings().size(), is(0));
      assertThat("Virtual Thread should return empty roles", 
          config.getRoles().size(), is(0));
      
      // Verify all expected privileges are present
      List<String> privilegeIds = config.getPrivileges().stream()
          .map(CPrivilege::getId)
          .collect(toList());
      
      assertThat("Virtual Thread should return all expected privileges", 
          privilegeIds,
          containsInAnyOrder(
              "nx-script-*-*", 
              "nx-script-*-browse", 
              "nx-script-*-read", 
              "nx-script-*-edit",
              "nx-script-*-add", 
              "nx-script-*-delete", 
              "nx-script-*-run"));
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Verifies that the security contributor is thread-safe by running multiple concurrent
   * virtual threads that all access the contributor simultaneously.
   */
  @Test
  public void testConcurrentAccessInVirtualThreads() throws Exception {
    // Number of concurrent threads to run
    final int threadCount = 50;
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a list of futures, each running in its own virtual thread
      List<CompletableFuture<List<String>>> futures = IntStream.range(0, threadCount)
          .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            // Get the security configuration
            SecurityConfiguration config = underTest.getContribution();
            
            // Extract and return the privilege IDs
            return config.getPrivileges().stream()
                .map(CPrivilege::getId)
                .collect(toList());
          }, executor))
          .collect(Collectors.toList());
      
      // Wait for all futures to complete and collect results
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          futures.toArray(new CompletableFuture[0]));
      
      // Wait for all threads to complete (with timeout)
      allFutures.get(10, TimeUnit.SECONDS);
      
      // Verify that all threads received the correct privileges
      for (int i = 0; i < threadCount; i++) {
        List<String> privilegeIds = futures.get(i).get();
        
        assertThat("Virtual Thread " + i + " should return all expected privileges",
            privilegeIds,
            containsInAnyOrder(
                "nx-script-*-*", 
                "nx-script-*-browse", 
                "nx-script-*-read", 
                "nx-script-*-edit",
                "nx-script-*-add", 
                "nx-script-*-delete", 
                "nx-script-*-run"));
      }
    }
  }
}