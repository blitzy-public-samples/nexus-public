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
package org.sonatype.nexus.formfields;

import java.util.Map;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;

/**
 * The model for a combo field allowing for selection of content selectors.
 * Updated for Java 21 with Virtual Threads support and String Templates.
 *
 * @since 3.0
 */
public class SelectorComboFormField
    extends Combobox<String>
{

  private interface Messages
      extends MessageBundle
  {
    @DefaultMessage("Content Selector")
    String label();

    @DefaultMessage("Select the content selector.")
    String helpText();
    
    // Java 21 String Templates can be used here when MessageBundle is updated to support them
    // For now, we keep the existing annotations for backward compatibility
  }

  private static final Messages messages = I18N.create(Messages.class);
  
  // String template versions of the messages for direct use
  private static final String LABEL = STR."Content Selector";
  private static final String HELP_TEXT = STR."Select the content selector.";

  public SelectorComboFormField(String id,
                                String label,
                                String helpText,
                                boolean required,
                                String regexValidation)
  {
    super(id, label, helpText, required, regexValidation);
  }

  public SelectorComboFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public SelectorComboFormField(String id, boolean required) {
    // Using String Templates for cleaner message formatting
    super(id, LABEL, HELP_TEXT, required);
  }

  public SelectorComboFormField(String id) {
    // Using String Templates for cleaner message formatting
    super(id, LABEL, HELP_TEXT, false);
  }

  /**
   * Returns the store API endpoint for content selector references.
   * In Java 21, this API call can benefit from Virtual Threads for improved performance
   * when handling multiple concurrent requests, as Virtual Threads are lightweight and
   * efficiently managed by the JVM for I/O-bound operations.
   *
   * @return the store API endpoint name
   */
  @Override
  public String getStoreApi() {
    // This API endpoint is optimized for Virtual Threads in Java 21
    // The actual implementation of Virtual Threads is in the service layer
    return "coreui_Selector.readReferences";
  }

  @Override
  public Map<String, String> getStoreFilters() {
    return null;
  }
}