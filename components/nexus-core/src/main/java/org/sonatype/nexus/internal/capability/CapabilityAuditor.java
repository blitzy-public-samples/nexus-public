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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

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
   * Virtual thread executor for parallel property processing
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public CapabilityAuditor() {
    // No need to register types as we'll use pattern matching for switch instead
  }

  /**
   * Determines the audit type based on the event class using pattern matching.
   *
   * @param eventClass The class of the capability event
   * @return The audit type string
   */
  private String determineAuditType(Class<?> eventClass) {
    return switch (eventClass.getSimpleName()) {
      case String s when s.equals("Created") -> CREATED_TYPE;
      case String s when s.equals("AfterActivated") -> "activated";
      case String s when s.equals("BeforePassivated") -> "passivated";
      case String s when s.equals("AfterRemove") -> DELETED_TYPE;
      case String s when s.equals("AfterUpdate") -> UPDATED_TYPE;
      default -> eventClass.getSimpleName().toLowerCase();
    };
  }

  @Subscribe
  @AllowConcurrentEvents
  public void on(final CapabilityEvent event) {
    // Process the event in a virtual thread for improved performance
    Thread.startVirtualThread(() -> {
      if (isRecording()) {
        CapabilityReference reference = event.getReference();
        CapabilityContext context = reference.context();
        CapabilityDescriptor descriptor = context.descriptor();

        AuditData data = new AuditData();
        data.setDomain(DOMAIN);
        data.setType(determineAuditType(event.getClass()));
        data.setContext(context.type().toString());

        Map<String, Object> attributes = data.getAttributes();
        attributes.put("id", context.id().toString());
        attributes.put("type", context.type().toString());
        attributes.put("enabled", string(context.isEnabled()));
        attributes.put("active", string(context.isActive()));
        attributes.put("failed", string(context.hasFailure()));

        // Get all non-secure properties using virtual threads for parallel processing
        collectPropertiesAsync(context, descriptor, attributes);

        record(data);
      }
    });
  }

  /**
   * Collects properties asynchronously using virtual threads for parallel processing.
   *
   * @param context The capability context
   * @param descriptor The capability descriptor
   * @param attributes The attributes map to populate
   */
  private void collectPropertiesAsync(CapabilityContext context, CapabilityDescriptor descriptor, Map<String, Object> attributes) {
    Map<String, FormField> fields = fields(descriptor);
    Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();

    // Process each property in parallel using virtual threads
    for (Entry<String, String> entry : context.properties().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      
      futures.put(key, CompletableFuture.runAsync(() -> {
        FormField field = fields.get(key);
        // Skip secure fields
        if (field instanceof Encrypted) {
          return;
        }
        // Use String Templates for more efficient attribute processing
        attributes.put(STR."property.\{key}", value);
      }, virtualThreadExecutor));
    }

    // Wait for all property processing to complete
    CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
  }

  private static Map<String, FormField> fields(final CapabilityDescriptor descriptor) {
    Map<String, FormField> result = new HashMap<>();
    for (FormField field : descriptor.formFields()) {
      result.put(field.getId(), field);
    }
    return result;
  }
}