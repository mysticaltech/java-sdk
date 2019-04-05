/****************************************************************************
 * Copyright 2016-2019, Optimizely, Inc. and contributors                   *
 *                                                                          *
 * Licensed under the Apache License, Version 2.0 (the "License");          *
 * you may not use this file except in compliance with the License.         *
 * You may obtain a copy of the License at                                  *
 *                                                                          *
 *    http://www.apache.org/licenses/LICENSE-2.0                            *
 *                                                                          *
 * Unless required by applicable law or agreed to in writing, software      *
 * distributed under the License is distributed on an "AS IS" BASIS,        *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 * See the License for the specific language governing permissions and      *
 * limitations under the License.                                           *
 ***************************************************************************/
package com.optimizely.ab;

import com.optimizely.ab.annotations.VisibleForTesting;
import com.optimizely.ab.bucketing.Bucketer;
import com.optimizely.ab.bucketing.DecisionService;
import com.optimizely.ab.bucketing.FeatureDecision;
import com.optimizely.ab.bucketing.UserProfileService;
import com.optimizely.ab.config.*;
import com.optimizely.ab.config.parser.ConfigParseException;
import com.optimizely.ab.error.ErrorHandler;
import com.optimizely.ab.error.NoOpErrorHandler;
import com.optimizely.ab.event.EventHandler;
import com.optimizely.ab.event.LogEvent;
import com.optimizely.ab.event.internal.BuildVersionInfo;
import com.optimizely.ab.event.internal.EventFactory;
import com.optimizely.ab.event.internal.payload.EventBatch.ClientEngine;
import com.optimizely.ab.notification.NotificationCenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level container class for Optimizely functionality.
 * Thread-safe, so can be created as a singleton and safely passed around.
 *
 * Example instantiation:
 * <pre>
 *     Optimizely optimizely = Optimizely.builder(projectWatcher, eventHandler).build();
 * </pre>
 *
 * To activate an experiment and perform variation specific processing:
 * <pre>
 *     Variation variation = optimizely.activate(experimentKey, userId, attributes);
 *     if (variation.is("ALGORITHM_A")) {
 *         // execute code for algorithm A
 *     } else if (variation.is("ALGORITHM_B")) {
 *         // execute code for algorithm B
 *     } else {
 *         // execute code for default algorithm
 *     }
 * </pre>
 *
 * <b>NOTE:</b> by default, all exceptions originating from {@code Optimizely} calls are suppressed.
 * For example, attempting to activate an experiment that does not exist in the project config will cause an error
 * to be logged, and for the "control" variation to be returned.
 */
@ThreadSafe
public class Optimizely {

    private static final Logger logger = LoggerFactory.getLogger(Optimizely.class);

    @VisibleForTesting
    final DecisionService decisionService;
    @VisibleForTesting
    final EventFactory eventFactory;
    @VisibleForTesting
    final EventHandler eventHandler;
    @VisibleForTesting
    final ErrorHandler errorHandler;

    private final ProjectConfigManager projectConfigManager;

    // TODO should be private
    public final NotificationCenter notificationCenter = new NotificationCenter();

    @Nullable
    private final UserProfileService userProfileService;

    private Optimizely(@Nonnull EventHandler eventHandler,
                       @Nonnull EventFactory eventFactory,
                       @Nonnull ErrorHandler errorHandler,
                       @Nonnull DecisionService decisionService,
                       @Nonnull UserProfileService userProfileService,
                       @Nonnull ProjectConfigManager projectConfigManager
    ) {
        this.decisionService = decisionService;
        this.eventHandler = eventHandler;
        this.eventFactory = eventFactory;
        this.errorHandler = errorHandler;
        this.userProfileService = userProfileService;
        this.projectConfigManager = projectConfigManager;
    }

    /**
     * Determine if the instance of the Optimizely client is valid. An instance can be deemed invalid if it was not
     * initialized properly due to an invalid datafile being passed in.
     *
     * @return True if the Optimizely instance is valid.
     * False if the Optimizely instance is not valid.
     */
    public boolean isValid() {
        return getProjectConfig() != null;
    }

    //======== activate calls ========//

    @Nullable
    public Variation activate(@Nonnull String experimentKey,
                              @Nonnull String userId) throws UnknownExperimentException {
        return activate(experimentKey, userId, Collections.<String, String>emptyMap());
    }

