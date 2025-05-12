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
package com.sonatype.nexus.ssl.plugin.internal.keystore;

import java.security.KeyStore;

import org.sonatype.nexus.common.entity.EntityVersion;

/**
 * Event sent out when {@link KeyStore} data changes.
 * <p>
 * This interface is designed to work with Java 21 record patterns for efficient event processing.
 * Events are processed asynchronously using virtual threads in the EventManager.
 *
 * @since 3.1
 */
public interface KeyStoreDataEvent
{
  /**
   * Determines if this event originated from the local node.
   *
   * @return true if the event is local, false if it came from a remote node
   */
  boolean isLocal();

  /**
   * Gets the ID of the remote node that originated this event, if applicable.
   *
   * @return the remote node ID, or null if this is a local event
   */
  String getRemoteNodeId();

  /**
   * Gets the version information for the entity associated with this event.
   *
   * @return the entity version
   */
  EntityVersion getVersion();

  /**
   * Gets the name of the KeyStore that was changed.
   *
   * @return the KeyStore name
   */
  String getKeyStoreName();
}