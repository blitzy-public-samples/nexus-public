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
package org.sonatype.nexus.extender;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NexusContextListenerTest
{
  final boolean isFeatureFlagEnabled;

  private final String installMode;

  private final String flag;

  private final Boolean flagValue;

  private final String edition;

  private final NexusContextListener underTest;

  public NexusContextListenerTest(
      final String installMode,
      final String flag,
      final Boolean flagValue,
      final String edition,
      final boolean isFeatureFlagEnabled)
  {
    this.installMode = installMode;
    this.flag = flag;
    this.flagValue = flagValue;
    this.edition = edition;
    this.isFeatureFlagEnabled = isFeatureFlagEnabled;

    NexusBundleExtender bundleExtender = mock(NexusBundleExtender.class);
    when(bundleExtender.getBundleContext()).thenReturn(mock(BundleContext.class));
    underTest = new NexusContextListener(bundleExtender);
  }

  @BeforeEach
  public void setup() {
    System.clearProperty(flag);
    if (flagValue != null) {
      System.setProperty(flag, String.valueOf(flagValue));
    }
  }

  @ParameterizedTest
  @MethodSource("provideTestData")
  void isFeatureFlagEnabled() {
    boolean enabled = underTest.isFeatureFlagEnabled(edition, installMode);
    assertThat(enabled, is(isFeatureFlagEnabled));
  }

  static Stream<Arguments> provideTestData() {
    return Stream.of(
        Arguments.of("oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true),
        Arguments.of("oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true),
        Arguments.of("oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true),
        Arguments.of("oss,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", false),
        Arguments.of("oss,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true),
        Arguments.of("pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false),
        Arguments.of("pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true),
        Arguments.of("pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true),
        Arguments.of("community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false),
        Arguments.of("community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true),
        Arguments.of("featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true),

        Arguments.of("oss:featureFlag:foo.enabled", "foo.enabled", true, "OSS", true),
        Arguments.of("oss:featureFlag:foo.enabled", "foo.enabled", true, "PRO", false),
        Arguments.of("oss:featureFlag:foo.enabled", "foo.enabled", false, "OSS", false),
        Arguments.of("oss:featureFlag:foo.enabled", "foo.enabled", false, "PRO", false),
        Arguments.of("oss:featureFlag:foo.enabled", "foo.enabled", null, "OSS", false),
        Arguments.of("oss:featureFlag:foo.enabled", "foo.enabled", null, "PRO", false),
        Arguments.of("oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true),
        Arguments.of("oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", false),
        Arguments.of("oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false),
        Arguments.of("oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "PRO", false),
        Arguments.of("oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", true),
        Arguments.of("oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "PRO", false),
        // pro-only feature flag
        Arguments.of("pro:featureFlag:foo.enabled", "foo.enabled", true, "OSS", false),
        Arguments.of("pro:featureFlag:foo.enabled", "foo.enabled", true, "PRO", true),
        Arguments.of("pro:featureFlag:foo.enabled", "foo.enabled", false, "OSS", false),
        Arguments.of("pro:featureFlag:foo.enabled", "foo.enabled", false, "PRO", false),
        Arguments.of("pro:featureFlag:foo.enabled", "foo.enabled", null, "OSS", false),
        Arguments.of("pro:featureFlag:foo.enabled", "foo.enabled", null, "PRO", false),
        Arguments.of("pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false),
        Arguments.of("pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true),
        Arguments.of("pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false),
        Arguments.of("pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "PRO", false),
        Arguments.of("pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", false),
        Arguments.of("pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "PRO", true),
        // common feature flag
        Arguments.of("featureFlag:foo.enabled", "foo.enabled", true, "OSS", true),
        Arguments.of("featureFlag:foo.enabled", "foo.enabled", true, "PRO", true),
        Arguments.of("featureFlag:foo.enabled", "foo.enabled", false, "OSS", false),
        Arguments.of("featureFlag:foo.enabled", "foo.enabled", false, "PRO", false),
        Arguments.of("featureFlag:foo.enabled", "foo.enabled", null, "OSS", false),
        Arguments.of("featureFlag:foo.enabled", "foo.enabled", null, "PRO", false),
        Arguments.of("featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true),
        Arguments.of("featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true),
        Arguments.of("featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false),
        Arguments.of("featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "PRO", false),
        Arguments.of("featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", true),
        Arguments.of("featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "PRO", true),
        // malformed feature flag strings
        Arguments.of("featureFlag:", "foo.enabled", null, "OSS", false),
        Arguments.of("featureFlag:", "foo.enabled", null, "PRO", false),
        Arguments.of("featureFlag:enabledByDefault:", "foo.enabled", null, "OSS", false),
        Arguments.of("foo:featureFlag:enabledByDefault:", "foo.enabled", null, "OSS", false),
        Arguments.of("fooFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", false),
        Arguments.of("featureFlag:", "foo.enabled", true, "OSS", false),
        Arguments.of("featureFlag:", "foo.enabled", true, "PRO", false),
        Arguments.of("featureFlag:enabledByDefault:", "foo.enabled", true, "OSS", false),
        Arguments.of("foo:featureFlag:enabledByDefault:", "foo.enabled", true, "OSS", false),
        Arguments.of("fooFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false),
        Arguments.of("featureFlag:", "foo.enabled", false, "OSS", false),
        Arguments.of("featureFlag:", "foo.enabled", false, "PRO", false),
        Arguments.of("featureFlag:enabledByDefault:", "foo.enabled", false, "OSS", false),
        Arguments.of("foo:featureFlag:enabledByDefault:", "foo.enabled", false, "OSS", false),
        Arguments.of("fooFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false),
        Arguments.of("", "foo.enabled", null, "OSS", false)
    );
  }
}