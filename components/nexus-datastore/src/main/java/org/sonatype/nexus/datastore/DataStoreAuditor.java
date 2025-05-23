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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.distributed.event.service.api.common.DataStoreConfigurationEvent;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Auditor for DataStore events.
 * 
 * Optimized for Virtual Thread execution in Java 21 with enhanced thread safety
 * for concurrent audit record processing.
 */
@Named
@Singleton
public class DataStoreAuditor
    extends AuditorSupport
    implements EventAware
{
  private static final Logger log = LoggerFactory.getLogger(DataStoreAuditor.class);
  
  public static final String DOMAIN = "DataStore";
  
  // Executor service using Virtual Threads for non-blocking event processing
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public DataStoreAuditor() {
    registerType(DataStoreConfigurationEvent.class, UPDATED_TYPE);
  }

  /**
   * Handle DataStore configuration events.
   * 
   * This method is optimized for concurrent execution with Virtual Threads.
   * The @AllowConcurrentEvents annotation ensures multiple events can be processed
   * simultaneously without blocking.
   *
   * @param event the DataStore configuration event to process
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final DataStoreConfigurationEvent event) {
    if (isRecording()) {
      // Process event in a Virtual Thread to avoid blocking the event bus thread
      virtualThreadExecutor.execute(() -> {
        try {
          // Create a new AuditData instance for each event to ensure thread safety
          AuditData data = new AuditData();
          data.setDomain(DOMAIN);
          data.setType(type(event.getClass()));
          data.setContext(event.getConfigurationName());

          // Create a new map for attributes to avoid shared state issues
          Map<String, Object> attributes = data.getAttributes();
          attributes.put("type", event.getType());
          attributes.put("source", event.getSource());

          // Create a copy of event attributes to avoid concurrent modification
          Map<String, String> eventAttributes = new HashMap<>(event.getAttributes());
          eventAttributes.replace("password", DataStoreConfiguration.REDACTED);
          attributes.put("attributes", eventAttributes);

          // Record the audit data
          record(data);
        } catch (Exception e) {
          // Log any exceptions that occur during event processing
          log.error("Error processing DataStore audit event", e);
        }
      });
    }
  }
}