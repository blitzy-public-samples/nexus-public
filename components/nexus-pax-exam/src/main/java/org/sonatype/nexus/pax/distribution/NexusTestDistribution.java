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
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.propagateSystemProperty;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFileExtend;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.replaceConfigurationFile;

import java.io.File;

import org.ops4j.pax.exam.Option;
import org.sonatype.nexus.pax.exam.TestDatabase;
import org.ops4j.pax.exam.options.WrappedUrlProvisionOption.OverwriteMode;


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
  int priority(TestDatabase database, Distribution variant);

  /**
   * Returns the options for this distribution.
   */
  Option[] distribution(Distribution variant);

  /**
   * Returns options to configure Nexus.
   */
  default Option[] configureNexus() {
    return new Option[] {
        // merge system properties
        editConfigurationFileExtend("etc/system.properties", "org.ops4j.pax.url.mvn.repositories",
            "https://repo1.maven.org/maven2@id=central"),

        // merge nexus properties
        editConfigurationFileExtend("etc/nexus.properties", "nexus.security.randompassword", "false"),
        editConfigurationFileExtend("etc/nexus.properties", "nexus.onboarding.enabled", "false"),
        editConfigurationFileExtend("etc/nexus.properties", "nexus.scripts.allowCreation", "true"),

        // install OSGi bundles
        mavenBundle("org.apache.aries.spifly", "org.apache.aries.spifly.dynamic.bundle").versionAsInProject(),
        wrappedBundle(maven("org.ow2.asm", "asm").versionAsInProject()),
        wrappedBundle(maven("org.ow2.asm", "asm-commons").versionAsInProject()),
        wrappedBundle(maven("org.ow2.asm", "asm-util").versionAsInProject()),
        wrappedBundle(maven("org.ow2.asm", "asm-tree").versionAsInProject()),
        wrappedBundle(maven("org.ow2.asm", "asm-analysis").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-ant").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-cli-commons").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-cli-picocli").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-console").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-datetime").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-docgenerator").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-groovydoc").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-groovysh").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-jmx").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-json").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-jsr223").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-macro").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-nio").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-servlet").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-sql").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-swing").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-templates").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-test").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-test-junit5").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-testng").versionAsInProject()),
        wrappedBundle(maven("org.apache.groovy", "groovy-xml").versionAsInProject()),

        // Awaitility
        wrappedBundle(maven("org.awaitility", "awaitility").versionAsInProject()).overwriteManifest(
            OverwriteMode.MERGE),

        // propagate system property for virtual thread support in tests
        propagateSystemProperty("test.virtual.threads"),

        // Java VM options
        javaVMCompositeOption()
    };
  }

  /**
   * Returns composite option for Java VM options.
   */
  default Option javaVMCompositeOption() {
    return composite(
        systemProperty("java.awt.headless").value( "true"),
        systemProperty("java.net.preferIPv4Stack").value("true")
    );
  }
}