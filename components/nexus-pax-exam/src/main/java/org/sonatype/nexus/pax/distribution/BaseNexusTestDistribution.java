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
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.propagateSystemProperty;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFileExtend;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;

import org.ops4j.pax.exam.Option;

/**
 * Base implementation of {@link NexusTestDistribution} that provides core Nexus functionality.
 *
 * @since 3.0
 */
public class BaseNexusTestDistribution
    implements NexusTestDistribution
{
  @Override
  public int priority(final TestDatabase database, final Distribution distribution) {
    return distribution == Distribution.BASE ? 0 : -1;
  }

  @Override
  public Option[] distribution(final Distribution distribution) {
    return new Option[] {
        // Configure Nexus for testing
        configureNexus(),
        
        // Provision core bundles
        mavenBundle("org.sonatype.nexus", "nexus-base-template"),
        
        // Edit configuration files
        editConfigurationFileExtend("etc/system.properties", "nexus.loadAsOSS", "true"),
        
        // Install features
        features("mvn:org.sonatype.nexus/nexus-repository-content-testsupport/*/xml/features"),
        features("mvn:org.sonatype.nexus/nexus-repository-testsupport/*/xml/features"),
        
        // Enable Virtual Thread support in tests
        propagateSystemProperty("test.virtual.threads"),
        
        // Apply Java 21 compatible VM options
        javaVMCompositeOption()
    };
  }
  
  @Override
  public Option javaVMCompositeOption() {
    return composite(
        // Java 21 specific VM options
        propagateSystemProperty("java.version"),
        propagateSystemProperty("java.vm.version"),
        propagateSystemProperty("java.vm.vendor")
    );
  }
}