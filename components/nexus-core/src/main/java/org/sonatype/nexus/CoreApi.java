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
package org.sonatype.nexus;

import org.sonatype.nexus.common.script.ScriptApi;

/**
 * Core provisioning capabilities of the repository manager.
 * <p>
 * This interface defines operations that can be performed on the repository manager's core configuration.
 * All methods in this interface are designed to be compatible with Java 21 Virtual Threads,
 * allowing implementations to leverage high-throughput concurrent execution for I/O-bound operations
 * without blocking platform threads.
 * <p>
 * Implementations should leverage Virtual Threads for I/O-bound operations
 * to improve throughput and resource utilization without blocking platform threads.
 *
 * @since 3.0
 * @see java.lang.Thread#startVirtualThread(Runnable) Java 21 Virtual Threads
 */
public interface CoreApi
    extends ScriptApi
{
  default String getName() {
    return "core";
  }

  /**
   * Set the base url of the repository manager.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   */
  void baseUrl(String url);

  /**
   * Remove any existing base url capability.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   */
  void removeBaseUrl();

  /**
   * Customize the User-Agent header in outgoing HTTP requests by appending this value.
   * Can be removed by calling with an empty string.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   */
  void userAgentCustomization(String userAgentSuffix);

  /**
   * Set the connection timeout between 1 and 3600 seconds.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   * When implemented with Virtual Threads, this setting affects how long Virtual Threads
   * will wait before timing out on I/O operations.
   */
  void connectionTimeout(int timeout);

  /**
   * Set the number of connection retries between 1 and 10.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   * When implemented with Virtual Threads, this affects retry behavior without blocking platform threads.
   */
  void connectionRetryAttempts(int retries);

  /**
   * Create an unauthenticated http proxy.
   * <p>
   * This I/O-bound operation is suitable for execution in a Virtual Thread context,
   * allowing for efficient handling of proxy configuration without blocking platform threads.
   */
  void httpProxy(String host, int port);

  /**
   * Create an http proxy using username/password authentication.
   * <p>
   * This I/O-bound operation is suitable for execution in a Virtual Thread context,
   * allowing for efficient handling of authenticated proxy configuration without blocking platform threads.
   */
  void httpProxyWithBasicAuth(String host, int port, String username, String password);

  /**
   * Create an http proxy using Windows NTLM authentication.
   * <p>
   * This I/O-bound operation is suitable for execution in a Virtual Thread context,
   * allowing for efficient handling of NTLM authenticated proxy configuration without blocking platform threads.
   */
  void httpProxyWithNTLMAuth(String host, int port, String username, String password, String ntlmHost,
                             String domain);

  /**
   * Remove any existing http proxy configuration.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   */
  void removeHTTPProxy();

  /**
   * Create an unauthenticated https proxy.
   * <p>
   * This I/O-bound operation is suitable for execution in a Virtual Thread context,
   * allowing for efficient handling of secure proxy configuration without blocking platform threads.
   */
  void httpsProxy(String host, int port);

  /**
   * Create an https proxy using username/password authentication.
   * <p>
   * This I/O-bound operation is suitable for execution in a Virtual Thread context,
   * allowing for efficient handling of authenticated secure proxy configuration without blocking platform threads.
   */
  void httpsProxyWithBasicAuth(String host, int port, String username, String password);

  /**
   * Create an https proxy using Windows NTLM authentication.
   * <p>
   * This I/O-bound operation is suitable for execution in a Virtual Thread context,
   * allowing for efficient handling of NTLM authenticated secure proxy configuration without blocking platform threads.
   */
  void httpsProxyWithNTLMAuth(String host, int port, String username, String password, String ntlmHost,
                              String domain);

  /**
   * Remove any existing https proxy configuration.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   */
  void removeHTTPSProxy();
  
  /**
   * Set hosts that should not be proxied. Accepts Java "http.nonProxyHosts" wildcard patterns (one per line, no '|'
   * hostname delimiters).
   * Previously configured values can be removed by calling this with no parameters.
   * <p>
   * This operation is suitable for execution in a Virtual Thread context.
   * When implemented with Virtual Threads, this configuration affects how proxy decisions are made
   * during concurrent network operations without blocking platform threads.
   */
  void nonProxyHosts(String... nonProxyHosts);
}