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
 * Example class demonstrating JMX annotations for testing compatibility with Java 21's reflection system.
 * This class verifies that JMX annotations work correctly with Java 21's enhanced encapsulation rules.
 * 
 * @since 3.0
 */
@Named
@Singleton
@ManagedObject(
    domain = "org.sonatype.nexus.jmx",
    entries = {
        @ObjectNameEntry(name="foo", value="bar")
    },
    description = "Example managed object for testing Java 21 compatibility"
)
public class ExampleManagedObject
{
  private String name;

  private String password;

  /**
   * Read accessor for the 'name' attribute.
   * Tests {@link ManagedAttribute} annotation with Java 21 reflection.
   *
   * @return The current name value
   */
  @ManagedAttribute(description = "Get the current name value")
  public String getName() {
    return name;
  }

  /**
   * Write accessor for the 'name' attribute.
   * Tests {@link ManagedAttribute} annotation with Java 21 reflection.
   *
   * @param name The name value to set
   */
  @ManagedAttribute(description = "Set the name value")
  public void setName(final String name) {
    this.name = name;
  }

  // W-only attribute - intentionally not exposing getter via JMX

  /**
   * Read accessor for the 'password' attribute.
   * Not exposed via JMX to test selective attribute exposure.
   *
   * @return The current password value
   */
  public String getPassword() {
    return password;
  }

  /**
   * Write accessor for the 'password' attribute.
   * Tests {@link ManagedAttribute} annotation with write-only attribute pattern.
   *
   * @param password The password value to set
   */
  @ManagedAttribute(
      description = "Set password (write-only attribute)"
  )
  public void setPassword(final String password) {
    this.password = password;
  }

  /**
   * Operation to reset the name attribute to null.
   * Tests {@link ManagedOperation} annotation with Java 21 reflection.
   */
  @ManagedOperation(
      description = "Reset name to null",
      impact = javax.management.MBeanOperationInfo.ACTION
  )
  public void resetName() {
    this.name = null;
  }
}
