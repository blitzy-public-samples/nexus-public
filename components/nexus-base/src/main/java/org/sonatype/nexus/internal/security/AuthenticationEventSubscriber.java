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
package org.sonatype.nexus.internal.security;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.ClientInfo;
import org.sonatype.nexus.security.ClientInfoProvider;
import org.sonatype.nexus.security.authc.AuthenticationEvent;
import org.sonatype.nexus.security.authc.NexusAuthenticationEvent;

import com.google.common.eventbus.Subscribe;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Forwards {@link AuthenticationEvent} as {@link NexusAuthenticationEvent}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class AuthenticationEventSubscriber
    implements EventAware
{
  private static final Logger logger = Logger.getLogger(AuthenticationEventSubscriber.class.getName());
  
  // Executor for handling authentication events with virtual threads
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  
  private final Provider<EventManager> eventManager;

  private final Provider<ClientInfoProvider> clientInfoProvider;

  @Inject
  public AuthenticationEventSubscriber(
      final Provider<EventManager> eventManager,
      final Provider<ClientInfoProvider> clientInfoProvider)
  {
    this.eventManager = checkNotNull(eventManager);
    this.clientInfoProvider = checkNotNull(clientInfoProvider);
  }

  /**
   * Handles authentication events and forwards them as NexusAuthenticationEvents.
   * Uses Virtual Threads for processing to improve scalability and performance.
   * 
   * @param event The authentication event to process
   */
  @Subscribe
  public void on(final AuthenticationEvent event) {
    // Use virtual threads to handle the event processing
    virtualThreadExecutor.execute(() -> {
      // Capture thread context for virtual thread execution
      final ClientInfo clientInfo = clientInfoProvider.get().getCurrentThreadClientInfo();
      
      // Using Java 21 String Templates for logging
      logger.fine(STR."Processing authentication event for user: \{event.getUserId()}, success: \{event.isSuccessful()}");
      
      ClientInfo.Builder builder = ClientInfo
          .builder()
          .userId(event.getUserId());
      
      // Using pattern matching to check clientInfo properties
      if (clientInfo instanceof ClientInfo info) {
        builder
            .remoteIP(info.getRemoteIP())
            .userAgent(info.getUserAgent())
            .path(info.getPath());
            
        // Using String Templates for more efficient logging
        logger.fine(STR."Client info: IP=\{info.getRemoteIP()}, Agent=\{info.getUserAgent()}, Path=\{info.getPath()}");
      }
      
      // Post the event using the EventManager provider which properly handles thread-local inheritance
      eventManager.get()
          .post(new NexusAuthenticationEvent(
              builder.build(),
              event.isSuccessful(),
              event.getAuthenticationFailureReasons()));
    });
  }
}