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
package org.sonatype.nexus.internal.web;

import javax.inject.Named;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.internal.metrics.JwtMetricsModule;
import com.google.inject.Binder;

import static org.sonatype.nexus.common.app.FeatureFlags.JWT_ENABLED;

/**
 * Web module to use JWT.
 * 
 * This module is compatible with Java 21 and JWT 4.4.0 library.
 * It supports Virtual Threads for metrics collection and is compatible with Guice 7.0.0.
 *
 * @since 3.38
 */
@Named
@FeatureFlag(name = JWT_ENABLED)
public class JwtWebModule
    extends WebModule
{
  /**
   * Installs the JWT metrics module which is compatible with Java 21 Virtual Threads.
   * The metrics collection will work efficiently with both platform threads and virtual threads.
   * 
   * @param highPriorityBinder the binder to use for installing the metrics module
   */
  @Override
  protected void installMetricsModule(final Binder highPriorityBinder) {
    highPriorityBinder.install(new JwtMetricsModule());
  }
}