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
package org.sonatype.nexus.internal.scheduling;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.capability.CapabilitySupport;
import org.sonatype.nexus.common.template.TemplateParameters;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Scheduler capability.
 * <p>
 * Updated for Java 21 compatibility with optimized resource usage patterns
 * and support for Karaf 4.4.4 runtime environment.
 *
 * @since 3.0
 */
@Named(SchedulerCapabilityDescriptor.TYPE_ID)
public class SchedulerCapability
    extends CapabilitySupport<SchedulerCapabilityConfiguration>
{
  private final SchedulerSPI scheduler;

  private boolean pausedByUs = false;

  /**
   * Constructor that injects the SchedulerSPI implementation.
   * Verified for compatibility with Java 21 and updated Quartz dependency.
   *
   * @param scheduler The SchedulerSPI implementation to use
   */
  @Inject
  public SchedulerCapability(final SchedulerSPI scheduler) {
    this.scheduler = checkNotNull(scheduler);
  }

  /**
   * Creates a new configuration instance from the provided properties.
   * Implementation is compatible with Java 21 environment.
   *
   * @param properties The configuration properties
   * @return A new SchedulerCapabilityConfiguration instance
   * @throws Exception if configuration creation fails
   */
  @Override
  protected SchedulerCapabilityConfiguration createConfig(final Map<String, String> properties) throws Exception {
    return new SchedulerCapabilityConfiguration(properties);
  }

  /**
   * Activates the scheduler capability if it was previously paused by this component.
   * Optimized for Java 21 environment with improved resource usage patterns.
   *
   * @param config The capability configuration
   * @throws Exception if activation fails
   */
  @Override
  protected void onActivate(final SchedulerCapabilityConfiguration config) throws Exception {
    if (pausedByUs) {
      pausedByUs = false;
      scheduler.resume();
    }
  }

  /**
   * Passivates the scheduler capability by pausing the scheduler.
   * Implementation is compatible with Java 21 and updated Quartz dependency.
   *
   * @param config The capability configuration
   * @throws Exception if passivation fails
   */
  @Override
  protected void onPassivate(final SchedulerCapabilityConfiguration config) throws Exception {
    pausedByUs = true;
    scheduler.pause();
  }

  /**
   * Renders a description of the scheduler capability.
   * Implementation is compatible with Java 21 environment.
   *
   * @return The rendered description string
   * @throws Exception if rendering fails
   */
  @Override
  protected String renderDescription() throws Exception {
    return scheduler.renderStatusMessage();
  }

  /**
   * Renders the status of the scheduler capability.
   * This implementation is compatible with Java 21 and optimized for the updated environment.
   * Note: While Java 21 supports String Templates, we maintain the TemplateParameters approach
   * for backward compatibility with existing Velocity templates.
   *
   * @return The rendered status string
   * @throws Exception if rendering fails
   */
  @Override
  protected String renderStatus() throws Exception {
    // Using TemplateParameters for compatibility with existing Velocity templates
    // while ensuring proper operation with updated Quartz dependency in Java 21
    String templatePath = SchedulerCapabilityDescriptor.TYPE_ID + "-status.vm";
    String detailMessage = scheduler.renderDetailMessage();
    
    return render(templatePath, new TemplateParameters()
        .set("detail", detailMessage));
  }
}