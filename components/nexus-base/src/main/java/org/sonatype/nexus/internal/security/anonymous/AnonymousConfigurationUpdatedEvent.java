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

import org.sonatype.nexus.common.event.EventWithSource;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;

/**
 * Event fired when anonymous configuration is updated.
 * 
 * @since 3.0
 */
public class AnonymousConfigurationUpdatedEvent
    extends EventWithSource
    implements AnonymousConfigurationEvent
{
  private AnonymousConfigurationData anonymousConfiguration;

  /**
   * Default constructor for deserialization.
   */
  public AnonymousConfigurationUpdatedEvent() {
    // deserialization
  }

  /**
   * Constructor with anonymous configuration data.
   * 
   * @param anonymousConfiguration the updated anonymous configuration data
   */
  public AnonymousConfigurationUpdatedEvent(final AnonymousConfigurationData anonymousConfiguration) {
    this.anonymousConfiguration = anonymousConfiguration;
  }

  @Override
  public AnonymousConfiguration getAnonymousConfiguration() {
    return anonymousConfiguration;
  }

  /**
   * Sets the anonymous configuration data.
   * 
   * @param anonymousConfiguration the anonymous configuration data to set
   */
  public void setAnonymousConfiguration(final AnonymousConfigurationData anonymousConfiguration) {
    this.anonymousConfiguration = anonymousConfiguration;
  }
  
  /**
   * Returns a string representation of this event using Java 21 String Templates.
   * 
   * @return a string representation of this event
   */
  @Override
  public String toString() {
    return STR."AnonymousConfigurationUpdatedEvent{anonymousConfiguration=\{anonymousConfiguration}, isLocal=\{isLocal()}, remoteNodeId=\{getRemoteNodeId()}}";
  }
}