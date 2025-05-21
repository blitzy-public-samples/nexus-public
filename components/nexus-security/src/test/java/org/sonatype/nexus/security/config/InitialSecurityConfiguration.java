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
package org.sonatype.nexus.security.config;

import java.security.Security;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.nexus.security.config.memory.MemoryCPrivilege.MemoryCPrivilegeBuilder;
import org.sonatype.nexus.security.config.memory.MemoryCRole;

/**
 * Provides initial security configuration for testing purposes.
 * Enhanced for Java 21 compatibility with improved initialization and type inference.
 *
 * @since 3.0
 */
public class InitialSecurityConfiguration
{
  private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

  /**
   * Initializes security properties required for Java 21 compatibility.
   * This ensures that the test security configuration works properly with Java 21's security model.
   */
  private static void initializeSecurityProperties() {
    if (INITIALIZED.compareAndSet(false, true)) {
      // Ensure cryptographic algorithms are properly initialized for tests
      Security.setProperty("crypto.policy", "unlimited");
      
      // Set additional security properties if needed for testing
      // These are test-only settings and should not be used in production
      Security.setProperty("jdk.tls.disabledAlgorithms", "");
    }
  }

  /**
   * Returns a test security configuration with predefined privileges and roles.
   * Uses Java 21 features like enhanced type inference where applicable.
   *
   * @return A memory-based security configuration for testing
   */
  public static MemorySecurityConfiguration getConfiguration() {
    // Initialize security properties for Java 21 compatibility
    initializeSecurityProperties();
    
    // Use var for enhanced type inference (Java 21 feature)
    var privilege1 = new MemoryCPrivilegeBuilder("1-test")
        .type("method")
        .name("1-test")
        .description("")
        .property("method", "read")
        .property("permission", "/some/path/")
        .build();
        
    var privilege2 = new MemoryCPrivilegeBuilder("2-test")
        .type("method")
        .name("2-test")
        .description("")
        .property("method", "read")
        .property("permission", "/some/path/")
        .build();
        
    var testRole = new MemoryCRole()
        .withId("test")
        .withName("test Role")
        .withDescription("Test Role Description")
        .withPrivileges("2-test");
    
    // Create and return the configuration
    return new MemorySecurityConfiguration()
        .withPrivileges(privilege1, privilege2)
        .withRoles(testRole);
  }
}