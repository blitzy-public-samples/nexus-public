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
package org.sonatype.nexus.repository.httpbridge;

import org.sonatype.nexus.repository.httpbridge.internal.LegacyViewServlet;

/**
 * Legacy view contributor to {@link LegacyViewServlet}.
 * <p>
 * This interface defines a service extension point for contributing format-specific legacy view configurations
 * to the HTTP bridge. Implementations are discovered and injected into the {@link LegacyViewServlet} through
 * the OSGi/Guice dependency injection framework.
 * <p>
 * Implementations should be registered as OSGi services to be properly discovered and utilized.
 *
 * @since 3.7
 * @see LegacyViewConfiguration
 * @see LegacyViewServlet
 */
@FunctionalInterface
public interface LegacyViewContributor
{
  /**
   * Contributes a {@link LegacyViewConfiguration} to the legacy view servlet.
   * <p>
   * This method is called during request processing to determine if a request matches
   * a format-specific legacy URL pattern.
   *
   * @return the legacy view configuration for a specific format
   */
  LegacyViewConfiguration contribute();
}
