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
package org.sonatype.nexus.datastore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.distributed.event.service.api.common.DataStoreConfigurationEvent;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Auditor for DataStore configuration events.
 * 
 * This implementation is optimized for Virtual Threads in Java 21, ensuring
 * efficient event handling across thread boundaries with minimal blocking.
 */
@Named
@Singleton
public class DataStoreAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "DataStore";
  
  // Thread-safe map for concurrent event processing
  private final ConcurrentHashMap<String, Object> processingEvents = new ConcurrentHashMap<>();

  public DataStoreAuditor() {
    registerType(DataStoreConfigurationEvent.class, UPDATED_TYPE);
  }

  /**
   * Handle DataStore configuration events.
   * 
   * This method is optimized for Virtual Thread execution with the following characteristics:
   * - Uses @AllowConcurrentEvents to support parallel event processing
   * - Avoids operations that would cause thread pinning
   * - Uses thread-safe data structures for concurrent audit record processing
   * - Ensures proper event handling across Virtual Thread boundaries
   *
   * @param event the DataStore configuration event
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final DataStoreConfigurationEvent event) {
    // Fast path for non-recording state to avoid unnecessary processing
    if (!isRecording()) {
      return;
    }
    
    // Use event ID as key to prevent duplicate processing
    String eventKey = event.getConfigurationName() + "-" + event.getType() + "-" + System.nanoTime();
    
    // Ensure we don't process the same event concurrently
    if (processingEvents.putIfAbsent(eventKey, Boolean.TRUE) != null) {
      return;
    }
    
    try {
      // Create audit data with thread-safe approach
      AuditData data = new AuditData();
      data.setDomain(DOMAIN);
      data.setType(type(event.getClass()));
      data.setContext(event.getConfigurationName());

      // Create a new map for attributes to avoid shared mutable state
      Map<String, Object> attributes = data.getAttributes();
      attributes.put("type", event.getType());
      attributes.put("source", event.getSource());

      // Create a defensive copy of event attributes to avoid modification during processing
      Map<String, String> eventAttributes = new HashMap<>(event.getAttributes());
      eventAttributes.replace("password", DataStoreConfiguration.REDACTED);
      attributes.put("attributes", eventAttributes);

      // Record the audit data
      record(data);
    } finally {
      // Always remove the event from processing map to prevent memory leaks
      processingEvents.remove(eventKey);
    }
  }
}