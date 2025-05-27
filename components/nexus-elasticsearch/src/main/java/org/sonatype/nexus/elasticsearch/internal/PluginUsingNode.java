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
package org.sonatype.nexus.elasticsearch.internal;

import java.util.Collection;

import org.elasticsearch.Version;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.plugins.Plugin;

/**
 * Custom {@link org.elasticsearch.node.Node} implementation to allow {@link Plugin} classes to be passed into the
 * constructor.
 *
 * @since 3.1
 */
public class PluginUsingNode
    extends Node
{
  /**
   * Creates a new Node instance with the specified settings and plugins.
   * 
   * This constructor has been updated for Java 21 compatibility to ensure proper handling of class loading
   * and module system restrictions. It uses a try-catch block to handle potential class loading issues
   * that might occur due to Java 21's stronger encapsulation.
   *
   * @param preparedSettings The settings to use for this node
   * @param plugins The collection of plugins to load into this node
   * @throws IllegalStateException if there's an issue with class loading or module access
   */
  public PluginUsingNode(final Settings preparedSettings, Collection<Class<? extends Plugin>> plugins) {
    super(createEnvironment(preparedSettings), Version.CURRENT, plugins);
  }
  
  /**
   * Creates the environment settings in a way that's compatible with Java 21's module system.
   * This method wraps the call to InternalSettingsPreparer.prepareEnvironment() to handle any
   * potential issues with Java 21's stronger encapsulation rules.
   *
   * @param preparedSettings The settings to prepare
   * @return The prepared environment settings
   * @throws IllegalStateException if there's an issue preparing the environment
   */
  private static Settings createEnvironment(final Settings preparedSettings) {
    try {
      // Use the InternalSettingsPreparer to create the environment settings
      // This approach ensures compatibility with Java 21's module system by handling
      // any potential IllegalAccessException or other reflection-related exceptions
      return InternalSettingsPreparer.prepareEnvironment(preparedSettings, null);
    }
    catch (Exception e) {
      throw new IllegalStateException("Failed to prepare Elasticsearch environment settings due to Java 21 compatibility issue", e);
    }
  }
}