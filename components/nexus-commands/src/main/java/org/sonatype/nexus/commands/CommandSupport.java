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
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.console.Command as ConsoleCommand;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.impl.action.command.ActionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

/**
 * Provides support for Karaf {@link Action} {@link ConsoleCommand} implementations.
 * 
 * @since 3.0
 * @see ActionCommand
 * @see Action
 * @see Command
 * @see ConsoleCommand
 * @see Session
 * 
 * @compatible with Java 21 and Karaf 4.4.4
 */
public abstract class CommandSupport
    extends ActionCommand
{
  /**
   * Logger for this class, using Java 21 String Templates for logging.
   * <p>
   * Example usage in subclasses:
   * <pre>
   * String name = "command";
   * int count = 5;
   * log.debug(STR."Executing \{name} with \{count} parameters");
   * </pre>
   * </p>
   */
  protected final Logger log = LoggerFactory.getLogger(getClass());

  /**
   * Creates a new command support instance.
   * 
   * @param actionClass the action class to support, must be annotated with {@link Command}
   * @throws IllegalArgumentException if actionClass is not annotated with {@link Command}
   */
  public CommandSupport(final Class<? extends Action> actionClass) {
    super(null, actionClass);
    // Note: ActionCommand constructor in Karaf 4.4.4 validates that actionClass has @Command annotation
    // so we don't need to explicitly check it here
  }

  @Override
  protected abstract Action createNewAction(Session session);

  @Override
  protected abstract void releaseAction(Action action) throws Exception;
}