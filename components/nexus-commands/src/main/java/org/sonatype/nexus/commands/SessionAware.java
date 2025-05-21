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
package org.sonatype.nexus.commands;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.console.Session;

/**
 * Allows {@link Action} instances to be aware of the current session.
 * <p>
 * This interface is compatible with Karaf 4.4.4 and Java 21, maintaining
 * backward compatibility with existing implementations while supporting
 * the latest Java features and runtime environment.
 * 
 * @since 3.0
 */
public interface SessionAware
{
  /**
   * Sets the current session for this action.
   * <p>
   * This method is called by the Karaf shell framework before executing
   * the action, providing access to the current console session context.
   *
   * @param session the current Karaf shell session
   */
  void setSession(Session session);
}
