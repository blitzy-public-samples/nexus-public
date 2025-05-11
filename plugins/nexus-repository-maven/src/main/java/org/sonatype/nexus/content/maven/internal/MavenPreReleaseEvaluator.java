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
package org.sonatype.nexus.content.maven.internal;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.utils.PreReleaseEvaluator;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;

import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Constants.SNAPSHOT_VERSION_SUFFIX;

/**
 * Maven implementation of the {@link PreReleaseEvaluator} that identifies SNAPSHOT versions.
 *
 * @since 3.38
 * @see PreReleaseEvaluator
 */
@Named(Maven2Format.NAME)
@Singleton
public class MavenPreReleaseEvaluator
    implements PreReleaseEvaluator
{

  /**
   * Determines if a component is a pre-release (SNAPSHOT) version.
   * 
   * @param component the component to evaluate
   * @return true if the component is a SNAPSHOT version
   */
  @Override
  public boolean isPreRelease(final FluentComponent component) {
    // Using Java 21 pattern matching for instanceof to avoid explicit casting
    if (component instanceof Component c) {
      return isPreRelease(c);
    }
    return false;
  }

  /**
   * Determines if a component is a pre-release (SNAPSHOT) version, ignoring the assets.
   * 
   * @param component the component to evaluate
   * @param assets the component's assets (not used in this implementation)
   * @return true if the component is a SNAPSHOT version
   */
  @Override
  public boolean isPreRelease(final Component component, final Iterable<Asset> assets) {
    return isPreRelease(component);
  }

  /**
   * Internal helper method to evaluate if a component is a pre-release version.
   * Uses the Maven-specific base version attribute to determine if it's a SNAPSHOT.
   *
   * @param component the component to evaluate
   * @return true if the component is a SNAPSHOT version
   */
  private static boolean isPreRelease(final Component component) {
    String baseVersion = component.attributes().child(Maven2Format.NAME).get(P_BASE_VERSION, String.class);
    if (baseVersion == null) {
      return false;
    }
    // Check if the version ends with the SNAPSHOT suffix
    return baseVersion.endsWith(SNAPSHOT_VERSION_SUFFIX);
  }
}