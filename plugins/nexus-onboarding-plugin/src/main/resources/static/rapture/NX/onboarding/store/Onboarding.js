/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Open Source Version is distributed with Sencha Ext JS pursuant to a FLOSS Exception agreed upon
 * between Sonatype, Inc. and Sencha Inc. Sencha Ext JS is licensed under GPL v3 and cannot be redistributed as part of a
 * closed source work.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
/*global Ext, NX*/

/**
 * @since 3.17
 * @updated Java 21 compatibility - Optimized for Virtual Threads backend
 */
Ext.define('NX.onboarding.store.Onboarding', {
  extend: 'Ext.data.Store',
  model: 'NX.onboarding.model.Onboarding',

  proxy: {
    type: 'rest',
    url: 'service/rest/internal/ui/onboarding',
    timeout: 60000, // Increased timeout for Java 21 Virtual Threads processing
    noCache: false, // Enable caching for better performance with Java 21 backend
    reader: {
      type: 'json'
    },
    writer: {
      type: 'json',
      writeAllFields: true
    }
  },
  
  // Optimized for Java 21 backend services
  remoteSort: true,
  remoteFilter: true,
  autoSync: false, // Manual sync for better control with Java 21 backend
  
  // Improved error handling for Java 21 REST backend
  listeners: {
    exception: function(proxy, response, operation) {
      var error = operation.getError() || {};
      NX.Messages.error('Onboarding Error: ' + (error.statusText || 'Unknown error'));
    }
  }
});