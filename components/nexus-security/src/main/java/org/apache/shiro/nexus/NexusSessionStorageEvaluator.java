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
package org.apache.shiro.nexus;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.security.anonymous.AnonymousHelper;

import org.apache.shiro.mgt.SessionStorageEvaluator;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.mgt.DefaultWebSessionStorageEvaluator;

/**
 * Custom {@link SessionStorageEvaluator} for Nexus that disables session storage for anonymous subjects
 * and respects the global session configuration.
 *
 * @since 3.0
 */
public class NexusSessionStorageEvaluator
  extends DefaultWebSessionStorageEvaluator
{
  /**
   * Flag indicating whether sessions are enabled globally.
   * Injected from system property or configuration with default of true.
   */
  @Inject
  @Named("${nexus.session.enabled:-true}")
  private volatile boolean sessionsEnabled;

  /**
   * Determines if session storage is enabled for the given subject.
   * <p>
   * Session storage is disabled for anonymous subjects to improve performance and reduce resource usage.
   * If sessions are globally disabled via configuration, this method always returns false.
   *
   * @param subject the subject to check
   * @return true if session storage is enabled for the subject, false otherwise
   */
  @Override
  public boolean isSessionStorageEnabled(final Subject subject) {
    if (!sessionsEnabled) {
      return false;
    }
    
    // Disable session storage for anonymous subjects
    if (subject != null && AnonymousHelper.isAnonymous(subject)) {
      return false;
    }
    
    return super.isSessionStorageEnabled(subject);
  }
}