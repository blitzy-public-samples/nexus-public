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
package org.sonatype.nexus.internal.httpclient;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ByteSize;
import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.httpclient.HttpClientPlan;
import org.sonatype.nexus.httpclient.HttpDefaultsCustomizer;
import org.sonatype.nexus.utils.httpclient.UserAgentGenerator;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.impl.client.StandardHttpRequestRetryHandler;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Applies defaults to {@link HttpClientPlan}.
 * 
 * Optimized for Java 21 Virtual Threads to ensure efficient I/O operations
 * and prevent thread pinning during HTTP operations.
 *
 * @since 3.0
 */
@Named
@Singleton
public class DefaultsCustomizer
  extends ComponentSupport
  implements HttpDefaultsCustomizer
{
  private final UserAgentGenerator userAgentGenerator;

  private final Time requestTimeout;

  private final Time connectionRequestTimeout;

  private final Time keepAliveDuration;

  private final ByteSize bufferSize;

  private final int retryCount;

  @Inject
  public DefaultsCustomizer(
      final UserAgentGenerator userAgentGenerator,
      @Named("${nexus.httpclient.requestTimeout:-20s}") final Time requestTimeout,
      @Named("${nexus.httpclient.connectionRequestTimeout:-30s}") final Time connectionRequestTimeout,
      @Named("${nexus.httpclient.keepAliveDuration:-30s}") final Time keepAliveDuration,
      @Named("${nexus.httpclient.bufferSize:-8k}") final ByteSize bufferSize,
      @Named("${nexus.httpclient.retryCount:-2}") final int retryCount)
  {
    this.userAgentGenerator = checkNotNull(userAgentGenerator);

    this.requestTimeout = checkNotNull(requestTimeout);
    log.debug(STR."Request timeout: \{requestTimeout}");

    this.connectionRequestTimeout = checkNotNull(connectionRequestTimeout);
    log.debug(STR."Connection request timeout: \{connectionRequestTimeout}");

    this.keepAliveDuration = checkNotNull(keepAliveDuration);
    log.debug(STR."Keep-alive duration: \{keepAliveDuration}");

    this.bufferSize = checkNotNull(bufferSize);
    log.debug(STR."Buffer-size: \{bufferSize}");

    this.retryCount = checkNotNull(retryCount);
    log.debug(STR."Retry count: \{retryCount}");
  }

  @Override
  public void customize(final HttpClientPlan plan) {
    checkNotNull(plan);

    plan.setUserAgentBase(userAgentGenerator.generate());

    // Configure keep-alive strategy optimized for Virtual Threads
    // Shorter keep-alive durations work better with Virtual Threads as they
    // allow more efficient resource utilization
    plan.getClient().setKeepAliveStrategy(new NexusConnectionKeepAliveStrategy(keepAliveDuration.toMillis()));
    
    // Configure retry handler with appropriate retry count
    // Virtual Threads benefit from explicit retry policies rather than connection pooling
    plan.getClient().setRetryHandler(new StandardHttpRequestRetryHandler(retryCount, false));

    // Set buffer size for optimal I/O operations with Virtual Threads
    plan.getConnection().setBufferSize(bufferSize.toBytesI());

    // Configure request timeouts appropriate for Virtual Thread operations
    // Virtual Threads perform best with explicit timeouts to prevent resource leaks
    plan.getRequest().setConnectionRequestTimeout(connectionRequestTimeout.toMillisI());
    plan.getRequest().setCookieSpec(CookieSpecs.IGNORE_COOKIES);
    plan.getRequest().setExpectContinueEnabled(false);

    int requestTimeoutMillis = requestTimeout.toMillisI();
    plan.getSocket().setSoTimeout(requestTimeoutMillis);
    plan.getRequest().setConnectTimeout(requestTimeoutMillis);
    plan.getRequest().setSocketTimeout(requestTimeoutMillis);
    
    log.debug(STR."HTTP client configured with request timeout: \{requestTimeoutMillis}ms, buffer size: \{bufferSize}, retry count: \{retryCount}");
  }

  @Override
  public Time getRequestTimeout() {
    return requestTimeout;
  }

  @Override
  public int getRetryCount() {
    return retryCount;
  }
}