    @Nullable
    public Variation activate(@Nonnull String experimentKey,
                              @Nonnull String userId,
                              @Nonnull Map<String, ?> attributes) throws UnknownExperimentException {

        if (experimentKey == null) {
            logger.error("The experimentKey parameter must be nonnull.");
            return null;
        }

        if (!validateUserId(userId)) {
            logger.info("Not activating user for experiment \"{}\".", experimentKey);
            return null;
        }

        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing activate call.");
            return null;
        }

        Experiment experiment = projectConfig.getExperimentForKey(experimentKey, errorHandler);
        if (experiment == null) {
            // if we're unable to retrieve the associated experiment, return null
            logger.info("Not activating user \"{}\" for experiment \"{}\".", userId, experimentKey);
            return null;
        }

        return activate(projectConfig, experiment, userId, attributes);
    }

    @Nullable
    public Variation activate(@Nonnull Experiment experiment,
                              @Nonnull String userId) {
        return activate(experiment, userId, Collections.<String, String>emptyMap());
    }

    @Nullable
    public Variation activate(@Nonnull Experiment experiment,
                              @Nonnull String userId,
                              @Nonnull Map<String, ?> attributes) {
        return activate(getProjectConfig(), experiment, userId, attributes);
    }

    @Nullable
    private Variation activate(@Nullable ProjectConfig projectConfig,
                               @Nonnull Experiment experiment,
                               @Nonnull String userId,
                               @Nonnull Map<String, ?> attributes) {
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing activate call.");
            return null;
        }

        if (!validateUserId(userId)) {
            logger.info("Not activating user \"{}\" for experiment \"{}\".", userId, experiment.getKey());
            return null;
        }
        Map<String, ?> copiedAttributes = copyAttributes(attributes);
        // bucket the user to the given experiment and dispatch an impression event
        Variation variation = decisionService.getVariation(experiment, userId, copiedAttributes, projectConfig);
        if (variation == null) {
            logger.info("Not activating user \"{}\" for experiment \"{}\".", userId, experiment.getKey());
            return null;
        }

        sendImpression(projectConfig, experiment, userId, copiedAttributes, variation);

        return variation;
    }

    private void sendImpression(@Nonnull ProjectConfig projectConfig,
                                @Nonnull Experiment experiment,
                                @Nonnull String userId,
                                @Nonnull Map<String, ?> filteredAttributes,
                                @Nonnull Variation variation) {
        if (experiment.isRunning()) {
            LogEvent impressionEvent = eventFactory.createImpressionEvent(
                projectConfig,
                experiment,
                variation,
                userId,
                filteredAttributes);
            logger.info("Activating user \"{}\" in experiment \"{}\".", userId, experiment.getKey());

            if (logger.isDebugEnabled()) {
                logger.debug(
                    "Dispatching impression event to URL {} with params {} and payload \"{}\".",
                    impressionEvent.getEndpointUrl(), impressionEvent.getRequestParams(), impressionEvent.getBody());
            }

            try {
                eventHandler.dispatchEvent(impressionEvent);
            } catch (Exception e) {
                logger.error("Unexpected exception in event dispatcher", e);
            }

            notificationCenter.sendNotifications(NotificationCenter.NotificationType.Activate, experiment, userId,
                filteredAttributes, variation, impressionEvent);
        } else {
            logger.info("Experiment has \"Launched\" status so not dispatching event during activation.");
        }
    }

    //======== track calls ========//

    public void track(@Nonnull String eventName,
                      @Nonnull String userId) throws UnknownEventTypeException {
        track(eventName, userId, Collections.<String, String>emptyMap(), Collections.<String, Object>emptyMap());
    }

    public void track(@Nonnull String eventName,
                      @Nonnull String userId,
                      @Nonnull Map<String, ?> attributes) throws UnknownEventTypeException {
        track(eventName, userId, attributes, Collections.<String, String>emptyMap());
    }

    public void track(@Nonnull String eventName,
                      @Nonnull String userId,
                      @Nonnull Map<String, ?> attributes,
                      @Nonnull Map<String, ?> eventTags) throws UnknownEventTypeException {
        if (!validateUserId(userId)) {
            logger.info("Not tracking event \"{}\".", eventName);
            return;
        }

        if (eventName == null || eventName.trim().isEmpty()) {
            logger.error("Event Key is null or empty when non-null and non-empty String was expected.");
            logger.info("Not tracking event for user \"{}\".", userId);
            return;
        }

        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing isFeatureEnabled call.");
            return;
        }

        Map<String, ?> copiedAttributes = copyAttributes(attributes);

        EventType eventType = projectConfig.getEventTypeForName(eventName, errorHandler);
        if (eventType == null) {
            // if no matching event type could be found, do not dispatch an event
            logger.info("Not tracking event \"{}\" for user \"{}\".", eventName, userId);
            return;
        }

        if (eventTags == null) {
            logger.warn("Event tags is null when non-null was expected. Defaulting to an empty event tags map.");
        }

        // create the conversion event request parameters, then dispatch
        LogEvent conversionEvent = eventFactory.createConversionEvent(
            projectConfig,
            userId,
            eventType.getId(),
            eventType.getKey(),
            copiedAttributes,
            eventTags);

        logger.info("Tracking event \"{}\" for user \"{}\".", eventName, userId);

        if (logger.isDebugEnabled()) {
            logger.debug("Dispatching conversion event to URL {} with params {} and payload \"{}\".",
                conversionEvent.getEndpointUrl(), conversionEvent.getRequestParams(), conversionEvent.getBody());
        }

        try {
            eventHandler.dispatchEvent(conversionEvent);
        } catch (Exception e) {
            logger.error("Unexpected exception in event dispatcher", e);
        }

        notificationCenter.sendNotifications(NotificationCenter.NotificationType.Track, eventName, userId,
            copiedAttributes, eventTags, conversionEvent);
    }

    //======== FeatureFlag APIs ========//

    /**
     * Determine whether a boolean feature is enabled.
     * Send an impression event if the user is bucketed into an experiment using the feature.
     *
     * @param featureKey The unique key of the feature.
     * @param userId     The ID of the user.
     * @return True if the feature is enabled.
     * False if the feature is disabled.
     * False if the feature is not found.
     */
    @Nonnull
    public Boolean isFeatureEnabled(@Nonnull String featureKey,
                                    @Nonnull String userId) {
        return isFeatureEnabled(featureKey, userId, Collections.<String, String>emptyMap());
    }

    /**
     * Determine whether a boolean feature is enabled.
     * Send an impression event if the user is bucketed into an experiment using the feature.
     *
     * @param featureKey The unique key of the feature.
     * @param userId     The ID of the user.
     * @param attributes The user's attributes.
     * @return True if the feature is enabled.
     * False if the feature is disabled.
     * False if the feature is not found.
     */
    @Nonnull
    public Boolean isFeatureEnabled(@Nonnull String featureKey,
                                    @Nonnull String userId,
                                    @Nonnull Map<String, ?> attributes) {
        if (featureKey == null) {
            logger.warn("The featureKey parameter must be nonnull.");
            return false;
        } else if (userId == null) {
            logger.warn("The userId parameter must be nonnull.");
            return false;
        }

        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing isFeatureEnabled call.");
            return false;
        }

        FeatureFlag featureFlag = projectConfig.getFeatureKeyMapping().get(featureKey);
        if (featureFlag == null) {
            logger.info("No feature flag was found for key \"{}\".", featureKey);
            return false;
        }

        Map<String, ?> copiedAttributes = copyAttributes(attributes);
        FeatureDecision featureDecision = decisionService.getVariationForFeature(featureFlag, userId, copiedAttributes, projectConfig);

        if (featureDecision.variation != null) {
            if (featureDecision.decisionSource.equals(FeatureDecision.DecisionSource.EXPERIMENT)) {
                sendImpression(
                    projectConfig,
                    featureDecision.experiment,
                    userId,
                    copiedAttributes,
                    featureDecision.variation);
            } else {
                logger.info("The user \"{}\" is not included in an experiment for feature \"{}\".",
                    userId, featureKey);
            }
            if (featureDecision.variation.getFeatureEnabled()) {
                logger.info("Feature \"{}\" is enabled for user \"{}\".", featureKey, userId);
                return true;
            }
        }

        logger.info("Feature \"{}\" is not enabled for user \"{}\".", featureKey, userId);
        return false;
    }

    /**
     * Get the Boolean value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @return The Boolean value of the boolean single variable feature.
     * Null if the feature could not be found.
     */
    @Nullable
    public Boolean getFeatureVariableBoolean(@Nonnull String featureKey,
                                             @Nonnull String variableKey,
                                             @Nonnull String userId) {
        return getFeatureVariableBoolean(featureKey, variableKey, userId, Collections.<String, String>emptyMap());
    }

    /**
     * Get the Boolean value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @param attributes  The user's attributes.
     * @return The Boolean value of the boolean single variable feature.
     * Null if the feature or variable could not be found.
     */
    @Nullable
    public Boolean getFeatureVariableBoolean(@Nonnull String featureKey,
                                             @Nonnull String variableKey,
                                             @Nonnull String userId,
                                             @Nonnull Map<String, ?> attributes) {

        String variableValue = getFeatureVariableValueForType(
            featureKey,
            variableKey,
            userId,
            attributes,
            FeatureVariable.VariableType.BOOLEAN
        );
        if (variableValue != null) {
            return Boolean.parseBoolean(variableValue);
        }
        return null;
    }

    /**
     * Get the Double value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @return The Double value of the double single variable feature.
     * Null if the feature or variable could not be found.
     */
    @Nullable
    public Double getFeatureVariableDouble(@Nonnull String featureKey,
                                           @Nonnull String variableKey,
                                           @Nonnull String userId) {
        return getFeatureVariableDouble(featureKey, variableKey, userId, Collections.<String, String>emptyMap());
    }

    /**
     * Get the Double value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @param attributes  The user's attributes.
     * @return The Double value of the double single variable feature.
     * Null if the feature or variable could not be found.
     */
    @Nullable
    public Double getFeatureVariableDouble(@Nonnull String featureKey,
                                           @Nonnull String variableKey,
                                           @Nonnull String userId,
                                           @Nonnull Map<String, ?> attributes) {

        String variableValue = getFeatureVariableValueForType(
            featureKey,
            variableKey,
            userId,
            attributes,
            FeatureVariable.VariableType.DOUBLE
        );
        if (variableValue != null) {
            try {
                return Double.parseDouble(variableValue);
            } catch (NumberFormatException exception) {
                logger.error("NumberFormatException while trying to parse \"" + variableValue +
                    "\" as Double. " + exception);
            }
        }
        return null;
    }

    /**
     * Get the Integer value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @return The Integer value of the integer single variable feature.
     * Null if the feature or variable could not be found.
     */
    @Nullable
    public Integer getFeatureVariableInteger(@Nonnull String featureKey,
                                             @Nonnull String variableKey,
                                             @Nonnull String userId) {
        return getFeatureVariableInteger(featureKey, variableKey, userId, Collections.<String, String>emptyMap());
    }

    /**
     * Get the Integer value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @param attributes  The user's attributes.
     * @return The Integer value of the integer single variable feature.
     * Null if the feature or variable could not be found.
     */
    @Nullable
    public Integer getFeatureVariableInteger(@Nonnull String featureKey,
                                             @Nonnull String variableKey,
                                             @Nonnull String userId,
                                             @Nonnull Map<String, ?> attributes) {

        String variableValue = getFeatureVariableValueForType(
            featureKey,
            variableKey,
            userId,
            attributes,
            FeatureVariable.VariableType.INTEGER
        );
        if (variableValue != null) {
            try {
                return Integer.parseInt(variableValue);
            } catch (NumberFormatException exception) {
                logger.error("NumberFormatException while trying to parse \"" + variableValue +
                    "\" as Integer. " + exception.toString());
            }
        }
        return null;
    }

    /**
     * Get the String value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @return The String value of the string single variable feature.
     * Null if the feature or variable could not be found.
     */
    @Nullable
    public String getFeatureVariableString(@Nonnull String featureKey,
                                           @Nonnull String variableKey,
                                           @Nonnull String userId) {
        return getFeatureVariableString(featureKey, variableKey, userId, Collections.<String, String>emptyMap());
    }

    /**
     * Get the String value of the specified variable in the feature.
     *
     * @param featureKey  The unique key of the feature.
     * @param variableKey The unique key of the variable.
     * @param userId      The ID of the user.
     * @param attributes  The user's attributes.
     * @return The String value of the string single variable feature.
     * Null if the feature or variable could not be found.
     */
    @Nullable
    public String getFeatureVariableString(@Nonnull String featureKey,
                                           @Nonnull String variableKey,
                                           @Nonnull String userId,
                                           @Nonnull Map<String, ?> attributes) {

        return getFeatureVariableValueForType(
            featureKey,
            variableKey,
            userId,
            attributes,
            FeatureVariable.VariableType.STRING);
    }

    @VisibleForTesting
    String getFeatureVariableValueForType(@Nonnull String featureKey,
                                          @Nonnull String variableKey,
                                          @Nonnull String userId,
                                          @Nonnull Map<String, ?> attributes,
                                          @Nonnull FeatureVariable.VariableType variableType) {
        if (featureKey == null) {
            logger.warn("The featureKey parameter must be nonnull.");
            return null;
        } else if (variableKey == null) {
            logger.warn("The variableKey parameter must be nonnull.");
            return null;
        } else if (userId == null) {
            logger.warn("The userId parameter must be nonnull.");
            return null;
        }

        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing getFeatureVariableValueForType call. type: {}", variableType);
            return null;
        }

        FeatureFlag featureFlag = projectConfig.getFeatureKeyMapping().get(featureKey);
        if (featureFlag == null) {
            logger.info("No feature flag was found for key \"{}\".", featureKey);
            return null;
        }

        FeatureVariable variable = featureFlag.getVariableKeyToFeatureVariableMap().get(variableKey);
        if (variable == null) {
            logger.info("No feature variable was found for key \"{}\" in feature flag \"{}\".",
                variableKey, featureKey);
            return null;
        } else if (!variable.getType().equals(variableType)) {
            logger.info("The feature variable \"" + variableKey +
                "\" is actually of type \"" + variable.getType().toString() +
                "\" type. You tried to access it as type \"" + variableType.toString() +
                "\". Please use the appropriate feature variable accessor.");
            return null;
        }

        String variableValue = variable.getDefaultValue();
        Map<String, ?> copiedAttributes = copyAttributes(attributes);
        FeatureDecision featureDecision = decisionService.getVariationForFeature(featureFlag, userId, copiedAttributes, projectConfig);
        if (featureDecision.variation != null) {
            FeatureVariableUsageInstance featureVariableUsageInstance =
                featureDecision.variation.getVariableIdToFeatureVariableUsageInstanceMap().get(variable.getId());
            if (featureVariableUsageInstance != null) {
                variableValue = featureVariableUsageInstance.getValue();
            } else {
                variableValue = variable.getDefaultValue();
            }
        } else {
            logger.info("User \"{}\" was not bucketed into any variation for feature flag \"{}\". " +
                    "The default value \"{}\" for \"{}\" is being returned.",
                userId, featureKey, variableValue, variableKey
            );
        }

        return variableValue;
    }

    /**
     * Get the list of features that are enabled for the user.
     * TODO revisit this method. Calling this as-is can dramatically increase visitor impression counts.
     *
     * @param userId     The ID of the user.
     * @param attributes The user's attributes.
     * @return List of the feature keys that are enabled for the user if the userId is empty it will
     * return Empty List.
     */
    public List<String> getEnabledFeatures(@Nonnull String userId, @Nonnull Map<String, ?> attributes) {
        List<String> enabledFeaturesList = new ArrayList<String>();
        if (!validateUserId(userId)) {
            return enabledFeaturesList;
        }

        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing isFeatureEnabled call.");
            return enabledFeaturesList;
        }

        Map<String, ?> copiedAttributes = copyAttributes(attributes);
        for (FeatureFlag featureFlag : projectConfig.getFeatureFlags()) {
            String featureKey = featureFlag.getKey();
            if (isFeatureEnabled(featureKey, userId, copiedAttributes))
                enabledFeaturesList.add(featureKey);
        }

        return enabledFeaturesList;
    }

    //======== getVariation calls ========//

    @Nullable
    public Variation getVariation(@Nonnull Experiment experiment,
                                  @Nonnull String userId) throws UnknownExperimentException {

        return getVariation(experiment, userId, Collections.<String, String>emptyMap());
    }

    @Nullable
    public Variation getVariation(@Nonnull Experiment experiment,
                                  @Nonnull String userId,
                                  @Nonnull Map<String, ?> attributes) throws UnknownExperimentException {
        Map<String, ?> copiedAttributes = copyAttributes(attributes);

        return decisionService.getVariation(experiment, userId, copiedAttributes, getProjectConfig());
    }

    @Nullable
    public Variation getVariation(@Nonnull String experimentKey,
                                  @Nonnull String userId) throws UnknownExperimentException {

        return getVariation(experimentKey, userId, Collections.<String, String>emptyMap());
    }

    @Nullable
    public Variation getVariation(@Nonnull String experimentKey,
                                  @Nonnull String userId,
                                  @Nonnull Map<String, ?> attributes) {
        if (!validateUserId(userId)) {
            return null;
        }

        if (experimentKey == null || experimentKey.trim().isEmpty()) {
            logger.error("The experimentKey parameter must be nonnull.");
            return null;
        }

        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing isFeatureEnabled call.");
            return null;
        }

        Experiment experiment = projectConfig.getExperimentForKey(experimentKey, errorHandler);
        if (experiment == null) {
            // if we're unable to retrieve the associated experiment, return null
            return null;
        }
        Map<String, ?> copiedAttributes = copyAttributes(attributes);
        return decisionService.getVariation(experiment, userId, copiedAttributes, projectConfig);
    }

    /**
     * Force a user into a variation for a given experiment.
     * The forced variation value does not persist across application launches.
     * If the experiment key is not in the project file, this call fails and returns false.
     * If the variationKey is not in the experiment, this call fails.
     *
     * @param experimentKey The key for the experiment.
     * @param userId        The user ID to be used for bucketing.
     * @param variationKey  The variation key to force the user into.  If the variation key is null
     *                      then the forcedVariation for that experiment is removed.
     * @return boolean A boolean value that indicates if the set completed successfully.
     */
    public boolean setForcedVariation(@Nonnull String experimentKey,
                                      @Nonnull String userId,
                                      @Nullable String variationKey) {
        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing isFeatureEnabled call.");
            return false;
        }

        // if the experiment is not a valid experiment key, don't set it.
        Experiment experiment = projectConfig.getExperimentKeyMapping().get(experimentKey);
        if (experiment == null) {
            logger.error("Experiment {} does not exist in ProjectConfig for project {}", experimentKey, projectConfig.getProjectId());
            return false;
        }

        // TODO this is problematic if swapping out ProjectConfigs.
        // This state should be represented elsewhere like in a ephemeral UserProfileService.
        return decisionService.setForcedVariation(experiment, userId, variationKey);
    }

    /**
     * Gets the forced variation for a given user and experiment.
     * This method just calls into the {@link DecisionService#getForcedVariation(Experiment, String)}
     * method of the same signature.
     *
     * @param experimentKey The key for the experiment.
     * @param userId        The user ID to be used for bucketing.
     * @return The variation the user was bucketed into. This value can be null if the
     * forced variation fails.
     */
    @Nullable
    public Variation getForcedVariation(@Nonnull String experimentKey,
                                        @Nonnull String userId) {
        ProjectConfig projectConfig = getProjectConfig();
        if (projectConfig == null) {
            logger.error("Optimizely instance is not valid, failing getForcedVariation call.");
            return null;
        }

        Experiment experiment = projectConfig.getExperimentKeyMapping().get(experimentKey);
        if (experiment == null) {
            logger.debug("No experiment \"{}\" mapped to user \"{}\" in the forced variation map ", experimentKey, userId);
            return null;
        }

        return decisionService.getForcedVariation(experiment, userId);
    }

    /**
     * @return the current {@link ProjectConfig} instance.
     */
    @Nullable
    public ProjectConfig getProjectConfig() {
        return projectConfigManager.getConfig();
    }

    @Nullable
    public UserProfileService getUserProfileService() {
        return userProfileService;
    }

    //======== Helper methods ========//

    /**
     * Helper function to check that the provided userId is valid
     *
     * @param userId the userId being validated
     * @return whether the user ID is valid
     */
    private boolean validateUserId(String userId) {
        if (userId == null) {
            logger.error("The user ID parameter must be nonnull.");
            return false;
        }

        return true;
    }

    /**
     * Helper method which makes separate copy of attributesMap variable and returns it
     *
     * @param attributes map to copy
     * @return copy of attributes
     */
    private Map<String, ?> copyAttributes(Map<String, ?> attributes) {
        Map<String, ?> copiedAttributes = null;
        if (attributes != null) {
            copiedAttributes = new HashMap<>(attributes);
        }
        return copiedAttributes;
    }

    //======== Builder ========//

    public static Builder builder(@Nonnull String datafile,
                                  @Nonnull EventHandler eventHandler) {
        return new Builder(datafile, eventHandler);
    }

    public static Builder builder(@Nonnull ProjectConfigManager projectConfigManager,
                                  @Nonnull EventHandler eventHandler) {
        return new Builder(projectConfigManager, eventHandler);
    }

    /**
     * {@link Optimizely} instance builder.
     * <p>
     * <b>NOTE</b>, the default value for {@link #eventHandler} is a {@link NoOpErrorHandler} instance, meaning that the
     * created {@link Optimizely} object will <b>NOT</b> throw exceptions unless otherwise specified.
     *
     * @see #builder(String, EventHandler)
     */
    public static class Builder {

        private String datafile;
        private Bucketer bucketer;
        private DecisionService decisionService;
        private ErrorHandler errorHandler;
        private EventHandler eventHandler;
        private EventFactory eventFactory;
        private ClientEngine clientEngine;
        private String clientVersion;
        private ProjectConfig projectConfig;
        private ProjectConfigManager projectConfigManager;
        private UserProfileService userProfileService;

        public Builder(@Nonnull String datafile,
                       @Nonnull EventHandler eventHandler) {
            this.datafile = datafile;
            this.eventHandler = eventHandler;
        }

        public Builder(@Nonnull ProjectConfigManager projectConfigManager,
                       @Nonnull EventHandler eventHandler) {
            this.projectConfigManager = projectConfigManager;
            this.eventHandler = eventHandler;
        }

        public Builder withErrorHandler(ErrorHandler errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public Builder withUserProfileService(UserProfileService userProfileService) {
            this.userProfileService = userProfileService;
            return this;
        }

        public Builder withClientEngine(ClientEngine clientEngine) {
            this.clientEngine = clientEngine;
            return this;
        }

        public Builder withClientVersion(String clientVersion) {
            this.clientVersion = clientVersion;
            return this;
        }

        public Builder withConfigManager(ProjectConfigManager projectConfigManager) {
            this.projectConfigManager = projectConfigManager;
            return this;
        }

        // Helper function for making testing easier
        protected Builder withBucketing(Bucketer bucketer) {
            this.bucketer = bucketer;
            return this;
        }

        protected Builder withConfig(ProjectConfig projectConfig) {
            this.projectConfig = projectConfig;
            return this;
        }

        protected Builder withDecisionService(DecisionService decisionService) {
            this.decisionService = decisionService;
            return this;
        }

        protected Builder withEventBuilder(EventFactory eventFactory) {
            this.eventFactory = eventFactory;
            return this;
        }


        public Optimizely build() {
            if (clientEngine == null) {
                clientEngine = ClientEngine.JAVA_SDK;
            }

            if (clientVersion == null) {
                clientVersion = BuildVersionInfo.VERSION;
            }

            if (eventFactory == null) {
                eventFactory = new EventFactory(clientEngine, clientVersion);
            }

            if (errorHandler == null) {
                errorHandler = new NoOpErrorHandler();
            }

            if (bucketer == null) {
                bucketer = new Bucketer();
            }

            if (decisionService == null) {
                decisionService = new DecisionService(bucketer, errorHandler, userProfileService);
            }

            if (projectConfig == null && datafile != null && !datafile.isEmpty()) {
                try {
                    projectConfig = new DatafileProjectConfig.Builder().withDatafile(datafile).build();
                    logger.info("Datafile successfully loaded with revision: {}", projectConfig.getRevision());
                } catch (ConfigParseException ex) {
                    logger.error("Unable to parse the datafile", ex);
                    logger.info("Datafile is invalid");
                    errorHandler.handleError(new OptimizelyRuntimeException(ex));
                }
            }

            FallbackProjectConfigManager.Builder builder = FallbackProjectConfigManager.builder();
            if (projectConfigManager != null) {
                builder.add(projectConfigManager);
            }

            if (projectConfig != null) {
                builder.add(StaticProjectConfigManager.create(projectConfig));
            }

            return new Optimizely(eventHandler, eventFactory, errorHandler, decisionService, userProfileService, builder.build());
        }
    }
}
