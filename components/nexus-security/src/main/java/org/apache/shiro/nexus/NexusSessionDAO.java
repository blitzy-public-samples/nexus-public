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

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.eis.EnterpriseCacheSessionDAO;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.StringTemplate.STR;

/**
 * Custom {@link SessionDAO} with Java 21 optimizations.
 *
 * @since 3.0
 */
public class NexusSessionDAO
  extends EnterpriseCacheSessionDAO
{
  private static final Logger log = LoggerFactory.getLogger(NexusSessionDAO.class);
  
  // Optimized cache for session lookups with Java 21 concurrency model
  private final ConcurrentHashMap<Serializable, CompletableFuture<Session>> sessionCache = new ConcurrentHashMap<>();

  @Override
  protected Serializable doCreate(final Session session) {
    // Use a virtual thread to handle session creation for improved I/O performance
    try {
      return Thread.startVirtualThread(() -> {
        Serializable id = super.doCreate(session);
        // Use Java 21 String Templates for logging
        if (log.isTraceEnabled()) {
          log.trace(STR."Created session-id: \{id} for session: \{session}");
        }
        return id;
      }).join();
    } catch (Exception e) {
      log.error(STR."Error creating session: \{e.getMessage()}", e);
      // Fall back to synchronous execution if virtual thread fails
      Serializable id = super.doCreate(session);
      if (log.isTraceEnabled()) {
        log.trace(STR."Created session-id: \{id} for session: \{session} (synchronous fallback)");
      }
      return id;
    }
  }
  
  @Override
  protected Session doReadSession(Serializable sessionId) {
    // Optimize session reading with CompletableFuture and virtual threads
    if (sessionId == null) {
      return null;
    }
    
    try {
      return sessionCache.computeIfAbsent(sessionId, id -> {
        CompletableFuture<Session> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
          try {
            Session session = super.doReadSession(id);
            future.complete(session);
            if (log.isTraceEnabled() && session != null) {
              log.trace(STR."Read session: \{session} with id: \{id} using virtual thread");
            }
          } catch (Exception e) {
            future.completeExceptionally(e);
            log.error(STR."Error reading session with id: \{id}", e);
          }
        });
        return future;
      }).get();
    } catch (InterruptedException | ExecutionException e) {
      log.error(STR."Failed to read session with id: \{sessionId}", e);
      // Fall back to synchronous execution if virtual thread approach fails
      return super.doReadSession(sessionId);
    }
  }
  
  @Override
  protected void doUpdate(Session session) {
    if (session == null || session.getId() == null) {
      return;
    }
    
    // Use virtual thread for session updates to improve I/O performance
    Thread.startVirtualThread(() -> {
      try {
        super.doUpdate(session);
        if (log.isTraceEnabled()) {
          log.trace(STR."Updated session: \{session}");
        }
        // Update the cache with the latest session
        CompletableFuture<Session> future = new CompletableFuture<>();
        future.complete(session);
        sessionCache.put(session.getId(), future);
      } catch (Exception e) {
        log.error(STR."Error updating session: \{session.getId()}", e);
        // Remove from cache on error to force a fresh read
        sessionCache.remove(session.getId());
      }
    });
  }
  
  @Override
  protected void doDelete(Session session) {
    if (session == null || session.getId() == null) {
      return;
    }
    
    // Use virtual thread for session deletion to improve I/O performance
    Thread.startVirtualThread(() -> {
      try {
        super.doDelete(session);
        if (log.isTraceEnabled()) {
          log.trace(STR."Deleted session: \{session}");
        }
        // Remove from cache when deleted
        sessionCache.remove(session.getId());
      } catch (Exception e) {
        log.error(STR."Error deleting session: \{session.getId()}", e);
      }
    });
  }
  
  /**
   * Clears the session cache to force fresh reads from the underlying storage.
   * This can be useful during system maintenance or when session data might be stale.
   */
  public void clearCache() {
    sessionCache.clear();
    if (log.isDebugEnabled()) {
      log.debug(STR."Session cache cleared");
    }
  }
}