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
package org.sonatype.nexus.internal.capability;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.capability.Capability;
import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityDescriptor;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.formfields.Encrypted;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.thread.NexusExecutorService;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * {@link Capability} auditor.
 *
 * @since 3.1
 */
@Named
@Singleton
public class CapabilityAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "capability";
  
  /**
   * Virtual thread executor for processing audit events asynchronously.
   * Using virtual threads improves performance for I/O-bound operations like audit logging.
   */
  private final ExecutorService virtualExecutor;

  public CapabilityAuditor() {
    // Create a virtual thread executor for processing audit events
    this.virtualExecutor = NexusExecutorService.forCurrentSubjectVirtual();
  }

  /**
   * Determines the audit type based on the event class using pattern matching.
   * 
   * @param eventClass the capability event class
   * @return the audit type string
   */
  private String determineAuditType(Class<?> eventClass) {
    return switch (eventClass.getSimpleName()) {
      case "Created" -> CREATED_TYPE;
      case "AfterActivated" -> "activated";
      case "BeforePassivated" -> "passivated";
      case "AfterRemove" -> DELETED_TYPE;
      case "AfterUpdate" -> UPDATED_TYPE;
      default -> eventClass.getSimpleName().toLowerCase();
    };
  }

  @Subscribe
  @AllowConcurrentEvents
  public void on(final CapabilityEvent event) {
    if (isRecording()) {
      // Process the audit event using a virtual thread for improved performance
      virtualExecutor.submit(() -> processAuditEvent(event));
    }
  }
  
  /**
   * Processes the capability event and records the audit data.
   * 
   * @param event the capability event to process
   */
  private void processAuditEvent(final CapabilityEvent event) {
    CapabilityReference reference = event.getReference();
    CapabilityContext context = reference.context();
    CapabilityDescriptor descriptor = context.descriptor();

    AuditData data = new AuditData();
    data.setDomain(DOMAIN);
    data.setType(determineAuditType(event.getClass()));
    data.setContext(context.type().toString());

    Map<String, Object> attributes = data.getAttributes();
    
    // Add basic capability attributes
    attributes.put("id", context.id().toString());
    attributes.put("type", context.type().toString());
    attributes.put("enabled", string(context.isEnabled()));
    attributes.put("active", string(context.isActive()));
    attributes.put("failed", string(context.hasFailure()));

    // Process properties in parallel using virtual threads if there are many properties
    Map<String, FormField> fields = fields(descriptor);
    Map<String, String> properties = context.properties();
    
    // For capabilities with many properties, process them in parallel
    if (properties.size() > 10) {
      processPropertiesInParallel(properties, fields, attributes);
    } else {
      // For smaller property sets, process sequentially
      processPropertiesSequentially(properties, fields, attributes);
    }

    record(data);
  }
  
  /**
   * Process properties sequentially and add them to attributes.
   * 
   * @param properties the capability properties
   * @param fields the form fields
   * @param attributes the audit attributes to populate
   */
  private void processPropertiesSequentially(
      Map<String, String> properties, 
      Map<String, FormField> fields, 
      Map<String, Object> attributes) {
    
    for (Entry<String, String> entry : properties.entrySet()) {
      String key = entry.getKey();
      FormField field = fields.get(key);
      
      // Skip secure fields
      if (field instanceof Encrypted) {
        continue;
      }
      
      // Use string concatenation for property key formatting
      attributes.put("property." + key, entry.getValue());
    }
  }
  
  /**
   * Process properties in parallel using virtual threads and add them to attributes.
   * 
   * @param properties the capability properties
   * @param fields the form fields
   * @param attributes the audit attributes to populate
   */
  private void processPropertiesInParallel(
      Map<String, String> properties, 
      Map<String, FormField> fields, 
      Map<String, Object> attributes) {
    
    // Create a synchronized map to safely collect results from multiple virtual threads
    Map<String, Object> syncAttributes = new HashMap<>();
    
    // Process each property in a separate virtual thread
    properties.entrySet().stream().parallel().forEach(entry -> {
      String key = entry.getKey();
      FormField field = fields.get(key);
      
      // Skip secure fields
      if (field instanceof Encrypted) {
        return;
      }
      
      // Use string concatenation for property key formatting
      synchronized (syncAttributes) {
        syncAttributes.put("property." + key, entry.getValue());
      }
    });
    
    // Add all collected attributes to the main attributes map
    attributes.putAll(syncAttributes);
  }

  /**
   * Creates a map of form field IDs to form fields.
   * 
   * @param descriptor the capability descriptor
   * @return a map of form field IDs to form fields
   */
  private static Map<String, FormField> fields(final CapabilityDescriptor descriptor) {
    Map<String, FormField> result = new HashMap<>();
    for (FormField field : descriptor.formFields()) {
      result.put(field.getId(), field);
    }
    return result;
  }
}