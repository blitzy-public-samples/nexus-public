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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.SessionContext;
import org.apache.shiro.session.mgt.SessionFactory;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.session.mgt.SimpleSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom {@link SessionFactory} optimized for Java 21 and Apache Shiro 2.0.0.
 *
 * @since 3.0
 */
public class NexusSessionFactory
  extends SimpleSessionFactory
{
  private static final Logger log = LoggerFactory.getLogger(NexusSessionFactory.class);

  /**
   * Ensure {@link SimpleSessionImpl} is used and provides logging.
   */
  @Override
  public Session createSession(final SessionContext initData) {
    log.trace("Creating session w/init-data: {}", initData);

    // duplicated from SimpleSessionFactory, retaining class-hierarchy for sanity
    if (initData != null) {
      String host = initData.getHost();
      if (host != null) {
        return new SimpleSessionImpl(host);
      }
    }
    return new SimpleSessionImpl();
  }

  /**
   * Customized session impl to use ConcurrentHashMap for attributes map.
   * This implementation is optimized for Java 21 virtual threads and provides
   * better concurrency without pinning threads.
   */
  private static class SimpleSessionImpl
    extends SimpleSession
  {
    public SimpleSessionImpl() {
      super();
    }

    public SimpleSessionImpl(final String host) {
      super(host);
    }

    /**
     * Override to use ConcurrentHashMap instead of Collections.synchronizedMap.
     * ConcurrentHashMap provides better performance for concurrent access patterns
     * and is optimized for Java 21 virtual threads.
     */
    @Override
    public void setAttributes(final Map<Object, Object> attributes) {
      if (attributes != null) {
        // Use ConcurrentHashMap directly instead of wrapping with Collections.synchronizedMap
        // This provides better concurrency and avoids thread pinning with virtual threads
        super.setAttributes(new ConcurrentHashMap<>(attributes));
      }
      else {
        super.setAttributes(null);
      }
    }
  }
}