/****************************************************************************
 * Copyright 2019, Optimizely, Inc. and contributors                        *
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
package com.optimizely.ab.decision.feature;

import com.optimizely.ab.config.FeatureFlag;
import com.optimizely.ab.bucketing.FeatureDecision;
import com.optimizely.ab.config.ProjectConfig;
import com.optimizely.ab.event.internal.UserContext;

import javax.annotation.Nonnull;

/**
 * FeatureService can make a decision about a Feature Flag (can be feature test or rollout)
 */
public interface FeatureService {
    /**
     * Returns a decision for the given feature and user context
     *
     * @param featureFlag The feature flag the user wants to access.
     * @param userContext It have user id, attributes and a reference to the current {@link ProjectConfig}
     * @return {@link FeatureDecision}
     */
    FeatureDecision getDecision(@Nonnull FeatureFlag featureFlag,
                                @Nonnull UserContext userContext);
}