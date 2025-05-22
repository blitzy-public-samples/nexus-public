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
package org.sonatype.nexus.jmx.reflect;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.jmx.ObjectNameEntry;

/**
 * Example managed object that demonstrates JMX annotations compatible with Java 21's enhanced encapsulation rules.
 * <p>
 * This class serves as a test case for verifying that JMX annotations work correctly with Java 21's reflection system,
 * which has stricter access controls compared to previous Java versions.
 * <p>
 * The annotations used here (ManagedObject, ManagedAttribute, ManagedOperation, ObjectNameEntry) are designed to be
 * compatible with Java 21's reflection system by ensuring they are properly accessible at runtime.
 */
@Named
@Singleton
@ManagedObject(
    domain = "org.sonatype.nexus.jmx",
    entries = {
        @ObjectNameEntry(name="foo", value="bar")
    },
    description = "Example managed object for Java 21 compatibility testing"
)
public class ExampleManagedObject
{
  private String name;

  private String password;

  // R/W attribute - demonstrates ManagedAttribute annotation compatibility with Java 21 reflection

  /**
   * Get name attribute - demonstrates a readable ManagedAttribute that is compatible with Java 21's reflection system.
   * 
   * @return The name value
   */
  @ManagedAttribute
  public String getName() {
    return name;
  }

  /**
   * Set name attribute - demonstrates a writable ManagedAttribute that is compatible with Java 21's reflection system.
   * 
   * @param name The name value to set
   */
  @ManagedAttribute
  public void setName(final String name) {
    this.name = name;
  }

  // W-only attribute - demonstrates write-only attribute pattern with Java 21 compatibility

  /**
   * Get password - not exposed as a managed attribute for security reasons.
   * 
   * @return The password value
   */
  public String getPassword() {
    return password;
  }

  /**
   * Set password - demonstrates a write-only ManagedAttribute that is compatible with Java 21's reflection system.
   * 
   * @param password The password value to set
   */
  @ManagedAttribute(
      description = "Set password (write-only attribute)"
  )
  public void setPassword(final String password) {
    this.password = password;
  }

  // Operation - demonstrates ManagedOperation annotation compatibility with Java 21 reflection

  /**
   * Reset name operation - demonstrates a ManagedOperation that is compatible with Java 21's reflection system.
   * This method can be invoked through JMX to reset the name attribute to null.
   */
  @ManagedOperation(
      description = "Reset name to null"
  )
  public void resetName() {
    this.name = null;
  }
}