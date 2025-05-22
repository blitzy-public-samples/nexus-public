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
package org.sonatype.nexus.pax.distribution;

import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.propagateSystemProperty;

import org.ops4j.pax.exam.Option;

/**
 * SPI for assembling Karaf-based Nexus test distributions.
 *
 * @since 3.0
 */
public interface NexusTestDistribution
{
  /**
   * Supported distribution variants.
   */
  enum Distribution
  {
    BASE, OSS, PRO
  }

  /**
   * Returns the priority of this distribution for the given database and variant.
   */
  int priority(TestDatabase database, Distribution distribution);

  /**
   * Returns the options for the given distribution variant.
   */
  Option[] distribution(Distribution distribution);

  /**
   * Configures Nexus for testing.
   */
  default Option configureNexus() {
    return composite(
        // Propagate system property for Virtual Thread support in tests
        propagateSystemProperty("test.virtual.threads"),
        
        // Ensure proper Java 21 VM options are applied
        javaVMCompositeOption()
    );
  }

  /**
   * Returns Java VM options for the test container.
   * 
   * @return Composite option with Java 21 compatible VM settings
   */
  default Option javaVMCompositeOption() {
    return composite();
  }
}