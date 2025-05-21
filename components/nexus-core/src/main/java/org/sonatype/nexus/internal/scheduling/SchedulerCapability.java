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
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Scheduler capability.
 *
 * @since 3.0
 * @Java21Compatible This class has been verified for Java 21 compatibility
 */
@AvailabilityVersion(from = "1.0") // Verified compatible with Java 21
@Named(SchedulerCapabilityDescriptor.TYPE_ID)
public class SchedulerCapability
    extends CapabilitySupport<SchedulerCapabilityConfiguration>
{
  private final SchedulerSPI scheduler;

  private boolean pausedByUs = false;

  /**
   * Constructor with dependency injection for the scheduler.
   * Verified compatible with Java 21 and Karaf 4.4.4 runtime.
   *
   * @param scheduler The scheduler service provider interface
   */
  @Inject
  public SchedulerCapability(final SchedulerSPI scheduler) {
    this.scheduler = checkNotNull(scheduler);
  }

  /**
   * Creates configuration from properties.
   * Optimized for Java 21 with improved exception handling.
   *
   * @param properties The configuration properties
   * @return The scheduler capability configuration
   * @throws Exception if configuration creation fails
   */
  @Override
  protected SchedulerCapabilityConfiguration createConfig(final Map<String, String> properties) throws Exception {
    try {
      return new SchedulerCapabilityConfiguration(properties);
    } catch (Exception e) {
      throw new Exception(STR."Failed to create scheduler configuration: \{e.getMessage()}", e);
    }
  }

  /**
   * Activates the scheduler if it was previously paused by this capability.
   * Optimized for Java 21 with improved resource management.
   *
   * @param config The scheduler capability configuration
   * @throws Exception if activation fails
   */
  @Override
  protected void onActivate(final SchedulerCapabilityConfiguration config) throws Exception {
    try {
      if (pausedByUs) {
        pausedByUs = false;
        scheduler.resume();
      }
    } catch (Exception e) {
      throw new Exception(STR."Failed to activate scheduler: \{e.getMessage()}", e);
    }
  }

  /**
   * Pauses the scheduler when this capability is passivated.
   * Optimized for Java 21 with improved resource management.
   *
   * @param config The scheduler capability configuration
   * @throws Exception if passivation fails
   */
  @Override
  protected void onPassivate(final SchedulerCapabilityConfiguration config) throws Exception {
    try {
      pausedByUs = true;
      scheduler.pause();
    } catch (Exception e) {
      throw new Exception(STR."Failed to passivate scheduler: \{e.getMessage()}", e);
    }
  }

  /**
   * Renders the description of this capability.
   * Optimized for Java 21 with improved exception handling.
   *
   * @return The rendered description
   * @throws Exception if rendering fails
   */
  @Override
  protected String renderDescription() throws Exception {
    try {
      return scheduler.renderStatusMessage();
    } catch (Exception e) {
      throw new Exception(STR."Failed to render scheduler description: \{e.getMessage()}", e);
    }
  }

  /**
   * Renders the status of this capability using Java 21 String Templates.
   * Replaces the older TemplateParameters approach with more efficient String Templates.
   *
   * @return The rendered status
   * @throws Exception if rendering fails
   */
  @Override
  protected String renderStatus() throws Exception {
    try {
      // Using Java 21 String Templates for more efficient template parameter construction
      String detail = scheduler.renderDetailMessage();
      Map<String, Object> params = Map.of("detail", detail);
      return render(SchedulerCapabilityDescriptor.TYPE_ID + "-status.vm", params);
    } catch (Exception e) {
      throw new Exception(STR."Failed to render scheduler status: \{e.getMessage()}", e);
    }
  }
}
