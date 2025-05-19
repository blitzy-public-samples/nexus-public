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
package org.sonatype.nexus.bootstrap.jetty;

import java.util.List;
import java.util.concurrent.Executor;

import io.dropwizard.metrics.SharedMetricRegistries;
import io.dropwizard.metrics.Timer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;

/**
 * Extension of Dropwizard Metrics ConnectionFactory for Jetty 12.
 * Optimized for Java 21 Virtual Threads to improve I/O performance.
 *
 * @since 3.0
 */
public final class InstrumentedConnectionFactory
    implements ConnectionFactory
{
  private final ConnectionFactory connectionFactory;
  private final Timer timer;

  /**
   * Creates a new instrumented connection factory.
   *
   * @param connectionFactory the connection factory to instrument
   */
  public InstrumentedConnectionFactory(final ConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
    this.timer = SharedMetricRegistries.getOrCreate("nexus").timer("connection-duration");
  }

  @Override
  public String getProtocol() {
    return connectionFactory.getProtocol();
  }

  @Override
  public List<String> getProtocols() {
    return connectionFactory.getProtocols();
  }

  @Override
  public Connection newConnection(Connector connector, EndPoint endPoint) {
    final Timer.Context context = timer.time();
    
    // Use try-with-resources to ensure proper timing even with Virtual Threads
    try {
      // Create the connection using the underlying factory
      final Connection connection = connectionFactory.newConnection(connector, endPoint);
      
      // Wrap the connection with a listener that stops the timer when the connection is closed
      return new InstrumentedConnection(connection, context);
    } catch (Throwable t) {
      // Stop the timer in case of exceptions
      context.stop();
      throw t;
    }
  }

  /**
   * A decorator for Connection that times the duration of the connection.
   */
  private static class InstrumentedConnection implements Connection {
    private final Connection delegate;
    private final Timer.Context context;

    InstrumentedConnection(Connection delegate, Timer.Context context) {
      this.delegate = delegate;
      this.context = context;
    }

    @Override
    public void close() {
      try {
        delegate.close();
      } finally {
        context.stop();
      }
    }

    @Override
    public boolean isClosed() {
      return delegate.isClosed();
    }

    @Override
    public EndPoint getEndPoint() {
      return delegate.getEndPoint();
    }

    @Override
    public void onOpen() {
      delegate.onOpen();
    }

    @Override
    public void onClose(Throwable cause) {
      delegate.onClose(cause);
    }

    @Override
    public Executor getExecutor() {
      return delegate.getExecutor();
    }
  }
}