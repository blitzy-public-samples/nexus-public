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
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link ScriptSecurityContributor} running in Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class ScriptSecurityContributorTest
    extends VirtualThreadTestSupport
{
  private ScriptSecurityContributor underTest;

  @BeforeEach
  public void setup() {
    assumeVirtualThreadSupported();
    underTest = new ScriptSecurityContributor();
  }

  /**
   * Verifies that the security contributor returns the expected configuration
   * when executed in a Virtual Thread.
   */
  @Test
  public void testGetContributionInVirtualThread() throws Exception {
    SecurityConfiguration config = callVirtual(() -> underTest.getContribution());
    
    assertThat(config, notNullValue());
    assertThat(config.getUsers().size(), is(0));
    assertThat(config.getUserRoleMappings().size(), is(0));
    assertThat(config.getRoles().size(), is(0));
    assertThat(config.getPrivileges().stream().map(CPrivilege::getId).collect(toList()),
        containsInAnyOrder("nx-script-*-*", "nx-script-*-browse", "nx-script-*-read", "nx-script-*-edit",
            "nx-script-*-add", "nx-script-*-delete", "nx-script-*-run"));
  }

  /**
   * Verifies that the security contributor can be accessed concurrently from multiple
   * Virtual Threads without issues.
   */
  @Test
  public void testConcurrentAccessInVirtualThreads() throws Exception {
    // Create a callable that retrieves the security configuration
    Callable<List<String>> getPrivilegeIds = () -> {
      SecurityConfiguration config = underTest.getContribution();
      return config.getPrivileges().stream()
          .map(CPrivilege::getId)
          .collect(Collectors.toList());
    };
    
    // Execute the callable concurrently in multiple Virtual Threads
    int threadCount = 10;
    Future<List<String>>[] results = callConcurrently(threadCount, () -> getPrivilegeIds);
    
    // Verify that all threads received the same correct configuration
    for (Future<List<String>> future : results) {
      List<String> privilegeIds = future.get();
      assertThat(privilegeIds, containsInAnyOrder(
          "nx-script-*-*", "nx-script-*-browse", "nx-script-*-read", "nx-script-*-edit",
          "nx-script-*-add", "nx-script-*-delete", "nx-script-*-run"));
    }
  }

  /**
   * Verifies that the security contributor is thread-safe by having multiple
   * Virtual Threads access it simultaneously and checking for consistency.
   */
  @Test
  public void testThreadSafetyInVirtualThreads() throws Exception {
    // Reference to hold any inconsistent configuration that might be detected
    AtomicReference<SecurityConfiguration> inconsistentConfig = new AtomicReference<>();
    
    // Run a task that repeatedly checks the configuration for consistency
    Runnable consistencyChecker = () -> {
      for (int i = 0; i < 100; i++) {
        SecurityConfiguration config = underTest.getContribution();
        
        // Check that the configuration is consistent
        boolean isConsistent = 
            config.getUsers().size() == 0 &&
            config.getUserRoleMappings().size() == 0 &&
            config.getRoles().size() == 0 &&
            config.getPrivileges().size() == 7;
        
        if (!isConsistent) {
          inconsistentConfig.set(config);
          break;
        }
      }
    };
    
    // Run the consistency checker concurrently in multiple Virtual Threads
    runConcurrently(5, consistencyChecker);
    
    // Verify that no inconsistent configuration was detected
    assertThat("An inconsistent security configuration was detected", 
        inconsistentConfig.get(), is(null));
  }
}