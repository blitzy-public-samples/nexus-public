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

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static java.lang.StringTemplate.STR;

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
 * Default {@link ClientInfoProvider} with Java 21 Virtual Thread support.
 *
 * This implementation provides client information for both platform threads and virtual threads,
 * ensuring proper context propagation and thread-local storage compatibility with Java 21.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ClientInfoProviderImpl
    implements ClientInfoProvider
{
  private static final Logger log = Logger.getLogger(ClientInfoProviderImpl.class.getName());
  
  private final Provider<HttpServletRequest> httpRequestProvider;

  // Using InheritableThreadLocal for better compatibility with Virtual Threads
  // This ensures values are inherited by child threads, including virtual threads
  private final InheritableThreadLocal<String> remoteIp = new InheritableThreadLocal<>();
  private final InheritableThreadLocal<String> userId = new InheritableThreadLocal<>();
  
  // Thread context map for virtual threads to ensure proper context propagation
  // when operating across thread boundaries
  private final ConcurrentHashMap<Thread, ClientInfoContext> virtualThreadContexts = new ConcurrentHashMap<>();

  /**
   * Context holder for client information in virtual threads
   */
  private static class ClientInfoContext {
    private final String remoteIp;
    private final String userId;

    public ClientInfoContext(String remoteIp, String userId) {
      this.remoteIp = remoteIp;
      this.userId = userId;
    }

    public String getRemoteIp() {
      return remoteIp;
    }

    public String getUserId() {
      return userId;
    }
  }

  @Inject
  public ClientInfoProviderImpl(final Provider<HttpServletRequest> httpRequestProvider) {
    this.httpRequestProvider = checkNotNull(httpRequestProvider);
  }

  /**
   * Checks if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
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
       * Create ClientInfo with the custom User Id and Remote address.
       * For virtual threads, check both thread-local and context map.  
       */
      if (isVirtualThread()) {
        // For virtual threads, check the context map first
        ClientInfoContext context = virtualThreadContexts.get(Thread.currentThread());
        if (context != null) {
          return ClientInfo
              .builder()
              .userId(context.getUserId())
              .remoteIP(context.getRemoteIp())
              .build();
        }
      }
      
      // Fall back to thread-local values
      String userIdValue = userId.get();
      String remoteIpValue = remoteIp.get();
      
      if (userIdValue != null && remoteIpValue != null) {
        return ClientInfo
            .builder()
            .userId(userIdValue)
            .remoteIP(remoteIpValue)
            .build();
      }
      
      // Log using String Templates for more efficient logging
      if (isVirtualThread()) {
        log.fine(STR."No client info available for virtual thread \{Thread.currentThread().getName()}");
      }
      
      return null;
    }
  }

  @Override
  public void setClientInfo(final String remoteIp, final String userId) {
    checkNotNull(remoteIp, "Remote IP cannot be null");
    checkNotNull(userId, "User ID cannot be null");
    
    // Store in thread-local for compatibility with both platform and virtual threads
    this.remoteIp.set(remoteIp);
    this.userId.set(userId);
    
    // For virtual threads, also store in the context map for better context propagation
    if (isVirtualThread()) {
      Thread currentThread = Thread.currentThread();
      virtualThreadContexts.put(currentThread, new ClientInfoContext(remoteIp, userId));
      log.fine(STR."Set client info for virtual thread \{currentThread.getName()}: userId=\{userId}, remoteIp=\{remoteIp}");
    }
  }

  @Override
  public void unsetClientInfo() {
    // Clean up thread-local storage
    remoteIp.remove();
    userId.remove();
    
    // For virtual threads, also clean up the context map
    if (isVirtualThread()) {
      Thread currentThread = Thread.currentThread();
      virtualThreadContexts.remove(currentThread);
      log.fine(STR."Unset client info for virtual thread \{currentThread.getName()}");
    }
  }
}
