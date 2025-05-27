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
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.MavenUrlReference;
import org.sonatype.nexus.pax.exam.NexusPaxExamSupport;
import org.sonatype.nexus.pax.exam.TestDatabase;

import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.propagateSystemProperty;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFile;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFileExtend;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFilePut;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.replaceConfigurationFile;
import static org.sonatype.nexus.pax.exam.NexusPaxExamSupport.NEXUS_PAX_EXAM_TIMEOUT_KEY;
import static org.sonatype.nexus.pax.exam.NexusPaxExamSupport.NEXUS_PAX_EXAM_TIMEOUT_DEFAULT;
import static org.sonatype.nexus.pax.exam.NexusPaxExamSupport.resolveBaseFile;

/**
 * Base {@link NexusTestDistribution} implementation.
 *
 * @since 3.0
 */
public class BaseNexusTestDistribution
    implements NexusTestDistribution
{
  @Override
  public int priority(final TestDatabase database, final Distribution variant) {
    return variant == Distribution.BASE ? 0 : -1;
  }

  @Override
  public Option[] distribution(final Distribution variant) {
    List<Option> options = new ArrayList<>();

    // add standard configuration
    options.add(configureNexus());

    // add common distribution options
    options.add(systemProperty("nexus-base-template", resolveBaseFile("target/nexus-base-template").getAbsolutePath()));

    // add nexus-base-template bundle
    options.add(mavenBundle("org.sonatype.nexus.assemblies", "nexus-base-template").versionAsInProject().type("zip"));

    // add repository content and repository test-support features
    MavenUrlReference nexusFeatures = maven("org.sonatype.nexus.assemblies", "nexus-base-template")
        .versionAsInProject().classifier("features").type("xml");
    options.add(features(nexusFeatures, "nexus-repository-content", "nexus-repository-test-support"));

    // add test-specific configuration
    options.add(editConfigurationFileExtend("etc/nexus-default.properties", "nexus.loadAsOSS", "true"));
    options.add(editConfigurationFileExtend("etc/nexus-default.properties", "nexus.security.randompassword", "false"));
    options.add(editConfigurationFileExtend("etc/nexus-default.properties", "nexus.scripts.allowCreation", "true"));
    options.add(editConfigurationFileExtend("etc/nexus-default.properties", "nexus.onboarding.enabled", "false"));
    options.add(editConfigurationFileExtend("etc/nexus-default.properties", "nexus.react.enabled", "false"));

    // add PAX-EXAM configuration
    options.add(systemProperty(NEXUS_PAX_EXAM_TIMEOUT_KEY).value(NEXUS_PAX_EXAM_TIMEOUT_DEFAULT));

    // add JVM options
    options.add(NexusPaxExamSupport.javaVMOption());
    
    // add propagation of test.virtual.threads system property to enable Virtual Thread support in tests
    options.add(propagateSystemProperty("test.virtual.threads"));
    
    // ensure javaVMCompositeOption() is properly called for Java 21 compatibility
    options.add(javaVMCompositeOption());

    return options.toArray(new Option[options.size()]);
  }
}