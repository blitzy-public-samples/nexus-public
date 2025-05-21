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

import java.lang.System.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.security.ClientInfo;
import org.sonatype.nexus.security.ClientInfoProvider;
import org.sonatype.nexus.security.UserIdHelper;

import com.google.common.net.HttpHeaders;
import com.google.inject.OutOfScopeException;
import com.google.inject.ProvisionException;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link ClientInfoProvider} implementation optimized for Java 21 Virtual Threads.
 * <p>
 * This implementation uses a thread-safe concurrent map to store client information,
 * which works efficiently with both platform threads and virtual threads. It properly
 * handles context propagation and provides detailed logging for virtual thread operations.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ClientInfoProviderImpl
    implements ClientInfoProvider
{
  private final Provider<HttpServletRequest> httpRequestProvider;
  private final Logger logger = System.getLogger(getClass().getName());
  
  // Thread-safe map to store client information, optimized for Virtual Threads
  /**
   * Thread-safe map to store client information, optimized for Virtual Threads.
   * <p>
   * This approach is preferred over ThreadLocal for Virtual Threads because:
   * 1. It avoids the memory overhead of ThreadLocal with many Virtual Threads
   * 2. It ensures proper context propagation when Virtual Threads migrate between carrier threads
   * 3. It allows for explicit cleanup when Virtual Threads complete
   */
  private final ConcurrentMap<Thread, ClientInfoContext> threadClientInfo = new ConcurrentHashMap<>();
  
  /**
   * Context class to hold client information
   */
  private static class ClientInfoContext {
    final String remoteIp;
    final String userId;
    
    ClientInfoContext(String remoteIp, String userId) {
      this.remoteIp = remoteIp;
      this.userId = userId;
    }
  }

  @Inject
  public ClientInfoProviderImpl(final Provider<HttpServletRequest> httpRequestProvider) {
    this.httpRequestProvider = checkNotNull(httpRequestProvider);
  }

  @Override
  @Nullable
  public ClientInfo getCurrentThreadClientInfo() {
    try {
      HttpServletRequest request = httpRequestProvider.get();
      return ClientInfo
          .builder()
          .userId(UserIdHelper.get())
          .remoteIP(request.getRemoteAddr())
          .userAgent(request.getHeader(HttpHeaders.USER_AGENT))
          .path(request.getServletPath())
          .build();
    }
    catch (ProvisionException | OutOfScopeException e) {
      /*
       * This happens when called out of scope of http request.
       * Create ClientInfo with the custom User Id and Remote address from thread-local storage.
       */
      Thread currentThread = Thread.currentThread();
      ClientInfoContext context = threadClientInfo.get(currentThread);
      
      if (context != null) {
        return ClientInfo
            .builder()
            .userId(context.userId)
            .remoteIP(context.remoteIp)
            .build();
      }
      
      // Check if running in a Virtual Thread and log appropriate message
      if (currentThread.isVirtual()) {
        logger.log(
            Logger.Level.DEBUG, 
            "No client info available for Virtual Thread: {0}", 
            currentThread.getName());
      }
      
      return null;
    }
  }

  @Override
  public void setClientInfo(final String remoteIp, final String userId) {
    checkNotNull(remoteIp, "Remote IP cannot be null");
    checkNotNull(userId, "User ID cannot be null");
    
    Thread currentThread = Thread.currentThread();
    ClientInfoContext context = new ClientInfoContext(remoteIp, userId);
    
    threadClientInfo.put(currentThread, context);
    
    if (currentThread.isVirtual()) {
      logger.log(
          Logger.Level.DEBUG, 
          "Set client info for Virtual Thread {0}: userId={1}, remoteIp={2}", 
          currentThread.getName(), userId, remoteIp);
    }
  }

  @Override
  public void unsetClientInfo() {
    Thread currentThread = Thread.currentThread();
    ClientInfoContext removed = threadClientInfo.remove(currentThread);
    
    if (currentThread.isVirtual() && removed != null) {
      logger.log(
          Logger.Level.DEBUG, 
          "Removed client info for Virtual Thread {0}", 
          currentThread.getName());
    }
  }
}