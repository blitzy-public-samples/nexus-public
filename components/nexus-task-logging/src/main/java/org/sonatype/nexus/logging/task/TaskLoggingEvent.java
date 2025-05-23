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
package org.sonatype.nexus.logging.task;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.copyOf;

/**
 * Object to hold log message information, supporting both traditional format strings and Java 21 String Templates.
 * This allows messages to be logged as progress later.
 *
 * @since 3.5
 */
public class TaskLoggingEvent
{
  private final Logger logger;

  private final String message;

  private final Object[] args;
  
  private final StringTemplate template;
  
  private final Map<String, Object> context;

  /**
   * Creates a logging event with a simple message and no arguments.
   *
   * @param logger the logger to use
   * @param message the message to log
   */
  public TaskLoggingEvent(final Logger logger, final String message) {
    this(logger, message, null);
  }

  /**
   * Creates a logging event with a message format and arguments.
   *
   * @param logger the logger to use
   * @param message the message format to log
   * @param args the arguments for the message format
   */
  public TaskLoggingEvent(final Logger logger, final String message, final Object[] args) {
    this.logger = checkNotNull(logger);
    this.message = checkNotNull(message);
    this.args = args == null ? null : copyOf(args, args.length);
    this.template = null;
    this.context = new HashMap<>();
  }
  
  /**
   * Creates a logging event with a String Template.
   * This constructor optimizes argument handling by not performing defensive copying,
   * as the template already contains the values.
   *
   * @param logger the logger to use
   * @param template the string template to log
   */
  public TaskLoggingEvent(final Logger logger, final StringTemplate template) {
    this.logger = checkNotNull(logger);
    this.template = checkNotNull(template);
    this.message = null;
    this.args = null;
    this.context = new HashMap<>();
  }
  
  /**
   * Creates a logging event with a String Template and additional context.
   *
   * @param logger the logger to use
   * @param template the string template to log
   * @param context additional context information for structured logging
   */
  public TaskLoggingEvent(final Logger logger, final StringTemplate template, final Map<String, Object> context) {
    this.logger = checkNotNull(logger);
    this.template = checkNotNull(template);
    this.message = null;
    this.args = null;
    this.context = context != null ? new HashMap<>(context) : new HashMap<>();
  }

  /**
   * @return the logger associated with this event
   */
  public Logger getLogger() {
    return logger;
  }

  /**
   * @return the message format string (may be null if using a template)
   */
  public String getMessage() {
    return message;
  }

  /**
   * @return the arguments for the message format (may be null if using a template)
   */
  public Object[] getArgumentArray() {
    return args;
  }
  
  /**
   * @return the string template (may be null if using a traditional message format)
   */
  public StringTemplate getTemplate() {
    return template;
  }
  
  /**
   * @return true if this event uses a string template, false otherwise
   */
  public boolean hasTemplate() {
    return template != null;
  }
  
  /**
   * @return the template fragments if this event uses a template, null otherwise
   */
  public List<String> getTemplateFragments() {
    return hasTemplate() ? template.fragments() : null;
  }
  
  /**
   * @return the template values if this event uses a template, null otherwise
   */
  public List<Object> getTemplateValues() {
    return hasTemplate() ? template.values() : null;
  }
  
  /**
   * @return additional context information for structured logging
   */
  public Map<String, Object> getContext() {
    return context;
  }
  
  /**
   * Add context information for structured logging.
   *
   * @param key the context key
   * @param value the context value
   * @return this event for method chaining
   */
  public TaskLoggingEvent addContext(String key, Object value) {
    context.put(key, value);
    return this;
  }
  
  /**
   * Returns a string representation of this event using String Templates for efficiency.
   */
  @Override
  public String toString() {
    if (hasTemplate()) {
      return STR."TaskLoggingEvent{logger=\{logger.getName()}, template=\{template.interpolate()}, context=\{context}}";
    } else {
      return STR."TaskLoggingEvent{logger=\{logger.getName()}, message='\{message}', args=\{args == null ? "null" : args.length + " args"}, context=\{context}}";
    }
  }
}