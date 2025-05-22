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
package org.sonatype.nexus.jmx.internal;

import java.lang.annotation.Annotation;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.jmx.MBean;
import org.sonatype.nexus.jmx.ObjectNameEntry;
import org.sonatype.nexus.jmx.reflect.ManagedObject;
import org.sonatype.nexus.jmx.reflect.ReflectionMBeanBuilder;

import com.google.common.base.Strings;
import com.google.inject.Key;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.EagerSingleton;
import org.eclipse.sisu.Mediator;
import org.eclipse.sisu.inject.BeanLocator;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Handles registration of {@link ManagedObject} components.
 *
 * @since 3.0
 */
@Named
@EagerSingleton
public class ManagedObjectRegistrar
    extends ComponentSupport
{
  private final ExecutorService jmxExecutor;

  @Inject
  public ManagedObjectRegistrar(final BeanLocator beanLocator,
                                final MBeanServer server)
  {
    checkNotNull(beanLocator);
    checkNotNull(server);

    // Create a virtual thread executor for JMX operations
    this.jmxExecutor = Executors.newVirtualThreadPerTaskExecutor();

    beanLocator.watch(Key.get(Object.class), new ManageObjectMediator(), server);
  }

  private class ManageObjectMediator
      implements Mediator<Annotation, Object, MBeanServer>
  {
    @Override
    public void add(final BeanEntry<Annotation, Object> entry, final MBeanServer server) throws Exception {
      ManagedObject descriptor = descriptor(entry);
      if (descriptor == null) {
        return;
      }

      // Submit MBean registration to virtual thread executor
      jmxExecutor.submit(() -> {
        try {
          ObjectName name = objectName(descriptor, entry);
          log.debug(STR."Registering: \{name} -> \{entry}");
          MBean mbean = mbean(descriptor, entry);
          
          // Use try-with-resources for enhanced error handling
          try {
            // Perform registration with proper access controls for Java 21
            AccessController.doPrivileged((PrivilegedExceptionAction<Void>) () -> {
              server.registerMBean(mbean, name);
              return null;
            });
          } catch (PrivilegedActionException e) {
            throw e.getException();
          }
        }
        catch (Exception e) {
          log.warn(STR."Failed to export: \{entry}; ignoring", e);
        }
        return null;
      });
    }

    @Override
    public void remove(final BeanEntry<Annotation, Object> entry, final MBeanServer server) throws Exception {
      ManagedObject descriptor = descriptor(entry);
      if (descriptor == null) {
        return;
      }

      // Submit MBean unregistration to virtual thread executor
      jmxExecutor.submit(() -> {
        try {
          ObjectName name = objectName(descriptor, entry);
          log.debug(STR."Un-registering: \{name} -> \{entry}");
          
          // Use try-with-resources for enhanced error handling
          try {
            // Perform unregistration with proper access controls for Java 21
            AccessController.doPrivileged((PrivilegedExceptionAction<Void>) () -> {
              server.unregisterMBean(name);
              return null;
            });
          } catch (PrivilegedActionException e) {
            throw e.getException();
          }
        }
        catch (Exception e) {
          log.warn(STR."Failed to un-export: \{entry}; ignoring", e);
        }
        return null;
      });
    }
  }

  @Nullable
  private ManagedObject descriptor(final BeanEntry<Annotation, Object> entry) {
    Class<?> type = entry.getImplementationClass();
    return type.getAnnotation(ManagedObject.class);
  }

  /**
   * Determine {@link ObjectName} for given {@link BeanEntry}.
   */
  private ObjectName objectName(final ManagedObject descriptor, final BeanEntry<Annotation, Object> entry)
      throws Exception
  {
    Class<?> type = entry.getImplementationClass();

    // default domain to package if missing
    String domain = descriptor.domain();
    if (Strings.emptyToNull(domain) == null) {
      domain = type.getPackage().getName();
    }

    // Hashtable is required by ancient JMX api
    Hashtable<String, String> entries = new Hashtable<>();

    // add custom object-name entries
    for (ObjectNameEntry kv : descriptor.entries()) {
      entries.put(kv.name(), kv.value());
    }

    // set object-name 'type'
    entries.put("type", type(descriptor, entry));

    // optionally set object-name 'name'
    String name = name(descriptor, entry);
    if (name != null) {
      entries.put("name", name);
    }

    return new ObjectName(domain, entries);
  }

  /**
   * Determine object-name 'type' value.
   */
  private String type(final ManagedObject descriptor, final BeanEntry<Annotation, Object> entry) {
    String type = Strings.emptyToNull(descriptor.type());
    if (type == null) {
      if (descriptor.typeClass() != null && descriptor.typeClass() != Void.class /*default*/) {
        type = descriptor.typeClass().getSimpleName();
      }
      else {
        // TODO: Consider inspecting @Typed?
        // TODO: It would really be nice if we could infer the proper intf type of simple components, but this may be too complex?
        type = entry.getImplementationClass().getSimpleName();
      }
    }
    return type;
  }

  /**
   * Determine object-name 'name' value.
   */
  @Nullable
  private String name(final ManagedObject descriptor, final BeanEntry<Annotation, Object> entry) {
    String name = Strings.emptyToNull(descriptor.name());
    if (name == null) {
      Class<?> type = entry.getImplementationClass();

      // use @Named entry-key if possible, this will be filled in by sisu
      if (entry.getKey() instanceof Named) {
        Named named = (Named) entry.getKey();

        // if named-value is NOT the same as the impl-type-name then use it
        // ie. if org.sonatype.nexus.FooImpl == org.sonatype.nexus.FooImpl, then leave name as null
        if (!type.getName().equals(named.value())) {
          name = Strings.emptyToNull(named.value());
        }
      }

      // else lookup @Named directly unable to determine from entry-key
      if (name == null) {
        Named named = entry.getImplementationClass().getAnnotation(Named.class);
        if (named != null) {
          name = Strings.emptyToNull(named.value());
        }
      }
    }
    return name;
  }

  /**
   * Construct mbean for given {@link BeanEntry} discovering its attributes and operations.
   */
  private MBean mbean(final ManagedObject descriptor, final BeanEntry<Annotation, Object> entry) throws Exception {
    Class<?> type = entry.getImplementationClass();

    ReflectionMBeanBuilder builder = new ReflectionMBeanBuilder(type);

    // attach managed target using lambda expression instead of anonymous inner class
    builder.target(() -> entry.getProvider().get());

    // allow custom description, or expose what sisu tells us
    String description = Strings.emptyToNull(descriptor.description());
    if (description == null) {
      description = entry.getDescription();
    }
    builder.description(description);

    // discover managed members
    builder.discover();

    return builder.build();
  }
}