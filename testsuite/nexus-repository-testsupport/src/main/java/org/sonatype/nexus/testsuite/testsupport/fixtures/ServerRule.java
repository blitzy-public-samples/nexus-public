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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sonatype.goodies.httpfixture.server.api.Behaviour;
import org.sonatype.goodies.httpfixture.server.fluent.Server;
import org.sonatype.nexus.common.net.PortAllocator;

import static org.apache.commons.lang3.StringUtils.prependIfMissing;

/**
 * A JUnit rule for managing test HTTP servers.
 * 
 * <p>This class is compatible with both JUnit 4 (via ExternalResource) and JUnit Jupiter 5.10.1 
 * (via @RegisterExtension). When used with JUnit Jupiter, register as an extension field:</p>
 * 
 * <pre>
 * {@code
 * @RegisterExtension
 * ServerRule servers = new ServerRule();
 * }
 * </pre>
 * 
 * <p>This class is compatible with Java 21 and supports concurrent test execution.</p>
 * 
 * @deprecated in favor of ServerTestSystem in the new IT Framework
 */
@Deprecated(forRemoval = true)
public class ServerRule
    extends ExternalResourceSupport
{
  private final List<Server> servers = new CopyOnWriteArrayList<>();

  @Override
  protected void after() {
    // Use enhanced for-each loop with lambda for cleaner error handling
    servers.forEach(server -> {
      try {
        server.stop();
      }
      catch (Exception e) {
        log.error("Failed to stop server", e);
      }
    });
    servers.clear();
  }

  /**
   * Creates a server on a dynamically allocated port with the specified behaviors.
   *
   * @param behaviors the map of path patterns to behaviors
   * @return the created server instance
   * @throws Exception if server creation fails
   */
  public Server createServer(final Map<String, Behaviour> behaviors) throws Exception {
    var port = PortAllocator.nextFreePort();
    return createServer(port, behaviors);
  }

  /**
   * Creates a server on the specified port with the specified behaviors.
   *
   * @param port the port to use for the server
   * @param behaviors the map of path patterns to behaviors
   * @return the created server instance
   * @throws Exception if server creation fails
   */
  public Server createServer(final int port, final Map<String, Behaviour> behaviors) throws Exception {
    var server = Server.withPort(port);
    behaviors.forEach((key, value) -> server.serve(prependIfMissing(key, "/")).withBehaviours(value));
    servers.add(server);
    server.start();
    return server;
  }
}