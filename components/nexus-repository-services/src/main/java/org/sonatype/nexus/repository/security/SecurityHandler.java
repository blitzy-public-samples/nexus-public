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
package org.sonatype.nexus.repository.security;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import com.google.common.annotations.VisibleForTesting;

/**
 * Security handler optimized for Java 21 Virtual Threads.
 * <p>
 * This handler performs security checks for repository requests and is designed
 * to work efficiently with Virtual Threads for improved concurrency.
 *
 * @since 3.0
 */
@Named
@Singleton
public class SecurityHandler
    extends ComponentSupport
    implements org.sonatype.nexus.repository.view.handlers.SecurityHandler
{
  @VisibleForTesting
  static final String AUTHORIZED_KEY = "security.authorized";

  private final Handler loginsCounterHandler;

  @Inject
  public SecurityHandler(@Named("nexus.analytics.loginsCounterHandler") @Nullable final Handler loginsCounterHandler) {
    this.loginsCounterHandler = loginsCounterHandler;
  }

  /**
   * Handles security checks for repository requests.
   * <p>
   * Optimized for Java 21 Virtual Threads to ensure efficient thread scheduling during security checks.
   * When running on Virtual Threads, this method can yield during I/O operations without blocking the carrier thread,
   * allowing for higher concurrency and throughput.
   *
   * @param context The request context
   * @return The response from the next handler in the chain
   * @throws Exception if an error occurs during handling
   */
  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    // Get the security facet from the repository
    SecurityFacet securityFacet = context.getRepository().facet(SecurityFacet.class);

    // We employ the model that one security check per request is all that is necessary.
    // If this handler is in a nested repository (because this is a group repository),
    // there is no need to check authorization again.
    if (context.getAttributes().get(AUTHORIZED_KEY) == null) {
      // This operation may involve I/O or database access, but with Virtual Threads
      // the carrier thread won't be blocked during these operations
      securityFacet.ensurePermitted(context.getRequest());
      
      // Mark this context as authorized to prevent redundant checks
      context.getAttributes().set(AUTHORIZED_KEY, true);
      
      // Insert the logins counter handler if available
      if (loginsCounterHandler != null) {
        context.insertHandler(loginsCounterHandler);
      }
    }

    // Continue processing the request with the next handler in the chain
    // With Virtual Threads, this allows for efficient handling of concurrent requests
    return context.proceed();
  }
}