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
package com.optimizely.ab.decision.experiment.services;

import com.optimizely.ab.config.Variation;
import com.optimizely.ab.config.Experiment;
import com.optimizely.ab.config.ProjectConfig;
import com.optimizely.ab.event.internal.UserContext;
import com.optimizely.ab.decision.experiment.ExperimentService;

import javax.annotation.Nonnull;

/**
 * ExperimentBucketerService buckets {@link Variation} of an {@link Experiment} to a user
 */
public class ExperimentBucketerService implements ExperimentService {

    /**
     * @param experiment  The Experiment the user will be bucketed into.
     * @param userContext It have user id, attributes and a reference to the current {@link ProjectConfig}
     * @return {@link Variation}
     */
    @Override
    public Variation getDecision(@Nonnull Experiment experiment,
                                 @Nonnull UserContext userContext) {
        return null;
    }
}
