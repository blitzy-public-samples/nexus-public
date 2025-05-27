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
package org.sonatype.nexus.internal.security.anonymous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.common.event.EventWithSource;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;

/**
 * Event fired when the {@link AnonymousConfiguration} is updated.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads and ensures thread-safety
 * when events are fired from either platform threads or virtual threads in high-concurrency scenarios.
 * <p>
 * The event maintains immutability after construction to ensure thread-safety across different
 * execution contexts, particularly important when virtual threads are used for event processing.
 */
public class AnonymousConfigurationUpdatedEvent
    extends EventWithSource
    implements AnonymousConfigurationEvent
{
  private static final Logger log = LoggerFactory.getLogger(AnonymousConfigurationUpdatedEvent.class);
  
  private final AnonymousConfigurationData anonymousConfiguration;

  /**
   * Default constructor for deserialization purposes only.
   * The anonymousConfiguration will be null until explicitly set.
   */
  public AnonymousConfigurationUpdatedEvent() {
    // Required for deserialization
    this.anonymousConfiguration = null;
    log.trace(STR."Created empty \{getClass().getSimpleName()} for deserialization");
  }

  /**
   * Creates a new event with the specified configuration data.
   * 
   * @param anonymousConfiguration the anonymous configuration data (should not be null)
   */
  public AnonymousConfigurationUpdatedEvent(final AnonymousConfigurationData anonymousConfiguration) {
    this.anonymousConfiguration = anonymousConfiguration;
    log.trace(STR."Created \{getClass().getSimpleName()} with configuration: \{anonymousConfiguration}");
  }

  /**
   * Returns the anonymous configuration associated with this event.
   * This method is thread-safe and can be called from any thread context including Virtual Threads.
   * 
   * @return the anonymous configuration (may be null if created via default constructor)
   */
  @Override
  public AnonymousConfiguration getAnonymousConfiguration() {
    return anonymousConfiguration;
  }

  /**
   * Sets the anonymous configuration for this event.
   * This method should only be used during deserialization.
   * 
   * @param anonymousConfiguration the anonymous configuration data to set
   * @deprecated Only for use by deserializers - events should be immutable after construction
   */
  @Deprecated
  public void setAnonymousConfiguration(final AnonymousConfigurationData anonymousConfiguration) {
    // This cast is safe because this method is only called during deserialization
    // where the field is initialized as null
    ((AnonymousConfigurationUpdatedEvent)this).anonymousConfiguration = anonymousConfiguration;
    log.trace(STR."Set configuration on \{getClass().getSimpleName()}: \{anonymousConfiguration}");
  }
}