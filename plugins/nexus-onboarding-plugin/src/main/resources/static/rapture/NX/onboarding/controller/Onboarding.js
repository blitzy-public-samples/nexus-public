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
 * Onboarding controller responsible for managing the onboarding wizard flow.
 * Enhanced for compatibility with Java 21 backend to ensure proper state management
 * and event handling with the updated runtime environment.
 * 
 * @since 3.17
 */
Ext.define('NX.onboarding.controller.Onboarding', {
  extend: 'NX.wizard.Controller',
  requires: [
    'NX.Messages',
    'NX.I18n',
    'NX.onboarding.step.OnboardingStartStep',
    'NX.onboarding.step.CommunityDiscoverStep',
    'NX.onboarding.step.CommunityEulaStep',
    'NX.onboarding.step.ChangeAdminPasswordStep',
    'NX.onboarding.step.ConfigureAnonymousAccessStep',
    'NX.onboarding.step.OnboardingCompleteStep',
    'NX.State'
  ],
  views: [
    'OnboardingWizard',
    'OnboardingStartScreen',
    'ChangeAdminPasswordScreen',
    'ConfigureAnonymousAccessScreen',
    'OnboardingCompleteScreen',
    'OnboardingModal',
    'CommunityEulaScreen',
    'CommunityDiscoverScreen'
  ],
  stores: [
    'Onboarding'
  ],

  /**
   * @override
   */
  init: function() {
    var me = this;

    me.callParent();

    me.listen({
      component: {
        'nx-onboarding-wizard': {
          closed: me.reset
        },
        'nx-signin': {
          beforeshow: me.beforeShowSignin
        }
      },
      controller: {
        '#State': {
          changed: me.stateChanged,
          userAuthenticated: me.stateChanged
        }
      },
      store: {
        '#Onboarding': {
          load: me.itemsLoaded,
          // Add error handling for store load failures with Java 21 backend
          exception: me.onStoreException
        }
      }
    });
  },

  /**
   * Handle store load exceptions, which might occur due to backend changes in Java 21
   * @private
   */
  onStoreException: function(store, response, operation) {
    var errorMessage = 'Error loading onboarding steps';
    
    // Extract more detailed error information if available
    if (response && response.error) {
      errorMessage += ': ' + response.error.statusText || response.error.message || '';
    }
    
    NX.Messages.error(errorMessage);
    console.error('Onboarding store load exception:', response);
  },

  beforeShowSignin: function(signin) {
    var doOnboarding = NX.State.getValue('onboarding.required'),
        passwordFile = NX.State.getValue("admin.password.file"),
        msg = NX.I18n.format('Onboarding_Authenticate', Ext.htmlEncode(passwordFile));

    if (doOnboarding && passwordFile) {
      signin.addMessage(msg);
    }
    else {
      signin.clearMessage();
    }
  },

  /**
   * Handle state changes from the Java 21 backend
   * Improved to handle potential state inconsistencies
   */
  stateChanged: function() {
    var me = this,
        isOnboardingRequired = NX.State.getValue('onboarding.required'),
        user = NX.State.getUser();
    
    // Enhanced logging for state changes to help diagnose issues with Java 21 backend
    if (NX.global.console && NX.global.console.debug) {
      NX.global.console.debug('Onboarding state changed - required:', isOnboardingRequired, 
                              'user:', user ? user.id : 'none');
    }

    // More robust state checking to handle potential inconsistencies
    if (isOnboardingRequired === true && user && typeof user === 'object' && user['administrator'] === true) {
      me.loadItems();
    }
  },

  /**
   * @override
   */
  finish: function() {
    // Update state in a try/catch to handle potential issues with Java 21 backend
    try {
      NX.State.setValue('onboarding.required', false);
    } 
    catch (e) {
      NX.Messages.error('Error updating onboarding state: ' + e.message);
      console.error('Error in onboarding finish:', e);
    }
    
    var results = Ext.ComponentQuery.query('nx-onboarding-modal');
    if (results && results.length) {
      results[0].close();
    }
  },

  /**
   * Load onboarding items from the store
   * Enhanced with better error handling for Java 21 backend
   */
  loadItems: function() {
    var me = this,
        store = me.getStore('Onboarding');

    if (!store) {
      NX.Messages.error('Onboarding store not available');
      return;
    }

    if (!store.isLoaded() && !store.isLoading()) {
      // Add error handling with timeout for Java 21 backend
      var loadTimeout = setTimeout(function() {
        if (store.isLoading()) {
          NX.Messages.warning('Onboarding data is taking longer than expected to load');
        }
      }, 10000); // 10 second timeout
      
      // Use callback to clear timeout
      store.load({
        callback: function() {
          clearTimeout(loadTimeout);
        }
      });
    }
  },

  /**
   * Process loaded items and initialize the onboarding wizard
   * Enhanced with better error handling for Java 21 backend
   */
  itemsLoaded: function (store, records, successful) {
    var me = this;
    
    // Always register the start step
    me.registerStep('NX.onboarding.step.OnboardingStartStep');
    
    if (successful && Array.isArray(records)) {
      // Log the number of steps loaded to help with debugging
      if (NX.global.console && NX.global.console.debug) {
        NX.global.console.debug('Onboarding loaded ' + records.length + ' steps');
      }
      
      // Process each record and register the corresponding step
      records.forEach(function(record) {
        try {
          var stepType = record.get('type');
          if (stepType) {
            var stepClass = 'NX.onboarding.step.' + stepType + 'Step';
            me.registerStep(stepClass);
          } else {
            console.warn('Onboarding step missing type:', record.data);
          }
        } catch (e) {
          console.error('Error registering onboarding step:', e, record.data);
        }
      });
    }
    else {
      // Enhanced error message with more details
      var errorMsg = NX.I18n.get('Onboarding_LoadStepsError');
      if (!successful) {
        errorMsg += ' - Server returned unsuccessful response';
      }
      else if (!Array.isArray(records)) {
        errorMsg += ' - Invalid response format';
      }
      NX.Messages.error(errorMsg);
      console.error('Onboarding steps load error:', successful, records);
    }
    
    // Always register the complete step
    me.registerStep('NX.onboarding.step.OnboardingCompleteStep');

    // Create and show the onboarding modal
    try {
      Ext.widget('nx-onboarding-modal');
      me.load();
    } catch (e) {
      NX.Messages.error('Error creating onboarding wizard: ' + e.message);
      console.error('Error creating onboarding modal:', e);
    }
  }
});