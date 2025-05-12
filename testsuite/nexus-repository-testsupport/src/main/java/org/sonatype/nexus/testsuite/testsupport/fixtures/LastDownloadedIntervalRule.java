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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.time.Duration;

import javax.inject.Provider;

import org.sonatype.nexus.repository.capability.GlobalRepositorySettings;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Jupiter extension for managing the lastDownloadedInterval setting in GlobalRepositorySettings.
 * This extension saves the current value before tests run and restores it after tests complete.
 * 
 * @since 3.41
 */
public class LastDownloadedIntervalRule
    implements BeforeEachCallback, AfterEachCallback
{
  private final Provider<GlobalRepositorySettings> repositorySettings;

  private Duration lastDownloadedInterval;

  /**
   * Constructor.
   * 
   * @param repositorySettings provider for repository settings
   */
  public LastDownloadedIntervalRule(final Provider<GlobalRepositorySettings> repositorySettings) {
    this.repositorySettings = repositorySettings;
  }

  @Override
  public void beforeEach(final ExtensionContext context) {
    lastDownloadedInterval = repositorySettings.get().getLastDownloadedInterval();
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    repositorySettings.get().setLastDownloadedInterval(lastDownloadedInterval);
  }

  /**
   * Sets the lastDownloadedInterval to the specified duration during test execution.
   * The original value will be restored after the test completes.
   * 
   * @param duration the duration to set
   */
  public void setLastDownloadedInterval(final Duration duration) {
    repositorySettings.get().setLastDownloadedInterval(duration);
  }
}