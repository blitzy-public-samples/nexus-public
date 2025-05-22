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
import {inspect} from '@xstate/inspect';

/**
 * Configures the plugin system for the Nexus UI.
 * This function initializes the plugin registry and sets up the onStart handler
 * that will be called by the ExtJS codebase to register React plugins.
 * 
 * Compatible with Java 21 backend services, ensuring proper interaction with
 * the updated security framework and state management.
 */
export default function configurePlugins() {
  // Declare an initial (empty) array for plugin configurations
  window.plugins = [];
  window.ReactComponents = {};
  window.BlobStoreTypes = {};

  // A function for the ExtJS codebase to call to register React plugins
  window.onStart = function() {
    try {
      window.plugins.forEach((plugin) => {
        if (plugin.features) {
          plugin.features.forEach(registerFeature);
        }
      });
      console.debug('Plugin registration completed successfully');
    } catch (error) {
      console.error('Error during plugin registration:', error);
    }
  };
};

/**
 * Registers a feature with the Nexus UI.
 * This function handles the registration of features with the ExtJS framework,
 * including visibility conditions that interact with the Java 21 backend services.
 * 
 * @param feature - {
 *   mode: 'browse' || 'admin',
 *   path: '/somepath',
 *   text: 'menu label',
 *   textComplement: 'text complement for menu label'
 *   description: 'description used for the header when visiting the feature',
 *   view: <reactViewReference>,
 *   iconCls: 'x-fa fa-icon-type',
 *   visibility: {
 *     bundle: 'an optional bundle expected to be available for the feature to be visible',
 *     featureFlags: [{ // optional
 *       key: 'featureFlagName',
 *       defaultValue: true // the value the feature flag is set to by default (optional)
 *     }],
 *     licenseValid: [{ // optional
 *       key: 'stateWithLicenseFlagName',
 *       defaultValue: false // the value the license validity is set to by default (optional)
 *     }],
 *     statesEnabled: [{ // optional
 *       key: 'stateWithEnabledFlagName',
 *       defaultValue: false // the value the state enablement is set to by default (optional)
 *     }],
 *     permissions: ['optional array of permission strings', 'nexus:settings:read']
 *   }
 * }
 */
function registerFeature(feature) {
  try {
    console.log(`Register feature`, feature);
    const reactViewController = Ext.getApplication().getController('NX.coreui.controller.react.ReactViewController');
    Ext.getApplication().getFeaturesController().registerFeature({
      mode: feature.mode,
      path: feature.path,
      text: feature.text,
      textComplement: feature.textComplement,
      description: feature.description,
      weight: feature.weight,
      view: {
        xtype: 'nx-coreui-react-main-container',
        itemId: 'react-view',
        reactView: feature.view
      },
      iconCls: feature.iconCls,
      visible: function () {
        var isVisible = true;
        const visibility = feature.visibility;

        if (!visibility) {
          console.warn('feature is active due to no visibility configuration defined', feature);
          return isVisible;
        }

        // Check bundle availability
        if (visibility.bundle) {
          isVisible = NX.app.Application.bundleActive(visibility.bundle)
          console.debug("bundleActive="+isVisible, visibility.bundle);
        }

        // Check license validity - compatible with Java 21 state management
        if (isVisible && visibility.licenseValid) {
          try {
            isVisible = visibility.licenseValid.every(licenseValid => {
              const stateValue = NX.State.getValue(licenseValid.key, licenseValid.defaultValue);
              return stateValue && stateValue['licenseValid'];
            });
            console.debug("licenseValid="+isVisible, visibility.licenseValid);
          } catch (error) {
            console.error('Error checking license validity:', error);
            isVisible = false;
          }
        }

        // Check feature flags - compatible with Java 21 state management
        if (isVisible && visibility.featureFlags) {
          try {
            isVisible = visibility.featureFlags.every(featureFlag => {
              return NX.State.getValue(featureFlag.key, featureFlag.defaultValue);
            });
            console.debug("featureFlagsActive="+isVisible, visibility.featureFlags);
          } catch (error) {
            console.error('Error checking feature flags:', error);
            isVisible = false;
          }
        }

        // Check state enablement - compatible with Java 21 state management
        if (isVisible && visibility.statesEnabled) {
          try {
            isVisible = visibility.statesEnabled.every(state => {
              const stateValue = NX.State.getValue(state.key, state.defaultValue);
              if (typeof stateValue === "boolean") {
                return stateValue;
              }
              else if (Array.isArray(stateValue)) {
                return stateValue.length > 0;
              }
              else if (stateValue && typeof stateValue === 'object') {
                return stateValue.enabled;
              }
              return false; // Default to false if state value is undefined or null
            });
            console.debug("statesEnabled="+isVisible, visibility.statesEnabled);
          } catch (error) {
            console.error('Error checking state enablement:', error);
            isVisible = false;
          }
        }

        // Check permissions - compatible with Java 21 security framework
        if (isVisible && visibility.permissions) {
          try {
            isVisible = visibility.permissions.every((permission) => NX.Permissions.check(permission));
            console.debug("permissionCheck="+isVisible, visibility.permissions);
          } catch (error) {
            console.error('Error checking permissions:', error);
            isVisible = false;
          }
        }

        // Check editions
        if (isVisible && visibility.editions) {
          try {
            isVisible = visibility.editions.some((edition) => NX.State.getEdition() === edition);
            console.debug("editionCheck="+isVisible, visibility.editions);
          } catch (error) {
            console.error('Error checking editions:', error);
            isVisible = false;
          }
        }

        // Check if user is required
        if (isVisible && visibility.requiresUser) {
          isVisible = NX.Security.hasUser();
        }

        return isVisible;
      }
    }, reactViewController);
  } catch (error) {
    console.error(`Failed to register feature ${feature.path}:`, error);
  }
}