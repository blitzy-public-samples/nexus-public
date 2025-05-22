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

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.MavenUrlReference;

import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.propagateSystemProperty;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.vmOption;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFileExtend;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;

/**
 * BASE distribution for Nexus testing.
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
    List<Option> options = new ArrayList<>();

    // Add base configuration options
    options.add(karafDistributionConfiguration());
    
    // Add bundle provisioning options
    options.add(provisionBundles());
    
    // Add feature installation options
    options.add(installFeatures());
    
    // Add configuration edit options
    options.add(editConfigurations());
    
    // Add Nexus configuration options
    options.add(configureNexus());
    
    // Add Java VM options with Java 21 compatibility
    options.add(javaVMCompositeOption());
    
    // Add Virtual Thread support
    options.add(propagateSystemProperty("test.virtual.threads"));
    
    return options.toArray(new Option[0]);
  }

  /**
   * Provisions required bundles for the test container.
   */
  protected Option provisionBundles() {
    return composite(
        mavenBundle("org.sonatype.nexus", "nexus-base-template"),
        wrappedBundle(maven("org.example", "example-bundle").versionAsInProject())
    );
  }

  /**
   * Installs required features in the test container.
   */
  protected Option installFeatures() {
    MavenUrlReference featuresUrl = maven()
        .groupId("org.sonatype.nexus")
        .artifactId("nexus-features")
        .classifier("features")
        .type("xml")
        .versionAsInProject();

    return composite(
        features(featuresUrl, "nexus-repository-content-test-support"),
        features(featuresUrl, "nexus-repository-test-support")
    );
  }

  /**
   * Edits configuration files in the test container.
   */
  protected Option editConfigurations() {
    return composite(
        editConfigurationFileExtend("etc/system.properties", "org.osgi.framework.system.packages.extra",
            "sun.misc"),
        editConfigurationFileExtend("etc/nexus.properties", "nexus.test.mode", "true")
    );
  }

  @Override
  public Option javaVMCompositeOption() {
    return composite(
        // Java 21 compatible VM options
        vmOption("-XX:+UseZGC"),
        vmOption("-XX:+ZGenerational"),
        vmOption("-Djdk.virtualThreadScheduler.parallelism=16"),
        vmOption("-Djdk.virtualThreadScheduler.maxPoolSize=256"),
        vmOption("-Djdk.tracePinnedThreads=full"),
        
        // System properties for Java 21 compatibility
        systemProperty("java.awt.headless").value("true"),
        systemProperty("java.net.preferIPv4Stack").value("true"),
        systemProperty("java.util.logging.config.file").value("etc/java.util.logging.properties")
    );
  }
}